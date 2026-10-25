'use client';

import { useRef, useEffect } from 'react';
import { Participant } from 'livekit-client';
import { useClassroom } from '@/contexts/ClassroomContext';
import { MicOff, VideoOff, UserX } from 'lucide-react';

interface ParticipantMenuProps {
    participant: Participant;
    closeMenu: () => void;
}

export function ParticipantMenu({ participant, closeMenu }: ParticipantMenuProps) {
    const { isModerator, muteParticipant, disableParticipantVideo, kickParticipant } = useClassroom();
    const menuRef = useRef<HTMLDivElement>(null);

    // Close on click outside
    useEffect(() => {
        function handleClickOutside(event: MouseEvent) {
            if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
                closeMenu();
            }
        }
        document.addEventListener("mousedown", handleClickOutside);
        return () => {
            document.removeEventListener("mousedown", handleClickOutside);
        };
    }, [closeMenu]);

    if (!isModerator) return null;

    return (
        <div
            ref={menuRef}
            className="absolute top-10 right-2 z-50 bg-gray-950 border border-gray-800 rounded-lg w-48 py-2 shadow-2xl backdrop-blur-xl animate-in fade-in zoom-in duration-200"
            onClick={(e) => e.stopPropagation()}
        >
            <button
                onClick={() => { muteParticipant(participant.sid); closeMenu(); }}
                className="w-full text-left px-4 py-2 text-sm text-gray-300 hover:bg-gray-800 flex items-center gap-3 transition-colors"
            >
                <MicOff className="w-4 h-4 text-gray-500" />
                <span className="font-medium">Mute Audio</span>
            </button>
            <button
                onClick={() => { disableParticipantVideo(participant.sid); closeMenu(); }}
                className="w-full text-left px-4 py-2 text-sm text-gray-300 hover:bg-gray-800 flex items-center gap-3 transition-colors"
            >
                <VideoOff className="w-4 h-4 text-gray-500" />
                <span className="font-medium">Stop Video</span>
            </button>
            <div className="h-px bg-gray-800 my-2 mx-2" />
            <button
                onClick={() => { kickParticipant(participant.sid); closeMenu(); }}
                className="w-full text-left px-4 py-2 text-sm text-red-400 hover:bg-red-500/10 flex items-center gap-3 transition-colors"
            >
                <UserX className="w-4 h-4" />
                <span className="font-medium">Remove from Class</span>
            </button>
        </div>
    );
}
