// public/js/profile.js
import { auth, db } from './firebase-config.js?v=19';
import { onAuthStateChanged, signOut } from './auth-client.js?v=1';
import { 
    doc, getDoc, updateDoc, setDoc, serverTimestamp, collection, query, where, getDocs, Timestamp 
} from "./pg-firestore.js?v=1";

// DOM Elements
const userName = document.getElementById('user-name');
const userRole = document.getElementById('user-role');
const userAvatar = document.getElementById('user-avatar');
const loadingBar = document.getElementById('top-loading-bar');

const profilePhotoContainer = document.getElementById('profile-photo-container');
const photoUpload = document.getElementById('photo-upload');
const profileName = document.getElementById('profile-name');
const profileRoleBadge = document.getElementById('profile-role-badge');
const profileEmail = document.getElementById('profile-email');
const displayEmail = document.getElementById('display-email');
const statClasses = document.getElementById('stat-classes');
const statSecondary = document.getElementById('stat-secondary');
const statSecondaryLabel = document.getElementById('stat-secondary-label');
const verificationStatus = document.getElementById('verification-status');
const verificationCard = document.getElementById('verification-card');
const requestVerificationBtn = document.getElementById('request-verification-btn');

const profileForm = document.getElementById('profile-form');
const inputFullname = document.getElementById('input-fullname');
const inputBio = document.getElementById('input-bio');
const resetPasswordBtn = document.getElementById('reset-password-btn');

// State
let currentUserId = null;
let userProfile = null;
let activeTab = 'personal';

// Auth Listener
onAuthStateChanged(auth, async (user) => {
    if (!user) {
        window.location.href = './auth/login.html';
        return;
    }
    currentUserId = user.uid;
    setLoading(true);
    
    // Fetch Profile. Fall back to the PostgreSQL-backed session user while the
    // remaining Firestore profile reads are migrated.
    let profileSnap = null;
    try {
        profileSnap = await getDoc(doc(db, 'profiles', user.uid));
    } catch (error) {
        console.warn('[profile] Firestore profile unavailable; using session profile', error);
    }
    userProfile = profileSnap?.data() || {
        fullName: user.displayName || user.email,
        email: user.email,
        photoURL: user.photoURL || null,
        role: user.role || 'student',
        isVerified: false,
    };
    
    // Hide password section entirely for Google/social accounts
    const isGoogleUser = (user.providerData || []).some(p => p.providerId === 'google.com');
    const passwordSection = document.getElementById('password-section');
    if (isGoogleUser && passwordSection) {
        passwordSection.style.display = 'none';
    }

    // Initial Render
    renderProfile();
    loadStatistics();
    
    // Show attendance nav if eligible
    if (userProfile.role === 'admin' || userProfile.role === 'lecturer' || userProfile.role === 'rep' || userProfile.isVerified) {
        const attendanceNav = document.getElementById('nav-attendance');
        if (attendanceNav) attendanceNav.classList.remove('hidden');
    }

    if (userProfile.role === 'lecturer') {
        document.getElementById('tab-recordings').classList.remove('hidden');
        fetchRecordings();
    }
    
    setLoading(false);
});

function setLoading(isLoading) {
    if (!loadingBar) return;
    loadingBar.style.width = isLoading ? '30%' : '100%';
    if (!isLoading) setTimeout(() => loadingBar.style.width = '0%', 400);
}

function renderProfile() {
    const nameStr = userProfile.fullName || auth.currentUser.email.split('@')[0];
    const roleStr = userProfile.role || 'student';
    
    // Sidebar
    userName.innerText = nameStr.split(' ')[0];
    userRole.innerText = roleStr.charAt(0).toUpperCase() + roleStr.slice(1);
    
    // Profile Main
    profileName.innerText = userProfile.fullName || 'Identity Pending';
    profileRoleBadge.innerText = roleStr;
    profileEmail.innerText = auth.currentUser.email;
    displayEmail.innerText = auth.currentUser.email;
    
    // Form
    inputFullname.value = userProfile.fullName || '';
    inputBio.value = userProfile.bio || '';
    
    // Avatars
    const photoHTML = userProfile.photoURL 
        ? `<img src="${userProfile.photoURL}" class="w-full h-full object-cover">` 
        : (userProfile.fullName || 'U').charAt(0).toUpperCase();
    
    userAvatar.innerHTML = photoHTML;
    profilePhotoContainer.innerHTML = photoHTML;
    
    // Verification Status
    if (userProfile.isVerified) {
        verificationStatus.innerText = 'Verified Rep';
        verificationStatus.classList.replace('text-[#8888A8]', 'text-[#1BA05C]');
        requestVerificationBtn.innerText = 'Badge Active';
        requestVerificationBtn.disabled = true;
        requestVerificationBtn.classList.add('opacity-50', 'cursor-not-allowed');
    } else if (roleStr === 'lecturer') {
        verificationCard.classList.add('hidden');
    }
}

async function loadStatistics() {
    try {
        // Hosted classes
        const hostedSnap = await getDocs(query(collection(db, 'sessions'), where('lecturerId', '==', currentUserId)));
        const hostedCount = hostedSnap.size;

        // Joined classes (via transactions)
        const joinedSnap = await getDocs(query(collection(db, 'transactions'), where('userId', '==', currentUserId), where('isHidden', '==', false)));
        const joinedCount = joinedSnap.size;

        statClasses.innerText = hostedCount + joinedCount;

        if (userProfile.role === 'lecturer') {
            // Unique learners across hosted sessions
            const logsSnap = await getDocs(query(collection(db, 'attendance_logs'), where('lecturerId', '==', currentUserId)));
            const studentIds = new Set(logsSnap.docs.map(d => d.data().userId));
            statSecondary.innerText = studentIds.size;
            statSecondaryLabel.innerText = 'Learners';
        } else {
            // Unique sessions attended
            const logsSnap = await getDocs(query(collection(db, 'attendance_logs'), where('userId', '==', currentUserId)));
            const sessionIds = new Set(logsSnap.docs.map(d => d.data().sessionId));
            statSecondary.innerText = sessionIds.size;
            statSecondaryLabel.innerText = 'Attended';
        }
    } catch (err) {
        console.error('Stats error:', err);
    }
}

// Tab Switching
window.switchProfileTab = (tab) => {
    activeTab = tab;
    const tabs = ['personal', 'security', 'recordings'];
    tabs.forEach(t => {
        const btn = document.getElementById(`tab-${t}`);
        const view = document.getElementById(`view-${t}`);
        if (!btn || !view) return;
        
        if (t === tab) {
            btn.classList.add('border-[#1845D4]', 'text-[#1845D4]');
            btn.classList.remove('border-transparent', 'text-[#8888A8]');
            view.classList.remove('hidden');
        } else {
            btn.classList.remove('border-[#1845D4]', 'text-[#1845D4]');
            btn.classList.add('border-transparent', 'text-[#8888A8]');
            view.classList.add('hidden');
        }
    });
};

// --- ACTIONS ---

// Save Changes
profileForm.onsubmit = async (e) => {
    e.preventDefault();
    const btn = e.target.querySelector('button[type="submit"]');
    btn.disabled = true;
    btn.innerText = 'Preserving...';
    
    try {
        await updateDoc(doc(db, 'profiles', currentUserId), {
            fullName: inputFullname.value,
            bio: inputBio.value,
            updatedAt: Timestamp.now()
        });
        userProfile.fullName = inputFullname.value;
        userProfile.bio = inputBio.value;
        renderProfile();
        showToast('Changes preserved.');
    } catch (err) {
        showToast('Failed to save.', 'error');
    } finally {
        btn.disabled = false;
        btn.innerText = 'Save Changes';
    }
};

// Photo Upload
async function compressImage(file, maxDim = 512, quality = 0.85) {
    const img = await new Promise((resolve, reject) => {
        const url = URL.createObjectURL(file);
        const im = new Image();
        im.onload = () => { URL.revokeObjectURL(url); resolve(im); };
        im.onerror = () => { URL.revokeObjectURL(url); reject(new Error('Not a valid image.')); };
        im.src = url;
    });
    const scale = Math.min(1, maxDim / Math.max(img.width, img.height));
    const canvas = document.createElement('canvas');
    canvas.width = Math.round(img.width * scale);
    canvas.height = Math.round(img.height * scale);
    canvas.getContext('2d').drawImage(img, 0, 0, canvas.width, canvas.height);
    const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', quality));
    if (!blob) throw new Error('Could not process image.');
    return blob;
}

photoUpload.onchange = async (e) => {
    const file = e.target.files[0];
    e.target.value = ''; // allow re-selecting the same file
    if (!file) return;
    if (!file.type.startsWith('image/')) {
        showToast('Please choose an image file.', 'error');
        return;
    }
    
    setLoading(true);
    try {
        // Downscale/compress so any phone photo fits the 5MB limit
        const blob = await compressImage(file);
        const token = await auth.currentUser.getIdToken();
        const res = await fetch('/api/storage/presign', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
            body: JSON.stringify({ kind: 'profile', size: blob.size })
        });
        const data = await res.json();
        if (!res.ok) throw new Error(data.error || 'Failed to create upload');

        const putRes = await fetch(data.uploadUrl, { method: 'PUT', body: blob, headers: { 'Content-Type': 'image/jpeg' } });
        if (!putRes.ok) throw new Error(`Upload failed (${putRes.status})`);

        await updateDoc(doc(db, 'profiles', currentUserId), { 
            photoURL: data.url, 
            updatedAt: Timestamp.now() 
        });
        
        userProfile.photoURL = data.url;
        renderProfile();
        showToast('Photo updated.');
    } catch (err) {
        console.error('Photo upload failed:', err);
        showToast(err.message || 'Upload failed.', 'error');
    } finally {
        setLoading(false);
    }
};

// Password Reset
resetPasswordBtn.onclick = async () => {
    try {
        const response = await fetch('/api/auth/reset-password', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email: auth.currentUser.email })
        });

        const result = await response.json();
        if (!response.ok) throw new Error(result.error || 'Failed to send');

        showToast('Reset link sent to email.');
    } catch (err) {
        showToast(err.message.toUpperCase(), 'error');
    }
};

// Verification Request
requestVerificationBtn.onclick = async () => {
    const adminPhone = "233550599755";
    const message = `Hi, I am ${userProfile.fullName} (${auth.currentUser.email}), and I'd like to be verified as a Course Representative on Podium.`;
    const whatsappUrl = `https://wa.me/${adminPhone}?text=${encodeURIComponent(message)}`;
    
    try {
        await setDoc(doc(db, 'verification_requests', currentUserId), {
            userId: currentUserId,
            userName: userProfile.fullName,
            userEmail: auth.currentUser.email,
            status: 'pending',
            requestedAt: serverTimestamp()
        });
        window.open(whatsappUrl, '_blank');
        showToast('Request logged.');
    } catch (err) {
        showToast('Failed to log request.', 'error');
    }
};

// Recordings (Lecturers only)
async function fetchRecordings() {
    const list = document.getElementById('recordings-list');
    list.innerHTML = `<div class="py-10 text-center animate-pulse"><i class="fas fa-circle-notch fa-spin text-[#1845D4]"></i></div>`;
    
    try {
        const token = await auth.currentUser.getIdToken();
        const response = await fetch(`/api/recordings/lecturer/${currentUserId}`, {
            headers: { Authorization: `Bearer ${token}` }
        });
        const data = await response.json();
        
        if (data.success && data.recordings.length > 0) {
            list.innerHTML = '';
            data.recordings.forEach(rec => {
                const div = document.createElement('div');
                div.className = 'group p-6 bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800 rounded-xl flex flex-col md:flex-row md:items-center justify-between gap-6 hover:border-[#1845D4] transition-all';
                div.innerHTML = `
                    <div class="flex items-center gap-6">
                        <div class="w-12 h-12 bg-[#F5F6FA] dark:bg-slate-800 rounded-lg flex items-center justify-center border border-[#DDE0F0] dark:border-slate-700 text-[#1845D4] dark:text-blue-400"><i class="fas fa-video"></i></div>
                        <div>
                            <h4 class="text-sm font-bold text-[#0D0D1A] dark:text-white">${rec.classTitle || 'Session'}</h4>
                            <div class="flex gap-4 text-[10px] font-bold text-[#8888A8] uppercase tracking-widest mt-1">
                                <span><i class="far fa-calendar"></i> ${new Date(rec.startedAt).toLocaleDateString()}</span>
                                <span><i class="far fa-clock"></i> ${Math.floor(rec.durationSeconds/60)}m</span>
                            </div>
                        </div>
                    </div>
                    <button data-recording-id="${rec.id}" class="download-recording px-6 py-2 bg-[#0D0D1A] dark:bg-slate-100 text-white dark:text-[#0D0D1A] rounded-lg text-[9px] font-black uppercase tracking-widest hover:bg-[#1845D4] hover:text-white transition-all text-center">Download</button>
                `;
                list.appendChild(div);
            });
            list.querySelectorAll('.download-recording').forEach(btn => {
                btn.addEventListener('click', () => window.downloadRecording(btn.dataset.recordingId));
            });
        } else {
            list.innerHTML = `<div class="py-20 text-center text-[#8888A8] text-[10px] font-black uppercase tracking-[0.4em] italic">No preservation logs found.</div>`;
        }
    } catch (err) {
        list.innerHTML = `<div class="py-20 text-center text-red-500 text-[10px] font-bold uppercase tracking-widest">Failed to load archive.</div>`;
    }
}

document.getElementById('refresh-recordings').onclick = fetchRecordings;

window.downloadRecording = async (recordingId) => {
    // The download route requires a Firebase token, so a plain <a href> would 401.
    const token = await auth.currentUser.getIdToken();
    const res = await fetch(`/api/recordings/download/${recordingId}`, {
        headers: { Authorization: `Bearer ${token}` }
    });
    if (!res.ok) {
        showToast('Unable to download this recording.', 'error');
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

// Toast Helper
function showToast(msg, type = 'success') {
    const toast = document.createElement('div');
    toast.className = `fixed bottom-8 left-1/2 -translate-x-1/2 px-6 py-3 ${type === 'success' ? 'bg-[#0D0D1A] dark:bg-slate-100 text-white dark:text-[#0D0D1A]' : 'bg-red-600 text-white'} text-[10px] font-black uppercase tracking-widest rounded-full shadow-2xl z-[200] animate-in slide-in-from-bottom duration-300`;
    toast.innerText = msg;
    document.body.appendChild(toast);
    setTimeout(() => {
        toast.classList.add('animate-out', 'fade-out', 'slide-out-to-bottom');
        setTimeout(() => toast.remove(), 300);
    }, 3000);
}

// Logout
document.getElementById('logout-btn').onclick = () => signOut(auth);
