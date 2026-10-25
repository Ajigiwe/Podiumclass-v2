'use client';

import { useState, useEffect, useCallback, useRef } from 'react';
import { TrackReferenceOrPlaceholder } from '@livekit/components-react';
import { Track, Participant } from 'livekit-client';
import { Minimize2 } from 'lucide-react';
import { FocusWrapper } from './FocusWrapper';
import { TileWrapper } from './TileWrapper';

interface FocusViewProps {
    focusTrack: TrackReferenceOrPlaceholder;
    carouselTracks: TrackReferenceOrPlaceholder[];
    activeSpeakers: Participant[];
    userRole: string;
    onExitFocus: () => void;
    onTileClick: (track: TrackReferenceOrPlaceholder) => void;
}

export function FocusView({
    focusTrack,
    carouselTracks,
    activeSpeakers,
    userRole,
    onExitFocus,
    onTileClick,
}: FocusViewProps) {
    // Starts above the floating control bar (~76px tall on mobile) so the PiP is
    // never hidden behind it; users can still drag it anywhere.
    const [pipPosition, setPipPosition] = useState({ x: 16, y: 96 });
    const [isDraggingPip, setIsDraggingPip] = useState(false);
    const pipDragRef = useRef<{ startX: number; startY: number; startPipX: number; startPipY: number } | null>(null);

    const startPipDrag = useCallback((e: React.MouseEvent | React.TouchEvent) => {
        e.preventDefault();
        setIsDraggingPip(true);
        const clientX = 'touches' in e ? e.touches[0].clientX : (e as React.MouseEvent).clientX;
        const clientY = 'touches' in e ? e.touches[0].clientY : (e as React.MouseEvent).clientY;
        pipDragRef.current = { startX: clientX, startY: clientY, startPipX: pipPosition.x, startPipY: pipPosition.y };
    }, [pipPosition]);

    useEffect(() => {
        if (!isDraggingPip) return;
        const move = (e: MouseEvent | TouchEvent) => {
            if (!pipDragRef.current) return;
            const cx = 'touches' in e ? e.touches[0].clientX : (e as MouseEvent).clientX;
            const cy = 'touches' in e ? e.touches[0].clientY : (e as MouseEvent).clientY;
            setPipPosition({
                x: Math.max(0, Math.min(window.innerWidth - 120, pipDragRef.current.startPipX + (pipDragRef.current.startX - cx))),
                y: Math.max(0, Math.min(window.innerHeight - 120, pipDragRef.current.startPipY + (pipDragRef.current.startY - cy))),
            });
        };
        const end = () => { setIsDraggingPip(false); pipDragRef.current = null; };
        window.addEventListener('mousemove', move);
        window.addEventListener('mouseup', end);
        window.addEventListener('touchmove', move, { passive: false });
        window.addEventListener('touchend', end);
        return () => { window.removeEventListener('mousemove', move); window.removeEventListener('mouseup', end); window.removeEventListener('touchmove', move); window.removeEventListener('touchend', end); };
    }, [isDraggingPip]);

    const pipTracks = carouselTracks.filter(t =>
        userRole === 'lecturer' ? !t.participant.isLocal : (t.participant.isLocal || activeSpeakers.some(s => s.sid === t.participant.sid))
    ).slice(0, userRole === 'lecturer' ? 4 : 3);

    return (
        <div className="absolute inset-0 flex bg-black z-50">
            <div className={`flex-1 relative h-full min-h-0 min-w-0 ${focusTrack.source === Track.Source.ScreenShare ? '[&_video]:!object-contain' : 'immersive-video-container [&_video]:!h-full'}`}>
                <FocusWrapper trackRef={focusTrack} onParticipantClick={() => onExitFocus()} />
                <button onClick={onExitFocus} className="absolute top-4 left-4 z-[60] bg-black/80 text-white px-3 py-2 rounded-lg hover:bg-black ring-1 ring-white/20 flex items-center gap-2 backdrop-blur-xl text-xs font-bold">
                    <Minimize2 className="w-4 h-4" />
                    <span className="hidden sm:inline">Grid</span>
                </button>
            </div>

            {focusTrack.source !== Track.Source.ScreenShare && pipTracks.length > 0 && (
                <div
                    className={`absolute z-[70] flex flex-col gap-2 cursor-grab active:cursor-grabbing ${isDraggingPip ? '' : 'transition-all duration-300'}`}
                    style={{ right: pipPosition.x, bottom: pipPosition.y }}
                    onMouseDown={startPipDrag}
                    onTouchStart={startPipDrag}
                >
                    {pipTracks.map((t) => (
                        <div key={`${t.participant.sid}-${t.source}`} className="w-24 sm:w-40 aspect-[3/4] sm:aspect-video rounded-lg overflow-hidden ring-1 ring-white/20 bg-gray-900 pointer-events-none">
                            <TileWrapper track={t} participant={t.participant} onTileClick={onTileClick} className="w-full h-full" />
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
}
