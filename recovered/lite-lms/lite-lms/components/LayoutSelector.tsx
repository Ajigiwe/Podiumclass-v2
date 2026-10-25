import { Grid2X2, Grid3X3, LayoutGrid, Grid, Square } from 'lucide-react';
import { GridLayout } from '@/types/layout';

interface LayoutSelectorProps {
    currentLayout: GridLayout;
    onLayoutChange: (layout: GridLayout) => void;
}

export const LayoutSelector = ({ currentLayout, onLayoutChange }: LayoutSelectorProps) => {
    return (
        <div className="flex gap-2 bg-gray-900 p-1.5 rounded-lg border border-white/10">
            <button
                onClick={() => onLayoutChange('spotlight')}
                className={`p-2 rounded-md transition-all ${currentLayout === 'spotlight'
                    ? 'bg-blue-600 text-white border-2 border-blue-400'
                    : 'text-gray-400 hover:text-white hover:bg-gray-800'
                    }`}
                title="Spotlight (1 participant)"
            >
                <Square className="w-4 h-4" />
            </button>

            <button
                onClick={() => onLayoutChange('2x2')}
                className={`p-2 rounded-md transition-all ${currentLayout === '2x2'
                    ? 'bg-blue-600 text-white border-2 border-blue-400'
                    : 'text-gray-400 hover:text-white hover:bg-gray-800'
                    }`}
                title="2x2 Grid (4 participants)"
            >
                <Grid2X2 className="w-4 h-4" />
            </button>

            <button
                onClick={() => onLayoutChange('4x4')}
                className={`p-2 rounded-md transition-all ${currentLayout === '4x4'
                    ? 'bg-blue-600 text-white border-2 border-blue-400'
                    : 'text-gray-400 hover:text-white hover:bg-gray-800'
                    }`}
                title="4x4 Grid (16 participants)"
            >
                <Grid className="w-4 h-4" />
            </button>

            <button
                onClick={() => onLayoutChange('5x5')}
                className={`p-2 rounded-md transition-all ${currentLayout === '5x5'
                    ? 'bg-blue-600 text-white border-2 border-blue-400'
                    : 'text-gray-400 hover:text-white hover:bg-gray-800'
                    }`}
                title="5x5 Grid (25 participants)"
            >
                <Grid3X3 className="w-4 h-4" />
            </button>

        </div>
    );
};
