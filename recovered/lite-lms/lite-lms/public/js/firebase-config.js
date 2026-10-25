// public/js/firebase-config.js
//
// Compatibility module for the static pages. Authentication is Postgres +
// NextAuth (`auth-client.js`); data access is the PostgreSQL data API through
// `pg-firestore.js`. No Firebase SDK is loaded.

import { auth } from "./auth-client.js?v=1";

const db = { __kind: 'db' };
const rtdb = null;
const storage = null;

function handleFirestoreError(error, operation, path) {
    console.error(`Data error during ${operation} at ${path}:`, error);
    throw error;
}

export { auth, db, rtdb, storage, handleFirestoreError };
