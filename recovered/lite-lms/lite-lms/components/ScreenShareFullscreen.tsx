'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useRemoteParticipants, VideoTrack } from '@livekit/components-react';
import { Track, type RemoteParticipant, type RemoteTrackPublication } from 'livekit-client';
import { Maximize2, Minimize2, X, Monitor } from 'lucide-react';

import { useIsMobile } from '@/hooks/useIsMobile';

export const ScreenShareFullscreen = () => {
    const remoteParticipants = useRemoteParticipants();
    const [isFullscreen, setIsFullscreen] = useState(false);
    const isMobile = useIsMobile();
    const containerRef = useRef<HTMLDivElement>(null);

    const exitFullscreen = useCallback(async () => {
        try {
            if (document.fullscreenElement) {
                await document.exitFullscreen();
            }

            setIsFullscreen(false);

            // Unlock orientation
            if (screen.orientation && screen.orientation.unlock) {
                screen.orientation.unlock();
            }
        } catch (error) {
            console.error('Exit fullscreen failed:', error);
        }
    }, []);

    const enterFullscreen = async () => {
        if (!containerRef.current) return;

        try {
            // Request fullscreen
            if (containerRef.current.requestFullscreen) {
                await containerRef.current.requestFullscreen();
            } else if ((containerRef.current as any).webkitRequestFullscreen) {
                await (containerRef.current as any).webkitRequestFullscreen();
            } else if ((containerRef.current as any).mozRequestFullScreen) {
                await (containerRef.current as any).mozRequestFullScreen();
            }

            setIsFullscreen(true);

            // Lock to landscape on mobile
            if (isMobile && screen.orientation && (screen.orientation as any).lock) {
                try {
                    await (screen.orientation as any).lock('landscape');
                } catch (e) {
                    console.log('Could not lock orientation:', e);
                }
            }
        } catch (error) {
            console.error('Fullscreen failed:', error);
        }
    };

    // Computed during render rather than memoized: publication state can change without
    // the remoteParticipants array identity changing, so re-reading it each render keeps
    // this correct. The loop is trivially cheap.
    let screenShareTrack: { participant: RemoteParticipant; publication: RemoteTrackPublication } | null = null;

    for (const participant of remoteParticipants) {
        const screenPublication = Array.from(participant.videoTrackPublications.values())
            .find(pub => pub.source === Track.Source.ScreenShare);

        if (screenPublication && screenPublication.isSubscribed && screenPublication.track) {
            screenShareTrack = { participant, publication: screenPublication };
            break;
        }
    }

    const hasScreenShare = screenShareTrack !== null;

    // Leave browser fullscreen once the presenter stops sharing. Keyed on a boolean so
    // the effect does not re-run every render from a fresh object identity. Exiting
    // fullscreen is a real side effect; the state update is a consequence of it.
    useEffect(() => {
        if (!hasScreenShare && isFullscreen) {
            // eslint-disable-next-line react-hooks/set-state-in-effect
            exitFullscreen();
        }
    }, [hasScreenShare, isFullscreen, exitFullscreen]);

    // Listen for fullscreen changes
    useEffect(() => {
        const handleFullscreenChange = () => {
            setIsFullscreen(!!document.fullscreenElement);
        };

        document.addEventListener('fullscreenchange', handleFullscreenChange);
        document.addEventListener('webkitfullscreenchange', handleFullscreenChange);
        document.addEventListener('mozfullscreenchange', handleFullscreenChange);

        return () => {
            document.removeEventListener('fullscreenchange', handleFullscreenChange);
            document.removeEventListener('webkitfullscreenchange', handleFullscreenChange);
            document.removeEventListener('mozfullscreenchange', handleFullscreenChange);
        };
    }, []);

    if (!screenShareTrack || !isMobile) return null;

    return (
        <>
            {/* Screen Share Container */}
            <div
                ref={containerRef}
                className={`${isFullscreen
                    ? 'fixed inset-0 z-[200] bg-black'
                    : 'fixed inset-4 z-[190] bg-black rounded-lg overflow-hidden border border-white/10'
                    }`}
            >
                {/* Screen Share Video */}
                <VideoTrack
                    trackRef={{
                        participant: screenShareTrack.participant,
                        source: Track.Source.ScreenShare,
                        publication: screenShareTrack.publication,
                    }}
                    className="w-full h-full object-contain"
                />

                {/* Controls Overlay */}
                <div className="absolute top-4 right-4 left-4 flex items-center justify-between pointer-events-none">
                    {/* Participant Name */}
                    <div className="bg-black/90 text-white px-4 py-2 rounded-md text-sm font-medium pointer-events-auto flex items-center gap-2 border border-white/10">
                        <Monitor className="w-4 h-4 text-blue-400" />
                        <span>{screenShareTrack.participant.name || 'Presenter'} is sharing</span>
                    </div>

                    <div className="flex items-center gap-2 pointer-events-auto">
                        {/* Fullscreen Toggle (Available on all devices) */}
                        <button
                            onClick={isFullscreen ? exitFullscreen : enterFullscreen}
                            className="bg-black/90 hover:bg-black text-white p-2.5 rounded-md transition-colors border border-white/10"
                            title={isFullscreen ? 'Exit fullscreen' : 'Enter fullscreen (Landscape)'}
                        >
                            {isFullscreen ? (
                                <Minimize2 className="w-5 h-5" />
                            ) : (
                                <Maximize2 className="w-5 h-5" />
                            )}
                        </button>

                        {/* Close */}
                        <button
                            onClick={exitFullscreen}
                            className="bg-red-600/80 hover:bg-red-600 text-white p-2.5 rounded-md transition-colors border border-red-500/20"
                            title="Close viewer"
                        >
                            <X className="w-5 h-5" />
                        </button>
                    </div>
                </div>
            </div>
        </>
    );
};

function isLandscape(): boolean {
    if (typeof window === 'undefined') return false;
    return window.innerWidth > window.innerHeight;
}
