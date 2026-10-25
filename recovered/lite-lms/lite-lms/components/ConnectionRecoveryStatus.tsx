// components/ConnectionRecoveryStatus.tsx
import { useConnectionRecovery } from '@/hooks/useConnectionRecovery';
import { ConnectionState } from 'livekit-client';
import { Wifi, WifiOff, RefreshCw, AlertCircle, Sparkles } from 'lucide-react';

export const ConnectionRecoveryStatus = () => {
    const { connectionState, isRecovering } = useConnectionRecovery();

    if (connectionState === ConnectionState.Connected && !isRecovering) {
        return null; // Don't show anything when connection is good
    }

    return (
        <div className="fixed top-24 right-8 z-[150] animate-in slide-in-from-right-8 duration-500">
            {connectionState === ConnectionState.Reconnecting && (
                <div className="bg-white border border-slate-100 text-slate-900 px-6 py-4 rounded-[1.5rem] shadow-2xl shadow-slate-200/40 flex items-center gap-4 border-l-4 border-l-amber-500">
                    <div className="w-10 h-10 bg-amber-50 rounded-xl flex items-center justify-center">
                        <RefreshCw className="w-5 h-5 text-amber-600 animate-spin" />
                    </div>
                    <div>
                        <p className="text-sm font-bold tracking-tight">Restoring Presence</p>
                        <p className="text-[10px] font-bold text-slate-400 uppercase tracking-widest">Reconnecting to session...</p>
                    </div>
                </div>
            )}

            {connectionState === ConnectionState.Disconnected && (
                <div className="bg-white border border-slate-100 text-slate-900 px-6 py-4 rounded-[1.5rem] shadow-2xl shadow-slate-200/40 flex items-center gap-4 border-l-4 border-l-red-500">
                    <div className="w-10 h-10 bg-red-50 rounded-xl flex items-center justify-center">
                        <WifiOff className="w-5 h-5 text-red-600" />
                    </div>
                    <div>
                        <p className="text-sm font-bold tracking-tight">Session Severed</p>
                        <p className="text-[10px] font-bold text-slate-400 uppercase tracking-widest">Attempting reconnection</p>
                    </div>
                </div>
            )}

            {isRecovering && connectionState === ConnectionState.Connected && (
                <div className="bg-white border border-slate-100 text-slate-900 px-6 py-4 rounded-[1.5rem] shadow-2xl shadow-slate-200/40 flex items-center gap-4 border-l-4 border-l-slate-900">
                    <div className="w-10 h-10 bg-slate-50 rounded-xl flex items-center justify-center">
                        <Sparkles className="w-5 h-5 text-slate-900 animate-pulse" />
                    </div>
                    <div>
                        <p className="text-sm font-bold tracking-tight">Synchronizing Media</p>
                        <p className="text-[10px] font-bold text-slate-400 uppercase tracking-widest">Restoring audio and video</p>
                    </div>
                </div>
            )}
        </div>
    );
};
