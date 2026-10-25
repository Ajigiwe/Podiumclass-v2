import {
    usePersistentUserChoices,
    useMediaDeviceSelect,
    useLocalParticipant,
    useRoomContext,
} from '@livekit/components-react';
import { Track, ConnectionState } from 'livekit-client';
import { Smile, Mic, VideoIcon, MicOff, VideoOff, MonitorUp, PhoneOff, MessageSquare, Hand, Lock, ChevronUp } from 'lucide-react';
import { useState, useRef, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { ReactionModal } from './ReactionModal';
import { usePermissions } from '@/hooks/usePermissions';

const DeviceMenu = ({
    kind,
    isOpen,
    onClose,
    triggerRef,
}: {
    kind: 'audioinput' | 'videoinput' | 'audiooutput';
    isOpen: boolean;
    onClose: () => void;
    triggerRef: React.RefObject<HTMLDivElement | null>;
}) => {
    const { devices, activeDeviceId, setActiveMediaDevice } = useMediaDeviceSelect({ kind });
    const menuRef = useRef<HTMLDivElement>(null);
    const [pos, setPos] = useState({ bottom: 0, left: 0 });

    useEffect(() => {
        if (isOpen && triggerRef.current) {
            const rect = triggerRef.current.getBoundingClientRect();
            const w = Math.min(window.innerWidth - 32, 260);
            let left = rect.left + rect.width / 2;
            if (left - w / 2 < 16) left = w / 2 + 16;
            if (left + w / 2 > window.innerWidth - 16) left = window.innerWidth - 16 - w / 2;
            setPos({ bottom: window.innerHeight - rect.top + 8, left });
        }
    }, [isOpen, triggerRef]);

    useEffect(() => {
        if (!isOpen) return;
        const handler = (e: MouseEvent) => {
            const t = e.target as HTMLElement;
            if (menuRef.current?.contains(t) || t.closest('.device-chevron')) return;
            onClose();
        };
        document.addEventListener('mousedown', handler, true);
        return () => document.removeEventListener('mousedown', handler, true);
    }, [isOpen, onClose]);

    if (!devices?.length || !isOpen) return null;

    const label = kind === 'audioinput' ? 'Microphone' : kind === 'videoinput' ? 'Camera' : 'Speaker';

    const menu = (
        <div ref={menuRef} style={{ position: 'fixed', bottom: pos.bottom, left: pos.left, transform: 'translateX(-50%)' }} className="z-[9999] animate-in fade-in zoom-in-95 duration-200">
            <div className="bg-slate-900 border border-white/10 rounded-2xl p-1.5 w-[260px] max-w-[calc(100vw-32px)] shadow-2xl">
                <div className="px-3 py-2 text-[10px] font-black text-slate-500 uppercase tracking-widest flex items-center justify-between">
                    {label}
                    <span className="bg-white/5 px-1.5 py-0.5 rounded text-[9px]">{devices.length}</span>
                </div>
                <div className="max-h-56 overflow-y-auto space-y-0.5">
                    {devices.map((device) => (
                        <button
                            key={device.deviceId}
                            onClick={(e) => { e.stopPropagation(); setActiveMediaDevice(device.deviceId); onClose(); }}
                            className={`w-full text-left px-3 py-2 text-xs rounded-lg transition-all truncate flex items-center gap-2.5 ${
                                activeDeviceId === device.deviceId
                                    ? 'bg-indigo-600 text-white font-semibold'
                                    : 'text-slate-400 hover:bg-white/5 hover:text-white'
                            }`}
                        >
                            <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${activeDeviceId === device.deviceId ? 'bg-white' : 'bg-slate-600'}`} />
                            {device.label || `Device ${device.deviceId.slice(0, 6)}`}
                        </button>
                    ))}
                </div>
            </div>
        </div>
    );

    return typeof window !== 'undefined' ? createPortal(menu, document.body) : null;
};

interface CustomControlBarProps {
    roomId: string;
    isLecturer: boolean;
    onReaction: (emoji: string) => void;
    onLeave: () => void;
    onToggleChat: () => void;
    isChatOpen: boolean;
    onToggleHand: () => void;
    isHandRaised: boolean;
    unreadChatCount: number;
    isActive: boolean;
    showAlert: (message: string, type: string) => void;
    customAlert: (options: any) => void;
}

export default function CustomControlBar({
    roomId,
    isLecturer,
    onReaction,
    onLeave,
    onToggleChat,
    isChatOpen,
    onToggleHand,
    isHandRaised,
    unreadChatCount,
    isActive,
    showAlert,
    customAlert,
}: CustomControlBarProps) {
    const { permissions, requestPermission } = usePermissions(roomId, isLecturer);
    const { saveAudioInputEnabled, saveVideoInputEnabled } = usePersistentUserChoices();
    const { localParticipant, isMicrophoneEnabled, isCameraEnabled, isScreenShareEnabled } = useLocalParticipant();
    const room = useRoomContext();
    const isConnected = room?.state === ConnectionState.Connected;

    const [toggling, setToggling] = useState({ mic: false, video: false, screen: false });
    const [activeMenu, setActiveMenu] = useState<'mic' | 'camera' | null>(null);
    const [showReactions, setShowReactions] = useState(false);
    const micRef = useRef<HTMLDivElement>(null);
    const camRef = useRef<HTMLDivElement>(null);
    const reactRef = useRef<HTMLButtonElement>(null);
    const emojis = ['👍', '👏', '❤️', '🔥', '🎉', '😂', '😮', '🤔'];

    useEffect(() => {
        if (!showReactions) return;
        const handler = (e: MouseEvent) => {
            const t = e.target as HTMLElement;
            if (reactRef.current?.contains(t) || document.getElementById('reaction-popover')?.contains(t)) return;
            setShowReactions(false);
        };
        document.addEventListener('mousedown', handler);
        return () => document.removeEventListener('mousedown', handler);
    }, [showReactions]);

    const toggleMic = async () => {
        if (!localParticipant || toggling.mic || !isConnected) return;
        if (!permissions.mic) { await requestPermission('microphone'); return; }
        setToggling(p => ({ ...p, mic: true }));
        try {
            const next = !localParticipant.isMicrophoneEnabled;
            await localParticipant.setMicrophoneEnabled(next);
            saveAudioInputEnabled?.(next);
        } catch {}
        setToggling(p => ({ ...p, mic: false }));
    };

    const toggleVideo = async () => {
        if (!localParticipant || toggling.video || !isConnected) return;
        if (!permissions.camera) { await requestPermission('camera'); return; }
        setToggling(p => ({ ...p, video: true }));
        try {
            const next = !localParticipant.isCameraEnabled;
            await localParticipant.setCameraEnabled(next);
            saveVideoInputEnabled?.(next);
        } catch {}
        setToggling(p => ({ ...p, video: false }));
    };

    const toggleScreen = async () => {
        if (!localParticipant || toggling.screen || !isConnected) return;
        setToggling(p => ({ ...p, screen: true }));
        try { await localParticipant.setScreenShareEnabled(!isScreenShareEnabled); } catch {}
        setToggling(p => ({ ...p, screen: false }));
    };

    const btn = (className: string) =>
        `h-10 w-10 sm:h-11 sm:w-11 flex items-center justify-center rounded-xl transition-all duration-200 disabled:opacity-40 ${className}`;

    return (
        <>
            <div className="fixed bottom-3 sm:bottom-4 left-1/2 -translate-x-1/2 z-[200] flex items-center gap-1.5 p-1.5 bg-neutral-900/90 backdrop-blur-sm border border-white/[0.06] rounded-2xl max-w-[98vw]">
                <div className="flex items-center" ref={micRef}>
                    <button onClick={toggleMic} disabled={toggling.mic || !isConnected} className={btn(isMicrophoneEnabled ? 'bg-indigo-600 text-white shadow-lg shadow-indigo-600/20' : 'text-slate-400 hover:bg-white/5')}>
                        {isMicrophoneEnabled ? <Mic className="w-5 h-5" /> : <MicOff className="w-5 h-5 text-red-400" />}
                        {!permissions.mic && <span className="absolute top-0.5 right-0.5 w-2 h-2 bg-red-500 rounded-full border border-slate-900"><Lock className="w-1.5 h-1.5 text-white" /></span>}
                    </button>
                    <button onClick={(e) => { e.stopPropagation(); setActiveMenu(p => p === 'mic' ? null : 'mic'); }} className={`device-chevron h-10 w-5 sm:h-11 sm:w-5 flex items-center justify-center rounded-lg text-slate-500 hover:text-white transition-colors`}>
                        <ChevronUp className={`w-3 h-3 transition-transform ${activeMenu === 'mic' ? 'rotate-180 text-indigo-400' : ''}`} />
                    </button>
                </div>

                <span className="w-px h-5 bg-white/10" />

                <div className="flex items-center" ref={camRef}>
                    <button onClick={toggleVideo} disabled={toggling.video || !isConnected} className={btn(isCameraEnabled ? 'bg-indigo-600 text-white shadow-lg shadow-indigo-600/20' : 'text-slate-400 hover:bg-white/5')}>
                        {isCameraEnabled ? <VideoIcon className="w-5 h-5" /> : <VideoOff className="w-5 h-5 text-red-400" />}
                        {!permissions.camera && <span className="absolute top-0.5 right-0.5 w-2 h-2 bg-red-500 rounded-full border border-slate-900"><Lock className="w-1.5 h-1.5 text-white" /></span>}
                    </button>
                    <button onClick={(e) => { e.stopPropagation(); setActiveMenu(p => p === 'camera' ? null : 'camera'); }} className={`device-chevron h-10 w-5 sm:h-11 sm:w-5 flex items-center justify-center rounded-lg text-slate-500 hover:text-white transition-colors`}>
                        <ChevronUp className={`w-3 h-3 transition-transform ${activeMenu === 'camera' ? 'rotate-180 text-indigo-400' : ''}`} />
                    </button>
                </div>

                <span className="w-px h-5 bg-white/10" />

                <button onClick={toggleScreen} disabled={toggling.screen || !isConnected} className={btn(isScreenShareEnabled ? 'bg-emerald-600/20 text-emerald-400 ring-1 ring-emerald-500/30' : 'text-slate-400 hover:bg-white/5')}>
                    <MonitorUp className="w-5 h-5" />
                </button>

                <button onClick={onToggleChat} className={`h-10 px-2 sm:h-11 sm:px-3 flex items-center gap-1.5 rounded-xl transition-all duration-200 relative ${isChatOpen ? 'bg-indigo-600 text-white shadow-lg shadow-indigo-600/20' : 'text-slate-400 hover:bg-white/5'}`}>
                    <MessageSquare className="w-5 h-5" />
                    {unreadChatCount > 0 && !isChatOpen && (
                        <span className="absolute -top-1.5 -right-1.5 bg-red-500 text-white text-[9px] font-black min-w-[18px] h-[18px] flex items-center justify-center rounded-full border-2 border-slate-900">{unreadChatCount > 9 ? '9+' : unreadChatCount}</span>
                    )}
                </button>

                <button onClick={onToggleHand} className={btn(isHandRaised ? 'bg-amber-500 text-slate-900 shadow-lg shadow-amber-500/20' : 'text-slate-400 hover:bg-white/5')}>
                    <Hand className={`w-5 h-5 ${isHandRaised ? 'animate-bounce' : ''}`} />
                </button>

                <button ref={reactRef} onClick={() => setShowReactions(!showReactions)} className={btn(showReactions ? 'bg-slate-700 text-white' : 'text-slate-400 hover:bg-white/5')}>
                    <Smile className="w-5 h-5" />
                </button>

                <span className="w-px h-5 bg-white/10" />

                <button onClick={onLeave} className="h-10 w-10 sm:h-11 sm:w-20 flex items-center justify-center sm:gap-1.5 bg-red-500/10 hover:bg-red-500 text-red-400 hover:text-white rounded-xl transition-all duration-200 border border-red-500/20 group">
                    <PhoneOff className="w-5 h-5 group-hover:-rotate-[135deg] transition-transform" />
                    <span className="hidden sm:inline text-[10px] font-black uppercase tracking-widest">Leave</span>
                </button>
            </div>

            <DeviceMenu kind="audioinput" isOpen={activeMenu === 'mic'} onClose={() => setActiveMenu(null)} triggerRef={micRef} />
            <DeviceMenu kind="videoinput" isOpen={activeMenu === 'camera'} onClose={() => setActiveMenu(null)} triggerRef={camRef} />

            {typeof window !== 'undefined' && createPortal(
                <ReactionModal isOpen={showReactions} onClose={() => setShowReactions(false)} onReaction={onReaction} emojis={emojis} />,
                document.body
            )}
        </>
    );
}
