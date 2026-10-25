'use client';

import { X, Users, Share2, Folder, Power, LogOut } from 'lucide-react';
import dynamic from 'next/dynamic';

const RecordingControls = dynamic(() => import('../RecordingControls').then(mod => mod.RecordingControls), { ssr: false });
const SimpleAttendanceConsole = dynamic(() => import('../attendance/SimpleAttendanceConsole').then(mod => mod.SimpleAttendanceConsole), { ssr: false });
const CoHostManagementPanel = dynamic(() => import('../CoHostManagementPanel').then(mod => mod.CoHostManagementPanel), { ssr: false });

interface MobileDrawerProps {
    open: boolean;
    onClose: () => void;
    isModerator: boolean;
    isHost: boolean;
    participantCount: number;
    hasNewMaterials: boolean;
    sessionId: string;
    ctxSessionId: string | null;
    ctxUserId: string | null;
    ctxTitle: string | null;
    isActive: boolean;
    onOpenParticipants: () => void;
    onOpenShare: () => void;
    onOpenMaterials: () => void;
    onEndSession: () => void;
    onLeave: () => void;
}

export function MobileDrawer({
    open,
    onClose,
    isModerator,
    isHost,
    participantCount,
    hasNewMaterials,
    sessionId,
    ctxSessionId,
    ctxUserId,
    ctxTitle,
    isActive,
    onOpenParticipants,
    onOpenShare,
    onOpenMaterials,
    onEndSession,
    onLeave,
}: MobileDrawerProps) {
    if (!open) return null;

    return (
        <>
            <div className="fixed inset-0 z-[400] bg-black/80 backdrop-blur-sm animate-in fade-in duration-300" onClick={onClose} />
            <div className="fixed inset-y-0 right-0 w-[300px] z-[500] bg-slate-950 shadow-2xl border-l border-white/10 flex flex-col animate-in slide-in-from-right duration-300">
                <div className="p-5 border-b border-white/5 flex items-center justify-between">
                    <h2 className="text-[10px] font-black text-slate-400 uppercase tracking-[0.2em]">Menu</h2>
                    <button onClick={onClose} className="p-2 text-slate-400 hover:text-white bg-white/5 rounded-lg transition-colors"><X className="w-4 h-4" /></button>
                </div>

                <div className="flex-1 overflow-y-auto p-5 space-y-6">
                    <div className="space-y-2">
                        <p className="text-[9px] font-bold text-indigo-400 uppercase tracking-widest px-2">Engagement</p>
                        <button onClick={() => { onOpenParticipants(); onClose(); }} className="w-full flex items-center justify-between p-3.5 bg-white/5 hover:bg-white/10 border border-white/5 rounded-xl transition-all">
                            <div className="flex items-center gap-3">
                                <div className="w-8 h-8 bg-indigo-500/10 rounded-lg flex items-center justify-center text-indigo-400"><Users className="w-4 h-4" /></div>
                                <span className="text-xs font-bold text-white">People</span>
                            </div>
                            <span className="bg-white/10 px-2 py-0.5 rounded text-[10px] font-black text-slate-400">{participantCount}</span>
                        </button>
                        <button onClick={() => { onOpenShare(); onClose(); }} className="w-full flex items-center gap-3 p-3.5 bg-white/5 hover:bg-white/10 border border-white/5 rounded-xl transition-all">
                            <div className="w-8 h-8 bg-blue-500/10 rounded-lg flex items-center justify-center text-blue-400"><Share2 className="w-4 h-4" /></div>
                            <span className="text-xs font-bold text-white">Invite</span>
                        </button>
                        <button onClick={() => { onOpenMaterials(); onClose(); }} className="w-full flex items-center justify-between p-3.5 bg-white/5 hover:bg-white/10 border border-white/5 rounded-xl transition-all">
                            <div className="flex items-center gap-3">
                                <div className="w-8 h-8 bg-slate-500/10 rounded-lg flex items-center justify-center text-white"><Folder className="w-4 h-4" /></div>
                                <span className="text-xs font-bold text-white">Files</span>
                            </div>
                            {hasNewMaterials && <span className="w-2 h-2 bg-indigo-500 rounded-full animate-pulse" />}
                        </button>
                    </div>

                    {isModerator && (
                        <div className="space-y-2">
                            <p className="text-[9px] font-bold text-emerald-400 uppercase tracking-widest px-2">Tools</p>
                            <div className="bg-white/5 border border-white/5 rounded-xl p-4 space-y-3">
                                <RecordingControls roomId={ctxSessionId || ''} lecturerId={ctxUserId || ''} classTitle={ctxTitle || 'Untitled'} isLecturer={isModerator} />
                                <div className="w-full h-px bg-white/5" />
                                <SimpleAttendanceConsole sessionId={sessionId} isActive={isActive} />
                                {isHost && (
                                    <>
                                        <div className="w-full h-px bg-white/5" />
                                        <CoHostManagementPanel sessionId={sessionId} />
                                        <div className="w-full h-px bg-white/5" />
                                        <button onClick={onEndSession} className="w-full flex items-center justify-center gap-2 py-3 bg-red-500/10 hover:bg-red-500 text-red-500 hover:text-white rounded-xl font-black text-[10px] uppercase tracking-widest transition-all">
                                            <Power className="w-4 h-4" /> End Session
                                        </button>
                                    </>
                                )}
                            </div>
                        </div>
                    )}
                </div>

                <div className="p-5 border-t border-white/5">
                    <button onClick={onLeave} className="w-full flex items-center justify-center gap-2 py-4 bg-red-500/10 hover:bg-red-500 text-red-500 hover:text-white rounded-2xl font-black text-[10px] uppercase tracking-widest transition-all">
                        <LogOut className="w-4 h-4" /> Exit Classroom
                    </button>
                </div>
            </div>
        </>
    );
}
