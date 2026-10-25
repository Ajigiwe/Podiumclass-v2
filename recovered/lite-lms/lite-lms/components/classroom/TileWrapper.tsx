'use client';

import { memo, useEffect, useState } from 'react';
import { ParticipantTile, useIsSpeaking } from '@livekit/components-react';
import { useInView } from 'react-intersection-observer';
import { Track, ParticipantEvent, VideoQuality } from 'livekit-client';
import { User, Mic, MoreVertical } from 'lucide-react';
import { useClassroom } from '@/contexts/ClassroomContext';
import { ParticipantMenu } from './ParticipantMenu';

export const TileWrapper = memo(({ track, participant, onTileClick, className, ...props }: any) => {
    const { ref, inView } = useInView({ threshold: 0 });
    const { isModerator, userId } = useClassroom();
    const [menuOpen, setMenuOpen] = useState(false);
    const hookSpeaking = useIsSpeaking(participant);
    const [rawSpeaking, setRawSpeaking] = useState(participant.isSpeaking);

    useEffect(() => {
        if (track.publication && typeof (track.publication as any).setVideoQuality === 'function') {
            const pub = track.publication as any;
            pub.setVideoQuality(inView ? VideoQuality.HIGH : VideoQuality.LOW);
            if (typeof pub.setSubscribed === 'function') pub.setSubscribed(!!inView);
        }
    }, [inView, track.publication]);

    useEffect(() => {
        const handler = (speaking: boolean) => setRawSpeaking(speaking);
        participant.on(ParticipantEvent.IsSpeakingChanged, handler);
        return () => { participant.off(ParticipantEvent.IsSpeakingChanged, handler); };
    }, [participant]);

    const isSpeaking = hookSpeaking || rawSpeaking;
    const isCameraOff = track.source === Track.Source.Camera && (track.publication?.isMuted || !participant.isCameraEnabled);

    let photoURL: string | null = null;
    try { photoURL = participant.metadata ? JSON.parse(participant.metadata).photoURL : null; } catch {}

    return (
        <div
            ref={ref}
            onClick={() => onTileClick(track)}
            className={`relative group cursor-pointer rounded-lg overflow-hidden transition-all duration-300 ${isSpeaking ? 'ring-1 ring-emerald-500/40' : ''} ${className || ''}`}
        >
            {inView ? (
                <ParticipantTile
                    trackRef={track}
                    {...props}
                    className={`!w-full !h-full [&_video]:!object-center ${track.source === Track.Source.ScreenShare ? '[&_video]:!object-contain bg-black' : '[&_video]:!object-cover'}`}
                />
            ) : (
                <div className="absolute inset-0 bg-neutral-900 flex items-center justify-center">
                    <User className="w-8 h-8 text-white/20" />
                </div>
            )}

            {isSpeaking && (
                <div className="absolute bottom-2 right-2 z-20 bg-emerald-500 text-white p-1 rounded-full pointer-events-none">
                    <Mic className="w-3 h-3" />
                </div>
            )}

            {isModerator && participant.identity !== userId && (
                <div className="absolute top-2 right-2 z-30 opacity-0 group-hover:opacity-100 transition-opacity">
                    <button
                        onClick={(e) => { e.stopPropagation(); setMenuOpen(!menuOpen); }}
                        className="p-1 rounded-full bg-black/50 hover:bg-black/80 text-white/70 hover:text-white backdrop-blur-sm transition-colors"
                    >
                        <MoreVertical className="w-3.5 h-3.5" />
                    </button>
                    {menuOpen && <ParticipantMenu participant={participant} closeMenu={() => setMenuOpen(false)} />}
                </div>
            )}

            {isCameraOff && inView && (
                <div className="absolute inset-0 flex flex-col items-center justify-center bg-neutral-900">
                    <div className="w-14 h-14 rounded-full flex items-center justify-center overflow-hidden bg-white/[0.06] mb-2">
                        {photoURL ? (
                            <img src={photoURL} alt="" className="w-full h-full object-cover" />
                        ) : (
                            <span className="text-white/40 font-semibold text-lg">{(participant.name || participant.identity || '?')[0].toUpperCase()}</span>
                        )}
                    </div>
                    <span className="text-white/40 text-xs truncate max-w-[100px]">{participant.name || participant.identity || ''}</span>
                </div>
            )}
        </div>
    );
});

TileWrapper.displayName = 'TileWrapper';
