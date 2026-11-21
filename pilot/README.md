# Pilot

The closest thing to a pilot without a user: three small services, one per framework (Spring Boot 4.1, Micronaut 5.2,
Quarkus 3.40), each using resilience4cata **from Maven Central** as its guide says, call a simulated backend over HTTP
while k6 sends them 10 minutes of load with outages. Not part of the library's build: its own Gradle build, run by hand.

```bash
pilot/run.sh aws               # one c7g.4xlarge on AWS, about 35 minutes, AWS keys from .env (like infra/bench.sh)
CPU=1 pilot/run.sh aws         # the service limited to 1 core, as a Kubernetes CPU limit does (see below)
MINUTE=6 pilot/run.sh local    # on this machine, each "minute" of the load shortened to 6 s
```

Results and `report.md` land in `pilot/build/<aws|local>/<run>/`. On AWS the service, the backend and k6 get 4, 4 and 8
cores (`taskset`); every resource is destroyed at the end. `r4c` in `gradle.properties` picks the release;
`R4C=1.0.0-SNAPSHOT pilot/run.sh aws` runs this repository's code instead (published to `~/.m2` first).

## What runs

- `Inventory` (the same annotated class in the three services): `stock` with `@Fallback`, `@Retry` (3 attempts, 20 ms),
  `@CircuitBreaker` (100 calls, 50 %, 5 s open), `@Timeout("200ms")` and
  `@Bulkhead(40, maxWait 20 ms)`; `quote` with a fail-fast `@RateLimiter` (200 per second). The bodies run inside a
  `Probe` that records the most calls inside at once, the earliest interrupt and the rate-limited calls.
- `backend`: a JDK `HttpServer` on virtual threads; `normal` answers in 3 ms plus an exponential 5 ms with 1 % 503s,
  `down` answers 503 at once, `slow` takes 1 s.
- `load.js` (k6, open model): 1500 orders/s and 300 quotes/s. Minute 0–1 warm-up, 1–4 steady, 4–5 backend down,
  5–6 recovery, 6–7 backend slow, 7–8 recovery, 8–10 judged again.
- `machine.sh` runs the services one after another, samples them every 5 s, takes the heap after a full GC at the end of
  the warm-up and after the load, and keeps a JFR recording and a GC log; `report.py` writes the report and its checks.

## First run (2026-11-13, AWS Graviton3, JDK 25, 1.0.0-RC3)

Every check passed for the three services, with nearly identical numbers (about 1.04 million requests each):

| Phase | Orders answered by the backend | p50 | p99 | p99.9 |
|---|---|---|---|---|
| steady | 100 % (the backend's 1 % of 503s retried away) | 6.9 ms | 29 ms | 43 ms |
| backend down | 0 %: the breaker answered every order without calling it | 0.1 ms | 0.2 ms | 6–12 ms |
| backend slow (1 s) | 0 %: breaker 99.4 %, bulkhead 0.5 %, timeout; nobody waited the second | 0.1 ms | 0.5 ms | 236–242 ms |
| after (minutes 8–10) | 100 % | 6.9 ms | 29 ms | 43 ms |

At most 40 calls inside the bulkhead; no call interrupted before 188 ms in the method (200 ms minus at most 20 ms of
bulkhead wait); 120,195–120,197 quotes in 600 s for at most 120,197; breaker closed and nothing inside at the end; heap
after a full GC lower at the end than after the warm-up; one library thread throughout; 0.4–0.5 of the 4 cores used.

Found on the way: on 1.0.0-RC3 the Spring service opened its breaker during its cold start with no backend failure,
because the breaker counted its own inner bulkhead's rejections as failures (the run above used
`ignoreOn = BulkheadFullException.class` to avoid it). Since then the breaker never records a local limit's rejection
([circuit breaker](../docs/en/policies/circuit-breaker.md#with-other-policies)), and the services no longer need it.
A second run on that code (2026-11-13, same machine, no `ignoreOn`) passed every check for the three services with the
same numbers; its one order answered by an open breaker in the steady phase started just as the backend went down at
minute 4, and its retries met the breaker already open.

## Under a CPU limit (2026-11-15, AWS Graviton3, JDK 25, 1.0.0-RC4)

`CPU=<cores>` runs each service with `systemd-run --scope -p CPUQuota=<cores × 100>%` on its 4 cores: cgroup v2's
`cpu.max` with a 100 ms period, which is what a Kubernetes `limits.cpu` sets. With threads on 4 cores, a service at a
1-core limit can use its quota in 25 ms and is then frozen for the rest of the period. The JVM sees 1 processor (so it
picks Serial GC and one carrier thread for virtual threads). The report adds the share of periods in which the service
was throttled, how long its threads waited per second, and when interrupts arrived late.

Three runs on the same machine type, the same load:

| | No limit | 1 core | 0.5 core |
|---|---|---|---|
| Checks | all pass | all pass | Micronaut, Quarkus pass; Spring fails 2 (below) |
| Throttled periods, warm-up minute | – | 16–22 % | 67–99 % (threads waiting 1.6–2.2 s per second) |
| Throttled periods, steady | – | 0 % | Spring 8 %, Micronaut 0 %, Quarkus 2 % |
| Latest interrupt (200 ms timeout) | 221.6–225.1 ms | up to 1629 ms | up to 4802 ms, all in the warm-up minute |
| Earliest interrupt | 188 ms | 188 ms | 188 ms |
| Warm-up orders answered by an open breaker | 0 % | Spring 2.5 % | 2.9–35.9 % |

What it shows:

- **The policies hold their limits under throttling**: never an early timeout, at most 40 calls in the bulkhead, the
  rate limiter's permits, the breaker closed at the end, nothing left inside, one library thread, the heap flat.
- **A timeout is only as punctual as the process gets CPU.** Without a limit the latest interrupt came 21.6–25.1 ms
  after the 200 ms (the watchdog's two ticks are 25 ms). At 0.5 core, every interrupt later than 250 ms came in the
  warm-up minute, while the service was throttled most of the time (the JIT's warm-up): the watchdog and the
  interrupted thread were both frozen. None came later than 250 ms in the steady phase. (The 1-core run did not yet
  record when they came.)
- **A starved cold start opens the breaker.** In the warm-up minute calls really took longer than 200 ms because the
  service had no CPU, and the breaker counts a timeout as a failure of the dependency: it cannot tell a slow backend
  from a slow process. It closed again after `waitInOpen` (5 s), and no breaker opened outside the warm-up and the
  backend's outages.
- **Spring needs more than half a core at this load**: it uses 0.41 core on average against 0.34 for Micronaut and
  Quarkus, was throttled in 8 % of the steady periods, and its calls slowed enough to fill the bulkhead: 1.4 % of the
  steady orders were answered by the bulkhead's fallback (p99 318 ms), so the steady and after checks failed. The
  bulkhead did its job; the service needs more CPU.

Runs `20261115-114127` (no limit), `20261115-110010` (1 core; it did not record when late interrupts came yet) and
`20261115-121354` (0.5 core).


## Before 1.0.0 (2026-11-20, AWS Graviton3, JDK 25, 1.0.0-SNAPSHOT)

`R4C=1.0.0-SNAPSHOT pilot/run.sh aws` on `main` at `74ed4db`: the code that becomes 1.0.0, including the processor's
changes since 1.0.0-RC4 (Lombok rounds, member order, fallback matching, explicit type arguments), which generate the
three services' code. No CPU limit. Every check passed for the three services (42 of 42), about 1.04 million requests
each, with the numbers of the first run:

| Phase | Orders answered by the backend | p50 | p99 | p99.9 |
|---|---|---|---|---|
| steady | 100 % (the backend's 1 % of 503s retried away) | 6.8–6.9 ms | 28.7–29.4 ms | 42.5–42.7 ms |
| backend down | 0 %: the breaker answered every order without calling it | 0.1 ms | 0.2 ms | 3.9–9.4 ms |
| backend slow (1 s) | 0 %: breaker 99.4 %, bulkhead 0.5 %, timeout; nobody waited the second | 0.1 ms | 0.3 ms | 230–253 ms |
| after (minutes 8–10) | 100 % | 6.8–6.9 ms | 28.6–28.9 ms | 42.1–42.7 ms |

At most 40 calls inside the bulkhead; 157–172 calls interrupted by the 200 ms timeout, none before 185.5 ms in the
method, the latest after 221.1 ms (Micronaut), 223.6 ms (Quarkus) and 231.4 ms (Spring, whose 24 interrupts between
225 and 250 ms are past the watchdog's two ticks; not a check, and the no-limit run of 2026-11-15 saw up to 225.1 ms);
120,194–120,198 quotes in 600 s, each within its limit; breaker closed and nothing inside at the end; heap after a full
GC lower at the end than after the warm-up; one library thread throughout; 0.4–0.5 of the 4 cores used. Startup to the
first answer: Spring 2.4 s, Micronaut 1.6 s, Quarkus 1.3 s.

Run `20261120-204154`.
