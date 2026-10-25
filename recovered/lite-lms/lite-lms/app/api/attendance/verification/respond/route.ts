import { NextRequest, NextResponse } from 'next/server';
import { adminDb, getAuthenticatedUser } from '@/lib/firebase/admin';
import { Timestamp } from '@/lib/firebase/admin';

export async function POST(request: NextRequest) {
    try {
        const decoded = await getAuthenticatedUser(request);
        if (!decoded) {
            return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
        }

        const { sessionId, verificationId, studentId } = await request.json();

        if (!sessionId || !verificationId || !studentId) {
            return NextResponse.json({ error: 'Missing required fields' }, { status: 400 });
        }

        // Verify the authenticated user matches the claimed studentId
        if (decoded.uid !== studentId) {
            return NextResponse.json({ error: 'Forbidden' }, { status: 403 });
        }

        const sessionRef = adminDb.collection('sessions').doc(sessionId);
        const verifRef = sessionRef.collection('verifications').doc(verificationId);
        const verifSnap = await verifRef.get();

        if (!verifSnap.exists) {
            return NextResponse.json({ error: 'Verification not found' }, { status: 404 });
        }

        const verifData = verifSnap.data();
        if (!verifData) {
            return NextResponse.json({ error: 'Verification data missing' }, { status: 500 });
        }
        const now = Timestamp.now();

        if (now.seconds > verifData.expiresAt.seconds) {
            return NextResponse.json({ error: 'Verification has expired' }, { status: 410 });
        }

        const responseRef = verifRef.collection('responses').doc(studentId);
        await responseRef.set({
            attendanceRecordId: studentId,
            respondedAt: now,
            responseTimeSeconds: now.seconds - verifData.triggeredAt.seconds
        });

        const attendanceRef = sessionRef.collection('attendance').doc(studentId);
        const recordSnap = await attendanceRef.get();

        if (recordSnap.exists) {
            const data = recordSnap.data();
            if (!data) return NextResponse.json({ error: 'Attendance record data missing' }, { status: 500 });

            const completed = (data.totalVerificationsCompleted || 0) + 1;
            const sent = data.totalVerificationsSent || 1;

            await attendanceRef.update({
                totalVerificationsCompleted: completed,
                verificationPercentage: Math.round((completed / sent) * 100),
                lastRespondedAt: now
            });

            const logRef = adminDb.collection('attendance_logs').doc(`${sessionId}_${studentId}`);
            await logRef.update({
                totalVerificationsCompleted: completed,
                verificationPercentage: Math.round((completed / sent) * 100),
                lastRespondedAt: now
            }).catch(e => console.error('Error updating flat log:', e));
        }

        return NextResponse.json({ success: true });

    } catch (error: any) {
        console.error('Error recording verification response:', error);
        return NextResponse.json({ error: error.message }, { status: 500 });
    }
}
