import { NextRequest, NextResponse } from 'next/server';
import crypto from 'node:crypto';
import { adminDb } from '@/lib/firebase/admin';
import { resolveSessionAccess } from '@/lib/auth/session-access';
import { Timestamp, FieldValue } from '@/lib/firebase/admin';
import { RoomServiceClient } from 'livekit-server-sdk';

/** Cron/background jobs authenticate with CRON_SECRET instead of a user token. */
function isInternalCall(request: NextRequest): boolean {
    const configured = process.env.CRON_SECRET || '';
    const provided = request.headers.get('x-cron-secret') || '';
    if (!configured || !provided) return false;
    const given = crypto.createHash('sha256').update(provided).digest();
    const expected = crypto.createHash('sha256').update(configured).digest();
    return crypto.timingSafeEqual(given, expected);
}

/**
 * Triggers a verification event for all students in a room
 * POST /api/attendance/verification/trigger
 *
 * Only the host, lecturer, an active co-host, or an admin may trigger checks.
 * The caller identity always comes from the verified token, never the body.
 * Background schedulers use the x-cron-secret header.
 */
export async function POST(request: NextRequest) {
    try {
        const body = await request.json().catch(() => ({}));
        const { sessionId } = body;
        const requestedLimit = Number(body?.timeLimitSeconds ?? 30);
        const timeLimitSeconds = Number.isFinite(requestedLimit)
            ? Math.min(300, Math.max(5, Math.round(requestedLimit)))
            : 30;

        if (!sessionId) {
            return NextResponse.json({ error: 'Missing required fields' }, { status: 400 });
        }

        const internal = isInternalCall(request);
        let triggeredBy: string;
        let attendanceStatus: string | undefined;

        if (internal) {
            const sessionSnap = await adminDb.collection('sessions').doc(sessionId).get();
            if (!sessionSnap.exists) {
                return NextResponse.json({ error: 'Session not found' }, { status: 404 });
            }
            attendanceStatus = sessionSnap.data()?.attendanceStatus;
            triggeredBy = 'automatic';
        } else {
            const access = await resolveSessionAccess(request, sessionId);
            if (!access.ok) return access.response;
            if (!access.canManage) {
                return NextResponse.json({ error: 'Forbidden: only the class lecturer or a co-host can trigger checks' }, { status: 403 });
            }
            attendanceStatus = access.session.attendanceStatus;
            triggeredBy = access.uid;
        }

        if (attendanceStatus !== 'active') {
            return NextResponse.json({ error: 'Attendance is not active for this session' }, { status: 409 });
        }

        const apiKey = process.env.LIVEKIT_API_KEY;
        const apiSecret = process.env.LIVEKIT_API_SECRET;
        const livekitUrl = process.env.NEXT_PUBLIC_LIVEKIT_URL;

        if (!apiKey || !apiSecret || !livekitUrl) {
            return NextResponse.json({ error: 'LiveKit not configured' }, { status: 500 });
        }

        const roomName = `podium_${sessionId}`;

        // 1. Create verification document
        const sessionRef = adminDb.collection('sessions').doc(sessionId);
        const verificationsRef = sessionRef.collection('verifications');

        const verifCountSnap = await verificationsRef.count().get();
        const verificationNumber = verifCountSnap.data().count + 1;

        const verificationId = verificationsRef.doc().id;
        const now = Timestamp.now();
        const expiresAt = new Timestamp(now.seconds + timeLimitSeconds, now.nanoseconds);

        const verificationData = {
            id: verificationId,
            verificationNumber,
            triggeredBy,
            triggeredAt: now,
            expiresAt,
            timeLimitSeconds
        };

        await verificationsRef.doc(verificationId).set(verificationData);

        // 2. Increment totalVerificationsSent for all active students
        const attendanceRef = sessionRef.collection('attendance');
        const studentsSnap = await attendanceRef.where('isPresent', '==', true).get();

        // Firestore batches are capped at 500 operations and each student costs two,
        // so chunk into 250-student slices to keep large rooms below the limit.
        const BATCH_SIZE = 250;
        const studentDocs = studentsSnap.docs;
        for (let i = 0; i < studentDocs.length; i += BATCH_SIZE) {
            const batch = adminDb.batch();
            for (const doc of studentDocs.slice(i, i + BATCH_SIZE)) {
                batch.update(doc.ref, {
                    totalVerificationsSent: FieldValue.increment(1)
                });
                // Also update flat log for reports (merge so a missing log can't fail the batch)
                const logRef = adminDb.collection('attendance_logs').doc(`${sessionId}_${doc.id}`);
                batch.set(logRef, {
                    totalVerificationsSent: FieldValue.increment(1)
                }, { merge: true });
            }
            await batch.commit();
        }

        // 3. Broadcast to LiveKit room
        const svc = new RoomServiceClient(livekitUrl, apiKey, apiSecret);
        const encoder = new TextEncoder();
        const data = encoder.encode(JSON.stringify({
            type: 'VERIFICATION_TRIGGERED',
            payload: {
                verificationId,
                verificationNumber,
                expiresAt: expiresAt.toMillis(),
                timeLimitSeconds
            }
        }));

        // DataPacket_Kind.RELIABLE corresponds to 0
        await svc.sendData(roomName, data, 0);

        return NextResponse.json({
            success: true,
            verificationId
        });

    } catch (error: any) {
        console.error('Error triggering verification:', error);
        return NextResponse.json({ error: error.message }, { status: 500 });
    }
}
