'use client';

import { useState, useEffect, useMemo, useCallback } from 'react';
import { createPortal } from 'react-dom';
import { TrackReferenceOrPlaceholder, useRoomContext, useLayoutContext } from '@livekit/components-react';
import { Room, Track, Participant, RoomEvent } from 'livekit-client';
import { Video, X } from 'lucide-react';
import { useClassroom } from '@/contexts/ClassroomContext';
import { TileWrapper } from './TileWrapper';
import { FocusView } from './FocusView';
import { VideoGrid } from './VideoGrid';
import CustomControlBar from '../CustomControlBar';
import ClassroomChat from '../ClassroomChat';
import { RaisedHandsBanner } from '../RaisedHandsBanner';
import ReactionOverlay, { ReactionOverlayHandle } from '../ReactionOverlay';
import { useScreenShareOrientation } from '@/hooks/useScreenShareOrientation';

interface InnerVideoLayoutProps {
    onReaction: (emoji: string) => void;
    onLeave: () => void;
    reactionRef: any;
    tracks: TrackReferenceOrPlaceholder[];
    onToggleChat: () => void;
    isChatOpen: boolean;
    unreadChatCount: number;
    layout: any;
    config: any;
    spotlightParticipant: string | null;
    setSpotlightParticipant: (sid: string | null) => void;
    raisedHands: any[];
    clearAllHands: () => void;
    lowerHand: (userId: string) => void;
    onToggleHand: () => void;
    isHandRaised: boolean;
    userRole: string;
    setLayout: (layout: any) => void;
    showAlert: (message: string, type?: string) => Promise<void>;
    customAlert?: any;
    isActive: boolean;
    isDocked: boolean;
}

export function InnerVideoLayout(props: InnerVideoLayoutProps) {
    const {
        onReaction, onLeave, reactionRef, tracks, onToggleChat, isChatOpen,
        unreadChatCount, config, spotlightParticipant, setSpotlightParticipant,
        raisedHands, clearAllHands, lowerHand, onToggleHand, isHandRaised,
        userRole, setLayout, showAlert, customAlert, isActive, isDocked,
    } = props;

    const { sessionId, title, userId, isModerator } = useClassroom();
    const room = useRoomContext();
    const [activeSpeakers, setActiveSpeakers] = useState<Participant[]>([]);
    const { isScreenSharing, isMobile } = useScreenShareOrientation();
    const [focusTrack, setFocusTrack] = useState<TrackReferenceOrPlaceholder | null>(null);
    const [userDisabledAutoFocus, setUserDisabledAutoFocus] = useState(false);

    useEffect(() => {
        if (!room) return;
        const handler = (speakers: Participant[]) => setActiveSpeakers(speakers);
        room.on(RoomEvent.ActiveSpeakersChanged, handler);
        return () => { room.off(RoomEvent.ActiveSpeakersChanged, handler); };
    }, [room]);

    const sortedTracks = useMemo(() => {
        return [...tracks].sort((a, b) => {
            if (spotlightParticipant === a.participant.sid) return -1;
            if (spotlightParticipant === b.participant.sid) return 1;
            if (a.source === Track.Source.ScreenShare && b.source !== Track.Source.ScreenShare) return -1;
            if (b.source === Track.Source.ScreenShare && a.source !== Track.Source.ScreenShare) return 1;
            const aSp = activeSpeakers.some(p => p.sid === a.participant.sid) || a.participant.isSpeaking;
            const bSp = activeSpeakers.some(p => p.sid === b.participant.sid) || b.participant.isSpeaking;
            if (aSp && !bSp) return -1;
            if (bSp && !aSp) return 1;
            return 0;
        });
    }, [tracks, spotlightParticipant, activeSpeakers]);

    const PAGE_SIZE = config.maxVisible;
    const [currentPage, setCurrentPage] = useState(1);
    const totalPages = Math.ceil(sortedTracks.length / PAGE_SIZE);
    const paginatedTracks = sortedTracks.slice((currentPage - 1) * PAGE_SIZE, currentPage * PAGE_SIZE);
    const carouselTracks = sortedTracks.filter(t => !focusTrack || (t.participant.sid !== focusTrack.participant.sid || t.source !== focusTrack.source));

    // Auto-focuses the lecturer's screen share (or camera) as participants and tracks
    // change. focusTrack has to stay real state because a click can override it and
    // userDisabledAutoFocus latches that choice, so it cannot simply be derived.
    useEffect(() => {
        const lecturerTracks = tracks.filter((t: any) => {
            try { return JSON.parse(t.participant.metadata || '{}').role === 'lecturer'; } catch { return false; }
        });
        const screen = lecturerTracks.find((t: any) => t.source === Track.Source.ScreenShare);
        const camera = lecturerTracks.find((t: any) => t.source === Track.Source.Camera || t.source === Track.Source.Unknown);
        const target = screen || camera;
        if (target && !userDisabledAutoFocus && (!focusTrack || focusTrack.participant.sid !== target.participant.sid || focusTrack.source !== target.source)) {
            // eslint-disable-next-line react-hooks/set-state-in-effect
            setFocusTrack(target);
        } else if (focusTrack && !userDisabledAutoFocus && !tracks.some((t: any) => t.participant.sid === focusTrack.participant.sid && t.source === focusTrack.source)) {
            // eslint-disable-next-line react-hooks/set-state-in-effect
            setFocusTrack(null);
        }
    }, [tracks, userDisabledAutoFocus]);

    const handleTileClick = (track: TrackReferenceOrPlaceholder) => {
        if (focusTrack && focusTrack.participant?.sid === track.participant.sid && focusTrack.source === track.source) {
            setFocusTrack(null);
            setUserDisabledAutoFocus(true);
        } else {
            setFocusTrack(track);
            setUserDisabledAutoFocus(false);
        }
    };

    const exitFocus = () => { setFocusTrack(null); setUserDisabledAutoFocus(true); };

    return (
        <div className="flex flex-col h-full bg-black relative">
            <style>{`.lk-video-conference .lk-control-bar{display:none!important}
            /* Keep participant name tags readable and clear of the floating control bar:
               pin them to the tile's top edge as a compact pill in every layout. */
            .lk-participant-metadata{
                top:.5rem!important; bottom:auto!important; left:.5rem!important; right:auto!important;
                width:auto; max-width:calc(100% - 1rem);
                background:rgba(0,0,0,.55); border-radius:.5rem; padding:.25rem .5rem;
            }`}</style>

            {!isDocked && (
                <div className="h-12 bg-black/80 border-b border-white/5 px-4 flex items-center z-[100] backdrop-blur-md">
                    <div className="flex items-center gap-3">
                        <div className="bg-blue-600/20 p-1.5 rounded-md">
                            <Video className="w-4 h-4 text-blue-500" />
                        </div>
                        <span className="text-sm font-bold text-white truncate max-w-[200px] sm:max-w-md">{title || 'Current Class'}</span>
                    </div>
                </div>
            )}

            <div className="flex-1 relative overflow-hidden flex flex-col sm:flex-row">
                <div className="flex-1 relative">
                    <RaisedHandsBanner
                        isLecturer={isModerator}
                        raisedHands={raisedHands}
                        onClearAll={clearAllHands}
                        onLowerHand={lowerHand}
                    />

                    {focusTrack ? (
                        <FocusView
                            focusTrack={focusTrack}
                            carouselTracks={carouselTracks}
                            activeSpeakers={activeSpeakers}
                            userRole={userRole}
                            onExitFocus={exitFocus}
                            onTileClick={handleTileClick}
                        />
                    ) : (
                        <VideoGrid
                            tracks={paginatedTracks}
                            currentPage={currentPage}
                            totalPages={totalPages}
                            onPrevPage={() => currentPage > 1 && setCurrentPage(p => p - 1)}
                            onNextPage={() => currentPage < totalPages && setCurrentPage(p => p + 1)}
                            onTileClick={handleTileClick}
                        />
                    )}
                </div>
            </div>

            <CustomControlBar
                roomId={sessionId!}
                isLecturer={isModerator}
                onReaction={onReaction}
                onLeave={onLeave}
                onToggleChat={onToggleChat}
                isChatOpen={isChatOpen}
                onToggleHand={onToggleHand}
                isHandRaised={isHandRaised}
                unreadChatCount={unreadChatCount}
                isActive={isActive}
                showAlert={showAlert}
                customAlert={customAlert}
            />

            <div className={`absolute left-4 right-4 sm:left-auto sm:right-4 top-20 bottom-24 sm:w-80 z-[100] rounded-xl overflow-hidden border border-white/[0.06] bg-neutral-950 flex flex-col transition-all duration-300 ${isChatOpen ? 'opacity-100 translate-x-0' : 'opacity-0 translate-x-[120%] pointer-events-none'}`}>
                <div className="flex items-center justify-between px-4 py-3 border-b border-white/[0.06]">
                    <h3 className="text-sm font-semibold text-white/80">Chat</h3>
                    <button onClick={onToggleChat} className="flex w-8 h-8 items-center justify-center rounded-lg text-gray-400 hover:text-white hover:bg-white/10 transition-colors"><X className="w-5 h-5" /></button>
                </div>
                <div className="flex-1 min-h-0">
                    {sessionId ? <ClassroomChat sessionId={sessionId} /> : <div className="flex items-center justify-center h-full text-gray-500 text-sm">Connecting...</div>}
                </div>
            </div>

            {typeof window !== 'undefined' && createPortal(<ReactionOverlay ref={reactionRef} />, document.body)}
        </div>
    );
}
