<template>
  <div class="absolute inset-0 z-30 flex flex-col bg-white animate-fade-in">
    <!-- Toolbar. Only the host gets tools; everyone else gets a label that
         explains whose board this is. -->
    <div
      class="h-11 shrink-0 flex items-center justify-between gap-2 px-3 border-b border-slate-200 bg-slate-50"
    >
      <div class="flex items-center gap-2 min-w-0">
        <span class="text-[10px] font-black text-slate-500 uppercase tracking-widest shrink-0">
          Whiteboard
        </span>
        <span
          v-if="!canDraw"
          class="text-[10px] font-bold text-slate-400 uppercase tracking-widest truncate"
        >
          &bull; the host is presenting
        </span>
      </div>

      <div v-if="canDraw" class="flex items-center gap-1.5 sm:gap-2">
        <button
          v-for="c in PALETTE"
          :key="c.hex"
          type="button"
          :title="c.name"
          @click="pick(c.hex)"
          class="w-6 h-6 rounded-full border-2 transition-transform hover:scale-110 cursor-pointer"
          :class="color === c.hex ? 'border-[#1845D4] scale-110' : 'border-slate-300'"
          :style="{ background: c.hex }"
        ></button>

        <span class="w-px h-5 bg-slate-200 mx-0.5"></span>

        <button
          type="button"
          title="Thin"
          @click="width = 0.002"
          class="w-7 h-7 rounded-lg border cursor-pointer flex items-center justify-center"
          :class="width === 0.002 ? 'border-[#1845D4] bg-white' : 'border-transparent hover:bg-white'"
        >
          <span class="block w-3 h-0.5 bg-slate-800 rounded-full"></span>
        </button>
        <button
          type="button"
          title="Thick"
          @click="width = 0.006"
          class="w-7 h-7 rounded-lg border cursor-pointer flex items-center justify-center"
          :class="width === 0.006 ? 'border-[#1845D4] bg-white' : 'border-transparent hover:bg-white'"
        >
          <span class="block w-3 h-1.5 bg-slate-800 rounded-full"></span>
        </button>

        <span class="w-px h-5 bg-slate-200 mx-0.5"></span>

        <button
          type="button"
          title="Clear the board"
          @click="store.clearWhiteboard()"
          class="px-2.5 h-7 rounded-lg text-[10px] font-bold uppercase tracking-widest text-slate-500 hover:bg-white border border-transparent hover:border-slate-200 cursor-pointer"
        >
          Clear
        </button>
        <button
          type="button"
          title="Close the whiteboard"
          @click="store.closeWhiteboard()"
          class="px-2.5 h-7 rounded-lg text-[10px] font-bold uppercase tracking-widest text-white bg-rose-500 hover:bg-rose-400 cursor-pointer"
        >
          Close
        </button>
      </div>
    </div>

    <!-- One canvas, drawn on incrementally: each finished segment is painted
         once, when it arrives, so nothing redraws the whole board per frame. -->
    <div ref="wrapEl" class="relative flex-1 min-h-0 bg-white">
      <canvas
        ref="canvasEl"
        class="block w-full h-full touch-none"
        :class="canDraw ? 'cursor-crosshair' : 'cursor-default'"
        @pointerdown="onPointerDown"
        @pointermove="onPointerMove"
        @pointerup="onPointerUp"
        @pointercancel="onPointerUp"
        @pointerleave="onPointerUp"
      ></canvas>

      <p
        v-if="canDraw && !store.whiteboardStrokes.length"
        class="absolute inset-0 flex items-center justify-center pointer-events-none text-[11px] font-bold text-slate-300 uppercase tracking-[0.3em]"
      >
        Draw here
      </p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue';
import { useClassroomStore, type WhiteboardStroke } from '../stores/classroom';

const store = useClassroomStore();

// Drawing is the host's job; a student's board is a view of it.
const canDraw = computed(() => store.isModerator);

const PALETTE = [
  { name: 'Ink', hex: '#0F172A' },
  { name: 'Blue', hex: '#1845D4' },
  { name: 'Red', hex: '#E11D48' },
  { name: 'Green', hex: '#059669' },
  { name: 'Eraser', hex: '#FFFFFF' },
];

const color = ref(PALETTE[0].hex);
// Stroke width as a fraction of the board's width: a line keeps its weight
// when the same board is viewed on a projector and on a laptop.
const width = ref(0.003);

const wrapEl = ref<HTMLDivElement | null>(null);
const canvasEl = ref<HTMLCanvasElement | null>(null);
let ctx: CanvasRenderingContext2D | null = null;
//: How many of the store's strokes are already on this bitmap.
let painted = 0;
let lastRevision = -1;
let drawing = false;
//: Points drawn since the last packet, flat x,y. May hold a single point.
let pending: number[] = [];
//: Where the previous packet of *this* stroke stopped. Each new packet starts
//: there, so consecutive packets join into one continuous line instead of a
//: row of dots with a gap the width of one frame's movement between them.
let lastSent: [number, number] | null = null;
let frame: number | null = null;
let resizeFrame: number | null = null;

function toNorm(event: PointerEvent): { x: number; y: number } {
  const el = canvasEl.value;
  if (!el) return { x: 0, y: 0 };
  const rect = el.getBoundingClientRect();
  return {
    x: Math.min(1, Math.max(0, (event.clientX - rect.left) / (rect.width || 1))),
    y: Math.min(1, Math.max(0, (event.clientY - rect.top) / (rect.height || 1))),
  };
}

function paintStroke(stroke: WhiteboardStroke) {
  const el = canvasEl.value;
  if (!ctx || !el || !stroke?.points?.length) return;
  const w = el.width;
  const h = el.height;
  const lineWidth = Math.max(1, (stroke.width || 0.003) * w);

  ctx.strokeStyle = stroke.color;
  ctx.fillStyle = stroke.color;
  ctx.lineWidth = lineWidth;
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';

  if (stroke.points.length === 2) {
    // A tap: a round dot, which a zero-length line would not draw.
    ctx.beginPath();
    ctx.arc(stroke.points[0] * w, stroke.points[1] * h, lineWidth / 2, 0, Math.PI * 2);
    ctx.fill();
    return;
  }

  ctx.beginPath();
  for (let i = 0; i + 3 < stroke.points.length; i += 2) {
    ctx.moveTo(stroke.points[i] * w, stroke.points[i + 1] * h);
    ctx.lineTo(stroke.points[i + 2] * w, stroke.points[i + 3] * h);
  }
  ctx.stroke();
}

/** Repaint everything from the store: after a resize, a clear, or a snapshot. */
function rebuild() {
  const el = canvasEl.value;
  if (!ctx || !el) return;
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.clearRect(0, 0, el.width, el.height);
  painted = 0;
  const strokes = store.whiteboardStrokes as WhiteboardStroke[];
  for (const s of strokes) paintStroke(s);
  painted = strokes.length;
  lastRevision = store.whiteboardRevision;
}

/**
 * Catch the bitmap up with the store.
 *
 * Strokes only ever arrive at the end of the list, so the common case is
 * drawing the couple that are new. A replaced board (clear, snapshot) bumps
 * the revision and takes the rebuild path.
 */
function sync() {
  if (store.whiteboardRevision !== lastRevision) {
    lastRevision = store.whiteboardRevision;
    rebuild();
    return;
  }
  const strokes = store.whiteboardStrokes as WhiteboardStroke[];
  if (strokes.length < painted) {
    rebuild();
    return;
  }
  for (let i = painted; i < strokes.length; i++) paintStroke(strokes[i]);
  painted = strokes.length;
}

function applySize() {
  resizeFrame = null;
  const el = canvasEl.value;
  const wrap = wrapEl.value;
  if (!el || !wrap) return;
  // A drag-resize fires this continuously and each call repaints the whole
  // board, so coalesce to one per frame.
  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  el.width = Math.max(1, Math.floor(wrap.clientWidth * dpr));
  el.height = Math.max(1, Math.floor(wrap.clientHeight * dpr));
  rebuild();
}

function resize() {
  if (resizeFrame !== null) return;
  resizeFrame = requestAnimationFrame(applySize);
}

/**
 * Publish what has been drawn since the last packet.
 *
 * One packet per frame keeps a fast flick to a handful of polylines rather
 * than hundreds of two-pixel segments. A frame that captured only one point is
 * held until the next one arrives (`force` sends it anyway at the end of a
 * stroke): sending it on its own is what used to put a dot on the board every
 * frame, because a one-point stroke is drawn as a tap.
 */
function publishPending(force: boolean) {
  if (!pending.length) return;
  const points: number[] = lastSent ? [lastSent[0], lastSent[1]] : [];
  points.push(...pending);
  if (!force && points.length < 4) return;

  lastSent = [pending[pending.length - 2], pending[pending.length - 1]];
  pending = [];
  void store.addWhiteboardStroke({
    color: color.value,
    width: width.value,
    points,
  });
}

function flushPending() {
  frame = null;
  publishPending(false);
}

function scheduleFlush() {
  if (frame !== null) return;
  frame = requestAnimationFrame(flushPending);
}

function onPointerDown(event: PointerEvent) {
  if (!canDraw.value) return;
  const el = canvasEl.value;
  el?.setPointerCapture?.(event.pointerId);
  drawing = true;
  lastSent = null;
  const p = toNorm(event);
  pending = [p.x, p.y];
  scheduleFlush();
}

function onPointerMove(event: PointerEvent) {
  if (!drawing || !canDraw.value) return;
  // Coalesced events are the samples the browser folded into this frame: taking
  // them all turns a fast flick into a curve instead of a handful of long
  // straight jumps.
  const samples = event.getCoalescedEvents?.() ?? [];
  const events = samples.length ? samples : [event];
  for (const sample of events) {
    const p = toNorm(sample as PointerEvent);
    pending.push(p.x, p.y);
  }
  scheduleFlush();
}

function onPointerUp() {
  if (!drawing) return;
  drawing = false;
  if (frame !== null) {
    cancelAnimationFrame(frame);
    frame = null;
  }
  // The last point of the stroke is always sent, even alone: that is a real tap.
  publishPending(true);
  lastSent = null;
}

function pick(hex: string) {
  color.value = hex;
}

watch(
  () => [store.whiteboardStrokes.length, store.whiteboardRevision] as const,
  () => sync()
);

onMounted(() => {
  ctx = canvasEl.value?.getContext('2d') ?? null;
  resize();
  window.addEventListener('resize', resize);
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize);
  if (frame !== null) cancelAnimationFrame(frame);
  if (resizeFrame !== null) cancelAnimationFrame(resizeFrame);
  frame = null;
  resizeFrame = null;
});
</script>
