'use client';

import React, { useState, useCallback } from 'react';
import Cropper from 'react-easy-crop';
import { X, ZoomIn, ZoomOut, Check, Crop as CropIcon } from 'lucide-react';

interface ImageCropperModalProps {
    image: string;
    onCropComplete: (croppedAreaPixels: any) => void;
    onClose: () => void;
    onConfirm: () => void;
}

export default function ImageCropperModal({ image, onCropComplete, onClose, onConfirm }: ImageCropperModalProps) {
    const [crop, setCrop] = useState({ x: 0, y: 0 });
    const [zoom, setZoom] = useState(1);

    const onCropChange = (crop: { x: number; y: number }) => {
        setCrop(crop);
    };

    const onZoomChange = (zoom: number) => {
        setZoom(zoom);
    };

    return (
        <div className="fixed inset-0 z-[100] flex items-center justify-center p-4">
            <div className="absolute inset-0 bg-black/80" onClick={onClose} />

            <div className="relative w-full max-w-xl bg-white rounded-lg overflow-hidden flex flex-col border border-gray-200">
                {/* Header */}
                <div className="px-6 py-4 border-b border-gray-200  flex items-center justify-between">
                    <div className="flex items-center gap-2">
                        <CropIcon className="w-5 h-5 text-blue-600" />
                        <h2 className="text-lg font-bold text-gray-900 ">Crop Profile Picture</h2>
                    </div>
                    <button onClick={onClose} className="p-2 hover:bg-gray-100  rounded-full transition-colors">
                        <X className="w-5 h-5 text-gray-500" />
                    </button>
                </div>

                {/* Cropper Area */}
                <div className="relative h-[400px] bg-gray-100 ">
                    <Cropper
                        image={image}
                        crop={crop}
                        zoom={zoom}
                        aspect={1}
                        onCropChange={onCropChange}
                        onCropComplete={(_, croppedAreaPixels) => onCropComplete(croppedAreaPixels)}
                        onZoomChange={onZoomChange}
                        cropShape="round"
                        showGrid={false}
                    />
                </div>

                {/* Controls */}
                <div className="p-6 bg-white  space-y-6">
                    <div className="flex items-center gap-4">
                        <ZoomOut className="w-4 h-4 text-gray-400" />
                        <input
                            type="range"
                            min={1}
                            max={3}
                            step={0.1}
                            value={zoom}
                            onChange={(e) => setZoom(Number(e.target.value))}
                            className="flex-1 h-2 bg-gray-200  rounded appearance-none cursor-pointer accent-blue-600"
                        />
                        <ZoomIn className="w-4 h-4 text-gray-400" />
                    </div>

                    <div className="flex items-center justify-end gap-3">
                        <button
                            onClick={onClose}
                            className="px-4 py-2 text-sm font-semibold text-gray-600  hover:text-gray-900  transition-colors"
                        >
                            Cancel
                        </button>
                        <button
                            onClick={onConfirm}
                            className="px-6 py-2.5 bg-blue-600 text-white rounded-md font-bold hover:bg-blue-700 transition-colors flex items-center gap-2 border border-blue-500"
                        >
                            <Check className="w-4 h-4" />
                            Apply Crop
                        </button>
                    </div>
                </div>
            </div>
        </div>
    );
}
