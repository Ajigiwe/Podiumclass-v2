'use client';

import { useState, useEffect, useCallback } from 'react';
import { doc, updateDoc, collection, onSnapshot } from '@/lib/firebase/firestore';
import { Session } from '@/lib/firebase/types';
import { useClassroom } from '@/contexts/ClassroomContext';
import { useAlert } from '@/contexts/AlertContext';
import { db } from '@/lib/firebase/config';
import { generateMeetingCode } from '@/lib/meetingCode';
import { endSession } from '@/lib/firebase/session-utils';
import {
    subscribeToPermissionRequests,
    subscribeToAllPermissions,
    grantPermission,
    grantAllPermissions,
    denyPermission,
    revokePermission,
    revokeAllPermissions,
    PermissionRequest,
    ParticipantPermissions,
    PermissionType,
} from '@/lib/firebase/permissions';
import { MicOff, VideoOff, UserX, Mic, VideoIcon, Volume2, Crown, Shield, X } from 'lucide-react';

import { ClassroomHeader } from './classroom/ClassroomHeader';
import { ShareInviteModal } from './classroom/ShareInviteModal';
import { MobileDrawer } from './classroom/MobileDrawer';
import { MaterialsModal } from './classroom/MaterialsModal';

interface ClassroomContentProps {
    session: Session;
    user: any;
    profile: any;
    sessionId: string;
}

export default function ClassroomContent({ session, user, profile, sessionId }: ClassroomContentProps) {
    const {
        sessionId: ctxSessionId,
        title: ctxTitle,
        userId: ctxUserId,
        userRole: ctxUserRole,
        leaveClass,
        isFloating,
        participants,
        muteParticipant,
        muteAllParticipants,
        kickParticipant,
        isModerator,
        isHost,
        sessionData,
        coHosts,
        liveKitRoom,
    } = useClassroom();
    const { showAlert, showConfirm } = useAlert();

    const [showParticipantsModal, setShowParticipantsModal] = useState(false);
    const [showShareModal, setShowShareModal] = useState(false);
    const [showMobileMenu, setShowMobileMenu] = useState(false);
    const [showMaterialsModal, setShowMaterialsModal] = useState(false);
    const [hasNewMaterials, setHasNewMaterials] = useState(false);
    const [lastKnownMaterialCount, setLastKnownMaterialCount] = useState(0);

    const [pendingRequests, setPendingRequests] = useState<PermissionRequest[]>([]);
    const [activePermissions, setActivePermissions] = useState<{ participantId: string; permissions: ParticipantPermissions }[]>([]);
    const [autoApproveMic, setAutoApproveMic] = useState(false);

    const isMutedAll = sessionData?.isMutedAll || false;

    useEffect(() => {
        if (!isModerator || !sessionId) return;
        const unsub1 = subscribeToPermissionRequests(sessionId, setPendingRequests);
        const unsub2 = subscribeToAllPermissions(sessionId, setActivePermissions);
        return () => { unsub1(); unsub2(); };
    }, [sessionId, isModerator]);

    useEffect(() => {
        if (!sessionId) return;
        const materialsRef = collection(db, 'sessions', sessionId, 'materials');
        let isFirstLoad = true;
        const unsub = onSnapshot(materialsRef, (snapshot) => {
            const count = snapshot.docs.length;
            if (!isFirstLoad && count > lastKnownMaterialCount && !showMaterialsModal) setHasNewMaterials(true);
            setLastKnownMaterialCount(count);
            isFirstLoad = false;
        });
        return () => unsub();
    }, [sessionId, lastKnownMaterialCount, showMaterialsModal]);

    // autoApproveMic is held locally so the toggle can update optimistically, but the
    // Firestore value remains the source of truth and must win when it changes. Mirroring
    // a prop into state is the point of this effect, so the heuristic is suppressed.
    useEffect(() => {
        // eslint-disable-next-line react-hooks/set-state-in-effect
        if (session.autoApproveMic !== undefined) setAutoApproveMic(session.autoApproveMic);
    }, [session.autoApproveMic]);

    const toggleAutoApproveMic = async () => {
        const newState = !autoApproveMic;
        setAutoApproveMic(newState);
        try { await updateDoc(doc(db, 'sessions', sessionId), { autoApproveMic: newState }); } catch {}
    };

    const handleGrant = async (identity: string, type: PermissionType) => {
        try { await grantPermission(sessionId, identity, ctxUserId || '', type); } catch {}
    };
    const handleGrantAll = useCallback(async () => {
        if (pendingRequests.length === 0) return;
        try {
            await grantAllPermissions(sessionId, ctxUserId || '', pendingRequests.map(r => r.participantId), pendingRequests[0].requestType);
        } catch {}
    }, [pendingRequests, sessionId, ctxUserId]);
    const handleDeny = async (identity: string) => { try { await denyPermission(sessionId, identity); } catch {} };
    const handleRevoke = async (identity: string, type: PermissionType) => { try { await revokePermission(sessionId, identity, type); } catch {} };

    useEffect(() => {
        if (autoApproveMic && pendingRequests.length > 0 && isModerator && !isMutedAll) handleGrantAll();
    }, [pendingRequests.length, autoApproveMic, isModerator, handleGrantAll, isMutedAll]);

    const handleMuteStudent = async (participantId: string, identity: string) => {
        muteParticipant(participantId);
        try { await revokePermission(sessionId, identity, 'microphone'); } catch {}
    };

    const handleMuteAll = async () => {
        const studentIds = participants.filter(p => !p.isLocal && p.role !== 'moderator' && !coHosts.some(ch => ch.userId === p.metadata?.userId));
        if (isMutedAll) {
            try { await grantAllPermissions(sessionId, ctxUserId || '', studentIds.map(p => p.participantId), 'microphone'); } catch {}
            try { await updateDoc(doc(db, 'sessions', sessionId), { isMutedAll: false }); } catch {}
        } else {
            muteAllParticipants();
            try { await revokeAllPermissions(sessionId, studentIds.map(p => p.identity), 'microphone'); } catch {}
        }
    };

    const meetingCode = session.meetingCode || generateMeetingCode(sessionId);
    const fullLink = typeof window !== 'undefined' ? `${window.location.origin}/classroom/${sessionId}` : '';

    useEffect(() => {
        if (!session.meetingCode && isHost) {
            updateDoc(doc(db, 'sessions', sessionId), { meetingCode: generateMeetingCode(sessionId) }).catch(() => {});
        }
    }, [session.meetingCode, sessionId, isHost]);

    const handleEndSession = () => {
        showConfirm('End this class session? All students will be removed.', async () => {
            try {
                if (liveKitRoom) {
                    liveKitRoom.localParticipant.publishData(
                        new TextEncoder().encode(JSON.stringify({ type: 'class_ended' })),
                        { reliable: true }
                    ).catch(() => {});
                }
                await endSession(sessionId);
                showAlert('Class session ended.', 'success');
            } catch { showAlert('Failed to end session.', 'error'); }
        });
    };

    const handleLeave = () => { leaveClass(); window.location.href = '/dashboard.html'; };

    const getParticipantPermissions = (participantId: string) =>
        activePermissions.find(p => p.participantId === participantId)?.permissions || null;

    return (
        <div className="min-h-screen bg-black font-sans">
            <ClassroomHeader
                title={session.title}
                isActive={session.isActive}
                isModerator={isModerator}
                isHost={isHost}
                participantCount={participants.length}
                pendingRequestCount={pendingRequests.length}
                hasNewMaterials={hasNewMaterials}
                sessionId={sessionId}
                ctxSessionId={ctxSessionId}
                ctxUserId={ctxUserId}
                ctxTitle={ctxTitle}
                onOpenParticipants={() => setShowParticipantsModal(true)}
                onOpenMaterials={() => { setShowMaterialsModal(true); setHasNewMaterials(false); }}
                onOpenShare={() => setShowShareModal(true)}
                onOpenMobileMenu={() => setShowMobileMenu(true)}
                onEndSession={handleEndSession}
            />

            <div
                style={{ position: 'fixed', top: '56px', left: 0, right: 0, bottom: 0, backgroundColor: '#000', paddingBottom: '80px' }}
                className="sm:!top-[64px] sm:pb-[100px]"
            >
                <div
                    id="classroom-video-mount"
                    style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, display: isFloating ? 'none' : 'block' }}
                />
            </div>

            <MobileDrawer
                open={showMobileMenu}
                onClose={() => setShowMobileMenu(false)}
                isModerator={isModerator}
                isHost={isHost}
                participantCount={participants.length}
                hasNewMaterials={hasNewMaterials}
                sessionId={sessionId}
                ctxSessionId={ctxSessionId}
                ctxUserId={ctxUserId}
                ctxTitle={ctxTitle}
                isActive={session.isActive}
                onOpenParticipants={() => setShowParticipantsModal(true)}
                onOpenShare={() => setShowShareModal(true)}
                onOpenMaterials={() => { setShowMaterialsModal(true); setHasNewMaterials(false); }}
                onEndSession={handleEndSession}
                onLeave={handleLeave}
            />

            <ShareInviteModal open={showShareModal} onClose={() => setShowShareModal(false)} meetingCode={meetingCode} fullLink={fullLink} />

            {showMaterialsModal && (
                <MaterialsModal sessionId={sessionId} userId={ctxUserId || ''} isModerator={isModerator} onClose={() => setShowMaterialsModal(false)} />
            )}

            {/* Participants Modal */}
            {showParticipantsModal && (
                <div className="fixed inset-0 z-[500] flex items-center justify-center p-4">
                    <div className="absolute inset-0 bg-black/90 backdrop-blur-md" onClick={() => setShowParticipantsModal(false)} />
                    <div className="relative w-full max-w-md max-h-[80vh] bg-slate-900 border border-white/10 rounded-[2rem] shadow-[0_30px_100px_rgba(0,0,0,0.8)] flex flex-col overflow-hidden animate-in zoom-in-95 fade-in duration-300">
                        <div className="p-5 sm:p-6 border-b border-white/5 flex items-center justify-between">
                            <div>
                                <h2 className="text-lg font-black text-white">Participants</h2>
                                <p className="text-[10px] text-slate-400 font-bold uppercase tracking-widest">{participants.length} in room</p>
                            </div>
                            <button onClick={() => setShowParticipantsModal(false)} className="p-2 text-slate-400 hover:text-white bg-white/5 rounded-xl transition-all"><X className="w-5 h-5" /></button>
                        </div>

                        <div className="flex-1 overflow-y-auto p-5 sm:p-6 space-y-6">
                            {isModerator && (
                                <div className="bg-white/5 border border-white/5 rounded-2xl p-4 space-y-3">
                                    <div className="flex items-center justify-between">
                                        <span className="text-[10px] font-black text-slate-400 uppercase tracking-widest">Controls</span>
                                        <div className="flex items-center gap-2">
                                            <button onClick={handleMuteAll} className="px-3 py-1.5 text-[9px] font-black uppercase tracking-widest bg-white/5 hover:bg-white/10 rounded-lg transition-all text-white flex items-center gap-1.5">
                                                <Volume2 className="w-3 h-3" />
                                                {isMutedAll ? 'Unmute All' : 'Mute All'}
                                            </button>
                                            <label className="flex items-center gap-2 px-3 py-1.5 text-[9px] font-black uppercase tracking-widest bg-white/5 hover:bg-white/10 rounded-lg transition-all text-white cursor-pointer">
                                                <input type="checkbox" checked={autoApproveMic} onChange={toggleAutoApproveMic} className="rounded" />
                                                Smart Mic
                                            </label>
                                        </div>
                                    </div>
                                </div>
                            )}

                            {isModerator && pendingRequests.length > 0 && (
                                <div className="bg-amber-500/5 border border-amber-500/10 rounded-2xl p-4 space-y-3">
                                    <div className="flex items-center justify-between">
                                        <span className="text-[10px] font-black text-amber-400 uppercase tracking-widest">Requests ({pendingRequests.length})</span>
                                        <button onClick={handleGrantAll} className="px-3 py-1.5 text-[9px] font-black uppercase tracking-widest bg-amber-500/20 hover:bg-amber-500/30 text-amber-400 rounded-lg transition-all">Approve All</button>
                                    </div>
                                    {pendingRequests.map((req) => (
                                        <div key={req.participantId} className="flex items-center justify-between">
                                            <div className="flex items-center gap-2">
                                                <div className="w-6 h-6 rounded-full bg-amber-500/20 flex items-center justify-center text-amber-400 text-[9px] font-black uppercase">
                                                    {req.participantName?.[0] || '?'}
                                                </div>
                                                <span className="text-xs text-white font-bold">{req.participantName}</span>
                                                <span className="text-[9px] text-amber-400 font-bold uppercase">{req.requestType === 'microphone' ? 'Mic' : 'Camera'}</span>
                                            </div>
                                            <div className="flex gap-2">
                                                <button onClick={() => handleGrant(req.participantId, req.requestType)} className="px-3 py-1 text-[9px] font-black uppercase bg-emerald-500/20 text-emerald-400 rounded-lg hover:bg-emerald-500/30 transition-all">Approve</button>
                                                <button onClick={() => handleDeny(req.participantId)} className="px-3 py-1 text-[9px] font-black uppercase bg-red-500/20 text-red-400 rounded-lg hover:bg-red-500/30 transition-all">Deny</button>
                                            </div>
                                        </div>
                                    ))}
                                </div>
                            )}

                            <div className="space-y-1">
                                {participants.map((p) => {
                                    const perms = isModerator ? getParticipantPermissions(p.participantId) : null;
                                    const isCoHost = coHosts.some(ch => ch.userId === p.metadata?.userId);
                                    const isLecturer = p.role === 'lecturer' || p.identity === session.hostId || p.identity === session.lecturerId;
                                    return (
                                        <div key={p.participantId} className="flex items-center justify-between p-3 hover:bg-white/5 rounded-xl transition-all">
                                            <div className="flex items-center gap-3 min-w-0">
                                                <div className="relative shrink-0">
                                                    <div className={`w-9 h-9 rounded-xl flex items-center justify-center text-sm font-black uppercase ${isLecturer ? 'bg-amber-500/20 text-amber-400 border border-amber-500/30' : isCoHost ? 'bg-purple-500/20 text-purple-400 border border-purple-500/30' : 'bg-white/10 text-white'}`}>
                                                        {p.displayName?.[0] || p.identity?.[0] || '?'}
                                                    </div>
                                                    {isLecturer && <Crown className="w-3 h-3 text-amber-400 absolute -top-1 -right-1" />}
                                                    {isCoHost && <Shield className="w-3 h-3 text-purple-400 absolute -top-1 -right-1" />}
                                                </div>
                                                <div className="min-w-0">
                                                    <div className="flex items-center gap-1.5">
                                                        <span className="text-xs font-bold text-white truncate">{p.displayName || p.identity}</span>
                                                        {p.isLocal && <span className="text-[9px] text-blue-400 font-black">(You)</span>}
                                                        {isCoHost && <span className="text-[8px] bg-purple-500/20 text-purple-400 px-1.5 py-0.5 rounded font-black uppercase">Staff</span>}
                                                    </div>
                                                    <div className="flex items-center gap-2 mt-0.5">
                                                        {p.audioMuted ? <MicOff className="w-3 h-3 text-red-400" /> : <Mic className="w-3 h-3 text-emerald-400" />}
                                                        {p.videoMuted ? <VideoOff className="w-3 h-3 text-red-400" /> : <VideoIcon className="w-3 h-3 text-emerald-400" />}
                                                    </div>
                                                </div>
                                            </div>

                                            {isModerator && !p.isLocal && (
                                                <div className="flex items-center gap-1">
                                                    {perms?.micPermission !== false ? (
                                                        <button onClick={() => handleMuteStudent(p.participantId, p.identity)} className="p-1.5 text-red-400 hover:bg-red-500/10 rounded-lg transition-all" title="Mute">
                                                            <MicOff className="w-3.5 h-3.5" />
                                                        </button>
                                                    ) : (
                                                        <button onClick={() => handleGrant(p.identity, 'microphone')} className="p-1.5 text-emerald-400 hover:bg-emerald-500/10 rounded-lg transition-all" title="Allow Mic">
                                                            <Mic className="w-3.5 h-3.5" />
                                                        </button>
                                                    )}
                                                    {!isLecturer && !isCoHost && (
                                                        <button onClick={() => kickParticipant(p.participantId)} className="p-1.5 text-red-400 hover:bg-red-500/10 rounded-lg transition-all" title="Remove">
                                                            <UserX className="w-3.5 h-3.5" />
                                                        </button>
                                                    )}
                                                </div>
                                            )}
                                        </div>
                                    );
                                })}
                            </div>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
}
