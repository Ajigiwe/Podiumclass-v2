import { auth, db } from './firebase-config.js?v=19';
import { onAuthStateChanged, signOut } from './auth-client.js?v=1';
import { 
    collection, query, where, onSnapshot, doc, getDoc, getDocs, orderBy, deleteDoc, Timestamp 
} from "./pg-firestore.js?v=1";

// DOM Elements
const userName = document.getElementById('user-name');
const userRole = document.getElementById('user-role');
const userAvatar = document.getElementById('user-avatar');
const historyList = document.getElementById('history-list');
const historyTotal = document.getElementById('history-total');

// State
let activeTab = 'joined';
let currentUserId = null;
let joinedHistory = [];
let hostedHistory = [];

// Feature flag: history.html?v2=1 reads hosted sessions from PostgreSQL.
// Joined history and attendance logs stay on Firestore until those paths move.
const historyParams = new URLSearchParams(window.location.search);
const USE_V2_SESSIONS =
    historyParams.get('v2') === '1' || historyParams.get('v2Sessions') === '1';

function toDateValue(value) {
    if (!value) return null;
    if (value instanceof Date) return value;
    if (typeof value.toDate === 'function') return value.toDate();
    if (typeof value === 'string' || typeof value === 'number') {
        const parsed = new Date(value);
        return Number.isNaN(parsed.getTime()) ? null : parsed;
    }
    if (typeof value.seconds === 'number') return new Date(value.seconds * 1000);
    if (typeof value._seconds === 'number') return new Date(value._seconds * 1000);
    return null;
}

function timestampMillis(value) {
    return toDateValue(value)?.getTime() || 0;
}

// Auth Listener
onAuthStateChanged(auth, async (user) => {
    if (!user) {
        window.location.href = './auth/login.html';
        return;
    }
    currentUserId = user.uid;
    
    // Fetch Profile. Fall back to the PostgreSQL-backed session user while the
    // remaining Firestore profile reads are migrated.
    let profileSnap = null;
    try {
        profileSnap = await getDoc(doc(db, 'profiles', user.uid));
    } catch (error) {
        console.warn('[history] Firestore profile unavailable; using session profile', error);
    }
    const profile = profileSnap?.data() || {
        fullName: user.displayName || user.email,
        email: user.email,
        photoURL: user.photoURL || null,
        role: user.role || 'student',
    };
    
    // Update UI
    userName.innerText = profile.fullName?.split(' ')[0] || user.email.split('@')[0];
    userRole.innerText = profile.role || 'Student';
    if (profile.photoURL) {
        userAvatar.innerHTML = `<img src="${profile.photoURL}" class="w-full h-full object-cover">`;
    }

    // Start Listeners
    setLoading(true);
    fetchJoinHistory();
    if (USE_V2_SESSIONS) {
        console.info('[History] PostgreSQL v2 session read enabled for the Hosted tab');
        fetchHostedHistoryV2();
    } else {
        fetchHostedHistory();
    }
    
    // Show attendance nav if eligible
    if (profile.role === 'admin' || profile.role === 'lecturer' || profile.role === 'rep' || profile.isVerified) {
        const attendanceNav = document.getElementById('nav-attendance');
        if (attendanceNav) attendanceNav.classList.remove('hidden');
    }

    setTimeout(() => setLoading(false), 800);
});

// UI Helpers
const loadingBar = document.getElementById('top-loading-bar');
function setLoading(isLoading) {
    if (!loadingBar) return;
    loadingBar.style.width = isLoading ? '30%' : '100%';
    if (!isLoading) setTimeout(() => loadingBar.style.width = '0%', 400);
}

function fetchJoinHistory() {
    // Fetch all non-hidden transactions (enrollments)
    const qTx = query(collection(db, 'transactions'), where('userId', '==', currentUserId), where('isHidden', '==', false));
    
    onSnapshot(qTx, async (snap) => {
        if (snap.empty) {
            joinedHistory = [];
            if (activeTab === 'joined') render();
            return;
        }

        const sessionIds = Array.from(new Set(snap.docs.map(d => d.data().sessionId)));
        // Fetch session details for these transactions
        const sessionSnaps = await Promise.all(sessionIds.map(id => getDoc(doc(db, 'sessions', id))));
        
        joinedHistory = sessionSnaps
            .filter(s => s.exists())
            .map(s => {
                const data = s.data();
                return {
                    id: s.id,
                    ...data,
                    joinedAt: data.createdAt // Use creation as fallback
                };
            })
            .sort((a, b) => timestampMillis(b.createdAt) - timestampMillis(a.createdAt));
            
        if (activeTab === 'joined') render();
    }, (err) => console.error('[History:Joined] Error:', err));
}

async function fetchHostedHistory() {
    // Fetch all non-deleted sessions hosted by the user
    const q = query(collection(db, 'sessions'), where('lecturerId', '==', currentUserId));
    
    onSnapshot(q, async (snap) => {
        const sessions = snap.docs.map(d => ({ id: d.id, ...d.data() }));
        
        // 1. Ensure all sessions have a participant count (fallback to logs query if doc count is 0)
        const enhancedSessions = await Promise.all(sessions.map(async (s) => {
            if (s.participantCount && s.participantCount > 0) return { ...s, type: 'hosted' };
            
            try {
                const logsQuery = query(collection(db, 'attendance_logs'), where('sessionId', '==', s.id));
                const logsSnap = await getDocs(logsQuery);
                const count = new Set(logsSnap.docs.map(d => d.data().userId)).size;
                return { ...s, participantCount: count, type: 'hosted' };
            } catch (e) {
                return { ...s, participantCount: 0, type: 'hosted' };
            }
        }));

        await attachRecordings(enhancedSessions);
        hostedHistory = enhancedSessions.sort((a, b) => timestampMillis(b.createdAt) - timestampMillis(a.createdAt));
        if (activeTab === 'hosted') render();
    }, (err) => console.error('[History:Hosted] Error:', err));
}

async function attachRecordings(sessions) {
    if (!sessions.length || !auth.currentUser) return sessions;
    try {
        const token = await auth.currentUser.getIdToken();
        const response = await fetch(`/api/recordings/lecturer/${currentUserId}`, {
            headers: { Authorization: `Bearer ${token}` }
        });
        const recData = await response.json();
        if (recData.success) {
            recData.recordings.forEach(rec => {
                const session = sessions.find(s => s.id === rec.roomId);
                if (session) {
                    session.hasRecording = true;
                    session.recordingId = rec.id;
                }
            });
        }
    } catch (err) {
        console.error('Recordings fetch error:', err);
    }
    return sessions;
}

async function fetchHostedHistoryV2() {
    try {
        const token = await auth.currentUser.getIdToken();
        const sessions = [];
        let cursor = null;

        // Bounded pagination keeps the Hosted tab's result set aligned with
        // the old Firestore listener for normal accounts.
        for (let page = 0; page < 10; page += 1) {
            const url = new URL('/api/v2/sessions', window.location.origin);
            url.searchParams.set('limit', '100');
            if (cursor) url.searchParams.set('cursor', cursor);

            const response = await fetch(url, {
                headers: { Authorization: `Bearer ${token}` }
            });
            if (!response.ok) {
                throw new Error(`v2 sessions request failed (${response.status})`);
            }

            const data = await response.json();
            sessions.push(...(Array.isArray(data.sessions) ? data.sessions : []));
            cursor = data.nextCursor;
            if (!cursor) break;
        }

        const ownedSessions = sessions.filter(session =>
            session.hostId === currentUserId || session.lecturerId === currentUserId
        );

        const enhancedSessions = ownedSessions.map(session => {
            const { _count, ...rest } = session;
            return {
                ...rest,
                type: 'hosted',
                participantCount: session.participantCount > 0
                    ? session.participantCount
                    : (_count?.attendance || _count?.participants || 0)
            };
        });

        await attachRecordings(enhancedSessions);
        hostedHistory = enhancedSessions.sort(
            (a, b) => timestampMillis(b.createdAt) - timestampMillis(a.createdAt)
        );
        if (activeTab === 'hosted') render();
        console.info(`[History] Hosted sessions loaded from PostgreSQL v2 (${hostedHistory.length})`);
    } catch (error) {
        console.warn('[History] PostgreSQL v2 read failed; falling back to Firestore:', error);
        fetchHostedHistory();
    }
}

window.switchTab = (tab) => {
    activeTab = tab;
    const tabJoined = document.getElementById('tab-joined');
    const tabHosted = document.getElementById('tab-hosted');
    
    if (tab === 'joined') {
        tabJoined.className = 'flex-1 md:flex-none px-4 py-2 text-[10px] font-bold uppercase tracking-[0.15em] rounded transition-all bg-[#1845D4] text-white';
        tabHosted.className = 'flex-1 md:flex-none px-4 py-2 text-[10px] font-bold uppercase tracking-[0.15em] rounded transition-all text-[#8888A8] hover:text-[#0D0D1A] dark:hover:text-white';
    } else {
        tabHosted.className = 'flex-1 md:flex-none px-4 py-2 text-[10px] font-bold uppercase tracking-[0.15em] rounded transition-all bg-[#1845D4] text-white';
        tabJoined.className = 'flex-1 md:flex-none px-4 py-2 text-[10px] font-bold uppercase tracking-[0.15em] rounded transition-all text-[#8888A8] hover:text-[#0D0D1A] dark:hover:text-white';
    }
    render();
};

function render() {
    const data = activeTab === 'joined' ? joinedHistory : hostedHistory;
    historyTotal.innerText = data.length;
    historyList.innerHTML = '';

    if (data.length === 0) {
        historyList.innerHTML = `<div class="py-16 text-center text-[#8888A8] text-[11px] font-bold uppercase tracking-widest italic animate-fade-in">No records found.</div>`;
        return;
    }

    const fragment = document.createDocumentFragment();
    data.forEach((item, index) => {
        const div = document.createElement('div');
        div.className = 'flex flex-col sm:flex-row sm:items-center gap-4 px-6 py-5 sm:py-4 hover:bg-[#F5F6FA] dark:hover:bg-slate-800 transition-all group animate-fade-in border-b border-[#F5F6FA] dark:border-slate-800 sm:border-none';
        div.style.animationDelay = `${index * 0.05}s`;
        
        const date = toDateValue(item.joinedAt || item.createdAt) || new Date();
        const icon = activeTab === 'joined' ? 'fa-graduation-cap' : 'fa-video';

        div.innerHTML = `
            <div class="flex items-center gap-4 flex-1 min-w-0">
                <div class="w-8 h-8 bg-[#F5F6FA] dark:bg-slate-800 rounded-lg flex-shrink-0 flex items-center justify-center text-[#1845D4] dark:text-blue-400 border border-[#DDE0F0] dark:border-slate-700 group-hover:bg-[#1845D4] group-hover:text-white transition-all">
                    <i class="fas ${icon} text-xs"></i>
                </div>
                <div class="flex-1 min-w-0">
                    <div class="text-[14px] font-medium text-[#0D0D1A] dark:text-white truncate">${item.sessionTitle || item.title || 'Untitled Session'}</div>
                    <div class="flex flex-wrap items-center gap-x-3 gap-y-1 text-[10px] font-bold text-[#8888A8] uppercase tracking-widest mt-1">
                        <span class="flex items-center gap-1"><i class="far fa-calendar text-[10px]"></i> ${date.toLocaleDateString('en-GB')}</span>
                        ${activeTab === 'joined' 
                            ? `<span class="flex items-center gap-1"><i class="far fa-clock text-[10px]"></i> ${date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</span>`
                            : `<span class="flex items-center gap-1 text-[#1845D4]"><i class="fas fa-users text-[10px]"></i> ${item.participantCount || 0} Learners</span>`
                        }
                    </div>
                </div>
            </div>
            <div class="flex items-center gap-2 sm:ml-auto">
                ${activeTab === 'joined' 
                    ? `<button onclick="window.deleteRecord('${item.id}', event)" class="p-2 text-[#DDE0F0] dark:text-slate-700 hover:text-red-600 transition-colors sm:opacity-0 sm:group-hover:opacity-100"><i class="fas fa-trash-alt"></i></button>`
                    : `
                        <button onclick="window.viewLogs('${item.id}', '${item.title.replace(/'/g, "\\\\'")}')" class="flex-1 sm:flex-none px-4 py-2 bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800 text-[#0D0D1A] dark:text-white text-[10px] font-bold uppercase tracking-widest rounded hover:border-[#1845D4] transition-all">Logs</button>
                        ${item.hasRecording 
                            ? `<button onclick="window.downloadMedia('${item.recordingId}')" class="flex-1 sm:flex-none px-4 py-2 bg-[#1845D4] text-white text-[10px] font-bold uppercase tracking-widest rounded shadow-lg shadow-blue-600/10 hover:bg-[#0F2FA8] transition-all">Media</button>`
                            : `<button disabled class="flex-1 sm:flex-none px-4 py-2 bg-[#F5F6FA] dark:bg-slate-800 text-[#8888A8] text-[10px] font-bold uppercase tracking-widest rounded cursor-not-allowed border border-[#DDE0F0] dark:border-slate-700">No Media</button>`
                        }
                    `
                }
            </div>
        `;
        fragment.appendChild(div);
    });
    historyList.appendChild(fragment);
}

window.deleteRecord = async (id, e) => {
    if (e) e.stopPropagation();
    showConfirm('Permanently remove this entry?', async () => {
        await deleteDoc(doc(db, 'attendance_logs', id));
    });
};

window.viewLogs = async (sessionId, title) => {
    const modal = document.getElementById('modal-attendance');
    const content = document.getElementById('attendance-logs-content');
    const downloadBtn = document.getElementById('download-attendance-btn');
    
    modal.querySelector('h2').innerText = `Logs: ${title}`;
    content.innerHTML = `<div class="py-10 text-center animate-pulse"><i class="fas fa-circle-notch fa-spin text-[#1845D4]"></i></div>`;
    downloadBtn.classList.add('hidden');
    modal.classList.remove('hidden');

    try {
        const q = query(collection(db, 'attendance_logs'), where('sessionId', '==', sessionId));
        const snap = await getDocs(q);
        
        if (snap.empty) {
            content.innerHTML = `<p class="py-10 text-center text-[#8888A8] text-[11px] font-bold uppercase tracking-widest italic">No participants recorded.</p>`;
            return;
        }

        const logs = snap.docs
            .map(d => d.data())
            .sort((a, b) => (a.joinedAt?.toMillis?.() || 0) - (b.joinedAt?.toMillis?.() || 0));

        // Prepare for download
        window.currentLogs = logs;
        window.currentLogTitle = title;
        downloadBtn.classList.remove('hidden');
        downloadBtn.onclick = () => window.downloadLogs();

        content.innerHTML = `
            <table class="w-full text-left border-collapse">
                <thead>
                    <tr class="text-[10px] font-black text-[#8888A8] uppercase tracking-widest border-b border-[#F5F6FA]">
                        <th class="py-3">Student</th>
                        <th class="py-3">Index</th>
                        <th class="py-3">Check-ins</th>
                        <th class="py-3">Joined</th>
                    </tr>
                </thead>
                <tbody class="divide-y divide-[#F5F6FA]">
                    ${logs.map(data => {
                        const time = data.joinedAt?.toDate().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) || '--:--';
                        const checkins = `${data.totalVerificationsCompleted || 0}/${data.totalVerificationsSent || 0}`;
                        const percentage = data.verificationPercentage || 0;
                        return `
                            <tr>
                                <td class="py-3 text-[13px] font-medium text-[#0D0D1A] dark:text-white">${data.userName || 'Anonymous'}</td>
                                <td class="py-3 text-[11px] font-bold text-[#8888A8] uppercase">${data.userIndexNumber || 'N/A'}</td>
                                <td class="py-3 text-[11px] font-bold text-[#1845D4] dark:text-blue-400">
                                    ${checkins} 
                                    <span class="ml-1 text-[9px] text-[#8888A8]">(${percentage}%)</span>
                                </td>
                                <td class="py-3 text-[11px] font-bold text-[#8888A8] uppercase">${time}</td>
                            </tr>
                        `;
                    }).join('')}
                </tbody>
            </table>
        `;
    } catch (err) {
        console.error('Logs fetch error:', err);
        content.innerHTML = `<p class="py-10 text-center text-red-500 text-[11px] font-bold uppercase tracking-widest">Failed to load logs.</p>`;
    }
};

window.downloadMedia = async (recordingId) => {
    // The download route requires a Firebase token, so a plain <a href> would 401.
    const token = await auth.currentUser.getIdToken();
    const res = await fetch(`/api/recordings/download/${recordingId}`, {
        headers: { Authorization: `Bearer ${token}` }
    });
    if (!res.ok) {
        alert('Unable to download this recording.');
        return;
    }
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `recording_${recordingId}.mp4`;
    link.click();
    URL.revokeObjectURL(url);
};

window.downloadLogs = () => {
    if (!window.currentLogs || window.currentLogs.length === 0) return;
    
    const headers = ['Student Name', 'Index Number', 'Checks Completed', 'Total Checks', 'Percentage', 'Join Time'];
    const rows = window.currentLogs.map(log => [
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
    link.setAttribute("download", `attendance_${window.currentLogTitle.replace(/\s+/g, '_').toLowerCase()}.csv`);
    link.style.visibility = 'hidden';
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
};

// Logout
document.getElementById('logout-btn').onclick = () => signOut(auth);
