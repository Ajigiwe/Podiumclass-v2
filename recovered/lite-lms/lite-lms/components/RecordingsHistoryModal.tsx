'use client';

import { X, Video, Sparkles } from 'lucide-react';
import { RecordingsDashboard } from './RecordingsDashboard';

interface RecordingsHistoryModalProps { isOpen: boolean; onClose: () => void; lecturerId: string; }

export default function RecordingsHistoryModal({ isOpen, onClose, lecturerId }: RecordingsHistoryModalProps) {
    if (!isOpen) return null;

    return (
        <div className="fixed inset-0 z-[120] flex items-center justify-center p-8 animate-in fade-in duration-500">
            <div className="absolute inset-0 bg-slate-900/40 backdrop-blur-sm" onClick={onClose} />
            <div className="relative w-full max-w-4xl bg-white rounded-3xl p-10 border border-slate-100 shadow-2xl flex flex-col max-h-[85vh] animate-in zoom-in-95 duration-300 overflow-hidden">
                <div className="flex justify-between items-center mb-10 flex-shrink-0">
                    <div className="space-y-1">
                        <p className="text-[10px] font-bold text-slate-400 uppercase tracking-[0.3em] ml-1">Archive Repository</p>
                        <h2 className="text-3xl font-serif text-slate-900 tracking-tight">Class <span className="italic">Recordings</span></h2>
                    </div>
                    <button onClick={onClose} className="p-2 text-slate-400 hover:text-slate-900 transition-colors active:scale-90"><X className="w-6 h-6" /></button>
                </div>
                <div className="flex-1 overflow-y-auto min-h-0 pr-4 custom-scrollbar">
                    <RecordingsDashboard lecturerId={lecturerId} showTitle={false} />
                </div>
            </div>
        </div>
    );
}
