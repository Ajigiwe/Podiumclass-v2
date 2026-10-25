'use client';

import { useState } from 'react';
import { Crown, Shield, UserPlus, UserMinus, X, Users } from 'lucide-react';
import { useClassroom } from '@/contexts/ClassroomContext';

interface CoHostManagementPanelProps {
    sessionId: string;
}

export function CoHostManagementPanel({ sessionId }: CoHostManagementPanelProps) {
    const [showPanel, setShowPanel] = useState(false);
    const {
        isHost,
        coHosts,
        participants,
        userId,
        assignCoHost,
        removeCoHost,
    } = useClassroom();

    // Only rendered for the host — bail early if not host
    if (!isHost) return null;

    // Participants eligible to become co-hosts (not self, not already a co-host)
    const eligibleParticipants = participants.filter(
        (p) =>
            !p.isLocal &&
            p.metadata?.userId !== userId &&
            !coHosts.some((ch) => ch.userId === p.metadata?.userId)
    );

    return (
        <>
            {/* Toolbar Button */}
            <button
                id="cohost-panel-toggle"
                onClick={() => setShowPanel((v) => !v)}
                className={`h-8 flex items-center gap-2 px-3 text-[10px] font-black uppercase tracking-widest transition-all rounded-lg ${
                    showPanel ? 'bg-indigo-600 text-white shadow-lg shadow-indigo-600/20' : 'text-slate-400 hover:text-white hover:bg-white/5'
                }`}
                title="Manage Co-Hosts"
            >
                <Crown className="w-3.5 h-3.5" />
                <span className="hidden lg:inline">Co-Hosts</span>
                {coHosts.length > 0 && (
                    <span className="bg-white/20 text-white px-1.5 py-0.5 rounded text-[9px] tabular-nums">
                        {coHosts.length}
                    </span>
                )}
            </button>

            {/* Panel */}
            {showPanel && (
                <div className="fixed top-16 sm:top-20 right-2 sm:right-4 z-[150] w-[calc(100vw-16px)] sm:w-80 bg-slate-900/90 backdrop-blur-2xl rounded-2xl shadow-[0_20px_50px_rgba(0,0,0,0.5)] border border-white/5 flex flex-col max-h-[70vh] overflow-hidden animate-in fade-in slide-in-from-top-2 duration-300">
                    {/* Header */}
                    <div className="flex items-center justify-between p-4 border-b border-white/5 bg-white/5">
                        <h3 className="text-white font-black uppercase tracking-widest text-[10px] flex items-center gap-2">
                            <Crown className="w-3.5 h-3.5 text-indigo-400" />
                            Co-Host Management
                        </h3>
                        <button
                            onClick={() => setShowPanel(false)}
                            className="text-slate-400 hover:text-white transition-colors"
                        >
                            <X className="w-4 h-4" />
                        </button>
                    </div>

                    <div className="flex-1 overflow-y-auto">
                        {/* Current Co-Hosts */}
                        {coHosts.length > 0 && (
                            <div className="p-4 border-b border-gray-800/60">
                                <p className="text-[10px] font-black uppercase tracking-[0.2em] text-purple-400 mb-2">
                                    Active Co-Hosts ({coHosts.length})
                                </p>
                                <div className="space-y-2">
                                    {coHosts.map((ch) => (
                                        <div
                                            key={ch.userId}
                                            className="flex items-center justify-between bg-purple-600/10 border border-purple-600/20 rounded-md p-3"
                                        >
                                            <div className="flex items-center gap-2 min-w-0">
                                                <div className="w-7 h-7 rounded-md bg-purple-600/30 flex items-center justify-center shrink-0">
                                                    <Shield className="w-3.5 h-3.5 text-purple-400" />
                                                </div>
                                                <p className="text-white font-semibold text-sm truncate">
                                                    {ch.userName}
                                                </p>
                                            </div>
                                            <button
                                                id={`remove-cohost-${ch.userId}`}
                                                onClick={() => removeCoHost(ch.userId)}
                                                className="p-1.5 text-red-400 hover:text-red-300 hover:bg-red-500/10 rounded-md transition-colors shrink-0"
                                                title="Remove co-host"
                                            >
                                                <UserMinus className="w-3.5 h-3.5" />
                                            </button>
                                        </div>
                                    ))}
                                </div>
                            </div>
                        )}

                        {/* Assign New Co-Host */}
                        <div className="p-4">
                            <p className="text-[10px] font-black uppercase tracking-[0.2em] text-gray-500 mb-2">
                                Assign Co-Host
                            </p>
                            {eligibleParticipants.length === 0 ? (
                                <div className="flex flex-col items-center justify-center py-6 text-center">
                                    <Users className="w-8 h-8 text-gray-700 mb-2" />
                                    <p className="text-gray-500 text-xs">
                                        No eligible participants to promote
                                    </p>
                                </div>
                            ) : (
                                <div className="space-y-2">
                                    {eligibleParticipants.map((p) => {
                                        const targetId = p.metadata?.userId || p.identity;
                                        const targetName = p.displayName || 'Unknown';
                                        return (
                                            <div
                                                key={p.participantId}
                                                className="flex items-center justify-between bg-gray-800/50 border border-gray-700 rounded-md p-3 hover:border-purple-600/40 transition-colors"
                                            >
                                                <div className="flex items-center gap-2 min-w-0">
                                                    <div className="w-7 h-7 rounded-md bg-gray-700 flex items-center justify-center text-white text-[10px] font-bold shrink-0">
                                                        {targetName[0]?.toUpperCase() || '?'}
                                                    </div>
                                                    <p className="text-white font-medium text-sm truncate">
                                                        {targetName}
                                                    </p>
                                                </div>
                                                <button
                                                    id={`assign-cohost-${targetId}`}
                                                    onClick={() => assignCoHost(targetId, targetName)}
                                                    className="flex items-center gap-1 px-2.5 py-1.5 bg-purple-600 hover:bg-purple-700 text-white text-xs font-bold rounded-md transition-colors shrink-0"
                                                >
                                                    <UserPlus className="w-3 h-3" />
                                                    Make Co-Host
                                                </button>
                                            </div>
                                        );
                                    })}
                                </div>
                            )}
                        </div>
                    </div>

                    {/* Footer info */}
                    <div className="p-3 bg-purple-900/20 border-t border-purple-600/20">
                        <p className="text-purple-300 text-[10px] flex items-center gap-1.5">
                            <Shield className="w-3 h-3 shrink-0" />
                            Co-hosts have full classroom control. Only you can assign them.
                        </p>
                    </div>
                </div>
            )}
        </>
    );
}
