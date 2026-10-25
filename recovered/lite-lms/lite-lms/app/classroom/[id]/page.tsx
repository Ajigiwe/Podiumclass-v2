'use client';

import { useEffect, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { useAuth } from '@/contexts/AuthContext';
import { db } from '@/lib/firebase/config';
import { doc, getDoc, addDoc, updateDoc, collection, Timestamp, query, where, getDocs, increment, serverTimestamp, setDoc, onSnapshot } from '@/lib/firebase/firestore';
import { Session } from '@/lib/firebase/types';
import ClassroomContent from '@/components/ClassroomContent';
import { useClassroom } from '@/contexts/ClassroomContext';
import { checkIsCoHost } from '@/lib/firebase/cohost';
import { Clock, RefreshCw, ArrowLeft, Laptop, Mic, MicOff, Sparkles, User as UserIcon, GraduationCap, ArrowRight, Shield, Calendar, Users } from 'lucide-react';
import CountdownTimer from '@/components/CountdownTimer';
import { Skeleton } from '@/components/ui/Skeleton';
import { useAlert } from '@/contexts/AlertContext';
import { useQuery } from '@tanstack/react-query';

// --- Lobby helpers: ping + chime when the lecturer goes live ---
let lobbyChimeCtx: AudioContext | null = null;
function playClassStartChime() {
    try {
        const Ctx = window.AudioContext || (window as any).webkitAudioContext;
        if (!Ctx) return;
        lobbyChimeCtx = lobbyChimeCtx || new Ctx();
        if (lobbyChimeCtx.state === 'suspended') lobbyChimeCtx.resume();
        const now = lobbyChimeCtx.currentTime;
        [880, 1174.66].forEach((freq, i) => {
            const osc = lobbyChimeCtx!.createOscillator();
            const gain = lobbyChimeCtx!.createGain();
            osc.type = 'sine';
            osc.frequency.value = freq;
            const t0 = now + i * 0.18;
            gain.gain.setValueAtTime(0.0001, t0);
            gain.gain.exponentialRampToValueAtTime(0.18, t0 + 0.03);
            gain.gain.exponentialRampToValueAtTime(0.0001, t0 + 0.5);
            osc.connect(gain).connect(lobbyChimeCtx!.destination);
            osc.start(t0);
            osc.stop(t0 + 0.55);
        });
    } catch (e) { /* audio may be blocked until a user gesture — silent fail is fine */ }
}

function notifyClassStarted(title: string) {
    if ('Notification' in window && Notification.permission === 'granted') {
        try {
            const n = new Notification(`🔴 ${title} has started`, {
                body: 'Your lecturer is live — joining the classroom now.',
                icon: '/icon-192x192.png',
                badge: '/icon-192x192.png',
                tag: `class-start-${typeof window !== 'undefined' ? window.location.pathname : ''}`,
            });
            n.onclick = () => window.focus();
        } catch (e) { /* ignore */ }
    }
    playClassStartChime();
    if (navigator.vibrate) { try { navigator.vibrate([120, 60, 120]); } catch (e) { /* ignore */ } }
}

export default function ClassroomPage() {
    const params = useParams();
    const router = useRouter();
    const { user, profile, loading: authLoading } = useAuth();
    const { joinClass, sessionId: currentSessionId } = useClassroom();
    const { showAlert } = useAlert();
    const sessionId = params.id as string;

    const { data: session, isLoading: sessionLoading, error: sessionError } = useQuery({
        queryKey: ['session', sessionId],
        queryFn: async () => {
            const sessionDoc = await getDoc(doc(db, 'sessions', sessionId));
            if (!sessionDoc.exists()) throw new Error('Not found');
            return { id: sessionDoc.id, ...sessionDoc.data() } as Session;
        },
        staleTime: 30000,
    });

    const [loading, setLoading] = useState(true);
    const [canAccess, setCanAccess] = useState(false);
    const [waitingForLecturer, setWaitingForLecturer] = useState(false);
    const [isScheduledWait, setIsScheduledWait] = useState(false);
    const [showProfileModal, setShowProfileModal] = useState(false);
    const [studentName, setStudentName] = useState('');
    const [studentIndex, setStudentIndex] = useState('');
    const [submittingProfile, setSubmittingProfile] = useState(false);
    const [joinMicEnabled, setJoinMicEnabled] = useState(true);
    const [insufficientBalance, setInsufficientBalance] = useState<{ needed: number; have: number } | null>(null);

    console.log('[ClassroomPage] Render - loading:', loading, 'authLoading:', authLoading, 'sessionLoading:', sessionLoading, 'sessionError:', !!sessionError, 'user:', !!user, 'session:', !!session, 'profile:', !!profile);

    useEffect(() => {
        console.log('[ClassroomPage] useEffect triggered - authLoading:', authLoading, 'sessionLoading:', sessionLoading, 'user:', !!user, 'session:', !!session);
        if (authLoading || sessionLoading) {
            console.log('[ClassroomPage] useEffect: early return because authLoading or sessionLoading is true');
            return;
        }
        if (sessionError) { 
            console.error('[ClassroomPage] useEffect: sessionError occurred:', sessionError);
            showAlert('Class not found', 'error'); 
            router.push('/'); 
            return; 
        }
        if (!user || !session) { 
            console.log('[ClassroomPage] useEffect: user or session missing. user:', !!user, 'session:', !!session);
            if (!authLoading && !user) {
                console.log('[ClassroomPage] Redirecting to login...');
                router.push(`/login?redirect=${encodeURIComponent(window.location.pathname)}`); 
            }
            return; 
        }

        const verifyAccess = async () => {
            try {
                console.log('[Classroom:VerifyAccess] Starting verification for sessionId:', sessionId, 'userId:', user.uid);
                
                console.log('[Classroom:VerifyAccess] Checking if user is moderator...');
                const isCoHost = await checkIsCoHost(sessionId, user.uid);
                console.log('[Classroom:VerifyAccess] isCoHost:', isCoHost);
                const isModerator = session.hostId === user.uid || session.lecturerId === user.uid || isCoHost || profile?.role === 'lecturer' || profile?.role === 'admin';
                console.log('[Classroom:VerifyAccess] isModerator result:', isModerator);
                
                // Group Access Control
                if (session.groupId && !isModerator) {
                    console.log('[Classroom:VerifyAccess] Checking group membership for groupId:', session.groupId);
                    const membershipRef = doc(db, 'group_memberships', `${user.uid}_${session.groupId}`);
                    const membershipSnap = await getDoc(membershipRef);
                    if (!membershipSnap.exists()) {
                        console.warn('[Classroom:VerifyAccess] User is not a member of group:', session.groupId);
                        showAlert('This class is restricted to verified group members.', 'error');
                        window.location.href = '/dashboard.html';
                        return;
                    }
                    console.log('[Classroom:VerifyAccess] Group membership verified.');
                }

                if (!session.isActive) {
                    if (session.scheduledStartTime && session.scheduledStartTime.toDate() > new Date()) {
                        console.log('[Classroom:VerifyAccess] Session is scheduled for future. Showing scheduled wait page.');
                        setIsScheduledWait(true); 
                        setLoading(false); 
                        return;
                    }
                    if (!isModerator) {
                        console.log('[Classroom:VerifyAccess] Awaiting host to start class. Showing lobby wait page.');
                        setWaitingForLecturer(true); 
                        setLoading(false);
                        return;
                    }
                }
                
                // Use /api/wallet/deduct for all wallet checks and deductions (Admin SDK)
                if (!isModerator) {
                    try {
                        const token = await user.getIdToken();
                        const deductRes = await fetch('/api/wallet/deduct', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
                            body: JSON.stringify({ sessionId }),
                        });
                        const deductData = await deductRes.json();

                        if (deductRes.status === 402 || deductData.insufficient) {
                            console.warn('[Classroom:VerifyAccess] Insufficient wallet balance.');
                            setInsufficientBalance({ needed: deductData.required || deductData.price || 0, have: deductData.balance || 0 });
                            setLoading(false);
                            return;
                        }
                        if (!deductRes.ok && !deductData.alreadyPaid && !deductData.free) {
                            console.error('[Classroom:VerifyAccess] Deduct failed:', deductData);
                            showAlert(deductData.error || 'Payment failed', 'error');
                            window.location.href = '/dashboard.html';
                            return;
                        }
                        console.log('[Classroom:VerifyAccess] Deduct result:', deductData);
                    } catch (e) {
                        console.error('[Classroom:VerifyAccess] Deduct error:', e);
                        showAlert('Network error. Please try again.', 'error');
                        window.location.href = '/dashboard.html';
                        return;
                    }
                }

                console.log('[Classroom:VerifyAccess] Fetching attendance log for sessionId:', sessionId, 'userId:', user.uid);
                const attendanceSnap = await getDocs(query(collection(db, 'attendance_logs'), where('sessionId', '==', sessionId), where('userId', '==', user.uid)));
                console.log('[Classroom:VerifyAccess] attendanceSnap count:', attendanceSnap.size);
                
                if (attendanceSnap.empty) {
                    // If it's a student with incomplete profile, show the modal
                    if (!isModerator && (!profile?.fullName || !profile?.indexNumber)) {
                        console.log('[Classroom:VerifyAccess] Student profile incomplete. Showing profile modal...');
                        setStudentName(profile?.fullName || '');
                        setStudentIndex(profile?.indexNumber || '');
                        setShowProfileModal(true);
                        setLoading(false);
                        return;
                    }

                    // Otherwise (Lecturer, Co-host, or Student with profile), auto-create the log
                    console.log('[Classroom:VerifyAccess] Creating attendance log...');
                    const logRef = doc(db, 'attendance_logs', `${sessionId}_${user.uid}`);
                    await setDoc(logRef, {
                        sessionId,
                        sessionTitle: session.title || 'Class',
                        userId: user.uid,
                        userName: profile?.fullName || user.email?.split('@')[0] || 'User',
                        userEmail: user.email,
                        userIndexNumber: profile?.indexNumber || 'N/A',
                        joinedAt: serverTimestamp(),
                        lecturerId: session.lecturerId || session.hostId || '',
                        totalVerificationsSent: 0,
                        totalVerificationsCompleted: 0,
                        verificationPercentage: 0,
                        role: isModerator ? 'lecturer' : 'student'
                    });
                    console.log('[Classroom:VerifyAccess] Attendance log created successfully.');
                    
                    // Increment count
                    console.log('[Classroom:VerifyAccess] Incrementing session participantCount...');
                    await updateDoc(doc(db, 'sessions', sessionId), { participantCount: increment(1) });
                    console.log('[Classroom:VerifyAccess] Participant count incremented.');
                }

                if (isModerator && !session.isActive && session.status === 'active') {
                    const scheduledTime = session.scheduledStartTime?.toDate();
                    if (!scheduledTime || scheduledTime <= new Date()) {
                        try {
                            console.log('[Classroom:VerifyAccess] Moderator auto-activating session...');
                            await updateDoc(doc(db, 'sessions', sessionId), { isActive: true, startedAt: serverTimestamp() });
                            console.log('[Classroom:VerifyAccess] Session activated.');
                        } catch (err) {
                            console.error('[Classroom:AutoActivate] Failed to activate session:', err);
                        }
                    }
                }

                console.log('[Classroom:VerifyAccess] Access verified successfully. canAccess -> true');
                setCanAccess(true);
                setLoading(false);
                if (typeof window !== 'undefined') sessionStorage.setItem('podium_user_interacted', 'true');
                
                const finalName = profile?.fullName || user.email?.split('@')[0] || 'User';
                console.log('[Classroom:VerifyAccess] Joining class with userName:', finalName, 'isModerator:', isModerator);
                joinClass(sessionId, session.title || 'Class', finalName, isModerator ? 'lecturer' : 'student', user.uid, profile?.photoURL, profile?.displayIcon, isModerator);
            } catch (error) { 
                console.error('[Classroom:VerifyAccess] Error during verification:', error);
                setLoading(false); 
            }
        };
        verifyAccess();
    }, [user, profile, session, authLoading, sessionLoading, sessionError]);

    // While waiting in the lobby, watch for the lecturer going live: ping + auto-join
    useEffect(() => {
        if (!user || !session || (!waitingForLecturer && !isScheduledWait)) return;
        let done = false;
        const unsub = onSnapshot(doc(db, 'sessions', sessionId), (snap) => {
            if (done) return;
            const s = snap.data();
            if (!s || s.isActive !== true) return;
            done = true;
            const isHost = s.hostId === user.uid || s.lecturerId === user.uid;
            if (!isHost) notifyClassStarted(String(s.title || 'Your class'));
            // Re-run the join flow now that the class is live (lets the chime land first)
            setTimeout(() => window.location.reload(), 1400);
        }, (err) => console.error('[ClassroomLobby] watch failed:', err));
        return () => { done = true; unsub(); };
    }, [user, session, waitingForLecturer, isScheduledWait, sessionId]);

    const handleProfileSubmit = async (e: React.FormEvent) => {
        e.preventDefault(); 
        if (!studentName.trim() || !studentIndex.trim()) return showAlert("Fields required.", "error");
        setSubmittingProfile(true);
        try {
            // Create or update profile
            await setDoc(doc(db, 'profiles', user!.uid), { 
                fullName: studentName, 
                indexNumber: studentIndex, 
                updatedAt: serverTimestamp(),
                id: user!.uid,
                email: user!.email,
                role: 'student',
                createdAt: serverTimestamp() // setDoc with merge will not overwrite if it exists and we use serverTimestamp? 
                // Actually, if we want to preserve createdAt, we should probably check if it exists or use a more complex update.
                // But for a new user, this is fine.
            }, { merge: true });

            // Create log
            const logRef = doc(db, 'attendance_logs', `${sessionId}_${user!.uid}`);
            await setDoc(logRef, { 
                sessionId, 
                sessionTitle: session?.title || 'Class', 
                userId: user!.uid, 
                userName: studentName, 
                userEmail: user!.email, 
                userIndexNumber: studentIndex, 
                joinedAt: serverTimestamp(), 
                lecturerId: session?.lecturerId || session?.hostId || '',
                totalVerificationsSent: 0,
                totalVerificationsCompleted: 0,
                verificationPercentage: 0,
                role: 'student'
            });

            await updateDoc(doc(db, 'sessions', sessionId), { participantCount: increment(1) });
            
            setShowProfileModal(false); 
            setCanAccess(true);
            if (typeof window !== 'undefined') sessionStorage.setItem('podium_user_interacted', 'true');
            joinClass(sessionId, session?.title || 'Class', studentName, 'student', user!.uid, profile?.photoURL, profile?.displayIcon, joinMicEnabled);
        } catch (error) { 
            console.error('[Classroom:ProfileSubmit] Error:', error);
            showAlert("Identity check failed.", "error"); 
        } finally { 
            setSubmittingProfile(false); 
        }
    };

    if (loading) return <div className="min-h-screen flex items-center justify-center bg-[#F8F9FF]"><div className="w-8 h-8 border-2 border-indigo-600 border-t-transparent rounded-full animate-spin" /></div>;

    if (insufficientBalance) {
        return (
            <div className="min-h-screen bg-[#F8F9FF] flex items-center justify-center p-8 animate-in fade-in duration-500">
                <div className="max-w-md w-full space-y-8 text-center">
                    <div className="space-y-4">
                        <div className="w-20 h-20 bg-red-50 rounded-2xl flex items-center justify-center mx-auto border border-red-100">
                            <span className="text-3xl">💰</span>
                        </div>
                        <div className="space-y-2">
                            <p className="text-[10px] font-bold text-red-500 uppercase tracking-[0.4em]">Insufficient Balance</p>
                            <h1 className="text-2xl font-black text-slate-900 tracking-tight">Top Up Required</h1>
                            <p className="text-sm text-slate-500 font-medium">You need GHS {(insufficientBalance.needed / 100).toFixed(2)} to enter this class. Your balance is GHS {(insufficientBalance.have / 100).toFixed(2)}.</p>
                        </div>
                    </div>
                    <div className="bg-white rounded-xl p-6 border border-slate-200 shadow-sm space-y-4">
                        <p className="text-xs text-slate-500">Add funds to your wallet to join this class.</p>
                        <div className="flex gap-3">
                            <a href="/wallet.html" className="flex-1 py-3.5 bg-indigo-600 text-white rounded-xl font-bold text-[10px] uppercase tracking-widest shadow-lg shadow-indigo-600/10 active:scale-95 transition-all text-center">Top Up Wallet</a>
                            <a href="/dashboard.html" className="flex-1 py-3.5 bg-white text-slate-500 rounded-xl font-bold text-[10px] uppercase tracking-widest border border-slate-200 hover:text-indigo-600 transition-all text-center">Back to Dashboard</a>
                        </div>
                    </div>
                </div>
            </div>
        );
    }

    if (isScheduledWait || waitingForLecturer) {
        return (
            <div className="min-h-screen bg-[#F8F9FF] flex items-center justify-center p-8 animate-in fade-in duration-700">
                <div className="max-w-md w-full space-y-12 text-center">
                    <div className="space-y-6">
                        <div className="w-20 h-20 bg-white rounded-xl flex items-center justify-center mx-auto border border-slate-200 shadow-sm transition-transform hover:scale-105"><Clock className={`w-8 h-8 text-indigo-600 ${waitingForLecturer ? 'animate-pulse' : ''}`} /></div>
                        <div className="space-y-2"><p className="text-[10px] font-bold text-slate-400 uppercase tracking-[0.4em]">{isScheduledWait ? 'Upcoming Session' : 'Lobby Access'}</p><h1 className="text-3xl lg:text-4xl font-black text-slate-900 tracking-tight leading-tight">{isScheduledWait ? 'Starting Soon' : 'Awaiting Host'}</h1></div>
                    </div>
                    {isScheduledWait && session?.scheduledStartTime && <div className="py-4"><CountdownTimer targetDate={session.scheduledStartTime.toDate()} onComplete={() => { setIsScheduledWait(false); setWaitingForLecturer(true); }} /></div>}
                    <div className="bg-white rounded-xl p-8 border border-slate-200 text-left space-y-6 shadow-sm">
                        <div className="space-y-1"><p className="text-[10px] font-bold text-indigo-600 uppercase tracking-widest">Active Classroom</p><h2 className="text-2xl font-black text-slate-900 leading-tight">{session?.title}</h2></div>
                        <div className="flex flex-wrap items-center gap-6 pt-6 border-t border-slate-100 text-[10px] font-bold text-slate-400 uppercase tracking-widest">
                            <span className="flex items-center gap-1.5"><Laptop className="w-3.5 h-3.5" /> Virtual Environment</span>
                            <span className="flex items-center gap-1.5"><Calendar className="w-3.5 h-3.5" /> {session?.scheduledStartTime?.toDate().toLocaleDateString() || 'Today'}</span>
                        </div>
                    </div>
                    <div className="flex flex-col gap-3">
                        {waitingForLecturer && <button onClick={() => window.location.reload()} className="w-full py-4 bg-indigo-600 text-white rounded-xl font-bold text-[10px] uppercase tracking-widest shadow-lg shadow-indigo-600/10 active:scale-95 transition-all">Check In Again</button>}
                        <button onClick={() => window.location.href = '/dashboard.html'} className="w-full py-4 bg-white text-slate-500 rounded-xl font-bold text-[10px] uppercase tracking-widest border border-slate-200 hover:text-indigo-600 transition-all flex items-center justify-center gap-2">Return to Dashboard</button>
                    </div>
                </div>
            </div>
        );
    }

    if (!session || (!canAccess && !showProfileModal && !insufficientBalance)) return null;

    return (
        <div className="min-h-screen bg-black">
            <ClassroomContent session={session} user={user} profile={profile} sessionId={sessionId} />
            {showProfileModal && (
                <div className="fixed inset-0 z-[200] flex items-center justify-center p-8 animate-in fade-in duration-500">
                    <div className="absolute inset-0 bg-slate-900/40 backdrop-blur-sm" />
                    <div className="relative w-full max-w-md bg-white rounded-xl p-10 border border-slate-200 shadow-2xl animate-in zoom-in-95 duration-300">
                        <div className="space-y-6 mb-10 text-center">
                            <div className="w-16 h-16 bg-indigo-50 rounded-xl flex items-center justify-center mx-auto border border-indigo-100 text-indigo-600 shadow-sm"><Shield className="w-8 h-8" /></div>
                            <div className="space-y-2"><h2 className="text-2xl font-bold text-slate-900 leading-none">Identity Check</h2><p className="text-xs text-slate-500 font-bold uppercase tracking-widest">Verify details for the attendance registry.</p></div>
                        </div>
                        <form onSubmit={handleProfileSubmit} className="space-y-8">
                            <div className="space-y-4">
                                <div className="space-y-1.5"><label className="text-[10px] font-bold text-slate-400 uppercase tracking-widest ml-1">Student Full Name</label><input type="text" value={studentName} onChange={(e) => setStudentName(e.target.value)} required className="w-full px-5 py-3.5 bg-slate-50 border border-slate-200 rounded-xl text-sm font-medium outline-none focus:border-indigo-600 focus:bg-white transition-all" /></div>
                                <div className="space-y-1.5"><label className="text-[10px] font-bold text-slate-400 uppercase tracking-widest ml-1">Registration Index</label><input type="text" value={studentIndex} onChange={(e) => setStudentIndex(e.target.value)} required className="w-full px-5 py-3.5 bg-slate-50 border border-slate-200 rounded-xl text-sm font-medium outline-none focus:border-indigo-600 focus:bg-white transition-all" /></div>
                                <div className="flex items-center justify-between p-5 bg-slate-50 rounded-xl border border-slate-200"><div className="flex items-center gap-3"><div className={`p-2 rounded-lg transition-colors ${joinMicEnabled ? 'bg-emerald-50 text-emerald-600' : 'bg-red-50 text-red-600'}`}>{joinMicEnabled ? <Mic className="w-4 h-4" /> : <MicOff className="w-4 h-4" />}</div><p className="text-[10px] font-bold text-slate-900 uppercase tracking-widest">Mic {joinMicEnabled ? 'Active' : 'Silent'}</p></div><button type="button" onClick={() => setJoinMicEnabled(!joinMicEnabled)} className={`relative inline-flex h-6 w-11 items-center rounded-full transition-all ${joinMicEnabled ? 'bg-indigo-600' : 'bg-slate-200'}`}><span className={`inline-block h-4 w-4 transform rounded-full bg-white transition-all ${joinMicEnabled ? 'translate-x-6' : 'translate-x-1'}`} /></button></div>
                            </div>
                            <button type="submit" disabled={submittingProfile} className="w-full py-4 bg-indigo-600 text-white rounded-xl font-bold text-[10px] uppercase tracking-widest shadow-xl shadow-indigo-600/10 active:scale-95 transition-all">{submittingProfile ? '...' : 'Enter Workspace'}</button>
                        </form>
                    </div>
                </div>
            )}
        </div>
    );
}
