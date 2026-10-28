"""
Measure what the API actually serves, from several client processes.

Why processes: a single Python process driving 200 threads has 200 threads
competing for one GIL, and at that width the *client* caps out around 50 req/s --
latencies balloon even when the server is idle. Spreading the same clients over
K processes removes that artefact, so what is left is the server.

Two modes:

  --mode steady   keep hitting one endpoint for N seconds (throughput ceiling)
  --mode join     every client fires the real join sequence once -- fetch the
                  session, take a token, write the attendance row -- all released
                  together. This is the class-start spike: 600 requests in the
                  space of a few seconds.

    python loadtest/capacity_probe.py --mode steady --procs 8 --threads 25 --seconds 8
    python loadtest/capacity_probe.py --mode join --procs 8 --threads 25
"""

import argparse
import json
import multiprocessing as mp
import statistics
import tempfile
import threading
import time
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parent


def percentile(values, pct):
    if not values:
        return 0.0
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(round((pct / 100.0) * (len(ordered) - 1))))]


def worker(index, threads, seconds, base, session_id, tokens, start_epoch, out_path, mode):
    local = threading.local()
    samples = {}  # endpoint -> [latency_ms]
    lock = threading.Lock()

    def client():
        if not hasattr(local, "s"):
            local.s = requests.Session()
        return local.s

    def call(endpoint, method, url, token, body=None):
        headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
        started = time.perf_counter()
        status = None
        try:
            response = client().request(method, url, headers=headers, json=body, timeout=120)
            status = response.status_code
        except requests.RequestException as exc:
            status = type(exc).__name__
        with lock:
            samples.setdefault(endpoint, []).append(
                {"ms": (time.perf_counter() - started) * 1000, "status": status}
            )

    def run_steady(_):
        deadline = time.time() + seconds
        while time.time() < deadline:
            call("GET /verification/active", "GET",
                 f"{base}/api/v1/attendance/verification/active/?sessionId={session_id}&after=0",
                 tokens[_ % len(tokens)])

    def run_join(_):
        token = tokens[_ % len(tokens)]
        call("GET /sessions/{id}", "GET",
             f"{base}/api/v1/classrooms/sessions/{session_id}/", token)
        call("POST /sessions/{id}/token", "POST",
             f"{base}/api/v1/classrooms/sessions/{session_id}/token/", token,
             {"roomName": session_id})
        call("POST /attendance/join", "POST",
             f"{base}/api/v1/attendance/join/", token, {"sessionId": session_id})

    while time.time() < start_epoch:
        time.sleep(0.01)

    target = run_steady if mode == "steady" else run_join
    pool = [threading.Thread(target=target, args=(i,)) for i in range(threads)]
    for thread in pool:
        thread.start()
    for thread in pool:
        thread.join()
    Path(out_path).write_text(json.dumps(samples))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", default=str(ROOT / "manifest.json"))
    parser.add_argument("--base-url", default="http://127.0.0.1:8001")
    parser.add_argument("--mode", choices=("steady", "join"), default="steady")
    parser.add_argument("--procs", type=int, default=8)
    parser.add_argument("--threads", type=int, default=25)
    parser.add_argument("--seconds", type=float, default=8)
    args = parser.parse_args()

    manifest = json.loads(Path(args.manifest).read_text(encoding="utf-8"))
    session_id = manifest["session_id"]
    tokens = [s["token"] for s in manifest["students"]]
    base = args.base_url.rstrip("/")

    tmp = Path(tempfile.mkdtemp(prefix="podium-probe-"))
    start = time.time() + 2.0
    procs = [
        mp.Process(target=worker, args=(
            i, args.threads, args.seconds, base, session_id, tokens, start,
            str(tmp / f"{i}.json"), args.mode,
        ))
        for i in range(args.procs)
    ]
    for proc in procs:
        proc.start()
    for proc in procs:
        proc.join()

    merged = {}
    for i in range(args.procs):
        for endpoint, points in json.loads((tmp / f"{i}.json").read_text()).items():
            merged.setdefault(endpoint, []).extend(points)

    label = "JOIN BURST" if args.mode == "join" else f"STEADY {args.seconds:.0f}s"
    print(f"{label}: {args.procs} procs x {args.threads} threads = "
          f"{args.procs * args.threads} concurrent clients")
    for endpoint, points in sorted(merged.items()):
        latencies = [p["ms"] for p in points]
        failures = [p["status"] for p in points if not isinstance(p["status"], int) or p["status"] >= 400]
        print(f"  {endpoint:26s} n={len(latencies):4d} err={len(failures):3d} "
              f"p50={percentile(latencies, 50):7.0f}ms p95={percentile(latencies, 95):7.0f}ms "
              f"p99={percentile(latencies, 99):7.0f}ms max={max(latencies):7.0f}ms "
              f"mean={statistics.fmean(latencies):6.0f}ms")


if __name__ == "__main__":
    main()
