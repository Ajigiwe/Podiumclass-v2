'use client';

import { useMemo } from 'react';
import { TrackReferenceOrPlaceholder } from '@livekit/components-react';
import { Track } from 'livekit-client';
import { TileWrapper } from './TileWrapper';
import { ChevronLeft, ChevronRight } from 'lucide-react';

interface VideoGridProps {
    tracks: TrackReferenceOrPlaceholder[];
    currentPage: number;
    totalPages: number;
    onPrevPage: () => void;
    onNextPage: () => void;
    onTileClick: (track: TrackReferenceOrPlaceholder) => void;
}

export function VideoGrid({
    tracks,
    currentPage,
    totalPages,
    onPrevPage,
    onNextPage,
    onTileClick,
}: VideoGridProps) {
    const hasScreenShare = tracks.some(t => t.source === Track.Source.ScreenShare);

    const gridCols = useMemo(() => {
        const count = tracks.length;
        if (count === 1) return 'grid-cols-1 max-w-sm sm:max-w-lg';
        if (count === 2) return 'grid-cols-1 sm:grid-cols-2 max-w-2xl';
        if (count <= 4) return 'grid-cols-1 sm:grid-cols-2 max-w-4xl';
        if (count <= 9) return 'grid-cols-2 sm:grid-cols-3';
        return 'grid-cols-2 sm:grid-cols-3 lg:grid-cols-4';
    }, [tracks.length]);

    if (hasScreenShare) {
        return (
            <div className="absolute inset-0 overflow-hidden bg-black">
                {tracks.filter(t => t.source === Track.Source.ScreenShare).map(trackRef => (
                    <div key={trackRef.participant.sid + '_screen'}>
                        <div className="block sm:hidden" style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, zIndex: 200, background: 'black', overflow: 'hidden' }}>
                            <div style={{ position: 'absolute', top: '50%', left: '50%', width: '100vh', height: '100vw', transform: 'translate(-50%, -50%) rotate(90deg)', transformOrigin: 'center center' }}>
                                <TileWrapper track={trackRef} participant={trackRef.participant} onTileClick={onTileClick} className="w-full h-full !rounded-none" />
                            </div>
                        </div>
                        <div className="hidden sm:block absolute inset-0">
                            <TileWrapper track={trackRef} participant={trackRef.participant} onTileClick={onTileClick} className="w-full h-full !rounded-none" />
                        </div>
                    </div>
                ))}
            </div>
        );
    }

    return (
        // Bottom padding mirrors the floating control bar's footprint (bar ~76px on
        // mobile, ~60px on sm+) so tile name tags are never covered by it.
        <div className="absolute inset-0 flex flex-col items-center justify-center pb-[104px] sm:pb-[88px] pt-4 px-4 sm:pt-8 sm:px-8">
            <div className={`grid ${gridCols} gap-2 sm:gap-3 w-full max-w-6xl`}>
                {tracks.map((trackRef) => (
                    <div
                        key={trackRef.participant.sid + '_' + trackRef.source}
                        className="aspect-video min-h-0"
                    >
                        <TileWrapper
                            track={trackRef}
                            participant={trackRef.participant}
                            onTileClick={onTileClick}
                            className="w-full h-full rounded-lg overflow-hidden"
                        />
                    </div>
                ))}
            </div>

            {totalPages > 1 && (
                <div className="flex items-center justify-center gap-4 mt-3">
                    <button
                        onClick={onPrevPage}
                        disabled={currentPage === 1}
                        className="p-1.5 rounded-lg text-white/50 disabled:opacity-20 hover:text-white hover:bg-white/10 transition-colors"
                    >
                        <ChevronLeft className="w-5 h-5" />
                    </button>
                    <span className="text-white/50 text-xs font-semibold tabular-nums min-w-[3rem] text-center">
                        {currentPage} / {totalPages}
                    </span>
                    <button
                        onClick={onNextPage}
                        disabled={currentPage === totalPages}
                        className="p-1.5 rounded-lg text-white/50 disabled:opacity-20 hover:text-white hover:bg-white/10 transition-colors"
                    >
                        <ChevronRight className="w-5 h-5" />
                    </button>
                </div>
            )}
        </div>
    );
}
