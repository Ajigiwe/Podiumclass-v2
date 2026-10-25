import { useState, useEffect } from 'react';
import { Video, Square, Clock } from 'lucide-react';
import { useAlert } from '@/contexts/AlertContext';
import { auth } from '@/lib/firebase/config';

interface RecordingControlsProps {
    roomId: string;
    lecturerId: string;
    classTitle: string;
    isLecturer: boolean;
}

export const RecordingControls = ({
    roomId,
    lecturerId,
    classTitle,
    isLecturer
}: RecordingControlsProps) => {
    console.log('DEBUG: RecordingControls mounted', { roomId, lecturerId, isLecturer });
    const [isRecording, setIsRecording] = useState(false);
    const [egressId, setEgressId] = useState<string | null>(null);
    const [recordingTime, setRecordingTime] = useState(0);
    const [startTime, setStartTime] = useState<number | null>(null);
    const { showAlert, showConfirm } = useAlert();

    // Timer for recording duration
    useEffect(() => {
        if (!isRecording || !startTime) return;

        const interval = setInterval(() => {
            setRecordingTime(Math.floor((Date.now() - startTime) / 1000));
        }, 1000);

        return () => clearInterval(interval);
    }, [isRecording, startTime]);

    const startRecording = async () => {
        try {
            const token = await auth.currentUser?.getIdToken();
            const response = await fetch('/api/recordings/start', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    ...(token ? { Authorization: `Bearer ${token}` } : {}),
                },
                body: JSON.stringify({
                    roomId,
                    classTitle,
                }),
            });

            const data = await response.json();

            if (data.success) {
                setIsRecording(true);
                setEgressId(data.egressId);
                setStartTime(Date.now());
                console.log('Recording started:', data.egressId);
            } else {
                showAlert('Failed to start recording: ' + (data.error || 'Unknown error'), 'error');
            }
        } catch (error) {
            console.error('Failed to start recording:', error);
            showAlert('Failed to start recording', 'error');
        }
    };

    const stopRecording = async () => {
        if (!egressId) return;

        showConfirm('Are you sure you want to stop the recording?', async () => {
            try {
                const token = await auth.currentUser?.getIdToken();
                const response = await fetch('/api/recordings/stop', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        ...(token ? { Authorization: `Bearer ${token}` } : {}),
                    },
                    body: JSON.stringify({
                        egressId,
                        roomId,
                    }),
                });

                const data = await response.json();

                if (data.success) {
                    setIsRecording(false);
                    setEgressId(null);
                    setRecordingTime(0);
                    setStartTime(null);
                    showAlert('Recording saved! You can download it from your dashboard.', 'success');
                } else {
                    showAlert('Failed to stop recording: ' + (data.error || 'Unknown error'), 'error');
                }
            } catch (error) {
                console.error('Failed to stop recording:', error);
                showAlert('Failed to stop recording', 'error');
            }
        }, 'Stop Recording');
    };

    const formatTime = (seconds: number) => {
        const hrs = Math.floor(seconds / 3600);
        const mins = Math.floor((seconds % 3600) / 60);
        const secs = seconds % 60;
        return `${hrs.toString().padStart(2, '0')}:${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
    };

    if (!isLecturer) {
        if (!isRecording) return null;

        return (
            <div className="flex items-center gap-2 px-3 py-1 bg-red-500/10 border border-red-500/20 rounded-lg text-red-500">
                <div className="w-1.5 h-1.5 bg-red-500 rounded-full animate-pulse" />
                <span className="font-black text-[9px] tracking-widest uppercase">Recording</span>
                <span className="font-mono text-[10px] opacity-80 border-l border-red-500/20 pl-2">{formatTime(recordingTime)}</span>
            </div>
        );
    }

    return (
        <div className="flex items-center">
            {!isRecording ? (
                <button
                    onClick={startRecording}
                    className="h-8 px-3 text-[10px] font-black uppercase tracking-widest text-slate-400 hover:text-white hover:bg-red-500/10 rounded-lg transition-all flex items-center gap-2 group"
                    title="Start Recording"
                >
                    <div className="w-1.5 h-1.5 rounded-full bg-slate-600 group-hover:bg-red-500 transition-colors" />
                    <span>Record</span>
                </button>
            ) : (
                <div className="flex items-center h-8 bg-red-600 rounded-lg overflow-hidden border border-red-500 shadow-lg shadow-red-600/20">
                    <div className="px-3 flex items-center gap-2">
                        <div className="w-1.5 h-1.5 bg-white rounded-full animate-pulse" />
                        <span className="font-black text-[9px] tracking-widest uppercase text-white">REC</span>
                        <span className="font-mono text-[10px] text-white/90 border-l border-white/20 pl-2">{formatTime(recordingTime)}</span>
                    </div>
                    <button
                        onClick={stopRecording}
                        className="h-full px-2 bg-black/20 hover:bg-black/30 text-white transition-colors flex items-center justify-center border-l border-white/10"
                        title="Stop Recording"
                    >
                        <Square className="w-2.5 h-2.5 fill-current" />
                    </button>
                </div>
            )}
        </div>
    );
};
