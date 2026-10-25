import { NextRequest, NextResponse } from 'next/server';
import { AccessToken, VideoGrant, TrackSource } from 'livekit-server-sdk';

// LiveKit token generation API
// This endpoint generates access tokens with appropriate permissions based on user role
// Lecturers get moderator (roomAdmin) permissions that persist across sessions

export async function POST(request: NextRequest) {
    try {
        const body = await request.json();
        const { roomName, participantName, participantId, role, userId, photoURL } = body;

        // Validate required fields
        if (!roomName || !participantName || !role) {
            return NextResponse.json(
                { error: 'Missing required fields: roomName, participantName, role' },
                { status: 400 }
            );
        }

        // Get LiveKit credentials from environment
        const apiKey = process.env.LIVEKIT_API_KEY;
        const apiSecret = process.env.LIVEKIT_API_SECRET;

        if (!apiKey || !apiSecret) {
            console.error('LiveKit credentials not configured');
            return NextResponse.json(
                { error: 'LiveKit not configured' },
                { status: 500 }
            );
        }

        // Create access token
        const at = new AccessToken(apiKey, apiSecret, {
            identity: participantId || `user_${Date.now()}`,
            name: participantName,
            metadata: JSON.stringify({
                role,
                userId,
                name: participantName,
                photoURL: photoURL || null,
            }),
        });

        // Explicitly set TTL to 24 hours to ensure it's not ignored
        at.ttl = '24h';

        // Define video grants based on role
        const videoGrant: VideoGrant = {
            room: roomName,
            roomJoin: true,
            canSubscribe: true,
            canPublishData: true, // Allow chat for everyone
        };

        // Lecturers get full moderator permissions
        // This is the KEY to persistent moderator status!
        if (role === 'lecturer') {
            videoGrant.canPublish = true;
            videoGrant.canPublishSources = [
                TrackSource.CAMERA,
                TrackSource.MICROPHONE,
                TrackSource.SCREEN_SHARE,
                TrackSource.SCREEN_SHARE_AUDIO,
            ];
            videoGrant.roomAdmin = true; // Full room control
            videoGrant.roomCreate = true; // Can create room
            videoGrant.canUpdateOwnMetadata = true;
        } else {
            // Students can publish (for raising hand, unmuting when allowed)
            // but don't have admin privileges
            videoGrant.canPublish = true;
            videoGrant.canPublishSources = [
                TrackSource.CAMERA,
                TrackSource.MICROPHONE,
                TrackSource.SCREEN_SHARE,
                TrackSource.SCREEN_SHARE_AUDIO,
            ];
            videoGrant.roomAdmin = false;
            videoGrant.canUpdateOwnMetadata = false;
        }

        at.addGrant(videoGrant);

        // Generate the token
        const token = await at.toJwt();

        return NextResponse.json({
            token,
            roomName,
            identity: participantId || `user_${Date.now()}`,
        });

    } catch (error) {
        console.error('Error generating LiveKit token:', error);
        return NextResponse.json(
            { error: 'Failed to generate token' },
            { status: 500 }
        );
    }
}

// Also support GET for simpler testing
export async function GET(request: NextRequest) {
    const searchParams = request.nextUrl.searchParams;
    const roomName = searchParams.get('roomName');
    const participantName = searchParams.get('participantName');
    const participantId = searchParams.get('participantId');
    const role = searchParams.get('role') || 'student';
    const userId = searchParams.get('userId');
    const photoURL = searchParams.get('photoURL');

    if (!roomName || !participantName) {
        return NextResponse.json(
            { error: 'Missing required query params: roomName, participantName' },
            { status: 400 }
        );
    }

    // Reuse POST logic
    const mockRequest = {
        json: async () => ({
            roomName,
            participantName,
            participantId,
            role,
            userId,
            photoURL,
        }),
    } as NextRequest;

    return POST(mockRequest);
}
