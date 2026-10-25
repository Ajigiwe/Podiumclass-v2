'use client';

import { useState, useEffect } from 'react';
import { ParticipantTile, useIsSpeaking } from '@livekit/components-react';
import { Track, ParticipantEvent } from 'livekit-client';
import { MicOff, MoreVertical } from 'lucide-react';
import { useClassroom } from '@/contexts/ClassroomContext';
import { ParticipantMenu } from './ParticipantMenu';

export function FocusWrapper({ trackRef, onParticipantClick, ...props }: any) {
    const hookIsSpeaking = useIsSpeaking(trackRef.participant);
    const [manualIsSpeaking, setManualIsSpeaking] = useState(trackRef.participant.isSpeaking);
    const [isMenuOpen, setIsMenuOpen] = useState(false);
    const { isModerator, userId } = useClassroom();

    const isCameraOff = trackRef.source === Track.Source.Camera && (trackRef.publication?.isMuted || !trackRef.participant.isCameraEnabled);
    const showMenu = isModerator && trackRef.participant.identity !== userId;

    useEffect(() => {
        const p = trackRef.participant;
        const handleSpeakingChanged = (speaking: boolean) => {
            setManualIsSpeaking(speaking);
        };
        p.on(ParticipantEvent.IsSpeakingChanged, handleSpeakingChanged);
        return () => {
            p.off(ParticipantEvent.IsSpeakingChanged, handleSpeakingChanged);
        };
    }, [trackRef.participant]);

    const isSpeaking = hookIsSpeaking || manualIsSpeaking;
    const isScreenShare = trackRef.source === Track.Source.ScreenShare;

    let photoURL = null;
    try {
        if (trackRef.participant.metadata) {
            const meta = JSON.parse(trackRef.participant.metadata);
            photoURL = meta.photoURL;
        }
    } catch (_) {}

    return (
        <div className={`relative w-full h-full group transition-all duration-700 bg-black ${isSpeaking ? 'ring-4 ring-green-500/30' : ''}`}>
            <ParticipantTile
                trackRef={trackRef}
                onParticipantClick={onParticipantClick}
                className={`!w-full !h-full [&_video]:!object-center ${isScreenShare ? '[&_video]:!object-contain bg-black' : '[&_video]:!object-cover'}`}
                {...props}
            />

            {/* Moderator Menu */}
            {showMenu && (
                <div className="absolute top-6 right-6 z-[60]">
                    <button
                        onClick={(e) => {
                            e.stopPropagation();
                            setIsMenuOpen(!isMenuOpen);
                        }}
                        className="p-2.5 rounded-full bg-black/60 hover:bg-black/80 text-white backdrop-blur-xl border border-white/10 shadow-2xl"
                    >
                        <MoreVertical className="w-6 h-6" />
                    </button>
                    {isMenuOpen && (
                        <ParticipantMenu
                            participant={trackRef.participant}
                            closeMenu={() => setIsMenuOpen(false)}
                        />
                    )}
                </div>
            )}

            {/* Large Camera Off Placeholder */}
            {isCameraOff && (
                <div className="absolute inset-0 flex flex-col items-center justify-center bg-gray-950 bg-gradient-to-t from-black via-gray-950 to-gray-900">
                    <div className="w-48 h-48 rounded-full flex items-center justify-center overflow-hidden bg-gray-800 border-8 border-white/5 shadow-[0_0_50px_rgba(0,0,0,0.5)]">
                        {photoURL ? (
                            <img src={photoURL} alt={trackRef.participant.name || 'User'} className="w-full h-full object-cover" />
                        ) : (
                            <span className="text-white font-black text-7xl">
                                {(trackRef.participant.name || trackRef.participant.identity || 'P')[0].toUpperCase()}
                            </span>
                        )}
                    </div>
                    {/* Pinned to the top so the floating control bar never covers it */}
                    <div className="absolute top-16 left-4 z-[60] flex items-center gap-3 bg-black/50 backdrop-blur-2xl rounded-xl px-5 py-2.5 border border-white/10">
                        {!trackRef.participant.isMicrophoneEnabled && <MicOff className="w-5 h-5 text-red-500" />}
                        <span className="text-white text-lg font-bold">
                            {trackRef.participant.name || trackRef.participant.identity || 'Participant'}
                        </span>
                    </div>
                </div>
            )}
        </div>
    );
}
