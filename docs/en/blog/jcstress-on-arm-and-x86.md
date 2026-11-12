# Testing lock-free code on ARM as well as x86

resilience4cata's concurrency tests now run on ARM as well as x86 after every merge: all 65 pass on both, and the one race they keep finding turned out to be as common on x86 as on ARM.

*10 November 2026 · by [fabiangftech](https://github.com/fabiangftech), author of resilience4cata*

## Why x86 alone was not enough

The policies keep their state in lock-free structures: a circuit breaker swaps its phase with a compare-and-set, a bulkhead counts slots in an atomic counter, the retry budget counts calls in striped, deliberately lossy counters. Each of them uses the weakest memory ordering that is still correct, because on ARM a full fence costs two to three times more than an acquire or a plain access ([benchmarks](../benchmarks.md)).

[jcstress](https://github.com/openjdk/jcstress) is how we check that those orderings are correct: each test runs a few actions on several threads millions of times, in many thread placements, and fails on any outcome marked forbidden. The library has 65 of them.

Until now CI ran them only on x86. x86 is TSO (total store order): it keeps almost every pair of memory accesses in program order, and the only reordering it allows is a later read moving ahead of an earlier write. So if someone weakens a volatile read to a plain one by mistake, x86 still behaves as if it were ordered, and the test cannot fail. ARM reorders much more freely, and it is also the architecture the library was tuned on. The one ARM run so far was by hand, on Graviton3, for the release candidate.

## What CI runs now

| Workflow | When | Mode | Machines |
|---|---|---|---|
| `stress` | every push to `main`, and nightly | quick | x86 and ARM in parallel |
| `stress-arm` | weekly, and by hand | default (more forks, longer runs) | ARM |

Both run on GitHub's hosted runners, which are free for public repositories. The weekly run has its own workflow so that a push to `main`, which restarts `stress`, cannot cancel a run of several hours.

## The results

Three runs of `stress` since the change: the pull request, the merge, and the next night. Every run on both machines:

| | x86 (`ubuntu-24.04`) | ARM (`ubuntu-24.04-arm`) |
|---|---|---|
| CPU topology seen by jcstress | 2 cores × 2 hardware threads | 4 cores × 1 thread |
| Configurations per run | 2322 | 1050 |
| Failed, soft errors, hard errors | 0, 0, 0 | 0, 0, 0 |
| Time for the job | 59–66 min | 22–24 min |
| Executions of the "interesting" test | ~206 million per run | ~73 million per run |

The number of configurations differs because jcstress tries the actors in different placements on the CPU topology, and with two hardware threads per core there are more placements to try (inferred from the topology lines in the logs, not checked in jcstress's code). That also explains most of the time difference: x86 runs more than twice the configurations.

## The one interesting result

One test is marked "interesting" on every run, on both machines: `RetryBudgetStress.CallsAreNeverOvercounted`. It is meant to be. The [retry budget](../policies/retry.md#retry-budget) counts every call so that it can allow retries in proportion to traffic, and it counts with a plain read and write on a striped counter instead of an atomic add: 3.5 ns per call against 9.7 ns for a compare-and-set on Graviton3 ([Retry budget, 0.3.0](../benchmarks.md#retry-budget-030-2026-10-25-aws-graviton3)). The price is that two threads racing on one stripe can lose a call. A lost call makes the budget slightly stricter, never looser.

The test puts two threads on one stripe, each counting two calls. Four calls is the normal outcome; two or three is the lost call, allowed and reported as interesting; anything above four, or a retry counted, would be forbidden and fail the build. None was ever seen.

| Run | x86: executions that lost a call | ARM: executions that lost a call |
|---|---:|---:|
| Pull request | 6.7 % | 5.5 % |
| Merge | 6.3 % | 7.6 % |
| Next night | 18.6 % | 8.6 % |

This corrects something we wrote earlier. After the Graviton3 run (10.9 % of executions lost a call) the [plan to 1.0](../plan-1.0.md) said the loss was seen far more often on ARM than on x86. These runs do not show that: the rates overlap, and the highest one is on x86. That makes sense once you look at what the race needs. Losing a call takes no reordering at all, only two threads that both read the counter before either writes it back, and TSO does not prevent that. The rate depends on how often the two threads overlap, which changes with the machine, the placement and the noise of a shared runner.

## What this does not show yet

- **That the ARM job would catch a broken ordering.** A green run is not a proof. Each race test in the library was checked by mutation (weaken the guard, confirm the test fails), but those mutants ran on x86 and on Apple M1, not on these runners. Running them again on ARM is the next step, and it is still open in the [plan](../plan-1.0.md#after-10). *Update, 12 November 2026: done. ARM caught nothing that x86 missed: the mutants caught on one machine are caught on both, and those that survive survive on both (details in the [plan](../plan-1.0.md#after-10)).*
- **How long the weekly default mode takes.** From the quick runs, probably one to two hours on four ARM cores; the limit is just under six. The first scheduled run will tell.
- **Anything about speed.** These are correctness tests. The performance numbers are still from ARM only (Graviton3 and Apple M1); x86 [has not been measured yet](../performance.md).

## Run them yourself

```bash
./gradlew :stress:jcstress -Pjcstress="-m quick"                     # what CI runs on every push
./gradlew :stress:jcstress                                           # default mode, as the weekly ARM run
./gradlew :stress:jcstress -Pjcstress="-m quick -t RetryBudget"      # one group
```

The report lands in `stress/build/jcstress/results/index.html`. On an ARM machine (Apple Silicon, Graviton, Ampere) the same command tests the orderings that x86 hides.
