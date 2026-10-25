import { useState, useEffect, useCallback } from 'react';
import {
    Users,
    Zap,
    Download,
    Play,
    CheckCircle2,
    AlertCircle,
    LayoutDashboard,
    Clock,
    RefreshCw
} from 'lucide-react';
import { useAuth } from '@/contexts/AuthContext';
import { useAlert } from '@/contexts/AlertContext';

interface LecturerAttendanceControlsProps {
    sessionId: string;
    isActive: boolean;
}

export const LecturerAttendanceControls = ({ sessionId, isActive }: LecturerAttendanceControlsProps) => {
    const { user } = useAuth();
    const { showAlert } = useAlert();
    const [isStarting, setIsStarting] = useState(false);
    const [isTriggering, setIsTriggering] = useState(false);
    const [stats, setStats] = useState<{
        status: string;
        startedAt: number | null;
        durationMinutes: number;
        expectedVerificationCount: number;
        scheduledVerifications: number[];
        triggeredCount: number;
    } | null>(null);

    const [minutesElapsed, setMinutesElapsed] = useState(0);

    // Initial load and recovery
    const fetchStatus = useCallback(async () => {
        try {
            const token = user ? await user.getIdToken() : null;
            const response = await fetch(`/api/attendance/session/status/${sessionId}`, {
                headers: token ? { Authorization: `Bearer ${token}` } : {}
            });
            if (response.ok) {
                const data = await response.json();
                if (data.success && data.status === 'active') {
                    setStats({
                        status: data.status,
                        startedAt: data.startedAt,
                        durationMinutes: data.durationMinutes,
                        expectedVerificationCount: data.expectedVerificationCount,
                        scheduledVerifications: data.scheduledVerifications,
                        triggeredCount: data.triggeredCount
                    });
                } else {
                    setStats(null);
                }
            }
        } catch (error) {
            console.error('Error fetching attendance status:', error);
        }
    }, [sessionId, user]);

    useEffect(() => {
        fetchStatus();
    }, [fetchStatus]);

    // Timer for minutes elapsed
    useEffect(() => {
        if (!stats?.startedAt) return;

        const updateElapsed = () => {
            const now = Date.now();
            const elapsed = Math.floor((now - stats.startedAt!) / 60000);
            setMinutesElapsed(elapsed);
        };

        updateElapsed();
        const interval = setInterval(updateElapsed, 10000); // Update every 10s
        return () => clearInterval(interval);
    }, [stats?.startedAt]);

    const handleStartAttendance = async () => {
        if (!user) return;
        setIsStarting(true);
        try {
            const token = await user.getIdToken();
            const response = await fetch('/api/attendance/session/start', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
                body: JSON.stringify({
                    sessionId,
                    lecturerId: user.uid
                    // durationMinutes and verificationCount fetched from session on backend
                })
            });

            if (response.ok) {
                await fetchStatus(); // Refresh state from server
            } else {
                const error = await response.json();
                showAlert(error.error || 'Failed to start attendance', 'error');
            }
        } catch (error) {
            console.error('Error starting attendance:', error);
            showAlert('An unexpected error occurred', 'error');
        } finally {
            setIsStarting(false);
        }
    };

    const triggerManualCheck = async () => {
        setIsTriggering(true);
        try {
            const token = user ? await user.getIdToken() : null;
            const response = await fetch('/api/attendance/verification/trigger', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    ...(token ? { Authorization: `Bearer ${token}` } : {})
                },
                body: JSON.stringify({ sessionId })
            });
            if (response.ok) {
                await fetchStatus(); // Sync count
            }
        } catch (error) {
            console.error('Error triggering check:', error);
        } finally {
            setIsTriggering(false);
        }
    };

    if (!isActive) return null;

    const nextChecks = stats?.scheduledVerifications.filter(m => m > minutesElapsed) || [];

    return (
        <div className="bg-gray-900 border border-gray-800 rounded-lg p-5">
            <div className="flex items-center justify-between mb-5 border-b border-gray-800 pb-4">
                <div className="flex items-center gap-3">
                    <div className="w-10 h-10 bg-blue-600/20 rounded-md flex items-center justify-center border border-blue-500/20">
                        <CheckCircle2 className="w-5 h-5 text-blue-500" />
                    </div>
                    <div>
                        <h3 className="text-xs font-black text-white uppercase tracking-widest">Attendance</h3>
                        <p className="text-[10px] text-gray-500 font-bold uppercase tracking-tighter">System Console</p>
                    </div>
                </div>
                {stats && (
                    <button
                        onClick={fetchStatus}
                        className="p-2 text-gray-500 hover:text-white hover:bg-gray-800 rounded-md transition-all"
                        title="Refresh Status"
                    >
                        <RefreshCw className="w-4 h-4" />
                    </button>
                )}
            </div>

            {!stats ? (
                <div className="space-y-4">
                    <div className="bg-blue-600/5 border border-blue-500/10 rounded-lg p-4 text-center">
                        <p className="text-xs text-gray-400 leading-relaxed mb-4">
                            Activate the attendance monitoring using the settings pre-configured during class creation.
                        </p>
                        <button
                            onClick={handleStartAttendance}
                            disabled={isStarting}
                            className="w-full bg-blue-600 hover:bg-blue-700 disabled:bg-blue-800 text-white text-xs font-black py-3 rounded-md flex items-center justify-center gap-2 transition-all border border-blue-500 active:scale-[0.98]"
                        >
                            {isStarting ? (
                                <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                            ) : (
                                <Play className="w-4 h-4 fill-current" />
                            )}
                            START ATTENDANCE MONITORING
                        </button>
                    </div>
                </div>
            ) : (
                <div className="space-y-5">
                    <div className="grid grid-cols-2 gap-3">
                        <div className="bg-gray-800/40 rounded-lg p-3 border border-gray-700/30">
                            <div className="flex items-center gap-1.5 mb-1">
                                <span className="w-2 h-2 bg-green-500 rounded-full animate-pulse" />
                                <span className="text-[9px] font-black text-gray-500 uppercase tracking-widest">Status</span>
                            </div>
                            <p className="text-sm text-white font-bold tabular-nums">ACTIVE</p>
                        </div>
                        <div className="bg-gray-800/40 rounded-lg p-3 border border-gray-700/30">
                            <div className="flex items-center gap-1.5 mb-1">
                                <Zap className="w-2.5 h-2.5 text-blue-400" />
                                <span className="text-[9px] font-black text-gray-500 uppercase tracking-widest">Triggers</span>
                            </div>
                            <p className="text-sm text-white font-bold tabular-nums">
                                {stats.triggeredCount} / {stats.expectedVerificationCount}
                            </p>
                        </div>
                    </div>

                    <div className="space-y-2">
                        <button
                            onClick={triggerManualCheck}
                            disabled={isTriggering}
                            className="w-full bg-indigo-600 hover:bg-indigo-700 disabled:bg-gray-800 text-white text-[11px] font-black py-3 rounded-md flex items-center justify-center gap-2 transition-all border border-indigo-500/30 active:scale-[0.98]"
                        >
                            {isTriggering ? (
                                <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                            ) : (
                                <Zap className="w-4 h-4" />
                            )}
                            TRIGGER MANUAL CHECK
                        </button>
                    </div>

                    {nextChecks.length > 0 && (
                        <div className="pt-4 border-t border-gray-800">
                            <div className="flex items-center justify-between mb-3 px-1">
                                <span className="text-[9px] font-black text-gray-500 uppercase tracking-widest">Upcoming Auto-Checks</span>
                                <span className="text-[9px] font-bold text-blue-500">Min {minutesElapsed}</span>
                            </div>
                            <div className="flex gap-2 overflow-x-auto pb-1 no-scrollbar">
                                {nextChecks.map((m, i) => (
                                    <div
                                        key={i}
                                        className="px-3 py-1.5 bg-gray-800/60 border border-gray-700/50 rounded text-[10px] text-gray-300 font-mono whitespace-nowrap"
                                    >
                                        ~{m}m
                                    </div>
                                ))}
                            </div>
                        </div>
                    )}
                </div>
            )}
        </div>
    );
};
