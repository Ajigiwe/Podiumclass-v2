import { FocusLayout, TrackReferenceOrPlaceholder } from '@livekit/components-react';
import { Track } from 'livekit-client';
import { User } from 'lucide-react';

interface FocusWrapperProps {
    trackRef: TrackReferenceOrPlaceholder;
    onParticipantClick?: () => void;
    [key: string]: any;
}

export function FocusWrapper({ trackRef, onParticipantClick, ...props }: FocusWrapperProps) {
    const isCameraOff = trackRef.source === Track.Source.Camera &&
        (trackRef.publication?.isMuted || !trackRef.participant.isCameraEnabled);

    return (
        <div className="relative w-full h-full group">
            <FocusLayout trackRef={trackRef} onParticipantClick={onParticipantClick} {...props} />

            {/* Explicit Placeholder for Camera Off in Focus Mode */}
            {isCameraOff && (
                <div className="absolute inset-0 flex flex-col items-center justify-center bg-gray-900 border-2 border-dashed border-gray-800 rounded-lg m-4">
                    <div className="w-32 h-32 bg-gray-800 rounded-full flex items-center justify-center mb-6 shadow-2xl ring-4 ring-white/5">
                        <User className="w-16 h-16 text-gray-400" />
                    </div>
                    <span className="text-gray-200 font-bold text-2xl tracking-tight">
                        {trackRef.participant.name || trackRef.participant.identity || 'Participant'}
                    </span>
                    <span className="text-gray-500 mt-2 text-sm uppercase tracking-widest font-semibold">Camera Off</span>
                </div>
            )}
        </div>
    );
}
