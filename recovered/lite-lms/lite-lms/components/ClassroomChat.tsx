'use client';

import { useState, useEffect, useRef } from 'react';
import { db } from '@/lib/firebase/config';
import { collection, query, orderBy, onSnapshot, addDoc, serverTimestamp, Timestamp } from '@/lib/firebase/firestore';
import { useClassroom } from '@/contexts/ClassroomContext';
import { Send, User, GraduationCap, Book, Laptop, Star, Heart, Smile, Shield, Zap, Music, Palette, MessageSquare } from 'lucide-react';

interface ChatMessage {
    id: string;
    text: string;
    content?: string; // Support both naming conventions
    senderId: string;
    senderName: string;
    senderPhotoURL?: string;
    senderDisplayIcon?: string;
    createdAt: Timestamp | number;
    role?: 'lecturer' | 'student';
    senderRole?: 'lecturer' | 'student';
}

interface ClassroomChatProps {
    sessionId: string;
    height?: string;
}

export default function ClassroomChat({ sessionId, height = '100%' }: ClassroomChatProps) {
    const { userName, userId, userRole, liveMessages, sendMessage, sessionData, coHosts } = useClassroom();
    const [newMessage, setNewMessage] = useState('');
    const messagesEndRef = useRef<HTMLDivElement>(null);

    const scrollToBottom = () => {
        messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
    };

    useEffect(() => {
        scrollToBottom();
    }, [liveMessages]);

    const handleSend = (e: React.FormEvent) => {
        e.preventDefault();
        if (!newMessage.trim()) return;
        sendMessage(newMessage.trim());
        setNewMessage('');
    };

    return (
        <div className="flex flex-col bg-gray-950/20 backdrop-blur-3xl h-full border-l border-white/5" style={{ height }}>
            {/* Messages Area */}
            <div className="flex-1 overflow-y-auto p-4 space-y-6 scrollbar-thin scrollbar-thumb-white/10 scrollbar-track-transparent">
                {liveMessages.length === 0 ? (
                    <div className="flex flex-col items-center justify-center h-full text-center space-y-3 opacity-40">
                        <MessageSquare className="w-10 h-10 text-gray-500" />
                        <p className="text-sm font-bold text-gray-400 tracking-tight uppercase">No messages yet</p>
                    </div>
                ) : (
                    liveMessages.map((msg, idx) => {
                        const isMe = msg.senderId === userId;
                        const isHost = msg.senderId === sessionData?.hostId || msg.senderId === sessionData?.lecturerId || msg.senderRole === 'lecturer';
                        const isCoHost = coHosts.some(ch => ch.userId === msg.senderId);
                        
                        return (
                            <div key={msg.id} className={`flex gap-3 animate-in fade-in slide-in-from-bottom-4 duration-500 ${isMe ? 'flex-row-reverse' : 'flex-row'}`}>
                                <div className="flex-shrink-0">
                                    <div className={`w-9 h-9 rounded-xl flex items-center justify-center overflow-hidden ring-1 ${isMe ? 'ring-blue-500/50' : 'ring-white/10'} bg-white/5 shadow-xl`}>
                                        {msg.senderPhotoURL ? (
                                            <img src={msg.senderPhotoURL} alt="" className="w-full h-full object-cover" />
                                        ) : (
                                            (() => {
                                                const iconMap: Record<string, any> = { User, GraduationCap, Book, Laptop, Star, Heart, Smile, Shield, Zap, Music, Palette };
                                                const IconComponent = iconMap[msg.senderDisplayIcon || 'User'] || User;
                                                return <IconComponent className={`w-5 h-5 ${isMe ? 'text-blue-400' : 'text-gray-400'}`} />;
                                            })()
                                        )}
                                    </div>
                                </div>

                                <div className={`flex flex-col ${isMe ? 'items-end' : 'items-start'} flex-1 min-w-0`}>
                                    <div className={`flex items-center gap-2 mb-1.5 ${isMe ? 'flex-row-reverse' : 'flex-row'}`}>
                                        <span className={`text-[11px] font-black uppercase tracking-wider ${isMe ? 'text-blue-400' : 'text-gray-400'}`}>{msg.senderName}</span>
                                        {isHost ? (
                                            <span className="text-[9px] bg-gradient-to-r from-yellow-600 to-amber-600 text-white px-2 py-0.5 rounded-full font-black uppercase tracking-[0.1em] shadow-lg shadow-yellow-600/20">Host</span>
                                        ) : isCoHost ? (
                                            <span className="text-[9px] bg-gradient-to-r from-purple-600 to-indigo-600 text-white px-2 py-0.5 rounded-full font-black uppercase tracking-[0.1em] shadow-lg shadow-purple-600/20">Staff</span>
                                        ) : null}
                                    </div>
                                    <div className={`px-4 py-2.5 rounded-2xl text-[13px] leading-relaxed max-w-[90%] shadow-2xl relative ${
                                        isMe 
                                        ? 'bg-gradient-to-br from-blue-600 to-indigo-600 text-white rounded-tr-none' 
                                        : 'bg-white/5 backdrop-blur-md text-gray-200 border border-white/10 rounded-tl-none'
                                    }`}>
                                        {msg.content || msg.text}
                                    </div>
                                </div>
                            </div>
                        );
                    })
                )}
                <div ref={messagesEndRef} />
            </div>

            {/* Input Area */}
            <form onSubmit={handleSend} className="p-4 bg-white/5 border-t border-white/5">
                <div className="relative group">
                    <input
                        type="text"
                        value={newMessage}
                        onChange={(e) => setNewMessage(e.target.value)}
                        placeholder="Say something to the class..."
                        className="w-full bg-white/5 text-white rounded-2xl py-3.5 pl-5 pr-14 focus:outline-none focus:ring-2 focus:ring-blue-500/50 placeholder-gray-500 text-sm border border-white/5 transition-all duration-300 focus:bg-white/10"
                    />
                    <button
                        type="submit"
                        disabled={!newMessage.trim()}
                        className="absolute right-2 top-2 h-10 w-10 flex items-center justify-center bg-gradient-to-br from-blue-600 to-indigo-600 text-white rounded-xl shadow-lg shadow-blue-600/20 hover:scale-105 active:scale-95 disabled:opacity-0 disabled:scale-90 transition-all duration-300"
                    >
                        <Send className="w-5 h-5" />
                    </button>
                </div>
            </form>
        </div>
    );
}
