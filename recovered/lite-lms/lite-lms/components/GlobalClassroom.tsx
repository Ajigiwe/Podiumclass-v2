'use client';

import { useEffect, useState, useRef, useCallback, useMemo } from 'react';
import { createPortal } from 'react-dom';
import { useRouter } from 'next/navigation';
import {
    LiveKitRoom,
    LayoutContextProvider,
    RoomAudioRenderer,
    useRoomContext,
    useTracks,
} from '@livekit/components-react';
import '@livekit/components-styles';
import { Room, Track, RoomEvent, ConnectionState } from 'livekit-client';
import { useClassroom } from '@/contexts/ClassroomContext';
import { useAlert } from '@/contexts/AlertContext';
import { Maximize2, X, Video } from 'lucide-react';
import { useLayoutConfig } from '@/hooks/useLayoutConfig';
import { useRaisedHands } from '@/hooks/useRaisedHands';
import { InstantPiPManager } from './media/InstantPiPManager';
import { PiPPermissionPrompt } from './media/PiPPermissionPrompt';
import { StudentVerificationModal } from './attendance/StudentVerificationModal';
import { ConnectionRecoveryStatus } from './ConnectionRecoveryStatus';
import { roomOptions } from '@/config/livekit.config';
import { DeviceFailureHandler } from './media/DeviceFailureHandler';
import { useScreenSharePersistence } from '@/hooks/useScreenSharePersistence';
import { InnerVideoLayout } from './classroom/InnerVideoLayout';

function RoomConnector({ onRoomReady }: { onRoomReady: (room: Room) => void }) {
    const room = useRoomContext();
    useScreenSharePersistence();

    useEffect(() => {
        if (room) onRoomReady(room);
    }, [room, onRoomReady]);

    return null;
}

function VideoLayout({
    onReaction,
    onLeave,
    reactionRef,
    onToggleChat,
    isChatOpen,
    unreadChatCount,
    userId,
    userRole,
    userName,
    isActive,
    showAlert,
    customAlert,
    isDocked,
}: any) {
    const { layout, setLayout, config, spotlightParticipant, setSpotlightParticipant } = useLayoutConfig();
    const { raisedHands, raiseHand, lowerHand, clearAllHands } = useRaisedHands();
    const [isHandRaised, setIsHandRaised] = useState(false);

    const onToggleHand = useCallback(() => {
        if (isHandRaised) lowerHand(userId);
        else raiseHand(userId, userName);
        setIsHandRaised(!isHandRaised);
    }, [isHandRaised, lowerHand, raiseHand, userId, userName]);

    const room = useRoomContext();
    const tracks = useTracks(
        [
            { source: Track.Source.Camera, withPlaceholder: true },
            { source: Track.Source.ScreenShare, withPlaceholder: false },
        ],
        { onlySubscribed: false }
    ).filter(track => {
        if (!track.participant || !track.participant.sid) return false;
        if (room) {
            const isLocal = track.participant.sid === room.localParticipant.sid;
            const isRemote = room.remoteParticipants.has(track.participant.identity);
            return isLocal || isRemote;
        }
        return true;
    });

    useEffect(() => {
        if (raisedHands.length === 0 && isHandRaised) {
            setTimeout(() => setIsHandRaised(false), 0);
        }
    }, [raisedHands, isHandRaised]);

    return (
        <LayoutContextProvider>
            <InnerVideoLayout
                onReaction={onReaction}
                onLeave={onLeave}
                reactionRef={reactionRef}
                tracks={tracks}
                onToggleChat={onToggleChat}
                isChatOpen={isChatOpen}
                unreadChatCount={unreadChatCount}
                layout={layout}
                config={config}
                spotlightParticipant={spotlightParticipant}
                setSpotlightParticipant={setSpotlightParticipant}
                raisedHands={raisedHands}
                clearAllHands={clearAllHands}
                lowerHand={lowerHand}
                onToggleHand={onToggleHand}
                isHandRaised={isHandRaised}
                userRole={userRole}
                setLayout={setLayout}
                showAlert={showAlert}
                customAlert={customAlert}
                isActive={isActive}
                isDocked={isDocked}
            />
        </LayoutContextProvider>
    );
}

export default function GlobalClassroom() {
    const {
        sessionId, title, userName, userRole, userId, isActive,
        isMini, isFloating, toggleMinimize, leaveClass,
        setLiveKitRoom, toggleChat, isChatOpen, unreadChatCount,
        token, setToken, isHost, photoURL,
    } = useClassroom();
    const { showAlert, customAlert } = useAlert();

    const [mounted, setMounted] = useState(false);
    const [tokenError, setTokenError] = useState<string | null>(null);
    const [isConnecting, setIsConnecting] = useState(false);
    const roomRef = useRef<Room | null>(null);
    const router = useRouter();
    const reactionRef = useRef<any>(null);

    const finalRoomOptions = useMemo(() => ({
        ...roomOptions,
        publishDefaults: { ...roomOptions.publishDefaults, simulcast: true }
    }), []);

    useEffect(() => {
        setMounted(true);
        return () => {
            if (roomRef.current && roomRef.current.state !== ConnectionState.Disconnected) {
                roomRef.current.disconnect();
                roomRef.current = null;
            }
        };
    }, []);

    useEffect(() => {
        if (!isActive && roomRef.current && roomRef.current.state !== ConnectionState.Disconnected) {
            roomRef.current.disconnect();
            roomRef.current = null;
        }
    }, [isActive]);

    useEffect(() => {
        if (!isActive || !sessionId || !userName || !userRole || !userId || token) return;
        const fetchToken = async () => {
            setIsConnecting(true);
            setTokenError(null);
            try {
                const response = await fetch('/api/livekit/token', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        roomName: `podium_${sessionId}`,
                        participantName: userName,
                        participantId: userId,
                        role: userRole,
                        userId: userId,
                        photoURL: photoURL || undefined,
                    }),
                });
                if (!response.ok) throw new Error('Failed to get token');
                const data = await response.json();
                setToken(data.token);
            } catch (error: any) {
                setTokenError(error.message || 'Failed to connect');
            } finally {
                setIsConnecting(false);
            }
        };
        fetchToken();
    }, [isActive, sessionId, userName, userRole, userId, token, setToken, photoURL]);

    const [position, setPosition] = useState({ x: 20, y: 0 });
    const [size, setSize] = useState({ width: 400, height: 300 });
    const [mountNode, setMountNode] = useState<HTMLElement | null>(null);

    useEffect(() => {
        if (typeof window !== 'undefined') setPosition({ x: 20, y: window.innerHeight - 320 });
        const checkMount = () => {
            const el = document.getElementById('classroom-video-mount');
            setMountNode(el);
        };
        const interval = setInterval(checkMount, 200);
        return () => clearInterval(interval);
    }, []);

    const handleRoomReady = useCallback((room: Room) => {
        roomRef.current = room;
        setLiveKitRoom(room);
    }, [setLiveKitRoom]);

    const handleLeave = useCallback(() => {
        if (roomRef.current) roomRef.current.disconnect();
        leaveClass();
        router.push('/dashboard');
    }, [leaveClass, router]);

    const handleReaction = useCallback(async (emoji: string) => {
        if (roomRef.current) {
            const encoder = new TextEncoder();
            await roomRef.current.localParticipant.publishData(encoder.encode(JSON.stringify({ type: 'reaction', emoji })), { reliable: true, topic: 'reaction' });
            if (reactionRef.current) reactionRef.current.addReaction(emoji);
        }
    }, []);

    if (!mounted || !isActive || !sessionId || !userName) return null;

    if (isConnecting || !token) {
        return (
            <div className="fixed inset-0 z-[9999] flex items-center justify-center bg-gray-950">
                <div className="text-center">
                    {tokenError ? (
                        <>
                            <div className="text-red-500 text-xl mb-4 font-bold">Connection Error</div>
                            <p className="text-gray-400 mb-6">{tokenError}</p>
                            <button onClick={handleLeave} className="px-6 py-2 bg-red-600 text-white rounded-lg hover:bg-red-700 transition-colors shadow-lg">Go Back</button>
                        </>
                    ) : (
                        <>
                            <div className="w-12 h-12 border-4 border-blue-500/30 border-t-blue-500 rounded-full animate-spin mx-auto" />
                            <p className="mt-4 text-gray-400 font-medium animate-pulse">Connecting to classroom...</p>
                        </>
                    )}
                </div>
            </div>
        );
    }

    const LiveKitContent = (
        <LiveKitRoom
            serverUrl={process.env.NEXT_PUBLIC_LIVEKIT_URL || 'wss://your-project.livekit.cloud'}
            token={token}
            connect={!!token && isActive}
            video={false}
            audio={false}
            onConnected={() => setIsConnecting(false)}
            onDisconnected={() => { roomRef.current = null; setLiveKitRoom(null); }}
            options={finalRoomOptions}
            className="w-full h-full"
        >
            <PiPPermissionPrompt />
            <InstantPiPManager />
            <ConnectionRecoveryStatus />
            <DeviceFailureHandler />
            {isActive && !isHost && <StudentVerificationModal sessionId={sessionId!} />}
            <VideoLayout
                onReaction={handleReaction}
                onLeave={handleLeave}
                reactionRef={reactionRef}
                onToggleChat={toggleChat}
                isChatOpen={!!isChatOpen}
                userRole={userRole || 'student'}
                userId={userId || ''}
                userName={userName || ''}
                unreadChatCount={unreadChatCount}
                isActive={isActive}
                showAlert={showAlert}
                customAlert={customAlert}
                isDocked={!!(mountNode && !isMini && !isFloating)}
            />
            <RoomConnector onRoomReady={handleRoomReady} />
            <RoomAudioRenderer />
        </LiveKitRoom>
    );

    if (mountNode && !isMini && !isFloating) {
        return createPortal(<div className="absolute inset-0 bg-[#0a0a0a]">{LiveKitContent}</div>, mountNode);
    }

    if (isMini || isFloating) {
        return createPortal(
            <div className="fixed z-[9999] flex flex-col bg-gray-900 border border-white/10 rounded-xl overflow-hidden shadow-2xl" 
                 style={{ left: position.x, top: position.y, width: size.width, height: size.height }}>
                <div className="h-10 bg-gray-800 border-b border-white/5 flex items-center justify-between px-3 cursor-grab">
                    <span className="text-xs font-bold text-gray-300 truncate max-w-[60%]">{title}</span>
                    <div className="flex gap-2">
                        <button onClick={() => toggleMinimize(false)} className="p-1.5 hover:bg-white/10 rounded-md text-gray-400 hover:text-white transition-colors"><Maximize2 className="w-4 h-4" /></button>
                        <button onClick={handleLeave} className="p-1.5 bg-red-600/20 hover:bg-red-600 text-red-500 hover:text-white rounded-md transition-all"><X className="w-4 h-4" /></button>
                    </div>
                </div>
                <div className="flex-1 min-h-0 bg-black">{LiveKitContent}</div>
            </div>,
            document.body
        );
    }

    return (
        <div className="fixed inset-0 z-[9998] bg-[#0a0a0a]">
            {LiveKitContent}
        </div>
    );
}
