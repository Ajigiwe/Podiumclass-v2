'use client';

import { Users, Folder, Share2, Menu, Power, Home } from 'lucide-react';
import dynamic from 'next/dynamic';
import type { Session } from '@/lib/firebase/types';

const RecordingControls = dynamic(() => import('../RecordingControls').then(mod => mod.RecordingControls), {
    ssr: false, loading: () => <div className="h-8 w-24 bg-white/5 animate-pulse rounded-lg" />
});
const SimpleAttendanceConsole = dynamic(() => import('../attendance/SimpleAttendanceConsole').then(mod => mod.SimpleAttendanceConsole), {
    ssr: false, loading: () => <div className="h-8 w-28 bg-white/5 animate-pulse rounded-lg" />
});
const CoHostManagementPanel = dynamic(() => import('../CoHostManagementPanel').then(mod => mod.CoHostManagementPanel), {
    ssr: false, loading: () => <div className="h-8 w-24 bg-white/5 animate-pulse rounded-lg" />
});

interface ClassroomHeaderProps {
    title: string;
    isActive: boolean;
    isModerator: boolean;
    isHost: boolean;
    participantCount: number;
    pendingRequestCount: number;
    hasNewMaterials: boolean;
    sessionId: string;
    ctxSessionId: string | null;
    ctxUserId: string | null;
    ctxTitle: string | null;
    onOpenParticipants: () => void;
    onOpenMaterials: () => void;
    onOpenShare: () => void;
    onOpenMobileMenu: () => void;
    onEndSession: () => void;
}

export function ClassroomHeader({
    title,
    isActive,
    isModerator,
    isHost,
    participantCount,
    pendingRequestCount,
    hasNewMaterials,
    sessionId,
    ctxSessionId,
    ctxUserId,
    ctxTitle,
    onOpenParticipants,
    onOpenMaterials,
    onOpenShare,
    onOpenMobileMenu,
    onEndSession,
}: ClassroomHeaderProps) {
    return (
        <header className="fixed top-0 inset-x-0 z-[100] h-14 sm:h-16 flex items-center bg-black border-b border-white/[0.06]">
            <div className="w-full px-3 sm:px-6 flex items-center justify-between gap-2 sm:gap-4">
                <div className="flex items-center gap-3 min-w-0 flex-1">
                    <button
                        onClick={() => window.location.href = '/'}
                        className="sm:hidden p-2 -ml-2 text-slate-400 hover:text-white transition-colors"
                    >
                        <Home className="w-4 h-4" />
                    </button>
                    <div className="min-w-0">
                        <h1 className="text-[13px] sm:text-base font-bold text-white truncate leading-tight max-w-[120px] xs:max-w-[180px] sm:max-w-xs">{title}</h1>
                        <div className="flex items-center gap-1.5">
                            <span className={`w-1.5 h-1.5 rounded-full ${isActive ? 'bg-emerald-500 animate-pulse' : 'bg-slate-500'}`} />
                            <span className="text-[9px] font-black uppercase tracking-widest text-slate-500">{isActive ? 'Live' : 'Offline'}</span>
                        </div>
                    </div>
                </div>

                <div className="flex items-center gap-1.5 sm:gap-3">
                    {isModerator && (
                        <div className="hidden lg:flex items-center bg-white/5 rounded-xl p-1 gap-0.5 border border-white/5">
                            <RecordingControls roomId={ctxSessionId || ''} lecturerId={ctxUserId || ''} classTitle={ctxTitle || 'Untitled'} isLecturer={isModerator} />
                            <div className="w-px h-6 bg-white/10" />
                            <SimpleAttendanceConsole sessionId={sessionId} isActive={isActive} />
                            {isHost && (
                                <>
                                    <div className="w-px h-6 bg-white/10" />
                                    <CoHostManagementPanel sessionId={sessionId} />
                                    <div className="w-px h-6 bg-white/10" />
                                    <button onClick={onEndSession} className="h-8 w-8 flex items-center justify-center text-red-500 hover:bg-red-500/10 rounded-lg transition-all" title="End Session">
                                        <Power className="w-4 h-4" />
                                    </button>
                                </>
                            )}
                        </div>
                    )}

                    <div className="flex items-center gap-1">
                        <button onClick={onOpenParticipants} className="relative h-8 sm:h-9 px-2 sm:px-3 text-[9px] sm:text-[10px] font-black uppercase tracking-widest text-white bg-indigo-600 hover:bg-indigo-500 rounded-xl transition-all flex items-center gap-1.5 sm:gap-2 shadow-lg shadow-indigo-600/20 border border-indigo-400/20">
                            <Users className="w-3 h-3 sm:w-3.5 sm:h-3.5" />
                            <span className="hidden xs:inline">{isModerator ? 'People' : 'People'}</span>
                            <span className="bg-white/20 px-1 py-0.5 rounded text-[8px] sm:text-[10px] tabular-nums">{participantCount}</span>
                            {isModerator && pendingRequestCount > 0 && (
                                <span className="absolute -top-1.5 -right-1.5 bg-red-500 text-white text-[9px] font-black w-4.5 h-4.5 flex items-center justify-center rounded-full border-2 border-slate-900 animate-bounce">
                                    {pendingRequestCount > 9 ? '9+' : pendingRequestCount}
                                </span>
                            )}
                        </button>

                        <button onClick={onOpenMaterials} className="relative h-8 sm:h-9 px-2 sm:px-3 text-[9px] sm:text-[10px] font-black uppercase tracking-widest text-white bg-white/5 hover:bg-white/10 rounded-xl transition-all border border-white/5 flex items-center gap-1.5 sm:gap-2">
                            <Folder className="w-3 h-3 sm:w-3.5 sm:h-3.5" />
                            <span className="hidden xs:inline">Files</span>
                            {hasNewMaterials && (
                                <span className="absolute -top-1 -right-1 w-2 h-2 bg-indigo-500 rounded-full border-2 border-slate-900 animate-pulse" />
                            )}
                        </button>

                        <button onClick={onOpenShare} className="hidden md:flex h-9 px-3 text-[10px] font-black uppercase tracking-widest text-white bg-white/5 hover:bg-white/10 rounded-xl transition-all border border-white/5 items-center gap-2">
                            <Share2 className="w-3.5 h-3.5" />
                            <span className="hidden lg:inline">Invite</span>
                        </button>

                        <button onClick={onOpenMobileMenu} className="p-2 sm:p-2.5 text-slate-400 hover:text-white bg-white/5 hover:bg-white/10 rounded-xl transition-all border border-white/5">
                            <Menu className="w-4 h-4 sm:w-5 sm:h-5" />
                        </button>
                    </div>
                </div>
            </div>
        </header>
    );
}
