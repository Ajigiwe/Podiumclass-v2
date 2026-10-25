import { X, Hand } from 'lucide-react';
import { RaisedHand } from '@/types/layout';

interface RaisedHandsBannerProps {
    isLecturer: boolean;
    raisedHands: RaisedHand[];
    onClearAll: () => void;
    onLowerHand: (id: string) => void;
}

export const RaisedHandsBanner = ({ isLecturer, raisedHands, onClearAll, onLowerHand }: RaisedHandsBannerProps) => {
    if (raisedHands.length === 0) return null;

    // Sort by timestamp (oldest first)
    const sortedHands = [...raisedHands].sort((a, b) => a.timestamp - b.timestamp);

    return (
        <div className="absolute top-4 left-1/2 transform -translate-x-1/2 z-[9999] max-w-lg w-full px-4 pointer-events-none">
            <div className="bg-yellow-500 text-black px-3 py-2 rounded-lg flex items-center justify-between animate-slide-down border border-yellow-600 pointer-events-auto">
                <div className="flex items-center gap-2">
                    <Hand className="w-4 h-4 animate-bounce" />
                    <div>
                        <p className="font-bold text-xs sm:text-sm leading-tight">
                            {sortedHands.length} student{sortedHands.length > 1 ? 's' : ''} raised their hand
                        </p>
                        <p className="text-[10px] sm:text-xs font-medium opacity-90 truncate max-w-[200px] sm:max-w-md">
                            {sortedHands.map(h => h.participantName).join(', ')}
                        </p>
                    </div>
                </div>

                {isLecturer && (
                    <button
                        onClick={onClearAll}
                        className="p-1 hover:bg-yellow-600 rounded-md transition-colors ml-3"
                        title="Clear all raised hands"
                    >
                        <X className="w-4 h-4" />
                    </button>
                )}
            </div>
        </div>
    );
};
