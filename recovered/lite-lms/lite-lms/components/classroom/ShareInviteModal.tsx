'use client';

import { useState } from 'react';
import { X, Copy, Check, Link } from 'lucide-react';

interface ShareInviteModalProps {
    open: boolean;
    onClose: () => void;
    meetingCode: string;
    fullLink: string;
}

export function ShareInviteModal({ open, onClose, meetingCode, fullLink }: ShareInviteModalProps) {
    const [copiedCode, setCopiedCode] = useState(false);
    const [copiedLink, setCopiedLink] = useState(false);

    if (!open) return null;

    const copyCode = async () => {
        try { await navigator.clipboard.writeText(meetingCode); } catch {}
        setCopiedCode(true);
        setTimeout(() => setCopiedCode(false), 2000);
    };

    const copyLink = async () => {
        try { await navigator.clipboard.writeText(fullLink); } catch {}
        setCopiedLink(true);
        setTimeout(() => setCopiedLink(false), 2000);
    };

    return (
        <div className="fixed inset-0 z-[600] flex items-center justify-center p-4">
            <div className="absolute inset-0 bg-black/90 backdrop-blur-md" onClick={onClose} />
            <div className="relative w-full max-w-sm bg-slate-900 border border-white/10 rounded-[2rem] p-6 sm:p-8 animate-in zoom-in-95 fade-in duration-300 shadow-[0_30px_100px_rgba(0,0,0,0.8)]">
                <div className="flex items-center justify-between mb-6">
                    <div>
                        <h2 className="text-lg font-black text-white">Invite Students</h2>
                        <p className="text-[10px] text-slate-400 mt-1 font-bold uppercase tracking-widest">Share access code</p>
                    </div>
                    <button onClick={onClose} className="p-2 text-slate-400 hover:text-white bg-white/5 rounded-xl transition-all"><X className="w-5 h-5" /></button>
                </div>

                <div className="space-y-5">
                    <div className="p-5 bg-indigo-600/10 border border-indigo-500/20 rounded-2xl text-center">
                        <p className="text-[9px] font-black text-indigo-400 uppercase tracking-[0.3em] mb-3">Meeting Code</p>
                        <div className="flex items-center justify-center gap-3">
                            <span className="text-3xl font-black text-white tracking-[0.2em] font-mono">{meetingCode}</span>
                            <button onClick={copyCode} className="p-2 bg-indigo-600 hover:bg-indigo-500 rounded-xl transition-all">
                                {copiedCode ? <Check className="w-4 h-4 text-white" /> : <Copy className="w-4 h-4 text-white" />}
                            </button>
                        </div>
                    </div>

                    <div className="p-5 bg-white/5 border border-white/5 rounded-2xl">
                        <p className="text-[9px] font-black text-slate-400 uppercase tracking-[0.3em] mb-3">Full Link</p>
                        <div className="flex items-center gap-2">
                            <Link className="w-4 h-4 text-slate-500 shrink-0" />
                            <span className="text-xs text-slate-300 truncate font-mono flex-1">{fullLink}</span>
                            <button onClick={copyLink} className="p-2 bg-slate-700 hover:bg-slate-600 rounded-lg transition-all shrink-0">
                                {copiedLink ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5 text-slate-300" />}
                            </button>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
}
