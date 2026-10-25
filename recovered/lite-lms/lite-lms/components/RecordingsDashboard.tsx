'use client';

import { useEffect, useState } from 'react';
import { Download, Clock, HardDrive, Video, Calendar, RefreshCw, ChevronRight } from 'lucide-react';
import { useAlert } from '@/contexts/AlertContext';
import { Skeleton } from './ui/Skeleton';

interface Recording { id: string; roomId: string; egressId: string; classTitle: string; status: string; durationSeconds: number; fileSizeBytes: number; startedAt: string; endedAt: string; createdAt: string; }
interface RecordingsDashboardProps { lecturerId: string; showTitle?: boolean; }

export const RecordingsDashboard = ({ lecturerId, showTitle = false }: RecordingsDashboardProps) => {
    const [recordings, setRecordings] = useState<Recording[]>([]);
    const [loading, setLoading] = useState(true);
    const { showAlert } = useAlert();

    useEffect(() => { if (lecturerId) fetchRecordings(); }, [lecturerId]);

    const fetchRecordings = async () => {
        try {
            setLoading(true);
            const { auth } = await import('@/lib/firebase/config');
            const token = await auth.currentUser?.getIdToken();
            const response = await fetch(`/api/recordings/lecturer/${lecturerId}`, {
                headers: token ? { Authorization: `Bearer ${token}` } : {}
            });
            const data = await response.json();
            if (data.success) setRecordings(data.recordings);
        } catch (error) { console.error('Failed to fetch recordings:', error); } finally { setLoading(false); }
    };

    const downloadRecording = async (recordingId: string, classTitle: string) => {
        try {
            const { auth } = await import('@/lib/firebase/config');
            const user = auth.currentUser;
            if (!user) { showAlert('Please sign in to download.', 'error'); return; }
            const token = await user.getIdToken();
            const response = await fetch(`/api/recordings/download/${recordingId}`, {
                headers: { Authorization: `Bearer ${token}` }
            });
            if (!response.ok) {
                const data = await response.json();
                showAlert(data.error || 'Download failed.', 'error');
                return;
            }
            const blob = await response.blob();
            const url = URL.createObjectURL(blob);
            const link = document.createElement('a');
            link.href = url;
            link.download = `${(classTitle || 'Session').replace(/[^a-z0-9]/gi, '_').toLowerCase()}.mp4`;
            link.click();
            URL.revokeObjectURL(url);
        } catch (error) { showAlert('Download failed.', 'error'); }
    };

    const formatDuration = (s: number) => {
        if (!s) return '--:--';
        const m = Math.floor(s / 60); const rs = Math.floor(s % 60);
        return `${m}m ${rs}s`;
    };

    if (loading) return <div className="space-y-6">{[1, 2, 3].map(i => <Skeleton key={i} className="h-28 rounded-2xl bg-slate-50" />)}</div>;
    if (recordings.length === 0) return <div className="text-center py-24 text-slate-400 text-[10px] font-bold uppercase tracking-[0.4em] italic">No preservation logs found.</div>;

    return (
        <div className="space-y-8">
            {showTitle && <div className="flex items-center justify-between px-1"><h2 className="text-2xl font-serif text-slate-900 tracking-tight">Preservation <span className="italic">Archive</span></h2><button onClick={fetchRecordings} className="p-2 text-slate-400 hover:text-slate-900 transition-colors active:rotate-180 duration-500"><RefreshCw className="w-5 h-5" /></button></div>}
            <div className="space-y-6">
                {recordings.map((recording) => (
                    <div key={recording.id} className="group p-6 bg-white border border-slate-100 rounded-2xl flex flex-col md:flex-row md:items-center justify-between gap-8 hover:border-slate-900/20 hover:shadow-xl hover:shadow-slate-200/40 transition-all">
                        <div className="flex items-center gap-8">
                            <div className="w-14 h-14 bg-slate-50 rounded-2xl flex items-center justify-center border border-slate-100 transition-transform group-hover:scale-110"><Video className="w-6 h-6 text-slate-900" /></div>
                            <div className="space-y-1.5">
                                <h4 className="text-lg font-serif text-slate-900 tracking-tight leading-tight">{recording.classTitle || 'Preserved Session'}</h4>
                                <div className="flex flex-wrap items-center gap-5 text-[9px] font-bold text-slate-400 uppercase tracking-widest">
                                    <span className="flex items-center gap-1.5"><Calendar className="w-3.5 h-3.5" /> {new Date(recording.startedAt).toLocaleDateString()}</span>
                                    {recording.durationSeconds > 0 && <span className="flex items-center gap-1.5"><Clock className="w-3.5 h-3.5" /> {formatDuration(recording.durationSeconds)}</span>}
                                    <span className={`px-2 py-0.5 rounded-lg border ${recording.status === 'finished' ? 'bg-emerald-50 text-emerald-600 border-emerald-100' : 'bg-slate-50 text-slate-400 border-slate-100'}`}>{recording.status}</span>
                                </div>
                            </div>
                        </div>
                        {recording.status === 'finished' && <button onClick={() => downloadRecording(recording.id, recording.classTitle)} className="w-full md:w-auto px-6 py-3 bg-slate-900 text-white rounded-xl font-bold text-[9px] uppercase tracking-widest transition-all shadow-xl shadow-slate-900/10 active:scale-95 flex items-center justify-center gap-2.5"><Download className="w-4 h-4" /> Download File</button>}
                    </div>
                ))}
            </div>
        </div>
    );
};
