import type { Router } from 'vue-router';

/**
 * Absolute invite URL for a classroom.
 *
 * Built through the router instead of string-concatenating a path, so it always
 * matches the history mode the router actually uses. Under hash history (see
 * router/index.ts) this yields `https://host/#/classroom/<id>`; under web
 * history it would yield `https://host/classroom/<id>`.
 *
 * That matters because invite links outlive the decision: a host pastes one
 * into a class chat, and students open it days later. If the format were
 * hard-coded here, switching history modes again would silently produce links
 * that 204 on the host while looking perfectly correct in the source.
 */
export function classroomInviteLink(router: Router, sessionId: string): string {
  const href = router.resolve({ name: 'Classroom', params: { id: sessionId } }).href;
  return new URL(href, window.location.origin).toString();
}
