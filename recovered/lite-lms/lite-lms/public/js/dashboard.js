import { auth, db, storage } from './firebase-config.js?v=19';
import { onAuthStateChanged, signOut } from './auth-client.js?v=1';
import { 
    collection, query, where, onSnapshot, doc, getDoc, updateDoc, getDocs, addDoc, Timestamp, serverTimestamp, setDoc 
} from "./pg-firestore.js?v=1";
import { initCommunities } from './communities.js?v=18';

// DOM Elements
const sidebarLinks = {
    records: document.getElementById('nav-records'),
    communities: document.getElementById('nav-communities'),
    attendance: document.getElementById('nav-attendance'),
    recordings: document.getElementById('nav-recordings')
};
const contentSections = {
    records: document.getElementById('content-records'),
    communities: document.getElementById('content-communities'),
    attendance: document.getElementById('content-attendance'),
    recordings: document.getElementById('content-recordings')
};

const userName = document.getElementById('user-name');
const userRole = document.getElementById('user-role');
const userAvatar = document.getElementById('user-avatar');
const enrolledCount = document.getElementById('enrolled-count');
const hostedCount = document.getElementById('hosted-count');
const totalSessionsCount = document.getElementById('total-sessions-count');
const recordsList = document.getElementById('records-list');
const recordsListDrawer = document.getElementById('records-list-drawer');
const greetingName = document.getElementById('greeting-name');
const todayDate = document.getElementById('today-date');

// State
let activeWorkspace = 'records';
let activeRecordTab = 'join';
let currentUserId = null;
let userProfile = null;
let enrolledSessionsData = [];
let hostedSessionsData = [];
let walletSettings = { defaultSessionFee: 2000, minTopUpAmount: 500, isWalletPayToUse: true };

// Initialize Date
todayDate.innerText = new Date().toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });

// Auth Listener
onAuthStateChanged(auth, async (user) => {
    if (!user) {
        window.location.href = './auth/login.html';
        return;
    }
    currentUserId = user.uid;
    
    // Fetch Profile. The Firestore profile is optional during the migration;
    // fall back to the PostgreSQL-backed session user if Firestore is not
    // reachable or the document has not been migrated yet.
    let profileSnap = null;
    try {
        profileSnap = await getDoc(doc(db, 'profiles', user.uid));
    } catch (error) {
        console.warn('[dashboard] Firestore profile unavailable; using session profile', error);
    }
    userProfile = profileSnap?.data() || {
        fullName: user.displayName || user.email,
        email: user.email,
        photoURL: user.photoURL || null,
        role: user.role || 'student',
        isVerified: false,
    };
    if (userProfile.walletBalance === undefined) userProfile.walletBalance = 0;
    // Fetch wallet settings
    try {
        const wSnap = await getDoc(doc(db, 'system_settings', 'wallet'));
        if (wSnap.exists()) { const w=wSnap.data(); walletSettings.defaultSessionFee=w.defaultSessionFee||2000; walletSettings.minTopUpAmount=w.minTopUpAmount||500; walletSettings.isWalletPayToUse = w.isWalletPayToUse !== false; }
    } catch {}
    updateWalletUI();
    // handle topup success return (Paystack may send reference or trxref)
    const urlP = new URLSearchParams(window.location.search);
    const retRef = urlP.get('reference') || urlP.get('trxref') || urlP.get('trRef');
    if (urlP.get('topup')==='success' && retRef) {
        // Credit wallet via verify fallback (works even if webhook not reachable on localhost)
        fetch('/api/paystack/verify?reference='+retRef).then(r=>r.json()).then(d=>{
            if(d.success){ showToast('Top-up successful! New balance loading...','success'); setTimeout(async()=>{
                const fresh=await getDoc(doc(db,'profiles',user.uid)); userProfile=fresh.data()|| userProfile; updateWalletUI();
            },1000); } else { showToast(d.error||'Verification failed','error'); }
        }).catch(()=>{ showToast('Verifying...','success'); });
    }
    
    // Update UI
    userName.innerText = userProfile.fullName?.split(' ')[0] || user.email.split('@')[0];
    userRole.innerText = userProfile.role || 'Student';
    if (greetingName) greetingName.innerText = userProfile.fullName?.split(' ')[0] || user.email.split('@')[0];
    if (userProfile.photoURL) {
        userAvatar.innerHTML = `<img src="${userProfile.photoURL}" class="w-full h-full object-cover">`;
    } else {
        userAvatar.innerText = (userProfile.fullName || 'U').charAt(0).toUpperCase();
    }

    // Start Listeners
    setLoading(true);
    setupHostedSessions();
    setupEnrolledSessions();
    if (typeof initCommunities === 'function') initCommunities(user, userProfile);
    
    // Check Lecturer access
    let hasLecturerAccess = false;
    if (userProfile.role === 'lecturer') {
        const qL = query(collection(db, 'group_memberships'), where('userId', '==', currentUserId), where('role', '==', 'lecturer'));
        const snapL = await getDocs(qL);
        hasLecturerAccess = !snapL.empty;
    }
    
    // Create Class Card logic
    const createClassCard = document.getElementById('open-create-modal-card');
    const openCreateBtn = document.getElementById('open-create-modal');
    const hasCreatePermission = userProfile.role === 'admin' || (userProfile.role === 'student' && userProfile.isVerified) || (userProfile.role === 'lecturer' && hasLecturerAccess);
    
    if (createClassCard) {
        if (hasCreatePermission) {
            createClassCard.classList.remove('hidden');
        } else {
            createClassCard.classList.add('hidden');
        }
    }
    if (openCreateBtn) {
        if (hasCreatePermission) {
            openCreateBtn.classList.remove('hidden');
        } else {
            openCreateBtn.classList.add('hidden');
        }
    }
    // Admin-only price controls
    const adminPriceControls = document.getElementById('admin-price-controls');
    if (adminPriceControls) {
        if (userProfile.role === 'admin') adminPriceControls.classList.remove('hidden');
        else adminPriceControls.classList.add('hidden');
    }
    
    setupGroupOptions();
    setTimeout(() => {
        setLoading(false);
        // Hide splash screen
        const splash = document.getElementById('splash-screen');
        if (splash) { splash.classList.add('hidden'); }
    }, 800);

    // Show admin nav section if admin
    if (userProfile.role === 'admin' || userProfile.role === 'lecturer' || userProfile.role === 'rep' || userProfile.isVerified) {
        const adminSection = document.getElementById('admin-nav-section');
        if (adminSection && userProfile.role === 'admin') adminSection.classList.remove('hidden');
        
        const attendanceNav = document.getElementById('nav-attendance');
        if (attendanceNav) attendanceNav.classList.remove('hidden');
    } else {
        const attendanceNav = document.getElementById('nav-attendance');
        if (attendanceNav) attendanceNav.classList.add('hidden');
    }

    // Show recordings nav for all verified users, lecturers, and admins
    if (userProfile.role === 'lecturer' || userProfile.role === 'admin' || userProfile.isVerified) {
        const recordingsNav = document.getElementById('nav-recordings');
        if (recordingsNav) recordingsNav.classList.remove('hidden');
    }
    
    // Check for tab in URL
    const urlParams = new URLSearchParams(window.location.search);
    const targetTab = urlParams.get('tab');
    if (targetTab && contentSections[targetTab]) {
        window.switchTab(targetTab);
    }
    
    console.log('[Dashboard] Initialized for:', user.email);
});

function updateWalletUI(){
    const el=document.getElementById('wallet-balance');
    if(el) el.innerText = 'GHS ' + ((userProfile?.walletBalance||0)/100).toFixed(2);
    const minLabel=document.getElementById('topup-min-label');
    if(minLabel) minLabel.innerText = 'Minimum GHS ' + (walletSettings.minTopUpAmount/100).toFixed(2) + ' · GHS only';
}
// Top-up modal wiring — handled by inline script in dashboard.html (PaystackPop inline)

// UI Helpers
const loadingBar = document.getElementById('top-loading-bar');
function setLoading(isLoading) {
    if (!loadingBar) return;
    loadingBar.style.width = isLoading ? '30%' : '100%';
    if (!isLoading) setTimeout(() => loadingBar.style.width = '0%', 400);
}

// Sidebar Logic
const navColors = {
    records: 'bg-[#1845D4] text-white shadow-sm',
    communities: 'bg-amber-50 dark:bg-amber-900/20 text-amber-600',
    attendance: 'bg-emerald-50 dark:bg-emerald-900/20 text-emerald-600',
    recordings: 'bg-rose-50 dark:bg-rose-900/20 text-rose-600'
};
const navActiveColors = {
    records: 'bg-[#1845D4] text-white shadow-sm',
    communities: 'bg-amber-500 text-white shadow-sm',
    attendance: 'bg-emerald-500 text-white shadow-sm',
    recordings: 'bg-rose-500 text-white shadow-sm'
};

window.switchTab = (tab) => {
    activeWorkspace = tab;
    Object.keys(sidebarLinks).forEach(key => {
        const link = sidebarLinks[key];
        const section = contentSections[key];
        if (!link || !section) return;

        const icon = link.querySelector('.nav-icon');

        if (key === tab) {
            link.classList.add('sidebar-active');
            link.classList.remove('text-[#444460]', 'dark:text-slate-400', 'hover:bg-[#F5F6FA]', 'dark:hover:bg-slate-800', 'hover:text-[#0D0D1A]', 'dark:hover:text-white');
            if (icon && navActiveColors[key]) {
                icon.className = 'nav-icon w-8 h-8 rounded-lg flex items-center justify-center ' + navActiveColors[key];
            }
            section.classList.remove('hidden');
        } else {
            link.classList.remove('sidebar-active');
            link.classList.add('text-[#444460]', 'dark:text-slate-400', 'hover:bg-[#F5F6FA]', 'dark:hover:bg-slate-800', 'hover:text-[#0D0D1A]', 'dark:hover:text-white');
            if (icon && navColors[key]) {
                icon.className = 'nav-icon w-8 h-8 rounded-lg flex items-center justify-center ' + navColors[key] + ' transition-colors';
            }
            section.classList.add('hidden');
        }
    });
};

// Record Tabs Logic
window.switchRecordTab = (tab) => {
    activeRecordTab = tab;
    const tabJoin = document.getElementById('tab-join');
    const tabHost = document.getElementById('tab-host');
    
    if (tab === 'join') {
        if (tabJoin) { tabJoin.className = 'px-3 py-1.5 rounded-md text-[11px] font-bold transition-all text-[#1845D4] bg-white dark:bg-slate-900 shadow-sm'; }
        if (tabHost) { tabHost.className = 'px-3 py-1.5 rounded-md text-[11px] font-bold transition-all text-[#8888A8] hover:text-[#0D0D1A] dark:hover:text-white'; }
    } else {
        if (tabHost) { tabHost.className = 'px-3 py-1.5 rounded-md text-[11px] font-bold transition-all text-[#1845D4] bg-white dark:bg-slate-900 shadow-sm'; }
        if (tabJoin) { tabJoin.className = 'px-3 py-1.5 rounded-md text-[11px] font-bold transition-all text-[#8888A8] hover:text-[#0D0D1A] dark:hover:text-white'; }
    }
    renderInlineRecords();
};

// Drawer Logic
const drawer = document.getElementById('records-drawer');
const drawerBackdrop = document.getElementById('records-drawer-backdrop');
const drawerTitle = document.getElementById('drawer-title');
const drawerSubtitle = document.getElementById('drawer-subtitle');

window.openRecordsDrawer = (tab) => {
    activeRecordTab = tab;
    drawerTitle.innerText = tab === 'join' ? 'Classes Joined' : 'Classes Hosted';
    drawerSubtitle.innerText = tab === 'join' ? 'Your enrolled sessions' : 'Your teaching sessions';
    drawer.classList.remove('translate-x-full');
    drawerBackdrop.classList.remove('hidden');
    document.body.style.overflow = 'hidden';
    renderDrawerRecords();
};

window.closeRecordsDrawer = () => {
    drawer.classList.add('translate-x-full');
    drawerBackdrop.classList.add('hidden');
    document.body.style.overflow = 'auto';
};


// Listeners
function setupHostedSessions() {
    const q = query(collection(db, 'sessions'), where('lecturerId', '==', currentUserId));
    onSnapshot(q, (snapshot) => {
        hostedSessionsData = snapshot.docs
            .map(d => ({ id: d.id, ...d.data() }));
        hostedCount.innerText = hostedSessionsData.length;
        updateTotalSessions();
        renderInlineRecords();
        renderQuickAccess();
    }, (err) => console.error('[HostedSessions] Error:', err));
}

function setupEnrolledSessions() {
    const qTx = query(collection(db, 'transactions'), where('userId', '==', currentUserId), where('isHidden', '==', false));
    onSnapshot(qTx, async (snapshot) => {
        if (snapshot.empty) {
            enrolledSessionsData = [];
            enrolledCount.innerText = '0';
            updateTotalSessions();
            renderRecords();
            return;
        }

        const sessionIds = Array.from(new Set(snapshot.docs.map(d => d.data().sessionId)));
        const sessionSnaps = await Promise.all(sessionIds.map(id => getDoc(doc(db, 'sessions', id))));
        
        enrolledSessionsData = sessionSnaps
            .filter(s => s.exists())
            .map(s => ({ id: s.id, ...s.data() }));
            
        enrolledCount.innerText = enrolledSessionsData.length;
        updateTotalSessions();
        renderInlineRecords();
        renderQuickAccess();
    }, (err) => console.error('[EnrolledSessions] Error:', err));
}

function updateTotalSessions() {
    if (totalSessionsCount) {
        totalSessionsCount.innerText = (enrolledSessionsData.length || 0) + (hostedSessionsData.length || 0);
    }
}

function renderQuickAccess() {
    const quickSection = document.getElementById('quick-access-section');
    const quickList = document.getElementById('quick-access-list');
    if (!quickSection || !quickList) return;

    // Combine and prioritize: 1. Live sessions, 2. Most recent
    const combined = [
        ...enrolledSessionsData.map(s => ({ ...s, _type: 'join' })),
        ...hostedSessionsData.map(s => ({ ...s, _type: 'host' }))
    ].filter(s => !s.isDeleted && s.status !== 'ended' && s.status !== 'deleted')
    .sort((a, b) => {
        if (a.isActive && !b.isActive) return -1;
        if (!a.isActive && b.isActive) return 1;
        
        const timeA = a.createdAt?.toMillis?.() || (a.createdAt instanceof Date ? a.createdAt.getTime() : (typeof a.createdAt === 'number' ? a.createdAt : 0));
        const timeB = b.createdAt?.toMillis?.() || (b.createdAt instanceof Date ? b.createdAt.getTime() : (typeof b.createdAt === 'number' ? b.createdAt : 0));
        return timeB - timeA;
    });

    const displayData = combined.slice(0, 4);

    if (displayData.length === 0) {
        quickSection.classList.add('hidden');
        return;
    }

    quickSection.classList.remove('hidden');
    quickList.innerHTML = '';

    displayData.forEach(session => {
        const div = document.createElement('div');
        div.className = 'group bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800 rounded-lg p-5 flex items-center justify-between hover:border-[#1845D4] transition-all cursor-pointer';
        
        const isHost = session._type === 'host';
        const isActive = session.isActive;

        div.onclick = () => {
            if (isHost) window.location.href = `/classroom/${session.id}`;
            else window.openJoinPreview(session);
        };

        div.innerHTML = `
            <div class="flex items-center gap-4 flex-1 min-w-0">
                <div class="w-10 h-10 rounded-lg ${isActive ? 'bg-blue-50 dark:bg-blue-900/20 text-[#1845D4] dark:text-blue-400' : 'bg-[#F5F6FA] dark:bg-slate-800 text-[#8888A8]'} flex items-center justify-center flex-shrink-0 transition-all group-hover:bg-[#1845D4] group-hover:text-white">
                    <i class="fas ${isActive ? 'fa-video animate-pulse' : 'fa-graduation-cap'} text-sm"></i>
                </div>
                <div class="flex-1 min-w-0">
                    <h4 class="text-[14px] font-bold text-[#0D0D1A] dark:text-white truncate group-hover:text-[#1845D4] transition-colors">${session.title}</h4>
                    <p class="text-[10px] font-bold text-[#8888A8] uppercase tracking-widest mt-0.5">${isHost ? 'Owner' : (session.lecturerName || 'Faculty')}</p>
                </div>
            </div>
            <div class="ml-4">
                ${isActive ? `
                    <span class="px-2 py-1 bg-[#1845D4] text-white text-[8px] font-black uppercase tracking-[0.2em] rounded shadow-lg shadow-blue-600/20">Live</span>
                ` : `
                    <i class="fas fa-chevron-right text-[#DDE0F0] group-hover:text-[#1845D4] transition-all"></i>
                `}
            </div>
        `;
        quickList.appendChild(div);
    });
}

function renderInlineRecords() {
    if (!recordsList) return;
    const raw = activeRecordTab === 'join' ? enrolledSessionsData : hostedSessionsData;
    const data = [...raw].sort((a, b) => (b.createdAt?.toMillis?.() || 0) - (a.createdAt?.toMillis?.() || 0)).slice(0, 6);

    recordsList.innerHTML = '';

    if (data.length === 0) {
        recordsList.innerHTML = '<div class="py-12 text-center bg-white dark:bg-slate-900 rounded-xl border border-[#DDE0F0] dark:border-slate-800"><div class="w-12 h-12 bg-[#F5F6FA] dark:bg-slate-800 rounded-full flex items-center justify-center mx-auto mb-3 text-[#8888A8]"><i class="fas fa-inbox text-lg"></i></div><p class="text-[12px] font-bold text-[#8888A8] uppercase tracking-widest">No classes yet</p><p class="text-[11px] text-[#8888A8] mt-1">' + (activeRecordTab === 'join' ? 'Join a class using a code to get started.' : 'Create your first class session.') + '</p></div>';
        return;
    }

    const fragment = document.createDocumentFragment();
    data.forEach((session, index) => {
        const item = createRecordItem(session, activeRecordTab === 'host', true);
        item.classList.add('animate-fade-in');
        item.style.animationDelay = `${index * 0.05}s`;
        fragment.appendChild(item);
    });
    recordsList.appendChild(fragment);
}

function renderDrawerRecords() {
    if (!recordsListDrawer) return;
    const raw = activeRecordTab === 'join' ? enrolledSessionsData : hostedSessionsData;
    const data = [...raw].sort((a, b) => (b.createdAt?.toMillis?.() || 0) - (a.createdAt?.toMillis?.() || 0));

    recordsListDrawer.innerHTML = '';

    if (data.length === 0) {
        recordsListDrawer.innerHTML = '<div class="py-20 text-center"><p class="text-[11px] font-bold text-[#8888A8] uppercase tracking-[0.4em] italic">No records found.</p></div>';
        return;
    }

    const fragment = document.createDocumentFragment();
    data.forEach((session, index) => {
        const item = createRecordItem(session, activeRecordTab === 'host', false);
        item.classList.add('animate-fade-in');
        item.style.animationDelay = `${index * 0.05}s`;
        fragment.appendChild(item);
    });
    recordsListDrawer.appendChild(fragment);
}

function createRecordItem(session, isHost, compact) {
    const div = document.createElement('div');
    const isActive = session.isActive;
    const isEnded = session.status === 'ended' || session.status === 'deleted' || session.isDeleted;
    const dotColor = isActive ? 'bg-[#1845D4] animate-pulse' : isEnded ? 'bg-[#8888A8]' : 'bg-[#DDE0F0]';

    if (!isHost && !isEnded) {
        div.onclick = () => window.openJoinPreview(session);
    }

    if (compact) {
        div.className = `bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800 rounded-xl px-5 py-4 flex items-center justify-between ${isEnded ? 'opacity-60' : 'card-hover cursor-pointer'}`;
        div.innerHTML = `
            <div class="flex items-center gap-3 min-w-0 flex-1">
                <div class="w-10 h-10 rounded-lg ${isActive ? 'bg-blue-50 dark:bg-blue-900/20 text-[#1845D4]' : isEnded ? 'bg-[#F5F6FA] dark:bg-slate-800 text-[#8888A8]' : 'bg-[#F5F6FA] dark:bg-slate-800 text-[#8888A8]'} flex items-center justify-center flex-shrink-0">
                    <i class="fas ${isActive ? 'fa-video' : 'fa-graduation-cap'} text-sm"></i>
                </div>
                <div class="min-w-0 flex-1">
                    <h4 class="text-[13px] font-semibold text-[#0D0D1A] dark:text-white truncate">${session.title}</h4>
                    <p class="text-[11px] text-[#8888A8] font-medium">${session.course || session.lecturerName || 'General'}</p>
                </div>
            </div>
            <div class="flex items-center gap-3 ml-3 flex-shrink-0">
                ${isActive ? '<span class="text-[10px] font-bold text-[#1845D4] bg-blue-50 dark:bg-blue-900/20 px-2 py-1 rounded-full uppercase tracking-widest">Live</span>' : ''}
                ${isEnded ? '<span class="text-[10px] font-bold text-[#8888A8] bg-[#F5F6FA] dark:bg-slate-800 px-2 py-1 rounded-full uppercase tracking-widest">Ended</span>' : ''}
                ${isHost && !isEnded ? '<span class="text-[10px] font-bold text-[#8888A8] uppercase tracking-widest">Host</span>' : ''}
                <i class="fas fa-chevron-right text-[#DDE0F0] text-xs"></i>
            </div>
        `;
    } else {
        div.className = `flex flex-col sm:flex-row sm:items-center gap-4 px-6 py-5 sm:py-4 hover:bg-[#F5F6FA] dark:hover:bg-slate-800 transition-all group border-b border-[#F5F6FA] dark:border-slate-800 sm:border-none ${!isHost && !isEnded ? 'cursor-pointer' : ''}`;
        
        div.innerHTML = `
            <div class="flex items-center gap-4 flex-1 min-w-0">
                <div class="w-2 h-2 rounded-full flex-shrink-0 ${dotColor}"></div>
                <div class="flex-1 min-w-0">
                    <div class="text-[14px] font-medium text-[#0D0D1A] dark:text-white group-hover:text-[#1845D4] transition-colors truncate">
                        ${session.title}
                        ${isEnded ? '<span class="ml-2 text-[8px] bg-[#F5F6FA] dark:bg-slate-800 text-[#8888A8] px-1.5 py-0.5 rounded uppercase font-black">Ended</span>' : ''}
                        ${session.isDeleted ? '<span class="ml-2 text-[8px] bg-red-50 text-red-500 px-1.5 py-0.5 rounded border border-red-100 uppercase font-black">Deleted</span>' : ''}
                    </div>
                    <div class="flex flex-wrap items-center gap-x-2 gap-y-1 mt-1">
                        <span class="text-[10px] font-bold text-[#8888A8] uppercase tracking-widest bg-[#F5F6FA] dark:bg-slate-800 px-2 py-0.5 rounded whitespace-nowrap">${session.meetingCode || 'NO CODE'}</span>
                        <span class="text-[11px] text-[#8888A8] truncate">· ${session.course || session.lecturerName || 'General'}</span>
                    </div>
                </div>
            </div>
            <div class="flex items-center gap-2 sm:flex-shrink-0">
                ${isEnded ? `
                    <span class="px-4 py-2 text-[#8888A8] text-[9px] font-bold uppercase tracking-widest italic">Ended</span>
                ` : isHost ? `
                    <button onclick="window.copyCode('${session.meetingCode}', event)" class="p-2 text-[#8888A8] hover:text-[#1845D4] transition-colors flex-shrink-0"><i class="fas fa-copy"></i></button>
                    ${!session.isDeleted ? `
                        <a href="/classroom/${session.id}" class="flex-1 sm:flex-none px-4 py-2 bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800 text-[#0D0D1A] dark:text-white text-[10px] font-bold uppercase tracking-widest rounded hover:border-[#1845D4] transition-all whitespace-nowrap text-center">Control</a>
                        <button onclick="window.toggleLive('${session.id}', ${isActive}, event)" class="flex-1 sm:flex-none px-4 py-2 rounded text-[10px] font-bold uppercase tracking-widest transition-all whitespace-nowrap ${isActive ? 'bg-[#1845D4] text-white shadow-lg shadow-blue-600/10' : 'bg-[#F5F6FA] text-[#8888A8] hover:bg-[#E8EEFF]'}">
                            ${isActive ? 'Live' : 'Go Live'}
                        </button>
                        <button onclick="window.deleteSession('${session.id}', event)" class="p-2 text-[#DDE0F0] hover:text-red-600 transition-colors flex-shrink-0"><i class="fas fa-trash-alt"></i></button>
                    ` : `
                        <span class="px-4 py-2 text-[#8888A8] text-[9px] font-bold uppercase tracking-widest italic">Archived</span>
                    `}
                ` : `
                    <span class="text-[10px] font-bold px-2.5 py-1 rounded-full uppercase tracking-widest ${isActive ? 'bg-blue-50 text-[#1845D4]' : 'bg-[#F5F6FA] text-[#8888A8]'}">
                        ${isActive ? 'Live' : 'Active'}
                    </span>
                    <i class="fas fa-chevron-right text-[#DDE0F0] group-hover:text-[#1845D4] transition-all"></i>
                `}
            </div>
        `;
    }
    return div;
}

// Global Actions
window.copyCode = (code, e) => {
    if (e) e.stopPropagation();
    navigator.clipboard.writeText(code);
    showToast('Meeting code copied!');
};

window.toggleLive = async (id, current, e) => {
    if (e) e.stopPropagation();
    try {
        const nextState = !current;
        const updates={isActive: nextState};
        if(nextState){ updates.startedAt=serverTimestamp(); updates.endedAt=null; updates.refundProcessed=false; updates.notifiedAt=null; }
        else { updates.endedAt=serverTimestamp(); updates.status='ended'; updates.notifiedAt=null; }
        await updateDoc(doc(db, 'sessions', id), updates);
        if(nextState){
            // Community email alert (fire and forget; API dedups via notifiedAt)
            try {
                const token = await auth.currentUser.getIdToken();
                fetch('/api/notifications/class-start',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+token},body:JSON.stringify({sessionId:id})}).catch(()=>{});
            } catch(err) { console.error('Failed to call notify API', err); }
        }
        if(!nextState){
            try {
                const token = await auth.currentUser.getIdToken();
                fetch('/api/wallet/refund',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+token},body:JSON.stringify({sessionId:id})}).catch(()=>{});
            } catch(err) { console.error('Failed to call refund API', err); }
        }
    } catch (err) {
        showToast('Failed to toggle live state.', 'error');
    }
};

window.deleteSession = async (id, e) => {
    if (e) e.stopPropagation();
    showConfirm('Remove this session?', async () => {
        await updateDoc(doc(db, 'sessions', id), { isDeleted: true });
        showToast('Deleted successfully.');
    });
};

// Create Modal Logic
const createModal = document.getElementById('modal-create-class');
const createForm = document.getElementById('create-class-form');

// Modal Helpers
window.openModal = (id) => {
    const modal = document.getElementById(id);
    if (modal) modal.classList.remove('hidden');
    else console.warn(`[Modal] Element not found: ${id}`);
};

const openCreateBtn = document.getElementById('open-create-modal');
const openCreateCardBtn = document.getElementById('open-create-modal-card');

if (openCreateBtn) openCreateBtn.onclick = () => openModal('modal-create-class');
if (openCreateCardBtn) openCreateCardBtn.onclick = () => openModal('modal-create-class');

document.querySelectorAll('.close-modal').forEach(btn => {
    btn.addEventListener('click', () => {
        const modals = ['modal-create-class', 'modal-join-preview', 'modal-create-community', 'modal-join-community', 'modal-topup'];
        modals.forEach(id => {
            const el = document.getElementById(id);
            if (el) el.classList.add('hidden');
        });
    });
});

// Community-linked classes are now created from inside the community workspace,
// so the dashboard create-class form always creates an independent session.
function setupGroupOptions() {
    const groupSelect = document.getElementById('class-group-id');
    const groupContainer = document.getElementById('group-link-container');
    if (groupSelect) groupSelect.value = '';
    if (groupContainer) groupContainer.style.display = 'none';
}

if (createForm) {
    createForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        const title = document.getElementById('class-title').value;
        const course = document.getElementById('class-course').value;
        const program = document.getElementById('class-program').value;
        const durationMinutes = parseInt(document.getElementById('class-duration').value) || 60;
        const verificationCount = parseInt(document.getElementById('class-checks').value) || 3;
        const groupId = null;
        const scheduleDate = document.getElementById('class-schedule-date')?.value;
        const scheduleTime = document.getElementById('class-schedule-time')?.value;
        let priceGhs = document.getElementById('class-price')?.value;
        let isFree = document.getElementById('class-is-free')?.checked;
        // Only admin can set price/free; force defaults for others
        if (userProfile.role !== 'admin') { priceGhs = ''; isFree = false; }
        const submitBtn = createForm.querySelector('button[type="submit"]');

        submitBtn.disabled = true;
        submitBtn.innerText = 'Creating...';

        try {
            const sessionRef = doc(collection(db, 'sessions'));
            const arr = new Uint32Array(2);
            crypto.getRandomValues(arr);
            const code = `pod-${arr[0].toString(36).substring(0, 4)}-${arr[1].toString(36).substring(0, 4)}`;
            const price = isFree ? 0 : (priceGhs && priceGhs.trim() ? Math.round(parseFloat(priceGhs)*100) : walletSettings.defaultSessionFee);

            const sessionData = {
                id: sessionRef.id,
                title,
                course,
                program,
                durationMinutes,
                verificationCount,
                groupId: groupId || null,
                hostId: currentUserId,
                lecturerId: currentUserId,
                lecturerName: userProfile.fullName || 'Faculty',
                isActive: false,
                status: 'active',
                price,
                currency: 'GHS',
                isFree: isFree || price===0,
                meetingCode: code,
                createdAt: serverTimestamp()
            };

            if (scheduleDate && scheduleTime) {
                const [h, m] = scheduleTime.split(':');
                const d = new Date(scheduleDate + 'T' + scheduleTime + ':00');
                sessionData.scheduledStartTime = Timestamp.fromDate(d);
            }

            await setDoc(sessionRef, sessionData);
            
            createModal.classList.add('hidden');
            createForm.reset();
            showToast('Class created successfully!');
        } catch (err) {
            console.error('Create Error:', err);
            showToast('Failed to create class.', 'error');
        } finally {
            submitBtn.disabled = false;
            submitBtn.innerText = 'Create class';
        }
    });
}

// Join Logic
const joinForm = document.getElementById('join-form');
const joinPreviewModal = document.getElementById('modal-join-preview');
const joinTitle = document.getElementById('join-preview-title');
const joinFaculty = document.getElementById('join-preview-faculty');
const confirmJoinBtn = document.getElementById('confirm-join');
let pendingSessionId = null;
let pendingSessionGroupId = null;

joinForm.onsubmit = async (e) => {
    e.preventDefault();
    const code = document.getElementById('join-link').value.trim();
    if (!code) return;

    try {
        const q = query(collection(db, 'sessions'), where('meetingCode', '==', code.toLowerCase()));
        const snap = await getDocs(q);
        if (snap.empty) {
            showToast('Invalid meeting code.', 'error');
            return;
        }
        const session = { id: snap.docs[0].id, ...snap.docs[0].data() };
        window.openJoinPreview(session);
    } catch (err) {
        showToast('Error searching for class.', 'error');
    }
};

window.openJoinPreview = (session) => {
    pendingSessionId = session.id;
    pendingSessionGroupId = session.groupId || null;
    joinTitle.innerText = session.title;
    joinFaculty.innerText = session.lecturerName || 'Faculty Member';
    const priceEl=document.getElementById('join-preview-price');
    if(priceEl){
        const price=session.price||0;
        const isFree=session.isFree||price===0;
        priceEl.innerHTML = isFree ? '<span class="text-emerald-600">Free</span>' : `GHS ${(price/100).toFixed(2)} <span class="text-[#8888A8]">· Wallet: GHS ${((userProfile?.walletBalance||0)/100).toFixed(2)}</span>`;
        priceEl.className = isFree ? 'text-xs mt-2 font-bold text-emerald-600' : 'text-xs mt-2 font-bold';
    }
    joinPreviewModal.classList.remove('hidden');
};

confirmJoinBtn.onclick = async () => {
    if (!pendingSessionId) return;
    confirmJoinBtn.disabled = true;
    confirmJoinBtn.innerText = 'Processing...';

    try {
        // Enforce community check if the session is linked to a group
        if (pendingSessionGroupId) {
            const membershipId = `${currentUserId}_${pendingSessionGroupId}`;
            const membershipSnap = await getDoc(doc(db, 'group_memberships', membershipId));
            if (!membershipSnap.exists()) {
                showToast('Access denied: You must be a member of the associated community to join this class.', 'error');
                confirmJoinBtn.disabled = false;
                confirmJoinBtn.innerText = 'Enter Classroom';
                return;
            }
        }

        // Enroll Logic (isHidden handling, amount 0 record for dashboard list; actual deduct happens at classroom entry)
        const qTx = query(collection(db, 'transactions'), where('userId', '==', currentUserId), where('sessionId', '==', pendingSessionId));
        const snap = await getDocs(qTx);
        
        if (snap.empty) {
            await addDoc(collection(db, 'transactions'), {
                userId: currentUserId,
                sessionId: pendingSessionId,
                amount: 0,
                status: 'succeeded',
                type: 'session_payment',
                paymentChannel: 'direct',
                currency: 'GHS',
                email: auth.currentUser.email,
                isHidden: false,
                createdAt: serverTimestamp()
            });
        } else if (snap.docs[0].data().isHidden) {
            await updateDoc(snap.docs[0].ref, { isHidden: false });
        }
        
        window.location.href = `/classroom/${pendingSessionId}`;
    } catch (err) {
        showToast('Enrollment failed.', 'error');
        confirmJoinBtn.disabled = false;
        confirmJoinBtn.innerText = 'Enter Classroom';
    }
};

// Communities UI Connectors
const openCreateCommBtn = document.getElementById('open-create-community-modal');
const openJoinCommBtn = document.getElementById('open-join-community-modal');

if (openCreateCommBtn) openCreateCommBtn.onclick = () => openModal('modal-create-community');
if (openJoinCommBtn) openJoinCommBtn.onclick = () => openModal('modal-join-community');

// Toast Helper
function showToast(msg, type = 'success', sticky = false) {
    const toast = document.createElement('div');
    toast.className = `fixed bottom-8 left-1/2 -translate-x-1/2 px-6 py-3 ${type === 'success' ? 'bg-[#0D0D1A]' : 'bg-red-600'} text-white text-[10px] font-black uppercase tracking-widest rounded-full shadow-2xl z-[200] animate-in slide-in-from-bottom duration-300 ${sticky ? 'cursor-pointer' : ''}`;
    toast.innerText = msg;
    if (sticky) toast.title = 'Tap to dismiss';
    document.body.appendChild(toast);
    const dismiss = () => { toast.classList.add('animate-out', 'fade-out', 'slide-out-to-bottom'); setTimeout(() => toast.remove(), 300); };
    if (sticky) {
        toast.addEventListener('click', dismiss);
    } else {
        setTimeout(dismiss, 3000);
    }
}
window.showToast = showToast;

// Confirm Dialog Helper
function showConfirm(msg, onConfirm) {
    const overlay = document.createElement('div');
    overlay.className = 'fixed inset-0 bg-[#0D0D1A]/50 backdrop-blur-sm z-[250] flex items-center justify-center p-6 animate-in fade-in duration-200';
    overlay.innerHTML = `
        <div class="bg-white dark:bg-slate-900 rounded-2xl p-6 sm:p-8 max-w-sm w-full shadow-2xl animate-in zoom-in-95 duration-200 border border-[#DDE0F0] dark:border-slate-800">
            <p class="text-[14px] font-semibold text-[#0D0D1A] dark:text-white text-center leading-relaxed">${msg}</p>
            <div class="flex gap-3 mt-6">
                <button class="cancel-btn flex-1 px-4 py-2.5 bg-[#F5F6FA] dark:bg-slate-800 text-[#8888A8] dark:text-slate-400 rounded-xl text-[11px] font-bold uppercase tracking-widest hover:bg-[#DDE0F0] dark:hover:bg-slate-700 transition-all">Cancel</button>
                <button class="confirm-btn flex-1 px-4 py-2.5 bg-[#1845D4] text-white rounded-xl text-[11px] font-bold uppercase tracking-widest hover:bg-[#0F2FA8] transition-all shadow-lg shadow-blue-600/10">Confirm</button>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);
    overlay.querySelector('.cancel-btn').addEventListener('click', () => overlay.remove());
    overlay.querySelector('.confirm-btn').addEventListener('click', () => { overlay.remove(); if (onConfirm) onConfirm(); });
    overlay.addEventListener('click', (e) => { if (e.target === overlay) overlay.remove(); });
}

window.showConfirm = showConfirm;

// Recordings Tab Logic
async function loadRecordings() {
    const list = document.getElementById('recordings-list');
    if (!list) return;

    list.innerHTML = '<div class="col-span-full text-center py-12 text-[#8888A8] text-sm">Loading recordings...</div>';

    try {
        const user = auth.currentUser;
        if (!user) { list.innerHTML = '<div class="col-span-full text-center py-12 text-[#8888A8] text-sm">Sign in to view recordings.</div>'; return; }
        const token = await user.getIdToken();
        const response = await fetch('/api/recordings/lecturer/any', {
            headers: { Authorization: `Bearer ${token}` }
        });
        const data = await response.json();

        if (!data.success || !data.recordings || data.recordings.length === 0) {
            list.innerHTML = '<div class="col-span-full text-center py-12 text-[#8888A8] text-sm font-medium">No recordings found. Start a recording during your next class.</div>';
            return;
        }

        list.innerHTML = data.recordings.map(r => `
            <div class="p-4 sm:p-5 bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800 rounded-xl hover:border-[#1845D4] dark:hover:border-blue-600 transition-all">
                <div class="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
                    <div class="flex items-center gap-3 min-w-0">
                        <div class="w-10 h-10 bg-rose-50 dark:bg-rose-900/20 rounded-lg flex items-center justify-center shrink-0">
                            <i class="fas fa-video text-rose-500 text-sm"></i>
                        </div>
                        <div class="min-w-0">
                            <p class="text-sm font-bold text-[#0D0D1A] dark:text-white truncate">${r.classTitle || 'Untitled Session'}</p>
                            <div class="flex flex-wrap items-center gap-x-3 gap-y-0.5 mt-1">
                                <span class="text-[10px] text-[#8888A8] font-medium">${new Date(r.startedAt).toLocaleDateString()}</span>
                                ${r.durationSeconds > 0 ? `<span class="text-[10px] text-[#8888A8] font-medium">${Math.floor(r.durationSeconds / 60)}m ${Math.floor(r.durationSeconds % 60)}s</span>` : ''}
                                <span class="text-[10px] px-1.5 py-0.5 rounded ${r.status === 'finished' ? 'bg-emerald-50 text-emerald-600' : 'bg-amber-50 text-amber-600'} font-bold uppercase">${r.status}</span>
                            </div>
                        </div>
                    </div>
                    ${r.status === 'finished' ? `<button onclick="downloadRecording('${r.id}', '${(r.classTitle || 'session').replace(/'/g, "\\'")}')" class="sm:self-center px-4 py-2 bg-[#1845D4] text-white rounded-lg text-[10px] font-bold uppercase tracking-widest hover:bg-[#0F2FA8] transition-all w-full sm:w-auto text-center shrink-0"><i class="fas fa-download mr-1.5"></i>Download</button>` : ''}
                </div>
            </div>
        `).join('');
    } catch (err) {
        console.error('Failed to load recordings:', err);
        list.innerHTML = '<div class="col-span-full text-center py-12 text-red-500 text-sm">Failed to load recordings.</div>';
    }
}

async function downloadRecording(id, title) {
    try {
        const user = auth.currentUser;
        if (!user) { showToast('Sign in to download', 'error'); return; }
        const token = await user.getIdToken();
        const response = await fetch(`/api/recordings/download/${id}`, {
            headers: { Authorization: `Bearer ${token}` }
        });
        if (!response.ok) {
            const data = await response.json();
            showToast(data.error || 'Download failed', 'error');
            return;
        }
        const blob = await response.blob();
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `${title.replace(/[^a-z0-9]/gi, '_').toLowerCase()}.mp4`;
        a.click();
        URL.revokeObjectURL(url);
    } catch (err) {
        showToast('Download failed', 'error');
    }
}

// Load recordings when tab is switched to it
const origSwitchTab = window.switchTab;
window.navTo = function(tab) {
    const sidebar = document.getElementById('sidebar');
    const overlay = document.getElementById('sidebar-overlay');
    if (sidebar) {
        sidebar.classList.add('-translate-x-full');
        sidebar.classList.add('hidden');
    }
    if (overlay) overlay.classList.add('hidden');
    document.body.classList.remove('overflow-hidden');
    window.switchTab(tab);
    if (tab === 'recordings') loadRecordings();
};

window.downloadRecording = downloadRecording;

// Logout
const logoutBtn = document.getElementById('logout-btn');
if (logoutBtn) logoutBtn.addEventListener('click', () => signOut(auth));
window.logout = () => signOut(auth);
