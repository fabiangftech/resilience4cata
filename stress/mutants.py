#!/usr/bin/env python3
"""Mutation check of the jcstress tests: weaken one guard in core, run the jcstress group that should
catch it, put the code back, and report whether the mutant was caught.

    stress/mutants.py list                              # the mutant ids, one per line
    stress/mutants.py json [id]                         # the same as a JSON array (for a CI matrix)
    stress/mutants.py run <id> [mode] [--clean] [args]  # apply, run, restore; mode defaults to quick

A mutant is caught when jcstress reports at least one FAILED test in its group (jcstress then exits
non-zero, which fails the Gradle task); the failed tests are read from its console output.

Most mutants weaken a memory ordering (an acquire or volatile read made opaque, a release write made
plain). x86 keeps loads and stores in order (TSO) and every atomic read-modify-write there is a full
fence, so those can only fail on a machine that reorders, such as ARM. The "store-buffering" ones can
fail on x86 too (TSO lets a later load pass an earlier store), and the "atomicity" one is a control
that every machine must catch. The "masked" ones weaken an ordering that an atomic read-modify-write
just before it already provides on x86 and on ARM (the AQS enqueue CAS before the semaphore's re-check,
exit()'s getAndAdd of the permit before its read of the waiter count): they are expected to survive, and they stay in the
list in case that neighbour changes. On aarch64 HotSpot compiles getAcquire and getVolatile to the same
instruction (ldar), so a mutant must go down to opaque or plain to change anything there.

--clean runs the mutant's jcstress group on the unchanged code: the baseline when a run is
inconclusive (a test hung, which jcstress reports as an error rather than a failure). Other arguments
go to Gradle. Run from the repository root. Writes one JSON line per run to
stress/build/mutants/<id>[-clean].json. Exit code: 0 caught, 2 survived, 3 inconclusive, 1 error.
"""

import json
import os
import platform
import re
import subprocess
import sys
from pathlib import Path

CORE = "core/src/main/java/dev/resilience4cata/generated/"
REACTOR = "reactor/src/main/java/dev/resilience4cata/reactor/generated/"

# id: (kind, file, exact text, replacement, jcstress test regex, what the mutant breaks)
MUTANTS = {
    "timeout-enter-plain": (
        "ordering", CORE + "TimeoutPolicy.java",
        "STATE.setRelease(scope, running); // publishes start and limit to the watchdog",
        "STATE.set(scope, running);",
        "TimeoutStress.TickSeesWholeCall",
        "enter() publishes RUNNING with a plain store: the watchdog may read RUNNING with an old start or limit.",
    ),
    "timeout-watchdog-opaque": (
        "ordering", CORE + "TimeoutPolicy.java",
        "expireIfOverdue(now, (long) STATE.getAcquire(this));",
        "expireIfOverdue(now, (long) STATE.getOpaque(this));",
        "TimeoutStress.TickSeesWholeCall",
        "The watchdog reads the stamp without acquire: start and limit may be read before it.",
    ),
    "timeout-end-release-cas": (
        "ordering", CORE + "TimeoutPolicy.java",
        "long witness = (long) STATE.compareAndExchangeAcquire(scope, running, call | IDLE);",
        "long witness = (long) STATE.compareAndExchangeRelease(scope, running, call | IDLE);",
        "TimeoutStress",
        "RUNNING -> IDLE without acquire: the next call's limit may be seen with this call's stamp.",
    ),
    "semaphore-recheck-opaque": (
        "masked", CORE + "BulkheadSemaphore.java",
        "        int get() {\n            return (int) VALUE.getVolatile(this);",
        "        int get() {\n            return (int) VALUE.getOpaque(this);",
        "BulkheadSemaphoreStress",
        "A queued thread re-checks the permits with an opaque read: the release's wake-up can be lost.",
    ),
    "waiters-count-opaque": (
        "masked", CORE + "BulkheadWaiters.java",
        "return (int) COUNT.getVolatile(this);",
        "return (int) COUNT.getOpaque(this);",
        "BulkheadWaitersStress",
        "exit() reads the waiter count with an opaque read: a slot freed while a waiter queues is not handed over.",
    ),
    "breaker-quiet-release": (
        "store-buffering", CORE + "CircuitBreakerPolicy.java",
        "QUIET.setVolatile(this, token);",
        "QUIET.setRelease(this, token);",
        "CircuitBreakerQuietStress",
        "publishQuiet sets quiet with release only: its re-check of the window can pass a failure's write.",
    ),
    "flux-emitter-release": (
        "store-buffering", REACTOR + "FluxCall.java",
        "EMITTER.setVolatile(this, me);",
        "EMITTER.setRelease(this, me);",
        "FluxCallStress.ElementVsEnd",
        "emit marks the emitter with release only: an end can be delivered while an element is in onNext.",
    ),
    "breaker-transition-not-atomic": (
        "atomicity", CORE + "CircuitBreakerPolicy.java",
        "if (PHASE.compareAndSet(this, from, to)) {",
        "if (PHASE.getVolatile(this) == from && PHASE.getAndSet(this, to) != null) {",
        "EventStress|CircuitBreakerStress.ConcurrentFailuresOpenOnce",
        "Control: a check-then-set transition; two threads can both make it. Every machine must catch it.",
    ),
}

FAILED = re.compile(r"\[FAILED\]\s+(\S+)")
HUNG = re.compile(r"^(\S+) \[[^\]]*\] had timed out", re.MULTILINE)


def apply(file, old, new):
    path = Path(file)
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"error: expected the mutated text exactly once in {file}, found {count}:\n{old}")
    path.write_text(text.replace(old, new))
    return text


def run(mutant_id, mode, clean, gradle_args):
    if mutant_id not in MUTANTS:
        raise SystemExit(f"error: unknown mutant {mutant_id}; known: {', '.join(MUTANTS)}")
    kind, file, old, new, tests, what = MUTANTS[mutant_id]
    original = None if clean else apply(file, old, new)
    try:
        command = ["./gradlew", ":stress:jcstress", f"-Pjcstress=-m {mode} -t {tests}", "--console=plain"]
        command += gradle_args  # for example the CI's JDK selection
        process = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        output = process.stdout
        sys.stdout.write(output)
    finally:
        if original is not None:
            Path(file).write_text(original)
    failed = sorted(set(FAILED.findall(output)))
    hung = sorted(set(HUNG.findall(output)))
    if process.returncode != 0 and not failed and not hung:
        print(f"error: the run failed without a jcstress result (exit {process.returncode})", file=sys.stderr)
        return 1
    # A test that hangs is a jcstress error, not a forbidden outcome: it may be the mutant (a lost
    # wake-up) or the machine (a loaded two-core runner). A clean run of the same group tells which.
    verdict = "caught" if failed else "inconclusive" if hung else "survived"
    record = {
        "mutant": mutant_id,
        "kind": kind,
        "clean": clean,
        "arch": platform.machine(),
        "mode": mode,
        "verdict": verdict,
        "caught": bool(failed),
        "failed": failed,
        "hung": hung,
        "what": what,
    }
    out = Path("stress/build/mutants")
    out.mkdir(parents=True, exist_ok=True)
    (out / f"{mutant_id}{'-clean' if clean else ''}.json").write_text(json.dumps(record) + "\n")
    label = f"{mutant_id} (clean code)" if clean else f"mutant {mutant_id} ({kind})"
    detail = f" by {', '.join(failed)}" if failed else f", hung: {', '.join(hung)}" if hung else ""
    print(f"\n{label} on {record['arch']}: {verdict}{detail}")
    return {"caught": 0, "survived": 2, "inconclusive": 3}[verdict]


def main():
    args = sys.argv[1:]
    if not args or args[0] not in ("list", "json", "run"):
        print(__doc__)
        return 1
    only = args[1] if args[0] == "json" and len(args) > 1 and args[1] else None
    ids = [only] if only else list(MUTANTS)
    if only and only not in MUTANTS:
        raise SystemExit(f"error: unknown mutant {only}; known: {', '.join(MUTANTS)}")
    if args[0] == "list":
        print("\n".join(ids))
        return 0
    if args[0] == "json":
        print(json.dumps(ids))
        return 0
    if len(args) < 2:
        print("usage: stress/mutants.py run <id> [mode] [--clean] [gradle args...]", file=sys.stderr)
        return 1
    rest = args[2:]
    mode = rest.pop(0) if rest and not rest[0].startswith("-") else "quick"
    clean = "--clean" in rest
    return run(args[1], mode, clean, [a for a in rest if a != "--clean"])


if __name__ == "__main__":
    sys.exit(main())
