'use client';

import { useState, useEffect, useRef } from 'react';
import { 
    X, 
    FileText, 
    Download, 
    Upload, 
    File as FileIcon, 
    Trash2, 
    Loader2, 
    FilePlus,
    FileImage,
    FileArchive,
    Search,
    Filter
} from 'lucide-react';
import { db } from '@/lib/firebase/config';
import { 
    collection, 
    query, 
    orderBy, 
    onSnapshot, 
    addDoc, 
    deleteDoc, 
    doc, 
    serverTimestamp 
} from '@/lib/firebase/firestore';
import { uploadLearningMaterial } from '@/lib/firebase/storage';
import { useAlert } from '@/contexts/AlertContext';

interface Material {
    id: string;
    name: string;
    url: string;
    type: string;
    size: number;
    uploadedAt: any;
    uploadedBy: string;
}

interface MaterialsModalProps {
    sessionId: string;
    userId: string;
    isModerator: boolean;
    onClose: () => void;
}

export const MaterialsModal = ({ sessionId, userId, isModerator, onClose }: MaterialsModalProps) => {
    const [materials, setMaterials] = useState<Material[]>([]);
    const [isUploading, setIsUploading] = useState(false);
    const [searchQuery, setSearchQuery] = useState('');
    const [uploadProgress, setUploadProgress] = useState(0);
    const fileInputRef = useRef<HTMLInputElement>(null);
    const { showAlert, showConfirm } = useAlert();

    useEffect(() => {
        if (!sessionId) return;

        const materialsRef = collection(db, 'sessions', sessionId, 'materials');
        const q = query(materialsRef, orderBy('uploadedAt', 'desc'));

        const unsubscribe = onSnapshot(q, (snapshot) => {
            const mats = snapshot.docs.map(doc => ({
                id: doc.id,
                ...doc.data()
            })) as Material[];
            setMaterials(mats);
        });

        return () => unsubscribe();
    }, [sessionId]);

    const handleFileUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
        const file = e.target.files?.[0];
        if (!file) return;

        setIsUploading(true);
        try {
            // 1. Upload to Storage
            const fileData = await uploadLearningMaterial(sessionId, file);

            // 2. Add metadata to Firestore
            await addDoc(collection(db, 'sessions', sessionId, 'materials'), {
                ...fileData,
                uploadedAt: serverTimestamp(),
                uploadedBy: userId,
            });

            showAlert('Material uploaded successfully!', 'success');
        } catch (error: any) {
            showAlert(error.message || 'Failed to upload material', 'error');
        } finally {
            setIsUploading(false);
            if (fileInputRef.current) fileInputRef.current.value = '';
        }
    };

    const handleDelete = async (materialId: string) => {
        showConfirm('Delete this material?', async () => {
            try {
                await deleteDoc(doc(db, 'sessions', sessionId, 'materials', materialId));
                showAlert('Material deleted', 'info');
            } catch (error: any) {
                showAlert(error.message || 'Failed to delete material', 'error');
            }
        });
    };

    const formatSize = (bytes: number) => {
        if (bytes === 0) return '0 B';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
    };

    const getFileIcon = (type: string) => {
        if (type.includes('image')) return <FileImage className="w-5 h-5 text-purple-400" />;
        if (type.includes('pdf')) return <FileText className="w-5 h-5 text-red-400" />;
        if (type.includes('zip') || type.includes('rar')) return <FileArchive className="w-5 h-5 text-amber-400" />;
        return <FileIcon className="w-5 h-5 text-blue-400" />;
    };

    const filteredMaterials = materials.filter(m => 
        m.name.toLowerCase().includes(searchQuery.toLowerCase())
    );

    return (
        <div className="fixed inset-0 z-[600] flex items-center justify-center p-4">
            <div className="absolute inset-0 bg-black/90 backdrop-blur-md" onClick={onClose} />
            
            <div className="relative w-full max-w-2xl bg-slate-900 border border-white/10 rounded-[2rem] flex flex-col max-h-[85vh] animate-in zoom-in-95 fade-in duration-300 shadow-[0_30px_100px_rgba(0,0,0,0.8)] overflow-hidden">
                
                {/* Header */}
                <div className="p-4 sm:p-8 border-b border-white/5 shrink-0">
                    <div className="flex justify-between items-center mb-4 sm:mb-6">
                        <div>
                            <h2 className="text-lg sm:text-xl font-black text-white uppercase tracking-tight">Learning Materials</h2>
                            <p className="text-[9px] sm:text-xs text-slate-400 mt-0.5 uppercase tracking-widest font-bold">
                                {materials.length} Shared Resources
                            </p>
                        </div>
                        <button onClick={onClose} className="p-2 text-slate-400 hover:text-white bg-white/5 rounded-xl transition-all">
                            <X className="w-5 h-5" />
                        </button>
                    </div>

                    <div className="flex gap-2 sm:gap-3">
                        <div className="flex-1 relative">
                            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-slate-500" />
                            <input 
                                type="text" 
                                placeholder="Search materials..."
                                value={searchQuery}
                                onChange={(e) => setSearchQuery(e.target.value)}
                                className="w-full bg-white/5 border border-white/5 rounded-xl py-2 sm:py-2.5 pl-9 pr-4 text-xs sm:text-sm text-white placeholder-slate-500 focus:outline-none focus:border-indigo-500/50 transition-all"
                            />
                        </div>
                        {isModerator && (
                            <button 
                                onClick={() => fileInputRef.current?.click()}
                                disabled={isUploading}
                                className="px-3 sm:px-4 bg-indigo-600 hover:bg-indigo-500 text-white rounded-xl text-[9px] sm:text-[10px] font-black uppercase tracking-widest transition-all flex items-center gap-2 shadow-lg shadow-indigo-600/20"
                            >
                                {isUploading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Upload className="w-3.5 h-3.5" />}
                                <span className="hidden sm:inline">Upload</span>
                            </button>
                        )}
                        <input 
                            type="file" 
                            ref={fileInputRef} 
                            onChange={handleFileUpload} 
                            className="hidden" 
                        />
                    </div>
                </div>

                {/* List */}
                <div className="flex-1 overflow-y-auto p-4 sm:p-8 custom-scrollbar">
                    {filteredMaterials.length === 0 ? (
                        <div className="flex flex-col items-center justify-center py-20 text-center">
                            <div className="w-16 h-16 bg-white/5 rounded-2xl flex items-center justify-center mb-4 border border-white/5">
                                <FilePlus className="w-8 h-8 text-slate-600" />
                            </div>
                            <h3 className="text-white font-bold text-sm mb-1">No materials shared yet</h3>
                            <p className="text-slate-500 text-[10px] uppercase font-black tracking-widest">
                                {isModerator ? 'Upload your first resource for students' : 'Wait for the lecturer to share resources'}
                            </p>
                        </div>
                    ) : (
                        <div className="grid gap-3">
                            {filteredMaterials.map((material) => (
                                <div 
                                    key={material.id}
                                    className="flex items-center justify-between p-3 sm:p-4 bg-white/5 hover:bg-white/[0.08] border border-white/5 rounded-2xl transition-all group"
                                >
                                    <div className="flex items-center gap-3 sm:gap-4 min-w-0 flex-1">
                                        <div className="w-10 h-10 sm:w-12 sm:h-12 bg-slate-800 rounded-xl flex items-center justify-center shrink-0 border border-white/5 group-hover:border-indigo-500/30 transition-colors">
                                            {getFileIcon(material.type)}
                                        </div>
                                        <div className="min-w-0 flex-1">
                                            <p className="font-bold text-[11px] sm:text-sm text-white line-clamp-1 group-hover:text-indigo-300 transition-colors" title={material.name}>
                                                {material.name}
                                            </p>
                                            <div className="flex items-center gap-2 sm:gap-3 mt-1">
                                                <span className="text-[8px] sm:text-[9px] font-black text-slate-500 uppercase tracking-widest whitespace-nowrap">
                                                    {formatSize(material.size)}
                                                </span>
                                                <span className="text-[8px] sm:text-[9px] font-black text-slate-700">•</span>
                                                <span className="text-[8px] sm:text-[9px] font-black text-slate-500 uppercase tracking-widest whitespace-nowrap">
                                                    {material.uploadedAt ? material.uploadedAt.toDate().toLocaleDateString() : 'Just now'}
                                                </span>
                                            </div>
                                        </div>
                                    </div>

                                    <div className="flex items-center gap-1.5 sm:gap-2 shrink-0 ml-4">
                                        <a 
                                            href={material.url} 
                                            target="_blank" 
                                            rel="noopener noreferrer"
                                            download={material.name}
                                            className="p-2 sm:p-2.5 bg-white/5 hover:bg-indigo-600 text-slate-400 hover:text-white rounded-xl transition-all border border-white/5 shrink-0"
                                        >
                                            <Download className="w-3.5 h-3.5 sm:w-4 sm:h-4" />
                                        </a>
                                        {isModerator && (
                                            <button 
                                                onClick={() => handleDelete(material.id)}
                                                className="p-2 sm:p-2.5 bg-white/5 hover:bg-red-500 text-slate-400 hover:text-white rounded-xl transition-all border border-white/5 shrink-0"
                                            >
                                                <Trash2 className="w-3.5 h-3.5 sm:w-4 sm:h-4" />
                                            </button>
                                        )}
                                    </div>
                                </div>
                            ))}
                        </div>
                    )}
                </div>

                {/* Footer Tip */}
                <div className="p-4 sm:p-6 border-t border-white/5 bg-black/20 text-center">
                    <p className="text-[8px] sm:text-[10px] text-slate-500 font-bold uppercase tracking-widest flex items-center justify-center gap-2">
                        <FileText className="w-3 h-3" />
                        PDF, Images, and Documents supported
                    </p>
                </div>
            </div>
        </div>
    );
};
