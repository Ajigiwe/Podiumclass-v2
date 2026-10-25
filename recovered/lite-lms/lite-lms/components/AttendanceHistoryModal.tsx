'use client';

import { useState, useEffect } from 'react';
import { db } from '@/lib/firebase/config';
import { collection, query, where, orderBy, getDocs, Timestamp, doc, getDoc } from '@/lib/firebase/firestore';
import { AttendanceLog } from '@/lib/firebase/types';
import { X, History, Download, Sparkles, User, Calendar, BookOpen } from 'lucide-react';
import { useAlert } from '@/contexts/AlertContext';
import { Skeleton } from './ui/Skeleton';

interface AttendanceHistoryModalProps { isOpen: boolean; onClose: () => void; userId: string; }

export default function AttendanceHistoryModal({ isOpen, onClose, userId }: AttendanceHistoryModalProps) {
    const [historyData, setHistoryData] = useState<any[]>([]);
    const [loadingHistory, setLoadingHistory] = useState(false);
    const { showAlert } = useAlert();

    useEffect(() => { if (isOpen && userId) handleOpenHistory(); }, [isOpen, userId]);

    const handleOpenHistory = async () => {
        setLoadingHistory(true);
        try {
            const logsRef = collection(db, 'attendance_logs');
            const q = query(logsRef, where('lecturerId', '==', userId), orderBy('joinedAt', 'desc'));
            let snapshot;
            try { snapshot = await getDocs(q); } catch (e) { snapshot = await getDocs(query(logsRef, where('lecturerId', '==', userId))); }
            if (!snapshot || !snapshot.docs) { showAlert("Failed to load records.", "error"); setLoadingHistory(false); return; }
            const logs = snapshot.docs.map(doc => doc.data() as AttendanceLog);
            const grouped: Record<string, any> = {};
            logs.forEach(log => {
                if (!log.sessionId) return;
                if (!grouped[log.sessionId]) grouped[log.sessionId] = { sessionId: log.sessionId, title: log.sessionTitle || 'Class', studentIds: new Set(), lastJoined: log.joinedAt };
                if (log.userId) grouped[log.sessionId].studentIds.add(log.userId);
                if (log.joinedAt && (!grouped[log.sessionId].lastJoined || log.joinedAt > grouped[log.sessionId].lastJoined)) grouped[log.sessionId].lastJoined = log.joinedAt;
            });
            setHistoryData(Object.values(grouped).map(item => ({ ...item, count: item.studentIds.size })).sort((a, b) => (b.lastJoined?.seconds || 0) - (a.lastJoined?.seconds || 0)));
        } catch (error) { showAlert("Failed to load records.", "error"); } finally { setLoadingHistory(false); }
    };

    const handleDownloadAttendance = async (sessionId: string, title: string) => {
        try {
            const sessionSnap = await getDoc(doc(db, 'sessions', sessionId));
            let info = { lecturer: 'N/A', program: 'N/A', course: 'N/A', date: 'N/A' };
            if (sessionSnap.exists()) {
                const data = sessionSnap.data();
                info = { lecturer: data.lecturerName || 'N/A', program: data.program || 'N/A', course: data.course || 'N/A', date: data.scheduledStartTime?.toDate().toLocaleDateString() || data.createdAt?.toDate().toLocaleDateString() || 'N/A' };
            }
            const logsSnap = await getDocs(query(collection(db, 'attendance_logs'), where('sessionId', '==', sessionId)));
            const basicLogs = logsSnap.docs.map(doc => doc.data() as AttendanceLog).sort((a, b) => (a.joinedAt?.toMillis?.() || 0) - (b.joinedAt?.toMillis?.() || 0));
            if (basicLogs.length === 0) return showAlert("Registry is empty.", "info");
            const verifSnap = await getDocs(collection(db, 'sessions', sessionId, 'attendance'));
            const verifData: Record<string, number> = {}; verifSnap.docs.forEach(doc => { verifData[doc.id] = doc.data().totalVerificationsCompleted || 0; });
            const csv = [['ATTENDANCE REPORT'], [`Title,${title}`], [`Lecturer,${info.lecturer}`], [`Date,${info.date}`], [`Course,${info.course}`], [], ['Name', 'Email', 'ID', 'Joined', 'Checks'].join(',')];
            const seen = new Set();
            basicLogs.forEach(log => {
                if (seen.has(log.userId)) return; seen.add(log.userId);
                csv.push([`"${log.userName}"`, `"${log.userEmail}"`, `"${log.userIndexNumber}"`, `"${log.joinedAt?.toDate().toLocaleString()}"`, verifData[log.userId] || 0].join(','));
            });
            const blob = new Blob([csv.join('\n')], { type: 'text/csv' });
            const url = URL.createObjectURL(blob);
            const link = document.createElement('a'); link.href = url; link.download = `${title}_attendance.csv`; link.click();
            setTimeout(() => URL.revokeObjectURL(url), 1000);
        } catch (error) { showAlert("Download failed.", "error"); }
    };

    if (!isOpen) return null;

    return (
        <div className="fixed inset-0 z-[120] flex items-center justify-center p-8 animate-in fade-in duration-500">
            <div className="absolute inset-0 bg-slate-900/40 backdrop-blur-sm" onClick={onClose} />
            <div className="relative w-full max-w-2xl bg-white rounded-3xl p-10 border border-slate-100 shadow-2xl flex flex-col max-h-[80vh] animate-in zoom-in-95 duration-300">
                <div className="flex justify-between items-center mb-8"><h2 className="text-2xl font-serif text-slate-900 tracking-tight">Attendance <span className="italic">History</span></h2><button onClick={onClose} className="p-2 text-slate-400 hover:text-slate-900 transition-colors active:scale-90"><X className="w-6 h-6" /></button></div>
                <div className="flex-1 overflow-y-auto space-y-4 pr-2 custom-scrollbar">
                    {loadingHistory ? [1, 2, 3].map(i => <Skeleton key={i} className="h-20 rounded-2xl bg-slate-50" />) : historyData.length === 0 ? <div className="text-center py-24 text-slate-400 text-[10px] font-bold uppercase tracking-[0.4em] italic">No archive entries found.</div> : historyData.map(item => (
                        <div key={item.sessionId} className="group p-6 bg-white border border-slate-100 rounded-2xl flex items-center justify-between hover:border-slate-900/20 hover:shadow-xl hover:shadow-slate-200/40 transition-all">
                            <div className="space-y-1"><h4 className="text-base font-serif text-slate-900 leading-tight tracking-tight">{item.title}</h4><div className="flex gap-4 text-[9px] font-bold text-slate-400 uppercase tracking-widest"><span>{item.count} Attendees</span><span>{item.lastJoined?.toDate().toLocaleDateString()}</span></div></div>
                            <button onClick={() => handleDownloadAttendance(item.sessionId, item.title)} className="p-3 bg-slate-50 hover:bg-slate-900 hover:text-white rounded-xl text-slate-400 transition-all shadow-sm border border-slate-100 active:scale-95"><Download className="w-4 h-4" /></button>
                        </div>
                    ))}
                </div>
            </div>
        </div>
    );
}
