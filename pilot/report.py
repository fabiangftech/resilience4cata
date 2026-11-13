#!/usr/bin/env python3
"""Turns a pilot run's results (machine.sh's OUT directory) into a Markdown report with pass/fail checks.

    pilot/report.py <results-dir> [phase-seconds]
"""
import json
import re
import sys
from pathlib import Path

PHASES = ["warmup", "steady", "down", "recovery", "slow", "recovery2", "after"]
SPAN = {"warmup": (0, 1), "steady": (1, 4), "down": (4, 5), "recovery": (5, 6), "slow": (6, 7), "recovery2": (7, 8),
        "after": (8, 10)}
OUTCOMES = ["ok", "fallback_open", "fallback_bulkhead", "fallback_timeout", "fallback_backend", "fallback_other",
            "limited", "failed", "error"]
BULKHEAD, TIMEOUT_MS, MAX_WAIT_MS, PERMITS = 40, 200, 20, 200


def heap_used_mb(path):
    text = path.read_text() if path.exists() else ""
    m = re.search(r"garbage-first heap\s+total (?:reserved )?\d+K, (?:committed \d+K, )?used (\d+)K", text)
    return int(m.group(1)) / 1024 if m else None


def count(metrics, name):
    return metrics.get(name, {}).get("values", {}).get("count", 0)


def app_report(results, app, minute):
    k6 = json.loads((results / f"{app}-k6.json").read_text())["metrics"]
    final = json.loads((results / f"{app}-final.json").read_text())
    startup = json.loads((results / f"{app}-startup.json").read_text())["startupMillis"]
    samples = [json.loads(line) for line in (results / f"{app}-samples.jsonl").read_text().splitlines() if line.strip()]
    lines = [f"## {app}", "", f"Startup to the first answer: {startup / 1000:.1f} s.", ""]
    checks = []

    lines += ["| Phase | Orders/s | OK | Fallback: open | bulkhead | timeout | backend | p50 | p99 | p99.9 | max | Quotes: OK/s | limited/s |",
              "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    by_phase = {}
    for phase in PHASES:
        seconds = (SPAN[phase][1] - SPAN[phase][0]) * minute
        o = {k: count(k6, f"orders_{phase}_{k}") for k in OUTCOMES}
        q = {k: count(k6, f"quotes_{phase}_{k}") for k in OUTCOMES}
        total = sum(o.values()) or 1
        lat = k6.get(f"latency_{phase}", {}).get("values", {})
        by_phase[phase] = (o, q, total, lat)
        pct = lambda k: f"{100 * o[k] / total:.1f} %" if o[k] else "–"
        ms = lambda k: f"{lat.get(k, 0):.1f} ms"
        lines.append(f"| {phase} | {total / seconds:.0f} | {pct('ok')} | {pct('fallback_open')} | {pct('fallback_bulkhead')} "
                     f"| {pct('fallback_timeout')} | {pct('fallback_backend')} | {ms('med')} | {ms('p(99)')} "
                     f"| {ms('p(99.9)')} | {ms('max')} | {q['ok'] / seconds:.0f} | {q['limited'] / seconds:.0f} |")
    dropped = count(k6, "dropped_iterations")
    requests = count(k6, "http_reqs")
    lines += ["", f"Requests: {requests:,}; iterations k6 could not start on time: {dropped:,}.", ""]

    def check(ok, text):
        checks.append((ok, text))

    errors = sum(by_phase[p][0]["failed"] + by_phase[p][0]["error"] + by_phase[p][0]["fallback_other"] for p in PHASES)
    check(errors == 0, f"every order got an answer from the service or its fallback ({errors} errors)")
    o, _, total, _ = by_phase["steady"]
    check(o["ok"] / total >= 0.999, f"steady: {100 * o['ok'] / total:.2f} % answered by the backend, with 1 % of backend answers failing (≥ 99.9 %: retries hide them)")
    o, _, total, _ = by_phase["down"]
    check(o["fallback_open"] / total >= 0.9, f"backend down: the breaker answered {100 * o['fallback_open'] / total:.1f} % of orders without calling it (≥ 90 %)")
    o, _, total, _ = by_phase["slow"]
    contained = (o["fallback_open"] + o["fallback_bulkhead"] + o["fallback_timeout"]) / total
    lat = by_phase["slow"][3]
    check(lat.get("p(99)", 1e9) < 1000, f"backend slow (1 s per answer): p99 {lat.get('p(99)', 0):.1f} ms, below the backend's 1 s; {100 * contained:.1f} % of orders answered by the breaker, bulkhead or timeout")
    o, _, total, _ = by_phase["after"]
    check(o["ok"] / total >= 0.999, f"after the outages (minutes 8 to 10): {100 * o['ok'] / total:.2f} % answered by the backend again (≥ 99.9 %)")
    steady99 = by_phase["steady"][3].get("p(99)", 0)
    after99 = by_phase["after"][3].get("p(99)", 0)
    check(after99 <= 2 * steady99 + 5, f"latency recovers: p99 {after99:.1f} ms after vs {steady99:.1f} ms steady")
    stock, quote = final["stock"], final["quote"]
    check(stock["maxInside"] <= BULKHEAD, f"bulkhead: at most {stock['maxInside']} calls inside at once (limit {BULKHEAD})")
    early = stock["minInterruptedMillis"]
    check(early is None or early >= TIMEOUT_MS - MAX_WAIT_MS,
          f"timeout: {stock['interrupted']:,} calls interrupted, the earliest after {early or 0:.1f} ms in the method "
          f"(≥ {TIMEOUT_MS - MAX_WAIT_MS} ms: {TIMEOUT_MS} ms timeout minus at most {MAX_WAIT_MS} ms waiting for the bulkhead)")
    allowed = PERMITS * quote["spanSeconds"] + PERMITS
    check(quote["calls"] <= allowed, f"rate limiter: {quote['calls']:,} quotes let through in {quote['spanSeconds']:.1f} s (at most {allowed:,.0f})")
    breaker = next(b for b in final["snapshot"]["circuitBreakers"] if b["id"] == "inventory")
    check(breaker["state"] == "CLOSED", f"breaker {breaker['state']} at the end")
    check(stock["inside"] == 0 and quote["inside"] == 0, "nothing left inside the protected methods at the end")
    warm, end = heap_used_mb(results / f"{app}-heap-warm.txt"), heap_used_mb(results / f"{app}-heap-end.txt")
    if warm is not None and end is not None:
        check(end <= warm + 16, f"heap after a full GC: {warm:.1f} MB after the warm-up, {end:.1f} MB at the end (≤ +16 MB)")
    first = next((s for s in samples if s["t"] - samples[0]["t"] >= minute and s.get("stats")), None)
    if first:
        check(final["libraryThreads"] <= first["stats"]["libraryThreads"],
              f"library threads: {first['stats']['libraryThreads']} after the warm-up, {final['libraryThreads']} at the end")
    if samples and samples[0].get("ticks") is not None:
        cpu = []
        for phase in PHASES:
            lo, hi = SPAN[phase]
            inside = [s for s in samples if lo * minute <= s["t"] - samples[0]["t"] <= hi * minute]
            if len(inside) >= 2:
                cpu.append(f"{phase} {(inside[-1]['ticks'] - inside[0]['ticks']) / 100 / (inside[-1]['t'] - inside[0]['t']):.2f}")
        rss = max(s["rssKb"] for s in samples) / 1024
        lines += [f"CPU (cores used, of 4): {', '.join(cpu)}. Peak RSS {rss:.0f} MB, platform threads at the end: {final['platformThreads']}.", ""]
    lines += ["Checks:", ""] + [f"- {'✅' if ok else '❌'} {text}" for ok, text in checks] + [""]
    return lines, all(ok for ok, _ in checks)


def main():
    results = Path(sys.argv[1])
    minute = float(sys.argv[2]) if len(sys.argv) > 2 else 60
    out, verdicts = [], {}
    for app in ["spring", "micronaut", "quarkus"]:
        if not (results / f"{app}-k6.json").exists():
            out += [f"## {app}", "", "No results (did not start or did not finish).", ""]
            verdicts[app] = False
            continue
        lines, ok = app_report(results, app, minute)
        out += lines
        verdicts[app] = ok
    head = ["# Pilot run", "", "| Service | Result |", "|---|---|"]
    head += [f"| {app} | {'✅ every check passed' if ok else '❌ see below'} |" for app, ok in verdicts.items()]
    print("\n".join(head + [""] + out))
    return 0 if all(verdicts.values()) else 1


if __name__ == "__main__":
    sys.exit(main())
