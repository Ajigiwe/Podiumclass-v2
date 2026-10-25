// public/js/pg-firestore.js
//
// A small Firestore-shaped client backed by the PostgreSQL data API. It keeps
// the query/write vocabulary used by the existing pages, but every document is
// loaded from /api/data and snapshots are short polling instead of Firestore
// realtime listeners.

const API_URL = '/api/data';
const POLL_MS = 3000;
const DATE_KEYS = new Set([
  'createdAt', 'updatedAt', 'joinedAt', 'paidAt', 'startedAt', 'endedAt',
  'scheduledStartTime', 'lastSeenAt', 'lastRespondedAt', 'assignedAt',
  'grantedAt', 'requestedAt', 'triggeredAt', 'expiresAt', 'respondedAt',
  'reviewedAt', 'walletUpdatedAt', 'lastHeartbeat', 'leftAt', 'updatedAt',
]);

export class Timestamp {
  constructor(seconds, nanoseconds = 0) {
    this.seconds = seconds;
    this.nanoseconds = nanoseconds;
  }
  toDate() {
    return new Date(this.seconds * 1000 + Math.floor(this.nanoseconds / 1e6));
  }
  toMillis() {
    return this.seconds * 1000 + Math.floor(this.nanoseconds / 1e6);
  }
  isEqual(other) {
    return !!other && this.seconds === other.seconds && this.nanoseconds === other.nanoseconds;
  }
  toJSON() {
    return { type: 'timestamp', seconds: this.seconds, nanoseconds: this.nanoseconds };
  }
  static now() {
    return Timestamp.fromMillis(Date.now());
  }
  static fromDate(date) {
    return Timestamp.fromMillis(date.getTime());
  }
  static fromMillis(ms) {
    const seconds = Math.floor(ms / 1000);
    return new Timestamp(seconds, (ms - seconds * 1000) * 1e6);
  }
}

function isTimestamp(value) {
  return value instanceof Timestamp
    || (value && typeof value === 'object' && typeof value.seconds === 'number' && typeof value.toDate === 'function');
}

function isDateKey(key) {
  return DATE_KEYS.has(key) || /At$/.test(key) || /Time$/.test(key);
}

function serializeValue(value) {
  if (isTimestamp(value)) return value.toDate().toISOString();
  if (value instanceof Date) return value.toISOString();
  if (Array.isArray(value)) return value.map(serializeValue);
  if (value && typeof value === 'object') {
    if (value.__op) return value;
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, serializeValue(item)]));
  }
  return value;
}

function deserializeValue(value, key) {
  if (Array.isArray(value)) return value.map((item) => deserializeValue(item, key));
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([childKey, item]) => [childKey, deserializeValue(item, childKey)]));
  }
  if (typeof value === 'string' && isDateKey(key)) {
    const ms = Date.parse(value);
    if (!Number.isNaN(ms)) return Timestamp.fromMillis(ms);
  }
  return value;
}

function rowData(row) {
  const out = {};
  for (const [key, value] of Object.entries(row || {})) {
    if (key === 'id') continue;
    out[key] = deserializeValue(value, key);
  }
  return out;
}

function randomId() {
  if (globalThis.crypto?.randomUUID) return `pg_${globalThis.crypto.randomUUID().replace(/-/g, '').slice(0, 20)}`;
  return `pg_${Math.random().toString(36).slice(2)}${Date.now().toString(36)}`;
}

function pathOf(value) {
  if (!value) return '';
  if (typeof value === 'string') return value;
  if (value.__kind === 'collection' || value.__kind === 'doc' || value.__kind === 'query') return value.path;
  return '';
}

export function collection(dbOrRef, ...parts) {
  const base = pathOf(dbOrRef);
  const path = [base, ...parts.map(String)].filter(Boolean).join('/');
  return { __kind: 'collection', path };
}

export function doc(dbOrRef, ...parts) {
  const base = pathOf(dbOrRef);
  let path = [base, ...parts.map(String)].filter(Boolean).join('/');
  const segments = path.split('/').filter(Boolean);
  if (segments.length % 2 !== 0) {
    const id = randomId();
    path = `${path}/${id}`;
    return { __kind: 'doc', path, id };
  }
  return { __kind: 'doc', path, id: segments[segments.length - 1] || randomId() };
}

export function query(ref, ...constraints) {
  return { __kind: 'query', path: pathOf(ref), constraints: constraints.filter(Boolean) };
}

export function where(field, op, value) {
  return { __kind: 'where', field, op, value };
}
export function orderBy(field, direction = 'asc') {
  return { __kind: 'orderBy', field, dir: direction };
}
export function limit(value) {
  return { __kind: 'limit', value };
}
export function limitToLast(value) {
  return { __kind: 'limitToLast', value };
}
export function startAt() { return { __kind: 'startAt' }; }
export function startAfter() { return { __kind: 'startAfter' }; }
export function endAt() { return { __kind: 'endAt' }; }
export function endBefore() { return { __kind: 'endBefore' }; }
export function documentId() { return '__name__'; }

export function serverTimestamp() { return { __op: 'serverTimestamp' }; }
export function increment(value) { return { __op: 'increment', value }; }
export function arrayUnion(...values) { return { __op: 'arrayUnion', values }; }
export function arrayRemove(...values) { return { __op: 'arrayRemove', values }; }
export function deleteField() { return { __op: 'deleteField' }; }

function queryBody(ref) {
  const constraints = ref.__kind === 'query' ? ref.constraints : [];
  const filters = [];
  const ordering = [];
  let max = undefined;
  let last = false;
  for (const item of constraints) {
    if (!item) continue;
    if (item.__kind === 'where') filters.push({ field: item.field, op: item.op, value: serializeValue(item.value) });
    else if (item.__kind === 'orderBy') ordering.push({ field: item.field, dir: item.dir });
    else if (item.__kind === 'limit') max = item.value;
    else if (item.__kind === 'limitToLast') { max = item.value; last = true; }
  }
  return { action: 'query', path: pathOf(ref), filters, orderBy: ordering, limit: max, limitToLast: last };
}

async function api(body) {
  const response = await fetch(API_URL, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  const json = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(json.error || `Data request failed (${response.status})`);
  return json;
}

function makeDocRef(path, id) {
  return { __kind: 'doc', path, id: id || path.split('/').pop() };
}

function docSnapshot(row, path, id) {
  const ref = makeDocRef(path, id || row?.id);
  if (!row) {
    return { id: ref.id, exists: () => false, ref, data: () => ({}), get: (field) => undefined };
  }
  const data = rowData(row);
  return {
    id: row.id || ref.id,
    exists: () => true,
    ref,
    data: () => data,
    get: (field) => data[field],
  };
}

function querySnapshot(rows, path) {
  const docs = (rows || []).map((row) => docSnapshot(row, `${path}/${row.id}`, row.id));
  return {
    docs,
    empty: docs.length === 0,
    size: docs.length,
    forEach: (fn) => docs.forEach(fn),
    docChanges: () => docs.map((doc, index) => ({ type: 'added', doc, oldIndex: -1, newIndex: index })),
  };
}

export async function getDocs(ref) {
  const json = await api(queryBody(ref));
  return querySnapshot(json.docs, pathOf(ref));
}

export async function getDoc(ref) {
  if (ref.__kind !== 'doc') throw new Error('getDoc expects a document reference');
  const json = await api({ action: 'get', path: ref.path, id: ref.id });
  return docSnapshot(json.doc, ref.path, ref.id);
}

export async function addDoc(ref, data) {
  if (ref.__kind !== 'collection') throw new Error('addDoc expects a collection reference');
  const id = randomId();
  await api({ action: 'write', ops: [{ type: 'add', path: ref.path, id, data: serializeValue(data || {}) }] });
  return makeDocRef(`${ref.path}/${id}`, id);
}

export async function setDoc(ref, data, options = {}) {
  if (ref.__kind !== 'doc') throw new Error('setDoc expects a document reference');
  await api({
    action: 'write',
    ops: [{ type: 'set', path: ref.path, id: ref.id, data: serializeValue(data || {}), merge: !!options.merge }],
  });
}

export async function updateDoc(ref, data) {
  if (ref.__kind !== 'doc') throw new Error('updateDoc expects a document reference');
  await api({ action: 'write', ops: [{ type: 'update', path: ref.path, id: ref.id, data: serializeValue(data || {}) }] });
}

export async function deleteDoc(ref) {
  if (ref.__kind !== 'doc') throw new Error('deleteDoc expects a document reference');
  await api({ action: 'write', ops: [{ type: 'delete', path: ref.path, id: ref.id }] });
}

export async function getCountFromServer(ref) {
  const body = queryBody(ref);
  const json = await api({ action: 'count', path: body.path, filters: body.filters });
  return { data: () => ({ count: json.count || 0 }) };
}

export function onSnapshot(ref, next, error) {
  let cancelled = false;
  let timer = null;
  let last = '';
  const isDoc = ref.__kind === 'doc';

  const tick = async () => {
    if (cancelled) return;
    try {
      const snapshot = isDoc ? await getDoc(ref) : await getDocs(ref);
      const fingerprint = JSON.stringify(isDoc ? snapshot.data() : snapshot.docs.map((item) => item.data()));
      if (fingerprint !== last) {
        last = fingerprint;
        next(snapshot);
      }
    } catch (err) {
      if (error) error(err);
    } finally {
      if (!cancelled) timer = setTimeout(tick, POLL_MS);
    }
  };

  tick();
  return () => {
    cancelled = true;
    if (timer) clearTimeout(timer);
  };
}

export function writeBatch() {
  const ops = [];
  return {
    set(ref, data, options = {}) {
      ops.push({ type: 'set', path: ref.path, id: ref.id, data: serializeValue(data || {}), merge: !!options.merge });
    },
    update(ref, data) {
      ops.push({ type: 'update', path: ref.path, id: ref.id, data: serializeValue(data || {}) });
    },
    delete(ref) {
      ops.push({ type: 'delete', path: ref.path, id: ref.id });
    },
    commit() {
      return api({ action: 'write', ops });
    },
  };
}

export async function runTransaction(_db, fn) {
  const tx = {
    get: (ref) => getDoc(ref),
    set: (ref, data, options) => setDoc(ref, data, options),
    update: (ref, data) => updateDoc(ref, data),
    delete: (ref) => deleteDoc(ref),
  };
  return fn(tx);
}

export function connectFirestoreEmulator() {}
export function enableNetwork() { return Promise.resolve(); }
export function disableNetwork() { return Promise.resolve(); }
export function terminate() { return Promise.resolve(); }
