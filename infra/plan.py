#!/usr/bin/env python3
"""Plans and summarises a parallel JMH run for infra/bench.sh.

  plan.py bundle <classpath.txt> <out.tar.gz>        package a runtime classpath
  plan.py plan <base-dir> <new-dir> <machines> <threads> [include-regex]  > plan.json
  plan.py summary <results-dir>                      print a markdown table of a finished run

A unit of work is one benchmark method at one thread count. When the method exists in the base
too, the unit runs the base and the new version back to back on the same machine (alternating
which goes first), so every base/new comparison is made on the same hardware. Units are spread
over the machines longest-first onto the least loaded machine. Every machine also runs the same
calibration benchmark, so the spread between machines can be reported.
"""
import json
import os
import re
import shutil
import sys
import tarfile

SECONDS_PER_RUN = 33  # 3 forks x (5 warmup + 5 measurement) x 1 s, plus fork start-up
CALIBRATION = ("BulkheadBenchmark", "resilience4cataFree", 1)


def benchmark_methods(source_dir):
    """{class: [methods]} from the @Benchmark methods in benchmark/src/main/java."""
    pkg = os.path.join(source_dir, "benchmark/src/main/java/dev/resilience4cata/benchmark")
    found = {}
    for name in sorted(os.listdir(pkg)):
        if name.endswith("Benchmark.java"):
            text = open(os.path.join(pkg, name)).read()
            methods = re.findall(r"@Benchmark\s+(?:@\w+(?:\([^)]*\))?\s+)*public\s+\S+\s+(\w+)\s*\(", text)
            found[name[:-5]] = methods
    return found


def regex(cls, method):
    # JMH matches includes with find(); [.] avoids backslashes and keeps MultiTypeRetryBenchmark apart.
    return f"[.]{cls}[.]{method}$"


def plan(base_dir, new_dir, machines, threads, include=None):
    base, new = benchmark_methods(base_dir), benchmark_methods(new_dir)
    units = []
    for cls, methods in new.items():
        for method in methods:
            if include and not re.search(include, f"{cls}.{method}"):
                continue
            for t in threads:
                versions = ["base", "new"] if method in base.get(cls, []) else ["new"]
                units.append((cls, method, t, versions))
    units.sort(key=lambda u: -len(u[3]))
    loads = [[] for _ in range(machines)]
    for unit in units:
        min(loads, key=lambda runs: len(runs)).append(unit)
    result = []
    for i, assigned in enumerate(loads):
        if not assigned:
            continue
        runs = [{"version": "new", "regex": regex(CALIBRATION[0], CALIBRATION[1]),
                 "threads": CALIBRATION[2], "id": "calibration"}]
        for j, (cls, method, t, versions) in enumerate(assigned):
            order = versions if j % 2 == 0 else list(reversed(versions))
            for v in order:
                runs.append({"version": v, "regex": regex(cls, method), "threads": t,
                             "id": f"{cls}.{method}-t{t}-{v}"})
        result.append({"name": f"m{i:02d}", "runs": runs})
    total = sum(len(m["runs"]) for m in result)
    longest = max(len(m["runs"]) for m in result)
    print(f"{len(units)} units, {total} runs on {len(result)} machines; longest machine "
          f"{longest} runs ~ {longest * SECONDS_PER_RUN // 60} min of measurement", file=sys.stderr)
    return result


def bundle(classpath_file, out):
    root = out + ".dir"
    shutil.rmtree(root, ignore_errors=True)
    os.makedirs(os.path.join(root, "cp"))
    entries = []
    for i, path in enumerate(p for p in open(classpath_file).read().split("\n") if p):
        if not os.path.exists(path):
            continue
        name = f"{i:02d}-{os.path.basename(path)}"
        (shutil.copytree if os.path.isdir(path) else shutil.copy)(path, os.path.join(root, "cp", name))
        entries.append("cp/" + name)
    open(os.path.join(root, "classpath.txt"), "w").write(":".join(entries))
    with tarfile.open(out, "w:gz") as tar:
        tar.add(root, arcname=".")
    shutil.rmtree(root)


def summary(results_dir):
    rows = {}
    calibration = []
    for machine in sorted(os.listdir(results_dir)):
        mdir = os.path.join(results_dir, machine)
        if not os.path.isdir(mdir):
            continue
        for f in sorted(os.listdir(mdir)):
            if not f.endswith(".json"):
                continue
            data = json.load(open(os.path.join(mdir, f)))
            if not data:
                continue
            r = data[0]
            score, error = r["primaryMetric"]["score"], r["primaryMetric"]["scoreError"]
            alloc = r.get("secondaryMetrics", {}).get("gc.alloc.rate.norm", {}).get("score")
            run_id = f[:-5]
            if run_id == "calibration":
                calibration.append((machine, score))
                continue
            key, version = run_id.rsplit("-", 1)
            rows.setdefault(key, {})[version] = (score, error, alloc)
    print("| Benchmark | Threads | Base (ns/op) | New (ns/op) | Change | New B/op |")
    print("|---|---:|---:|---:|---:|---:|")
    for key in sorted(rows):
        name, t = key.rsplit("-t", 1)
        cell = rows[key]
        fmt = lambda v: f"{v[0]:.1f} ± {v[1]:.1f}" if v else "—"
        change = ""
        if "base" in cell and "new" in cell and cell["base"][0] > 0:
            change = f"{(cell['new'][0] / cell['base'][0] - 1) * 100:+.0f}%"
        alloc = cell.get("new", (0, 0, None))[2]
        print(f"| {name} | {t} | {fmt(cell.get('base'))} | {fmt(cell.get('new'))} | {change} | "
              f"{'' if alloc is None else f'{alloc:.0f}'} |")
    if calibration:
        scores = [s for _, s in calibration]
        mean = sum(scores) / len(scores)
        print(f"\nCalibration ({CALIBRATION[0]}.{CALIBRATION[1]}, -t {CALIBRATION[2]}) on "
              f"{len(scores)} machines: min {min(scores):.2f}, max {max(scores):.2f}, "
              f"mean {mean:.2f} ns/op (spread {(max(scores) - min(scores)) / mean * 100:.1f}%)")


if __name__ == "__main__":
    command, args = sys.argv[1], sys.argv[2:]
    if command == "bundle":
        bundle(*args)
    elif command == "plan":
        include = args[4] if len(args) > 4 else None
        json.dump(plan(args[0], args[1], int(args[2]), [int(t) for t in args[3].split(",")], include),
                  sys.stdout)
    elif command == "summary":
        summary(args[0])
    else:
        sys.exit(__doc__)
