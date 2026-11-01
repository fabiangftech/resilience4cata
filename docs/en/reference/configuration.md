# Configuration keys

Every key the `resilience4cata-config` module reads, with its environment variable, type, built-in default and valid values.

This page is the reference; [the configuration guide](../guides/configuration.md) explains how to use it. The tables are checked against the code by a test (`ConfigurationReferenceTest` in the `config` module), so a key or default that changes in the code fails the build until this page follows.

## Key forms :id=key-forms

Each table lists the `defaults.<policy>.<attribute>` form. The same `<policy>.<attribute>` part works in every form:

| Form | Applies to | Example |
|---|---|---|
| `defaults.<policy>.<attribute>` | every instance of the policy | `defaults.retry.maxAttempts=5` |
| `configs.<name>.<policy>.<attribute>` | instances whose annotation says `config = "<name>"` | `configs.external.timeout.value=2s` |
| `configs.<name>.extends=<other>` | inherits every policy of another named config | `configs.batch.extends=external` |
| `instances.<id>.<policy>.<attribute>` | one instance: its `name`, or its derived id in double quotes | `instances."com.acme.Api#get(java.lang.String)".timeout.value=2s` |
| `profiles.<profile>.<any key above>` | only while the profile is active | `profiles.test.defaults.retry.maxAttempts=1` |
| `profiles.active=<a>,<b>` | the active profiles, later ones win | `profiles.active=test` |

- Policies: `retry`, `hedge`, `circuitBreaker`, `rateLimiter`, `adaptiveLimit`, `timeout`, `bulkhead`. `@Fallback` has no keys.
- Policy and attribute names may be written in camelCase or kebab-case: `circuit-breaker.wait-in-open` is `circuitBreaker.waitInOpen`.
- Config and profile names are lowercase letters and digits separated by single hyphens (`slow-external`).
- An instance id is case-sensitive. It must be in double quotes unless it is made only of letters, digits, `_` and `-`. A derived id is `<type>#<method>(<parameter types>)`, with fully qualified, erased parameter types separated by commas without spaces, for example `com.acme.CatalogClient#price(java.lang.String,int)`.
- Every key may start with `r4c.` or `resilience4cata.`. In **system properties the prefix is required**; other system properties are ignored.
- A key with the short prefix (`r4c.`, or `R4C_` in the environment) whose first segment is not `defaults`, `configs`, `instances` or `profiles`, nor close to one, belongs to another tool and is ignored: `-Dr4c.debug=true` or `R4C_TOKEN` do not stop the application. A key that looks like ours (`r4c.defualts.…`) is still an error ([R4C-CFG-001](../errors.md#r4c-cfg-001)), and so is any unknown key with the long prefix or none.
- Some attributes exist only on annotations and have no key: `name`, `config`, `locked`, `classifier`, `onExhaustedResult`, and everything on `@Fallback`.

## Environment variables :id=environment-variables

`R4C_` (or `RESILIENCE4CATA_`) followed by the key in uppercase, with every character that is not a letter or digit replaced by `_`. The tables show the variable of each `defaults.` key; the other forms follow the same rule:

| Key | Variable |
|---|---|
| `configs.external.retry.maxAttempts` | `R4C_CONFIGS_EXTERNAL_RETRY_MAXATTEMPTS` |
| `configs.slow-external.timeout.value` | `R4C_CONFIGS_SLOW_EXTERNAL_TIMEOUT_VALUE` |
| `instances.payments-api.retry.maxAttempts` | `R4C_INSTANCES_PAYMENTS_API_RETRY_MAXATTEMPTS` |
| `profiles.active` | `R4C_PROFILES_ACTIVE` |

Words may also be separated (`R4C_DEFAULTS_CIRCUIT_BREAKER_WAIT_IN_OPEN`). A variable must match exactly one key ([R4C-CFG-010](../errors.md#r4c-cfg-010)). Instances can be set from the environment only when their name is lowercase letters, digits and hyphens; a derived id needs a file or a system property.

## Values :id=values

| Type | Syntax |
|---|---|
| duration | digits and a unit: `ns`, `us`, `ms`, `s`, `m`, `h`, `d` (`250ms`, `2s`); an ISO-8601 duration (`PT2S`, `PT1M30S`); or `0`. No decimals, no negative values, no spaces. The same grammar as the annotations. |
| int | a whole number (`5`) |
| decimal | a number such as `50` or `2.5` |
| boolean | `true` or `false` |
| enum | one of the listed values, case-insensitive (`exponential`, `EXPONENTIAL`) |
| class list | comma-separated binary class names of exceptions (`java.io.IOException, java.net.SocketTimeoutException`), loaded with the configured class loader; nested classes as `a.B$C` or `a.B.C`; empty for none |

Combinations are checked too: `maxDelay` must not be shorter than `delay`, and a rate limiter's `period` must be at least one nanosecond per permit ([R4C-CFG-006](../errors.md#r4c-cfg-006)).

## Sources and precedence :id=precedence

`ResilienceConfig.load()` reads, lowest first: the classpath resource `resilience4cata.properties` (optional), environment variables, system properties. With `ResilienceConfig.builder()` you choose the sources; for one key they rank classpath resources < files and `Properties` < environment variables < system properties, and among sources of the same kind the one added last wins. Within one source, a key under an active profile wins over the same key without a profile; a stronger source wins even over a profile key of a weaker one.

Values then combine, lowest first:

1. built-in defaults (the **Default** column below);
2. `defaults.*` (and `ResilienceRegistry.Builder.*Defaults(...)` in code);
3. `configs.<name>.*`, root of the `extends` chain first; external keys override a config of the same name registered in code, attribute by attribute;
4. annotation attributes;
5. `instances.<id>.*`, so operators can retune one instance without recompiling, except the attributes of an annotation with `locked = true`, which keep the annotation's value ([Locking an instance](../guides/configuration.md#locking-an-instance)).

Everything is resolved once, when an instance is created. Nothing is reloaded afterwards. To check that every `instances.<id>` key matches an instance, call `config.verifyInstances(registry)` after wrapping your services ([R4C-CFG-013](../errors.md#r4c-cfg-013)); it also reports keys that a locked annotation ignores ([R4C-CFG-014](../errors.md#r4c-cfg-014)).

## Retry :id=retry

`@Retry` attributes. See [Retry](../policies/retry.md).

| Key | Environment variable | Type | Default | Valid values | Meaning |
|---|---|---|---|---|---|
| `defaults.retry.enabled` | `R4C_DEFAULTS_RETRY_ENABLED` | boolean | `true` | `true`, `false` | `false` runs every call once, with no retry. |
| `defaults.retry.maxAttempts` | `R4C_DEFAULTS_RETRY_MAXATTEMPTS` | int | `3` | ≥ 1 | Total attempts, the first one included. |
| `defaults.retry.delay` | `R4C_DEFAULTS_RETRY_DELAY` | duration | `200ms` | ≥ 0 | Delay before the first retry; the base of the backoff. |
| `defaults.retry.backoff` | `R4C_DEFAULTS_RETRY_BACKOFF` | enum | `exponential` | `fixed`, `exponential` | `fixed`: every delay is `delay`. `exponential`: `delay × multiplier^(n−1)`, capped at `maxDelay`. |
| `defaults.retry.multiplier` | `R4C_DEFAULTS_RETRY_MULTIPLIER` | decimal | `2.0` | finite, ≥ 1 | Growth factor of the exponential backoff. |
| `defaults.retry.maxDelay` | `R4C_DEFAULTS_RETRY_MAXDELAY` | duration | `10s` | ≥ `delay` | Upper bound of a single delay. |
| `defaults.retry.jitter` | `R4C_DEFAULTS_RETRY_JITTER` | enum | `full` | `none`, `full`, `equal` | `full`: random in [0, d]. `equal`: d/2 plus a random half. `none`: exactly d. |
| `defaults.retry.maxDuration` | `R4C_DEFAULTS_RETRY_MAXDURATION` | duration | `0` | ≥ 0; `0` = unlimited | Time budget for the whole operation: gives up when waiting for the next delay would go past it. |
| `defaults.retry.retryOn` | `R4C_DEFAULTS_RETRY_RETRYON` | class list | `java.lang.Exception` | exception classes | Failures that are retried, subclasses included. `Error`s are not retried by default. |
| `defaults.retry.abortOn` | `R4C_DEFAULTS_RETRY_ABORTON` | class list | — | exception classes | Failures that are never retried; they win over `retryOn` and a classifier. A `CallNotPermittedException` (open breaker) is never retried either, whether listed or not. |
| `defaults.retry.budgetPercent` | `R4C_DEFAULTS_RETRY_BUDGETPERCENT` | decimal | `0` | finite, ≥ 0 | Retry budget: retries allowed, in percent of the instance's calls in the last `budgetWindow`, on top of `budgetMinRetriesPerSecond`. Above `0` it turns the budget on; `0` means no budget. |
| `defaults.retry.budgetMinRetriesPerSecond` | `R4C_DEFAULTS_RETRY_BUDGETMINRETRIESPERSECOND` | int | `10` | ≥ 0 | Retry budget: retries allowed per second of `budgetWindow`, whatever the traffic, once `budgetPercent` turns the budget on. `0` leaves the percentage alone. |
| `defaults.retry.budgetWindow` | `R4C_DEFAULTS_RETRY_BUDGETWINDOW` | duration | `10s` | 1s to 1h | Retry budget: how far back calls and retries are counted. |

## Hedge :id=hedge

`@Hedge` attributes. See [Hedge](../policies/hedge.md).

| Key | Environment variable | Type | Default | Valid values | Meaning |
|---|---|---|---|---|---|
| `defaults.hedge.enabled` | `R4C_DEFAULTS_HEDGE_ENABLED` | boolean | `true` | `true`, `false` | `false` runs every call once, with no hedge. |
| `defaults.hedge.maxAttempts` | `R4C_DEFAULTS_HEDGE_MAXATTEMPTS` | int | `2` | 2 to 10 | Attempts in flight at most, the first one included. |
| `defaults.hedge.delay` | `R4C_DEFAULTS_HEDGE_DELAY` | duration | `1s` | ≥ 0; `0` = all at once | How long the latest attempt runs without a result before the next one starts. |
| `defaults.hedge.hedgeOn` | `R4C_DEFAULTS_HEDGE_HEDGEON` | class list | `java.lang.Exception` | exception classes | Failures after which the next attempt starts at once; a failure not listed ends the call. |
| `defaults.hedge.abortOn` | `R4C_DEFAULTS_HEDGE_ABORTON` | class list | — | exception classes | Failures that end the call at once and cancel the other attempts; they win over `hedgeOn`. |
| `defaults.hedge.budgetPercent` | `R4C_DEFAULTS_HEDGE_BUDGETPERCENT` | decimal | `10` | finite, ≥ 0 | Hedge budget: hedges allowed, in percent of the instance's calls in the last `budgetWindow`, on top of `budgetMinHedgesPerSecond`. `0` turns the budget off. |
| `defaults.hedge.budgetMinHedgesPerSecond` | `R4C_DEFAULTS_HEDGE_BUDGETMINHEDGESPERSECOND` | int | `1` | ≥ 0 | Hedge budget: hedges allowed per second of `budgetWindow`, whatever the traffic. |
| `defaults.hedge.budgetWindow` | `R4C_DEFAULTS_HEDGE_BUDGETWINDOW` | duration | `10s` | 1s to 1h | Hedge budget: how far back calls and hedges are counted. |

## Circuit breaker :id=circuit-breaker

`@CircuitBreaker` attributes. See [Circuit breaker](../policies/circuit-breaker.md).

| Key | Environment variable | Type | Default | Valid values | Meaning |
|---|---|---|---|---|---|
| `defaults.circuitBreaker.enabled` | `R4C_DEFAULTS_CIRCUITBREAKER_ENABLED` | boolean | `true` | `true`, `false` | `false`: the breaker starts `DISABLED`, lets every call through and records nothing. |
| `defaults.circuitBreaker.windowType` | `R4C_DEFAULTS_CIRCUITBREAKER_WINDOWTYPE` | enum | `count` | `count`, `time` | `count`: the last `windowSize` calls. `time`: the calls of the last `windowDuration` (reads the clock on every call). |
| `defaults.circuitBreaker.windowSize` | `R4C_DEFAULTS_CIRCUITBREAKER_WINDOWSIZE` | int | `100` | ≥ 1 | Calls in a `count` window; a `time` window ignores it. |
| `defaults.circuitBreaker.windowDuration` | `R4C_DEFAULTS_CIRCUITBREAKER_WINDOWDURATION` | duration | `60s` | whole seconds, ≥ `1s` | Length of a `time` window; a `count` window ignores it. |
| `defaults.circuitBreaker.minimumCalls` | `R4C_DEFAULTS_CIRCUITBREAKER_MINIMUMCALLS` | int | `100` | ≥ 1 | Calls in the window before the rates are evaluated (capped at `windowSize` for a count window). |
| `defaults.circuitBreaker.failureRateThreshold` | `R4C_DEFAULTS_CIRCUITBREAKER_FAILURERATETHRESHOLD` | decimal | `50` | > 0 and ≤ 100 | Failure rate, in percent, that opens the breaker. |
| `defaults.circuitBreaker.slowCallRateThreshold` | `R4C_DEFAULTS_CIRCUITBREAKER_SLOWCALLRATETHRESHOLD` | decimal | `100` | > 0 and ≤ 100 | Slow-call rate, in percent, that opens the breaker. |
| `defaults.circuitBreaker.slowCallDuration` | `R4C_DEFAULTS_CIRCUITBREAKER_SLOWCALLDURATION` | duration | `0` | ≥ 0; `0` = off | Calls longer than this are slow. Calls are timed only when it is set. |
| `defaults.circuitBreaker.waitInOpen` | `R4C_DEFAULTS_CIRCUITBREAKER_WAITINOPEN` | duration | `60s` | ≥ 0 | How long the breaker stays open; the next call after it is a half-open probe. |
| `defaults.circuitBreaker.halfOpenCalls` | `R4C_DEFAULTS_CIRCUITBREAKER_HALFOPENCALLS` | int | `10` | ≥ 1 | Probe calls let through while half-open. |
| `defaults.circuitBreaker.maxWaitInHalfOpen` | `R4C_DEFAULTS_CIRCUITBREAKER_MAXWAITINHALFOPEN` | duration | `0` | ≥ 0; `0` = no limit | How long the breaker may stay half-open before opening again if its probes have not finished. |
| `defaults.circuitBreaker.recordOn` | `R4C_DEFAULTS_CIRCUITBREAKER_RECORDON` | class list | `java.lang.Exception` | exception classes | Failures recorded as failures. Anything else, `Error`s included, counts as a success. |
| `defaults.circuitBreaker.ignoreOn` | `R4C_DEFAULTS_CIRCUITBREAKER_IGNOREON` | class list | — | exception classes | Failures recorded neither as success nor as failure; they win over `recordOn` and a classifier. |

## Rate limiter :id=rate-limiter

`@RateLimiter` attributes. See [Rate limiter](../policies/rate-limiter.md).

| Key | Environment variable | Type | Default | Valid values | Meaning |
|---|---|---|---|---|---|
| `defaults.rateLimiter.enabled` | `R4C_DEFAULTS_RATELIMITER_ENABLED` | boolean | `true` | `true`, `false` | `false` lets every call through. |
| `defaults.rateLimiter.permits` | `R4C_DEFAULTS_RATELIMITER_PERMITS` | int | `50` | ≥ 1 | Calls per `period`, and the largest burst. |
| `defaults.rateLimiter.period` | `R4C_DEFAULTS_RATELIMITER_PERIOD` | duration | `1s` | > 0 and ≥ `permits` ns | Length of the period; permits are spaced `period / permits` apart. |
| `defaults.rateLimiter.maxWait` | `R4C_DEFAULTS_RATELIMITER_MAXWAIT` | duration | `0` | ≥ 0; `0` = reject at once | How long a call may wait for a permit before `RateLimitExceededException`. |

## Adaptive limit :id=adaptive-limit

`@AdaptiveLimit` attributes, plus the algorithms' tuning, which has no annotation attribute (`minWindowSamples`, `smoothing`, `rttTolerance`, `longWindow`, `queueSize`, `backoffRatio`). See [Adaptive limit](../policies/adaptive-limit.md).

| Key | Environment variable | Type | Default | Valid values | Meaning |
|---|---|---|---|---|---|
| `defaults.adaptiveLimit.enabled` | `R4C_DEFAULTS_ADAPTIVELIMIT_ENABLED` | boolean | `true` | `true`, `false` | `false` does not limit concurrent calls. |
| `defaults.adaptiveLimit.algorithm` | `R4C_DEFAULTS_ADAPTIVELIMIT_ALGORITHM` | enum | `gradient2` | `gradient2`, `aimd` | `gradient2`: compares each window's latency with a long-term average. `aimd`: one more slot after a busy window, times `backoffRatio` after a drop. |
| `defaults.adaptiveLimit.initialLimit` | `R4C_DEFAULTS_ADAPTIVELIMIT_INITIALLIMIT` | int | `20` | `minLimit` to `maxLimit` | The limit before the first window ends. |
| `defaults.adaptiveLimit.minLimit` | `R4C_DEFAULTS_ADAPTIVELIMIT_MINLIMIT` | int | `1` | ≥ 1 | The lowest the limit goes. |
| `defaults.adaptiveLimit.maxLimit` | `R4C_DEFAULTS_ADAPTIVELIMIT_MAXLIMIT` | int | `200` | ≥ `minLimit` | The highest the limit goes. |
| `defaults.adaptiveLimit.window` | `R4C_DEFAULTS_ADAPTIVELIMIT_WINDOW` | duration | `1s` | 10ms to 1h | How long a window lasts at least; the limit is recomputed at its end. |
| `defaults.adaptiveLimit.minWindowSamples` | `R4C_DEFAULTS_ADAPTIVELIMIT_MINWINDOWSAMPLES` | int | `10` | ≥ 1 | Completed calls a window needs before it ends; a window with fewer is extended by one more `window`, keeping its calls. |
| `defaults.adaptiveLimit.dropOn` | `R4C_DEFAULTS_ADAPTIVELIMIT_DROPON` | class list | `dev.resilience4cata.CallTimeoutException, dev.resilience4cata.BulkheadFullException, java.util.concurrent.TimeoutException` | exception classes | Failures that mean the dependency is overloaded: samples with a drop (`aimd` backs off). Any other failure is not a sample. |
| `defaults.adaptiveLimit.ignoreOn` | `R4C_DEFAULTS_ADAPTIVELIMIT_IGNOREON` | class list | — | exception classes | Failures that are never samples; they win over `dropOn`. |
| `defaults.adaptiveLimit.smoothing` | `R4C_DEFAULTS_ADAPTIVELIMIT_SMOOTHING` | decimal | `0.2` | > 0 and ≤ 1 | `gradient2`: how much of each new estimate is kept. |
| `defaults.adaptiveLimit.rttTolerance` | `R4C_DEFAULTS_ADAPTIVELIMIT_RTTTOLERANCE` | decimal | `1.5` | finite, ≥ 1 | `gradient2`: how many times the long-term latency a window may reach before the limit goes down. |
| `defaults.adaptiveLimit.longWindow` | `R4C_DEFAULTS_ADAPTIVELIMIT_LONGWINDOW` | int | `600` | ≥ 1 | `gradient2`: windows averaged into the long-term latency. |
| `defaults.adaptiveLimit.queueSize` | `R4C_DEFAULTS_ADAPTIVELIMIT_QUEUESIZE` | int | `4` | ≥ 0 | `gradient2`: room the limit leaves above the estimated concurrency, so that it can grow. Latency alone does not take the limit much below twice this value. |
| `defaults.adaptiveLimit.backoffRatio` | `R4C_DEFAULTS_ADAPTIVELIMIT_BACKOFFRATIO` | decimal | `0.9` | ≥ 0.5 and < 1 | `aimd`: what a window with a drop multiplies the limit by. |

## Timeout :id=timeout

`@Timeout` attributes. The annotation's `value` is the key `timeout.value`. `@Timeout` has no `name`, so `instances.<id>.timeout.*` always uses the derived id. See [Timeout](../policies/timeout.md).

| Key | Environment variable | Type | Default | Valid values | Meaning |
|---|---|---|---|---|---|
| `defaults.timeout.enabled` | `R4C_DEFAULTS_TIMEOUT_ENABLED` | boolean | `true` | `true`, `false` | `false` never times a call out. |
| `defaults.timeout.value` | `R4C_DEFAULTS_TIMEOUT_VALUE` | duration | `1s` | > 0 | How long one attempt may take (`@Timeout("2s")`; `TimeoutConfig.timeout()` in code). |

## Bulkhead :id=bulkhead

`@Bulkhead` attributes. See [Bulkhead](../policies/bulkhead.md).

| Key | Environment variable | Type | Default | Valid values | Meaning |
|---|---|---|---|---|---|
| `defaults.bulkhead.enabled` | `R4C_DEFAULTS_BULKHEAD_ENABLED` | boolean | `true` | `true`, `false` | `false` does not limit concurrent calls. |
| `defaults.bulkhead.maxConcurrent` | `R4C_DEFAULTS_BULKHEAD_MAXCONCURRENT` | int | `25` | ≥ 1 | Calls running at the same time. |
| `defaults.bulkhead.maxWait` | `R4C_DEFAULTS_BULKHEAD_MAXWAIT` | duration | `0` | ≥ 0; `0` = reject at once | How long a call may wait for a free slot before `BulkheadFullException`. |

## Errors :id=errors

All the problems in a configuration are reported together, in one `ConfigException` with an `R4C-CFG-nnn` code, the key, its source (file and line, environment variable or system property) and the closest valid key. See [error codes](../errors.md#r4c-cfg-001).
