const CHARS = 'abcdefghjkmnpqrstuvwxyz23456789';

export function generateMeetingCode(sessionId) {
    let hash = 0;
    for (let i = 0; i < sessionId.length; i++) {
        const char = sessionId.charCodeAt(i);
        hash = ((hash << 5) - hash) + char;
        hash = hash & hash;
    }
    hash = Math.abs(hash);
    let code = '';
    for (let i = 0; i < 8; i++) {
        const index = (hash + sessionId.charCodeAt(i % sessionId.length)) % CHARS.length;
        code += CHARS[index];
        hash = Math.floor(hash / CHARS.length) + sessionId.charCodeAt((i + 1) % sessionId.length);
    }
    return 'pod-' + code.slice(0, 4) + '-' + code.slice(4, 8);
}

export function normalizeCode(input) {
    return input.toLowerCase().replace(/[\s-]/g, '').replace(/^pod/, '');
}

export function isMeetingCode(input) {
    const normalized = input.toLowerCase().trim();
    return /^(pod-?)?[a-z0-9]{4}-?[a-z0-9]{4}$/i.test(normalized);
}

export function formatMeetingCode(code) {
    const normalized = normalizeCode(code);
    if (normalized.length === 8) {
        return 'pod-' + normalized.slice(0, 4) + '-' + normalized.slice(4, 8);
    }
    return code;
}
