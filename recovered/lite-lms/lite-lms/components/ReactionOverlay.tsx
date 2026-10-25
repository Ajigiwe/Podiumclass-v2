import { useEffect, useState, useCallback, forwardRef, useImperativeHandle } from 'react';
import { useRoomContext } from '@livekit/components-react';
import { DataPacket_Kind } from 'livekit-client';

interface Reaction {
    id: string;
    emoji: string;
    x: number;
}

export interface ReactionOverlayHandle {
    addReaction: (emoji: string) => void;
}

const ReactionOverlay = forwardRef<ReactionOverlayHandle, object>((props, ref) => {
    const room = useRoomContext();
    const [reactions, setReactions] = useState<Reaction[]>([]);

    const addReaction = useCallback((emoji: string) => {
        const id = Math.random().toString(36).substring(7);
        const x = Math.floor(Math.random() * 80) + 10;

        const newReaction = { id, emoji, x };
        setReactions(prev => [...prev, newReaction]);

        setTimeout(() => {
            setReactions(prev => prev.filter(r => r.id !== id));
        }, 2000);
    }, []);

    useImperativeHandle(ref, () => ({
        addReaction
    }));

    useEffect(() => {
        if (!room) return;

        const handleData = (
            payload: Uint8Array,
            participant?: any,
            kind?: DataPacket_Kind,
            topic?: string
        ) => {
            if (topic === 'reaction') {
                const decoder = new TextDecoder();
                const strData = decoder.decode(payload);
                try {
                    const data = JSON.parse(strData);
                    if (data.type === 'reaction' && data.emoji) {
                        addReaction(data.emoji);
                    }
                } catch (e) {
                    console.error('Failed to parse reaction:', e);
                }
            }
        };

        room.on('dataReceived', handleData);
        return () => {
            room.off('dataReceived', handleData);
        };
    }, [room, addReaction]);

    return (
        <div className="absolute inset-0 pointer-events-none overflow-hidden z-[9999]">
            <style>{`
                @keyframes floatUp {
                    0% { transform: translateY(0) scale(0.5); opacity: 0; }
                    10% { opacity: 1; transform: translateY(-20px) scale(1.2); }
                    100% { transform: translateY(-200px) scale(1); opacity: 0; }
                }
            `}</style>
            {reactions.map(r => (
                <div
                    key={r.id}
                    className="absolute bottom-20 text-4xl"
                    style={{
                        left: `${r.x}%`,
                        animation: 'floatUp 2s ease-out forwards',
                        textShadow: '0 2px 10px rgba(0,0,0,0.3)'
                    }}
                >
                    {r.emoji}
                </div>
            ))}
        </div>
    );
});

ReactionOverlay.displayName = 'ReactionOverlay';

export default ReactionOverlay;
