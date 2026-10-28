"""
Send requests at a fixed rate and measure response time.

A concurrency test with 200 threads answers "what happens if everyone hammers at
once", but a classroom does not do that: each student polls every 4 seconds.
200 students therefore demand 50 req/s steadily, and the question that decides
whether a lesson survives is whether a request sent at a fixed rate comes back
inside its own interval.

Latency here is measured from the moment a request was *due*, so it includes
queueing at the client as well as the server's work -- which is what the student
experiences.

    python loadtest/rate_probe.py --rps 50 --seconds 20
    python loadtest/rate_probe.py --rps 50 --seconds 20 --base-url http://127.0.0.1:8002
"""

import argparse
import json
import statistics
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parent


def percentile(values, pct):
    if not values:
        return 0.0
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(round((pct / 100.0) * (len(ordered) - 1))))]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", default=str(ROOT / "manifest.json"))
    parser.add_argument("--base-url", default="http://127.0.0.1:8001")
    parser.add_argument("--path", default="/api/v1/attendance/verification/active/?sessionId={sid}&after=0")
    parser.add_argument("--rps", type=float, default=50.0)
    parser.add_argument("--seconds", type=float, default=20.0)
    parser.add_argument("--workers", type=int, default=16)
    args = parser.parse_args()

    manifest = json.loads(Path(args.manifest).read_text(encoding="utf-8"))
    session_id = manifest["session_id"]
    # Spread students across tokens the way 200 distinct sessions would.
    tokens = [s["token"] for s in manifest["students"]]
    url = args.base_url.rstrip("/") + args.path.format(sid=session_id)

    lock = threading.Lock()
    latencies = []
    errors = []
    local = threading.local()

    def session():
        if not hasattr(local, "s"):
            local.s = requests.Session()
        return local.s

    def fire(due_at, index):
        payload = t0 + due_at
        wait = payload - time.perf_counter()
        if wait > 0:
            time.sleep(wait)
        headers = {"Authorization": f"Bearer {tokens[index % len(tokens)]}"}
        try:
            response = session().get(url, headers=headers, timeout=60)
            ok = response.status_code < 400
            status = response.status_code
        except requests.RequestException as exc:
            ok = False
            status = type(exc).__name__
        elapsed = (time.perf_counter() - payload) * 1000
        with lock:
            latencies.append(elapsed)
            if not ok:
                errors.append(status)

    total = int(args.rps * args.seconds)
    print(f"open loop: {args.rps:.0f} req/s for {args.seconds:.0f}s ({total} requests, "
          f"{args.workers} client threads) -> {url.split('?')[0]}")
    t0 = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = [
            pool.submit(fire, i / args.rps, i)
            for i in range(total)
        ]
        for future in futures:
            future.result()
    wall = time.perf_counter() - t0

    achieved = len(latencies) / wall
    print(f"  achieved {achieved:.1f} rps, {len(errors)} errors "
          f"{sorted(set(map(str, errors)))[:5] if errors else ''}")
    print(f"  p50 {percentile(latencies, 50):.0f}ms  p95 {percentile(latencies, 95):.0f}ms  "
          f"p99 {percentile(latencies, 99):.0f}ms  max {max(latencies):.0f}ms  "
          f"mean {statistics.fmean(latencies):.0f}ms")


if __name__ == "__main__":
    main()
