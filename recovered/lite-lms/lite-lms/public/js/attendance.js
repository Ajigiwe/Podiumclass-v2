import { auth, db } from './firebase-config.js?v=19';
import { onAuthStateChanged } from './auth-client.js?v=1';
import { 
    collection, query, where, onSnapshot, getDocs, orderBy 
} from "./pg-firestore.js?v=1";

// DOM Elements
const attendanceList = document.getElementById('attendance-list');
const attendanceTotalCount = document.getElementById('attendance-total-count');

// State
let currentUserId = null;
let attendanceReports = [];

// Auth Listener
onAuthStateChanged(auth, async (user) => {
    if (!user) return;
    currentUserId = user.uid;
    
    // Start Listeners
    setupAttendanceListener();
});

function setupAttendanceListener() {
    // We fetch attendance_logs where current user is the lecturer
    const q = query(collection(db, 'attendance_logs'), where('lecturerId', '==', currentUserId), orderBy('joinedAt', 'desc'));
    
    onSnapshot(q, (snap) => {
        const logs = snap.docs.map(d => d.data());
        const sessionsMap = new Map();

        logs.forEach(log => {
            if (!sessionsMap.has(log.sessionId)) {
                sessionsMap.set(log.sessionId, {
                    id: log.sessionId,
                    title: log.sessionTitle || 'Untitled Session',
                    date: log.joinedAt?.toDate() || new Date(),
                    participantCount: 0,
                    participants: new Set()
                });
            }
            const s = sessionsMap.get(log.sessionId);
            s.participants.add(log.userId);
            s.participantCount = s.participants.size;
            
            // Keep earliest date for the session
            const logDate = log.joinedAt?.toDate() || new Date();
            if (logDate < s.date) s.date = logDate;
        });

        attendanceReports = Array.from(sessionsMap.values()).sort((a, b) => b.date - a.date);
        renderAttendance();
    });
}

function renderAttendance() {
    if (!attendanceList) return;
    
    attendanceTotalCount.innerText = attendanceReports.length;
    attendanceList.innerHTML = '';

    if (attendanceReports.length === 0) {
        attendanceList.innerHTML = `
            <div class="col-span-full py-20 text-center bg-white dark:bg-slate-900 rounded-2xl border border-dashed border-[#DDE0F0] dark:border-slate-800">
                <div class="w-16 h-16 bg-[#F5F6FA] dark:bg-slate-800 rounded-full flex items-center justify-center mx-auto mb-4">
                    <i class="fas fa-clipboard-check text-[#8888A8] text-xl"></i>
                </div>
                <h3 class="text-[15px] font-bold text-[#0D0D1A] dark:text-white">No attendance records yet</h3>
                <p class="text-[13px] text-[#8888A8] mt-1 max-w-[240px] mx-auto">Start a class and trigger the attendance taker to see reports here.</p>
            </div>
        `;
        return;
    }

    attendanceReports.forEach((report, index) => {
        const card = document.createElement('div');
        card.className = 'bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800 rounded-xl p-6 hover:shadow-xl hover:shadow-blue-600/5 transition-all group animate-in fade-in slide-in-from-bottom-4';
        card.style.animationDelay = `${index * 0.05}s`;
        
        card.innerHTML = `
            <div class="flex justify-between items-start mb-6">
                <div class="w-10 h-10 bg-[#1845D4]/10 rounded-lg flex items-center justify-center text-[#1845D4]">
                    <i class="fas fa-file-invoice text-sm"></i>
                </div>
                <div class="text-right">
                    <div class="text-[9px] font-black text-[#8888A8] uppercase tracking-[0.2em] mb-1">Status</div>
                    <span class="px-2 py-1 bg-green-500/10 text-green-600 text-[9px] font-black uppercase rounded tracking-widest">Completed</span>
                </div>
            </div>
            
            <h3 class="text-[15px] font-bold text-[#0D0D1A] dark:text-white truncate group-hover:text-[#1845D4] transition-colors mb-1">${report.title}</h3>
            <p class="text-[11px] text-[#8888A8] font-medium flex items-center gap-1.5 mb-6">
                <i class="far fa-calendar text-[10px]"></i>
                ${report.date.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' })}
            </p>
            
            <div class="grid grid-cols-2 gap-4 py-4 border-y border-[#F5F6FA] dark:border-slate-800 mb-6">
                <div>
                    <div class="text-[9px] font-black text-[#8888A8] uppercase tracking-widest mb-1">Participants</div>
                    <div class="text-lg font-sans font-bold text-[#0D0D1A] dark:text-white">${report.participantCount}</div>
                </div>
                <div class="text-right">
                    <div class="text-[9px] font-black text-[#8888A8] uppercase tracking-widest mb-1">Time</div>
                    <div class="text-lg font-sans font-bold text-[#0D0D1A] dark:text-white">${report.date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</div>
                </div>
            </div>
            
            <button onclick="window.viewAttendanceLogs('${report.id}', '${report.title.replace(/'/g, "\\\\'")}')" class="w-full py-3 bg-[#F5F6FA] dark:bg-slate-800 hover:bg-[#1845D4] dark:hover:bg-[#1845D4] text-[#444460] dark:text-slate-400 hover:text-white rounded-lg text-[11px] font-bold uppercase tracking-widest transition-all active:scale-[0.98]">
                View Full Report
            </button>
        `;
        attendanceList.appendChild(card);
    });
}

window.viewAttendanceLogs = async (sessionId, title) => {
    const modal = document.getElementById('modal-attendance');
    const content = document.getElementById('attendance-logs-content');
    const downloadBtn = document.getElementById('download-attendance-btn');
    const subtitle = document.getElementById('attendance-modal-subtitle');
    
    modal.querySelector('h2').innerText = title;
    subtitle.innerText = 'Detailed Attendance Report';
    content.innerHTML = `<div class="py-20 text-center animate-pulse"><i class="fas fa-circle-notch fa-spin text-[#1845D4] text-2xl"></i></div>`;
    downloadBtn.classList.add('hidden');
    modal.classList.remove('hidden');

    try {
        const q = query(collection(db, 'attendance_logs'), where('sessionId', '==', sessionId));
        const snap = await getDocs(q);
        
        if (snap.empty) {
            content.innerHTML = `<p class="py-20 text-center text-[#8888A8] text-[11px] font-bold uppercase tracking-widest italic">No participants recorded for this session.</p>`;
            return;
        }

        const logs = snap.docs
            .map(d => d.data())
            .sort((a, b) => (a.joinedAt?.toMillis?.() || 0) - (b.joinedAt?.toMillis?.() || 0));

        // Prepare for download
        window.currentAttendanceLogs = logs;
        window.currentAttendanceTitle = title;
        downloadBtn.classList.remove('hidden');
        downloadBtn.onclick = () => window.downloadAttendanceCSV();

        content.innerHTML = `
            <div class="overflow-x-auto">
                <table class="w-full text-left border-collapse">
                    <thead>
                        <tr class="text-[10px] font-black text-[#8888A8] uppercase tracking-[0.2em] border-b border-[#F5F6FA] dark:border-slate-800">
                            <th class="py-4 px-2">Learner</th>
                            <th class="py-4 px-2">ID Number</th>
                            <th class="py-4 px-2 text-center">Engagement</th>
                            <th class="py-4 px-2 text-right">Join Time</th>
                        </tr>
                    </thead>
                    <tbody class="divide-y divide-[#F5F6FA] dark:divide-slate-800">
                        ${logs.map(data => {
                            const time = data.joinedAt?.toDate().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) || '--:--';
                            const checkins = `${data.totalVerificationsCompleted || 0}/${data.totalVerificationsSent || 0}`;
                            const percentage = data.verificationPercentage || 0;
                            const statusColor = percentage > 80 ? 'text-green-500' : percentage > 50 ? 'text-yellow-500' : 'text-red-500';
                            
                            return `
                                <tr class="group hover:bg-[#F5F6FA]/50 dark:hover:bg-slate-800/50 transition-colors">
                                    <td class="py-4 px-2">
                                        <div class="text-[13px] font-bold text-[#0D0D1A] dark:text-white">${data.userName || 'Anonymous'}</div>
                                    </td>
                                    <td class="py-4 px-2">
                                        <div class="text-[11px] font-bold text-[#8888A8] uppercase">${data.userIndexNumber || 'N/A'}</div>
                                    </td>
                                    <td class="py-4 px-2 text-center">
                                        <div class="text-[11px] font-black ${statusColor}">
                                            ${checkins} 
                                            <span class="ml-1 text-[9px] text-[#8888A8] opacity-60">(${percentage}%)</span>
                                        </div>
                                    </td>
                                    <td class="py-4 px-2 text-right">
                                        <div class="text-[11px] font-bold text-[#8888A8] uppercase tracking-tighter">${time}</div>
                                    </td>
                                </tr>
                            `;
                        }).join('')}
                    </tbody>
                </table>
            </div>
        `;
    } catch (err) {
        console.error('Attendance logs fetch error:', err);
        content.innerHTML = `<div class="py-20 text-center"><i class="fas fa-exclamation-triangle text-red-500 mb-2"></i><p class="text-red-500 text-[11px] font-bold uppercase tracking-widest">Failed to load reports.</p></div>`;
    }
};

window.downloadAttendanceCSV = () => {
    if (!window.currentAttendanceLogs || window.currentAttendanceLogs.length === 0) return;
    
    const headers = ['Learner Name', 'Index Number', 'Verifications Completed', 'Total Verifications', 'Engagement %', 'Join Time'];
    const rows = window.currentAttendanceLogs.map(log => [
        `"${log.userName || 'Anonymous'}"`,
        `"${log.userIndexNumber || 'N/A'}"`,
        log.totalVerificationsCompleted || 0,
        log.totalVerificationsSent || 0,
        `"${(log.verificationPercentage || 0)}%"`,
        `"${log.joinedAt?.toDate().toLocaleString() || 'N/A'}"`
    ]);
    
    const csvContent = [headers, ...rows].map(e => e.join(",")).join("\n");
    const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
    const link = document.createElement("a");
    const url = URL.createObjectURL(blob);
    
    link.setAttribute("href", url);
    link.setAttribute("download", `attendance_${window.currentAttendanceTitle.replace(/\s+/g, '_').toLowerCase()}.csv`);
    link.style.visibility = 'hidden';
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
};
