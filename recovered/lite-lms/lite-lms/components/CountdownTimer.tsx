'use client';

import { useState, useEffect, useMemo } from 'react';

interface CountdownTimerProps {
    targetDate: Date;
    onComplete?: () => void;
}

export default function CountdownTimer({ targetDate, onComplete }: CountdownTimerProps) {
    // The ticking clock is the only real state. The remaining time is derived from it
    // during render, so a change of targetDate recomputes immediately without needing
    // an effect to copy the result back into state.
    const [now, setNow] = useState(() => Date.now());

    const timeLeft = useMemo(() => {
        const difference = targetDate.getTime() - now;

        if (difference <= 0) {
            return { hours: 0, minutes: 0, seconds: 0, total: 0 };
        }

        return {
            hours: Math.floor((difference / (1000 * 60 * 60)) % 24),
            minutes: Math.floor((difference / 1000 / 60) % 60),
            seconds: Math.floor((difference / 1000) % 60),
            total: difference
        };
    }, [targetDate, now]);

    const isComplete = timeLeft.total <= 0;

    useEffect(() => {
        if (isComplete) return;

        const timer = setInterval(() => setNow(Date.now()), 1000);
        return () => clearInterval(timer);
    }, [isComplete]);

    useEffect(() => {
        if (isComplete) onComplete?.();
    }, [isComplete, onComplete]);

    if (isComplete) return null;

    return (
        <div className="flex gap-4 justify-center">
            {[
                { label: 'Hours', value: timeLeft.hours },
                { label: 'Minutes', value: timeLeft.minutes },
                { label: 'Seconds', value: timeLeft.seconds }
            ].map((unit, i) => (
                <div key={i} className="flex flex-col items-center">
                    <div className="w-16 h-16 bg-gray-900 border border-gray-800 rounded-md flex items-center justify-center mb-1">
                        <span className="text-2xl font-black text-blue-400">
                            {unit.value.toString().padStart(2, '0')}
                        </span>
                    </div>
                    <span className="text-[10px] uppercase tracking-wider font-bold text-gray-500">
                        {unit.label}
                    </span>
                </div>
            ))}
        </div>
    );
}
