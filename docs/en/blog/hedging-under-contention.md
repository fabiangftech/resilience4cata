# Hedging when the hedges compete for the same service

A hedge cuts the tail when the slow call was bad luck, but when every attempt waits in the same queue it can make the tail far worse: a simulation of `@Hedge` against one shared service, at loads from 30 % to 95 %.

*5 November 2026 · by [fabiangftech](https://github.com/fabiangftech), author of resilience4cata*

*Updated the same day: the tables now give the median of ten seeds instead of seed 1 alone, and the default budget at 95 % load turned out to be a slow collapse.*

## The question

After the release candidate was announced, a reader asked how hedging behaves under tail latency when the second call contends for the same downstream. It is the right question. A hedge sends a second request when the first one is slow. If the first one is slow because of bad luck (a GC pause, a cold cache, one slow disk), the second one usually wins and the tail shrinks. If the first one is slow because the service is busy, the second request makes it busier, for every caller.

The [hedge page](../policies/hedge.md) already says not to hedge a saturated dependency, and that the hedge budget "limits the damage, it does not undo it". This article puts numbers on that sentence.

## How it was simulated

The question is about queues, not about the cost of the policy, so this is not a JMH benchmark. JMH measures what code costs in a closed loop, where a slow service slows the callers down too and hides the collapse this article is about. Instead, a simulation runs the library's own `HedgePolicy` and `AdaptiveLimitPolicy`, through `Pipeline`, on the testkit's `ManualClock`, against a simulated service:

- **The service:** 16 workers, and one FIFO queue shared by every attempt of every call, so a hedge waits behind other callers' first attempts.
- **Service time:** log-normal with a 10 ms median; 2 % of requests take 100 to 300 ms instead. Each request draws on its own, so a hedge usually does not hit the same hiccup.
- **Arrivals:** open loop (Poisson), at a rate that would keep the workers busy 30 % to 95 % of the time without hedges. A slow service does not slow the arrivals down, as with real users.
- **The hedge:** a second attempt after 20 ms, a little above the service's p95.
- **Each point:** 200,000 calls, the first 10 % not measured, with ten seeds (1 to 10). The tables give the median over the seeds and, in brackets, the lowest and highest p99 when they differ.

These are the setups compared:

<!-- snippet: hedge-setups -->
```java
NO_HEDGE("no hedge", null),
HEDGE_NO_BUDGET("hedge, no budget", b -> b.hedge(h -> h.delay(DELAY).budgetPercent(0))),
HEDGE_DEFAULT_BUDGET("hedge, default budget", b -> b.hedge(h -> h.delay(DELAY))),
HEDGE_BUDGET_5("hedge, budget 5 %", b -> b.hedge(h -> h.delay(DELAY).budgetPercent(5))),
HEDGE_BUDGET_ADAPTIVE("hedge, default budget, adaptive limit", b -> b.hedge(h -> h.delay(DELAY)).adaptiveLimit()),
THREE_ATTEMPTS_NO_BUDGET("3 attempts, no budget", b -> b.hedge(h -> h.delay(DELAY).maxAttempts(3).budgetPercent(0)));
```

The default budget allows hedges for 10 % of the calls plus 1 per second over 10 seconds. The adaptive limit has its defaults (Gradient2). The one thing the simulation changes is whether a cancelled attempt stops its work on the server.

> [!NOTE]
> These are the results of a model, deterministic for a seed, not wall-clock measurements. They show how the policies react to load; a real network, real timers and a real server add noise and costs the model leaves out. The code is in `examples/src/test/java/dev/resilience4cata/examples/hedgesim`, and `./gradlew :examples:hedgeSimulation` writes the full report.

## When the server ignores the cancellation

This is the usual case for an HTTP call: when one attempt wins, the hedge cancels the other one, but cancelling only stops the client from waiting. The server finishes the work anyway.

p99 / p99.9 latency in ms, by load without hedges (median of ten seeds; lowest and highest p99 in brackets):

| Setup | 50 % | 70 % | 85 % | 95 % |
|---|---:|---:|---:|---:|
| No hedge | 200 / 290 | 201 / 290 | 205 / 294 | 237 / 349 |
| Hedge, no budget | **30 / 36** (30–76) | collapse | collapse | collapse |
| Hedge, default budget (10 %) | **30 / 36** (30–33) | 373 / 460 (322–407) | 530 / 616 (421–610) | slow collapse |
| Hedge, budget 5 % | **30 / 36** (30–31) | 154 / 275 (130–168) | 216 / 336 (191–262) | 962 / 1,049 (423–1,652) |
| Hedge, default budget, adaptive limit | **30 / 36** (30–31) | **58 / 268** (56–63), 1.5 % rejected | **122 / 293** (102–157), 3.8 % rejected | **167 / 305** (149–182), 7.4 % rejected |
| 3 attempts, no budget | collapse in 4 seeds of 10 | collapse | collapse | collapse |

"Collapse" means the queue never drains: every call waits long enough to send its hedges, so the load doubles (triples with three attempts) and stays there. Latency then grows for as long as the run lasts, here to minutes. "Slow collapse" is the same with less extra load: the workers are busy 99.9 % of the time and the queue grows slowly. With seed 1, the p99 is 2.6 s after 100,000 calls, 6.7 s after 200,000 and 14.2 s after 400,000, so no single number describes it.

What the numbers say:

- **With room to spare, hedging works as promised.** Up to 50 % load, the p99.9 falls from 290 ms to 36 ms for 2.3 % more requests, with or without a budget. Without one, a burst of arrivals in one seed of ten pushed the p99.9 to 199 ms.
- **Without a budget, it collapses.** Once queueing pushes ordinary calls past the 20 ms delay, they hedge too, and the extra load keeps the queue long. It does not recover by itself: it is a stable state. Three attempts collapsed already at 50 % in four seeds of ten, each time after a burst of arrivals.
- **The budget prevents the collapse, but not the damage.** The requests never exceed 1.10 per call. But hedges that fire because of the queue, not because of a hiccup, spend the budget and add load where there is little room. From 70 % load, the tail with the default budget is worse than with no hedge at all. At 95 %, 10 % more load does not fit in 5 % of headroom, and the service collapses slowly.
- **A smaller budget helps.** Here 2.2 % of calls needed a hedge, and 5 % was enough for them. It does better than 10 % at every load and beats no hedge at 70 %, but at 85 % and 95 % it is still worse than no hedge. At 95 % it runs the workers at 99.5 %, and its p99 varies the most between seeds: from 423 to 1,652 ms.
- **The adaptive limit keeps the tail short.** As latency rises, it lowers the number of attempts allowed in flight and turns the others away, first attempts and hedges alike; a rejected hedge also stops further ones. It is also the steadiest setup across seeds. At 95 % load the p99 is 167 ms instead of 237 ms without hedges, but 7.4 % of calls are rejected with `AdaptiveLimitFullException`, which the caller must handle (a fallback, for example).

## When the cancellation reaches the server

With cancellation that reaches the server (gRPC propagates it, for example), a queued loser leaves the queue and one in service frees its worker. The picture changes completely:

| Setup | 50 % | 70 % | 85 % | 95 % |
|---|---:|---:|---:|---:|
| No hedge | 200 / 290 | 201 / 290 | 205 / 294 | 237 / 349 |
| Any hedge setup above | 30 / 36 | 30 / 36 | 30 / 36 | 31 / 38 |

Every hedge setup gives the same result, at every load and in every seed. Cancelling the slow attempt saves the rest of its work: at 95 % load, the workers are busy 74 % of the time instead of 95 %. Hedging takes load off the service here, because most hedges cut short a request that would have kept its worker for 100 to 300 ms.

## What to take from it

- **Know whether your cancellation reaches the server.** If it does, hedging is safe across a wide range of loads. If it does not, hedging needs headroom.
- **Keep the budget on.** Without it, a burst can tip the service into a state it does not leave. resilience4cata turns it on by default for this reason.
- **Size the budget to the hedges you expect.** If 2 % of calls are slow by bad luck, a 10 % budget leaves 8 % for hedges caused by queueing, which only add load. `budgetPercent` is the knob.
- **Put an adaptive limit inside the hedge** when the service may run hot. It turns overload into fast rejections instead of a long queue.
- **Set the delay above the latency that queueing alone causes.** A delay close to the median hedges ordinary calls as soon as the service gets busy.

## Caveats

- One service with one queue. Real services have several replicas; a hedge sent to a less busy replica can help more than here, and a load balancer that sends it to the same busy one, less.
- The hiccups are independent. When every replica shares the same slow path (a slow database behind them all), a hedge waits twice and never helps; the simulation does not cover that case.
- No network, no real timers, no thread scheduling. A confirmation on real clocks, with a load generator and a server on separate machines, is possible future work.
- Ten seeds, one service-time model, one delay. The shape held in every seed, but the numbers at 85 % and 95 % vary between them, as the brackets show (up to four times, for the 5 % budget at 95 %).
- The model's cancellation is instant and free. A real cancellation takes a network trip, and many servers only check for it between steps of their work, so the second table is a ceiling, not a forecast.

## Try it

One seed runs in about a minute, the ten seeds of the tables in about ten:

```bash
./gradlew :examples:hedgeSimulation -Phedge.seed=1
./gradlew :examples:hedgeSimulation -Phedge.seeds=1-10
```

To try hedging in your own service, start with the [hedge page](../policies/hedge.md) and the [adaptive limit](../policies/adaptive-limit.md). Questions and results from your own services are welcome in the [discussions](https://github.com/fabiangftech/resilience4cata/discussions).
