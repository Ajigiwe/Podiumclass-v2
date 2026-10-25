import { X } from 'lucide-react';

interface ReactionModalProps {
    isOpen: boolean;
    onClose: () => void;
    onReaction: (emoji: string) => void;
    emojis: string[];
}

export const ReactionModal = ({ isOpen, onClose, onReaction, emojis }: ReactionModalProps) => {
    if (!isOpen) return null;

    return (
        <div className="fixed inset-0 z-[99999] flex items-center justify-center p-4">
            {/* Backdrop */}
            <div
                className="absolute inset-0 bg-black/60"
                onClick={onClose}
            />

            {/* Modal Content */}
            <div id="reaction-popover" className="relative bg-gray-900 border border-gray-800 rounded-lg p-4 w-auto max-w-[240px] animate-in zoom-in-95 duration-200">
                <div className="flex items-center justify-between mb-3">
                    <h3 className="text-sm font-bold text-white">Reactions</h3>
                    <button
                        onClick={onClose}
                        className="p-1 hover:bg-gray-800 rounded-full text-gray-400 hover:text-white transition-colors"
                    >
                        <X className="w-4 h-4" />
                    </button>
                </div>

                <div className="grid grid-cols-4 gap-2">
                    {emojis.map(emoji => (
                        <button
                            key={emoji}
                            onClick={() => {
                                onReaction(emoji);
                                onClose();
                            }}
                            className="aspect-square flex items-center justify-center text-2xl hover:bg-gray-800 rounded-md transition-all hover:scale-110 active:scale-95"
                        >
                            {emoji}
                        </button>
                    ))}
                </div>
            </div>
        </div>
    );
};
