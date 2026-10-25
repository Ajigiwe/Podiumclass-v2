'use client';

import { useState, useEffect } from 'react';
import { useAttendanceVerification } from '@/hooks/useAttendanceVerification';
import { CheckCircle, Clock, ShieldCheck } from 'lucide-react';

interface StudentVerificationModalProps {
    sessionId: string;
}

export const StudentVerificationModal = ({ sessionId }: StudentVerificationModalProps) => {
    const {
        activeVerification,
        respondToVerification,
        isResponding,
        dismissVerification
    } = useAttendanceVerification(sessionId);

    const [timeLeft, setTimeLeft] = useState<number>(0);

    useEffect(() => {
        if (!activeVerification) {
            return;
        }

        const timer = setInterval(() => {
            const remaining = Math.max(0, Math.floor((activeVerification.expiresAt - Date.now()) / 1000));
            setTimeLeft(remaining);

            if (remaining === 0) {
                clearInterval(timer);
                dismissVerification();
            }
        }, 1000);

        return () => clearInterval(timer);
    }, [activeVerification, dismissVerification]);

    const handleVerify = async () => {
        const success = await respondToVerification();
        if (success) {
            dismissVerification();
        }
    };

    if (!activeVerification) return null;

    return (
        <div className="fixed inset-0 z-[700] flex items-center justify-center p-4">
            <div className="absolute inset-0 bg-black/80 animate-in fade-in duration-500" />

            <div className="relative bg-[#0F172A] border-2 border-blue-500/40 rounded-lg p-6 max-w-xs w-full animate-in zoom-in duration-300">
                <div className="text-center mb-6">
                    <div className="relative w-16 h-16 mx-auto mb-4">
                        <div className="absolute inset-0 bg-blue-500/10 rounded-full animate-ping" />
                        <div className="relative w-16 h-16 bg-blue-600/20 rounded-full flex items-center justify-center border border-blue-500/30">
                            <ShieldCheck className="w-8 h-8 text-blue-500" />
                        </div>
                    </div>
                    <h2 className="text-xl font-black text-white mb-1 tracking-tight">
                        Attendance Check
                    </h2>
                    <p className="text-gray-400 text-sm font-medium">
                        Verification <span className="text-blue-400">#{activeVerification.verificationNumber}</span>
                    </p>
                </div>

                <div className="bg-white/5 border border-white/10 rounded-lg p-4 mb-6">
                    <div className="flex items-center justify-between mb-1">
                        <span className="text-[9px] font-black text-gray-500 uppercase tracking-widest">Time Remaining</span>
                    </div>
                    <div className="flex items-center gap-2">
                        <Clock className={`w-4 h-4 ${timeLeft < 10 ? 'text-red-500 animate-bounce' : 'text-blue-500'}`} />
                        <span className={`text-2xl font-black tabular-nums tracking-tighter ${timeLeft < 10 ? 'text-red-500' : 'text-white'}`}>
                            {Math.floor(timeLeft / 60)}:{(timeLeft % 60).toString().padStart(2, '0')}
                        </span>
                    </div>
                    <div className="mt-4 h-1.5 w-full bg-white/5 rounded-full overflow-hidden">
                        <div
                            className={`h-full transition-all duration-1000 ${timeLeft < 10 ? 'bg-red-500' : 'bg-blue-600'}`}
                            style={{ width: `${(timeLeft / (activeVerification.timeLimitSeconds || 30)) * 100}%` }}
                        />
                    </div>
                </div>

                <button
                    onClick={handleVerify}
                    disabled={isResponding || timeLeft === 0}
                    className={`w-full font-black py-4 rounded-md transition-all active:scale-95 flex items-center justify-center gap-2 text-base ${timeLeft < 10
                        ? 'bg-red-600 hover:bg-red-700 border-2 border-red-500'
                        : 'bg-blue-600 hover:bg-blue-700 border-2 border-blue-500'
                        } text-white`}
                >
                    {isResponding ? (
                        <div className="w-5 h-5 bg-white/40 rounded-full animate-pulse" />
                    ) : (
                        <>
                            <CheckCircle className="w-5 h-5" />
                            I AM PRESENT
                        </>
                    )}
                </button>

                <p className="mt-6 text-center text-[10px] text-gray-500 font-bold uppercase tracking-widest opacity-60">
                    Podium Anti-Cheat Protection Active
                </p>
            </div>
        </div>
    );
};
