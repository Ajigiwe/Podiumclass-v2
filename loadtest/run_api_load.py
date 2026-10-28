"""
Drive the real student join path with N concurrent students and report what the
API does.

Two phases, because they stress different things:

1. **Join burst** -- every student released from one barrier at the same moment,
   doing exactly what the classroom view does on entry: fetch the session, take
   a LiveKit token, write the attendance row. Polling does not start until the
   last join has landed, so the burst is measured on its own.
2. **Steady state** -- each client polls the verification state every 4s
   (CHECK_POLL_MS in ClassroomView.vue). At 200 students that is a constant
   ~50 req/s for the whole lesson, and it is the number that decides whether the
   server keeps up: a poll cycle that cannot finish inside its own interval
   means a queue that grows until the tab is closed.

Usage (backend must be running; see run_loadtest.md):

    python loadtest/run_api_load.py --students 200 --hold 30 --label threads16
"""

import argparse
import json
import statistics
import threading
import time
from collections import defaultdict
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parent


def percentile(values, pct):
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, int(round((pct / 100.0) * (len(ordered) - 1))))
    return ordered[index]


class Recorder:
    """Thread-safe sink of (timestamp, latency_ms, status) per endpoint."""

    def __init__(self):
        self._lock = threading.Lock()
        self.samples = defaultdict(list)

    def add(self, endpoint, started_at, elapsed_ms, status):
        with self._lock:
            self.samples[endpoint].append((started_at, elapsed_ms, status))

    def _window(self, endpoint, window):
        points = self.samples.get(endpoint, [])
        if window is not None:
            points = [p for p in points if window[0] <= p[0] <= window[1]]
        return points

    def summary(self, window=None, endpoints=None):
        rows = []
        for endpoint in sorted(endpoints or self.samples.keys()):
            points = self._window(endpoint, window)
            if not points:
                continue
            latencies = [ms for _, ms, _ in points]
            statuses = defaultdict(int)
            for _, _, status in points:
                statuses[status] += 1
            rows.append(
                {
                    "endpoint": endpoint,
                    "count": len(latencies),
                    "statuses": dict(statuses),
                    "p50": round(percentile(latencies, 50), 1),
                    "p95": round(percentile(latencies, 95), 1),
                    "p99": round(percentile(latencies, 99), 1),
                    "max": round(max(latencies), 1),
                    "mean": round(statistics.fmean(latencies), 1),
                }
            )
        return rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8001")
    parser.add_argument("--manifest", default=str(ROOT / "manifest.json"))
    parser.add_argument("--students", type=int, default=200)
    parser.add_argument("--hold", type=float, default=30.0, help="steady-state seconds")
    parser.add_argument("--poll-ms", type=int, default=4000)
    parser.add_argument("--timeout", type=float, default=90.0)
    parser.add_argument("--label", default="run")
    args = parser.parse_args()

    manifest = json.loads(Path(args.manifest).read_text(encoding="utf-8"))
    session_id = manifest["session_id"]
    students = manifest["students"][: args.students]
    base = args.base_url.rstrip("/")

    t0 = time.perf_counter()
    rec = Recorder()
    poll_gate = threading.Event()
    stop_polling = threading.Event()
    join_done = threading.Event()

    def elapsed():
        return time.perf_counter() - t0

    def timed(session, endpoint, method, url, **kwargs):
        started = elapsed()
        try:
            response = session.request(method, url, timeout=args.timeout, **kwargs)
            status, ok = response.status_code, response.ok
        except requests.RequestException as exc:
            status = "timeout" if isinstance(exc, requests.Timeout) else type(exc).__name__
            ok = False
        rec.add(endpoint, started, (elapsed() - started) * 1000, status)
        return ok

    barrier = threading.Barrier(len(students))

    def student_worker(entry):
        headers = {"Authorization": f"Bearer {entry['token']}", "Content-Type": "application/json"}
        # Keep-alive: production clients hold a connection open for the whole
        # lesson, and re-opening a socket per poll would measure the client's
        # TCP stack rather than the server.
        session = requests.Session()
        barrier.wait()

        timed(session, "GET /sessions/{id}", "GET",
              f"{base}/api/v1/classrooms/sessions/{session_id}/", headers=headers)
        joined = timed(session, "POST /sessions/{id}/token", "POST",
                       f"{base}/api/v1/classrooms/sessions/{session_id}/token/",
                       headers=headers, json={"roomName": session_id})
        if joined:
            timed(session, "POST /attendance/join", "POST",
                  f"{base}/api/v1/attendance/join/", headers=headers,
                  json={"sessionId": session_id})

        poll_gate.wait()
        # Small spread, so 200 clients are not phase-locked into one herd --
        # the app starts polling when each client finishes joining, not on a
        # shared tick.
        time.sleep((entry["index"] % 20) * (args.poll_ms / 20.0) / 1000.0)
        while not stop_polling.is_set():
            cycle = time.perf_counter()
            timed(session, "GET /verification/active", "GET",
                  f"{base}/api/v1/attendance/verification/active/?sessionId={session_id}&after=0",
                  headers=headers)
            remaining = args.poll_ms / 1000.0 - (time.perf_counter() - cycle)
            if remaining > 0:
                stop_polling.wait(remaining)
        session.close()

    threads = [threading.Thread(target=student_worker, args=(e,), daemon=True) for e in students]
    print(f"[{args.label}] releasing {len(students)} concurrent joins from one barrier...")
    join_started = elapsed()
    for thread in threads:
        thread.start()

    # The burst is over when every join has been attempted (2 requests each, or
    # 3 when the token call succeeded).
    expected = len(students) * 2
    while elapsed() - join_started < 180:
        if sum(len(rec.samples.get(k, [])) for k in (
            "GET /sessions/{id}", "POST /sessions/{id}/token", "POST /attendance/join"
        )) >= expected:
            break
        time.sleep(0.1)
    join_finished = elapsed()
    print(f"[{args.label}] join burst finished in {join_finished - join_started:.1f}s")
    poll_gate.set()

    steady_start = elapsed()
    print(f"[{args.label}] holding {args.hold:.0f}s of polling at {args.poll_ms}ms...")
    time.sleep(args.hold)
    steady_end = elapsed()
    stop_polling.set()
    for thread in threads:
        thread.join(timeout=15)

    poll_points = rec._window("GET /verification/active", (steady_start, steady_end))
    result = {
        "label": args.label,
        "students": len(students),
        "session_id": session_id,
        "join_burst_seconds": round(join_finished - join_started, 2),
        "hold_seconds": args.hold,
        "join_phase": rec.summary(
            window=(join_started, join_finished),
            endpoints=("GET /sessions/{id}", "POST /sessions/{id}/token", "POST /attendance/join"),
        ),
        "steady_phase": rec.summary(window=(steady_start, steady_end)),
        "steady_rps": round(len(poll_points) / max(args.hold, 0.001), 1),
        "steady_first_10s_p95": round(percentile(
            [ms for t, ms, _ in poll_points if t <= steady_start + 10], 95), 1),
        "steady_last_10s_p95": round(percentile(
            [ms for t, ms, _ in poll_points if t >= steady_end - 10], 95), 1),
    }
    out = ROOT / f"results-{args.label}.json"
    out.write_text(json.dumps(result, indent=2), encoding="utf-8")

    def table(rows):
        print(f"  {'endpoint':26s} {'n':>5s} {'p50':>9s} {'p95':>9s} {'p99':>9s} {'max':>9s}  statuses")
        for row in rows:
            print(f"  {row['endpoint']:26s} {row['count']:5d} {row['p50']:9.1f} {row['p95']:9.1f} "
                  f"{row['p99']:9.1f} {row['max']:9.1f}  {row['statuses']}")

    print(f"\n[{args.label}] JOIN BURST ({result['join_burst_seconds']}s wall clock)")
    table(result["join_phase"])
    print(f"\n[{args.label}] STEADY STATE {result['steady_rps']} rps sustained")
    table(result["steady_phase"])
    print(f"  poll p95 drift: first 10s {result['steady_first_10s_p95']}ms -> "
          f"last 10s {result['steady_last_10s_p95']}ms")
    print(f"[{args.label}] wrote {out}")


if __name__ == "__main__":
    main()
