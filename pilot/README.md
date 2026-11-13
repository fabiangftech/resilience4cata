# Pilot

The closest thing to a pilot without a user: three small services, one per framework (Spring Boot 4.1, Micronaut 5.2,
Quarkus 3.40), each using resilience4cata **from Maven Central** as its guide says, call a simulated backend over HTTP
while k6 sends them 10 minutes of load with outages. Not part of the library's build: its own Gradle build, run by hand.

```bash
pilot/run.sh aws               # one c7g.4xlarge on AWS, about 35 minutes, AWS keys from .env (like infra/bench.sh)
MINUTE=6 pilot/run.sh local    # on this machine, each "minute" of the load shortened to 6 s
```

Results and `report.md` land in `pilot/build/<aws|local>/<run>/`. On AWS the service, the backend and k6 get 4, 4 and 8
cores (`taskset`); every resource is destroyed at the end. `-Pr4c=<version>` in `gradle.properties` picks the release.

## What runs

- `Inventory` (the same annotated class in the three services): `stock` with `@Fallback`, `@Retry` (3 attempts, 20 ms),
  `@CircuitBreaker` (100 calls, 50 %, 5 s open, `ignoreOn = BulkheadFullException`), `@Timeout("200ms")` and
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

Found on the way: without `ignoreOn = BulkheadFullException.class` the Spring service opened its breaker during its cold
start with no backend failure, because the breaker counts its own inner bulkhead's rejections as failures. That is the
documented default ([circuit breaker](../docs/en/policies/circuit-breaker.md#with-other-policies)), and the page advises
`ignoreOn`; it is an easy trap.
