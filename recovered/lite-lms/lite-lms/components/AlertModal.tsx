'use client';

import { X, CheckCircle, AlertTriangle, Info, AlertOctagon } from 'lucide-react';
import { useEffect, useState } from 'react';

interface AlertModalProps {
    isOpen: boolean;
    onClose: () => void;
    title: string;
    message: string;
    type?: 'success' | 'error' | 'warning' | 'info';
    onConfirm?: () => void;
    onCancel?: () => void;
    showCancel?: boolean;
    confirmText?: string;
    cancelText?: string;
}

export default function AlertModal({
    isOpen,
    onClose,
    title,
    message,
    type = 'info',
    onConfirm,
    onCancel,
    showCancel = false,
    confirmText = 'OK',
    cancelText = 'Cancel'
}: AlertModalProps) {
    const [visible, setVisible] = useState(isOpen);

    // `visible` exists purely to keep the modal mounted for the 200ms fade-out after
    // `isOpen` flips to false. Mirroring a prop into state is the whole purpose of this
    // effect, so the set-state-in-effect heuristic is intentionally suppressed.
    useEffect(() => {
        if (isOpen) {
            // eslint-disable-next-line react-hooks/set-state-in-effect
            setVisible(true);
            return;
        }

        const timer = setTimeout(() => setVisible(false), 200); // Wait for animation
        return () => clearTimeout(timer);
    }, [isOpen]);

    if (!visible && !isOpen) return null;

    const getIcon = () => {
        switch (type) {
            case 'success':
                return <CheckCircle className="w-8 h-8 text-green-500" />;
            case 'error':
                return <AlertOctagon className="w-8 h-8 text-red-500" />;
            case 'warning':
                return <AlertTriangle className="w-8 h-8 text-amber-500" />;
            default:
                return <Info className="w-8 h-8 text-blue-500" />;
        }
    };

    const getColor = () => {
        switch (type) {
            case 'success': return 'bg-green-600 hover:bg-green-700';
            case 'error': return 'bg-red-600 hover:bg-red-700';
            case 'warning': return 'bg-amber-600 hover:bg-amber-700';
            default: return 'bg-blue-600 hover:bg-blue-700';
        }
    };

    return (
        <div
            className={`fixed inset-0 z-[100] flex items-center justify-center p-4 bg-black/60 transition-opacity duration-200 ${isOpen ? 'opacity-100' : 'opacity-0'
                }`}
            onClick={(e) => {
                if (e.target === e.currentTarget && !showCancel) onClose();
            }}
        >
            <div
                className={`bg-white rounded-lg border border-gray-200 w-full max-w-xs sm:max-w-sm transform transition-all duration-200 ${isOpen ? 'scale-100 translate-y-0' : 'scale-95 translate-y-4'
                    }`}
            >
                <div className="p-5">
                    <div className="flex flex-col items-center text-center">
                        <div className="mb-3 p-2.5 rounded-full bg-gray-50">
                            {getIcon()}
                        </div>

                        <h3 className="text-lg font-bold text-gray-900 mb-1.5">
                            {title}
                        </h3>

                        <p className="text-sm text-gray-600 mb-5">
                            {message}
                        </p>

                        <div className="flex gap-2.5 w-full">
                            {showCancel && (
                                <button
                                    onClick={() => {
                                        if (onCancel) onCancel();
                                        onClose();
                                    }}
                                    className="flex-1 px-3 py-2 bg-gray-100 text-gray-700 rounded-sm text-sm font-semibold hover:bg-gray-200 transition-colors"
                                >
                                    {cancelText}
                                </button>
                            )}
                            <button
                                onClick={() => {
                                    if (onConfirm) onConfirm();
                                    onClose();
                                }}
                                className={`flex-1 px-3 py-2 text-white rounded-sm text-sm font-semibold transition-all active:scale-95 ${getColor()}`}
                            >
                                {confirmText}
                            </button>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
}
