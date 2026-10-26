# Configuration

With the `resilience4cata-config` module, operators retune the same compiled code from properties files, environment variables and system properties.

Add `implementation 'dev.resilience4cata:resilience4cata-config'` (Maven: default scope) next to `core`; see [installation](../start/installation.md). Every key is listed in the [configuration reference](../reference/configuration.md).

## A first file

Put a `resilience4cata.properties` on the class path (for example in `src/main/resources`). This one retunes the client below:

<!-- snippet: configuration-file -->
```properties
# Every retry that does not set these attributes itself
defaults.retry.delay=50ms
defaults.retry.jitter=none

# A named config, used by @Retry(config = "external") and @CircuitBreaker(config = "external")
configs.external.retry.maxAttempts=4
configs.external.circuitBreaker.minimumCalls=50
configs.external.circuitBreaker.waitInOpen=10s

# A named config that starts from another one
configs.batch.extends=external
configs.batch.timeout.value=30s

# One instance, by its name...
instances.shipping.circuitBreaker.failureRateThreshold=30
# ...or by its derived id, in double quotes
instances."dev.resilience4cata.examples.docs.configuration.ShippingClient#cancel(java.lang.String)".timeout.value=3s

# Applied only while the profile "test" is active
profiles.test.configs.external.retry.maxAttempts=1
```

<!-- snippet: configuration-client -->
```java
public interface ShippingClient {

    @Retry(config = "external")
    @CircuitBreaker(name = "shipping", config = "external", minimumCalls = 20)
    String track(String parcel);

    @Retry(maxAttempts = 2)
    @Timeout("1s")
    String cancel(String parcel);
}
```

Load it once, apply it to the registry builder, then wrap your services as usual:

<!-- snippet: configuration-load -->
```java
ResilienceConfig config = ResilienceConfig.load();   // resilience4cata.properties, R4C_* variables, -Dr4c.* properties
ResilienceRegistry registry = config.applyTo(ResilienceRegistry.builder()).build();
ShippingClient shipping = Resilient.of(ShippingClient.class, new HttpShippingClient(), registry);
config.verifyInstances(registry);   // fails if an instances.<id> key matches no instance
```

With this file, `track` makes up to 4 attempts 50 ms apart (no jitter), and its breaker `shipping` opens at a 30% failure rate after 20 calls (the annotation's `minimumCalls = 20` beats the named config's 50), then waits 10 s. `cancel` makes 2 attempts and times out after 3 s instead of the annotation's 1 s.

`ResilienceConfig.load()` reads, in this order, the class path resource `resilience4cata.properties` (if present), the environment and the system properties, with the thread's context class loader. `config.toRegistry()` is a shortcut for `config.applyTo(ResilienceRegistry.builder()).build()`.

## Sources

To choose the sources yourself, use the builder:

<!-- snippet: configuration-builder -->
```java
ResilienceConfig config = ResilienceConfig.builder()
        .optionalClasspath("resilience4cata.properties")
        .optionalFile(Path.of("/etc/shop/resilience4cata.properties"))   // skipped if missing
        .environment()                                                     // R4C_… variables
        .systemProperties()                                                // -Dr4c.… properties
        .build();
```

| Method | Reads |
|---|---|
| `classpath(name)` / `optionalClasspath(name)` | A class path resource; the first form fails ([`R4C-CFG-009`](../errors.md#r4c-cfg-009)) if it is missing |
| `file(path)` / `optionalFile(path)` | A file; same rule |
| `properties(props)` / `properties(description, props)` | A `java.util.Properties` you built; the description appears in error messages |
| `properties(description, map, origins)` | A map of keys and values, with where each key was set (`origins`), shown in error messages instead of the description. For frameworks that track origins, such as Spring |
| `environment()` / `environment(map)` | Environment variables (`R4C_…`), or a map of them |
| `systemProperties()` / `systemProperties(props)` | System properties (`r4c.…`), or a `Properties` used as such |
| `profiles(names...)` | The active profiles; overrides `profiles.active` |
| `classLoader(loader)` | The class loader for resources and for exception classes named in values |
| `precedenceByOrder()` | Rank the sources by the order they were added, not by kind (below) |

Files use the `.properties` syntax, read as UTF-8. There is no YAML support and no reloading: the configuration is read once.

When the same key comes from several sources, the stronger source wins: **class path resources < files and `Properties` < environment variables < system properties**. Between two sources of the same kind, the one added last wins. With `precedenceByOrder()`, the one added last wins whatever its kind: the [Spring Boot integration](spring.md) uses it to keep Spring's precedence. A key under an active profile beats a key without a profile, whatever their sources.

<!-- snippet: configuration-sources -->
```java
Properties system = new Properties();
system.setProperty("r4c.configs.external.retry.maxAttempts", "7");   // the r4c. prefix is required here

ResilienceConfig config = ResilienceConfig.builder()
        .classpath("resilience4cata.properties")                      // maxAttempts=4
        .environment(Map.of(
                "R4C_CONFIGS_EXTERNAL_RETRY_MAXATTEMPTS", "6",
                "R4C_INSTANCES_SHIPPING_CIRCUIT_BREAKER_WAIT_IN_OPEN", "5s"))
        .systemProperties(system)
        .build();
ResilienceRegistry registry = config.applyTo(ResilienceRegistry.builder()).build();
// registry.retryConfig("external").maxAttempts() == 7: system properties > environment > files
```

## Keys

```properties
defaults.<policy>.<attribute>=…                 # registry-wide defaults
configs.<name>.<policy>.<attribute>=…           # a named config, used with config = "<name>"
configs.<name>.extends=<other>                  # start from every policy of another named config
instances.<id>.<policy>.<attribute>=…           # one instance, by its name or derived id
profiles.<profile>.<any key above>=…            # applies only while <profile> is active
profiles.active=<profile>,<profile>             # comma-separated; later profiles win
```

- **Policies:** `retry`, `hedge`, `circuitBreaker`, `rateLimiter`, `adaptiveLimit`, `timeout`, `bulkhead`. There are no `fallback` keys.
- **Attributes** are the annotation's, with the same names (`maxAttempts`, `waitInOpen`, …). The timeout's duration is `timeout.value`. `name`, `config`, `classifier` and `onExhaustedResult` can only be set in annotations.
- **Spelling:** camelCase and kebab-case are both accepted (`circuitBreaker.waitInOpen` = `circuit-breaker.wait-in-open`). Setting the same key twice in one source, even under two spellings, is an error ([`R4C-CFG-005`](../errors.md#r4c-cfg-005)).
- **Prefix:** any key may start with `r4c.` or `resilience4cata.`. In system properties the prefix is **required**, and system properties without it are ignored.
- **Names** of configs and profiles: lowercase letters and digits separated by single hyphens (`slow-external`). `default` is reserved for configs and `active` for profiles ([`R4C-CFG-004`](../errors.md#r4c-cfg-004)).
- **`extends`** may only name a config defined in external configuration, and must not form a cycle ([`R4C-CFG-007`](../errors.md#r4c-cfg-007)). The chain is applied root first.
- **Instance ids** are the policy's `name`, or the derived `<type>#<method>(<parameter types>)` ([how ids are built](../start/how-it-works.md#the-registry-and-instance-ids)). They are case-sensitive. An id with anything other than letters, digits, `_` and `-` goes in double quotes: `instances."com.acme.Api#get(java.lang.String)".retry.maxAttempts=5` ([`R4C-CFG-012`](../errors.md#r4c-cfg-012)).

### Values

| Type | Syntax | Examples |
|---|---|---|
| Duration | digits plus `ns`, `us`, `ms`, `s`, `m`, `h` or `d`; an ISO-8601 duration; or `0`. No decimals, signs or spaces | `250ms`, `2s`, `PT2S`, `0` |
| Integer, decimal | Plain numbers | `5`, `25`, `2.5` |
| Boolean | `true` or `false` | `retry.enabled=false` |
| Enum | Case-insensitive constant | `exponential`, `full`, `time` |
| Exception list | Comma-separated binary class names, loaded with the configured class loader | `java.io.IOException,java.util.concurrent.TimeoutException` |

The duration grammar is the same as in annotations.

## Environment variables

Take the key, uppercase it, replace every character that is not a letter or a digit with `_`, and add the prefix `R4C_` (or `RESILIENCE4CATA_`):

| Key | Environment variable |
|---|---|
| `configs.external.retry.maxAttempts` | `R4C_CONFIGS_EXTERNAL_RETRY_MAXATTEMPTS` |
| `configs.slow-external.timeout.value` | `R4C_CONFIGS_SLOW_EXTERNAL_TIMEOUT_VALUE` |
| `instances.shipping.circuitBreaker.waitInOpen` | `R4C_INSTANCES_SHIPPING_CIRCUITBREAKER_WAITINOPEN` or `R4C_INSTANCES_SHIPPING_CIRCUIT_BREAKER_WAIT_IN_OPEN` |
| `profiles.active` | `R4C_PROFILES_ACTIVE` |

Words of policy and attribute names may be run together or separated by `_`. A variable must map to exactly one key; when `_` could stand for both `.` and `-` and two keys fit, it is an error ([`R4C-CFG-010`](../errors.md#r4c-cfg-010)): set that key in a file or a system property instead. Instances can be addressed from the environment only when their name is made of lowercase letters, digits and hyphens (`R4C_INSTANCES_PAYMENTS_API_RETRY_MAXATTEMPTS` sets `instances.payments-api.retry.maxAttempts`); derived ids need a file or a system property such as `-Dr4c.instances."com.acme.Api#get(java.lang.String)".retry.maxAttempts=5`.

## Profiles

A profile groups keys that apply only in some environments. Keys under `profiles.<profile>.` replace the same keys without the profile; they don't add a precedence level of their own.

<!-- snippet: configuration-profiles -->
```java
ResilienceConfig config = ResilienceConfig.builder()
        .classpath("resilience4cata.properties")
        .profiles("test")                         // or profiles.active=test, or R4C_PROFILES_ACTIVE=test
        .build();
// config.activeProfiles() == [test]; configs.external.retry.maxAttempts is now 1
```

Several profiles can be active: `profiles.active=test,local`. When two active profiles set the same key, the later one wins.

## Precedence

Lowest first, for each attribute of each instance:

1. Built-in defaults (each [policy page](../policies/README.md) lists them).
2. `defaults.<policy>.*`, or `retryDefaults(...)` and friends in code.
3. The named config the annotation picks with `config = "…"`: first the code's `retry("<name>", ...)`, then `configs.<name>.*`, attribute by attribute (the `extends` chain root first).
4. The annotation's own attributes.
5. `instances.<id>.*`, or `retryInstance(id, ...)` and friends in code.

So **an annotation beats a named config**, and **an instance key beats the annotation**: operators can retune one method without recompiling. Per-instance overrides apply once, when the instance is created.

Code and external configuration combine like this:

- A named config defined in both is merged, external values winning attribute by attribute, whatever the order of the calls (`applyTo` registers them with `retryOverlay(...)` and friends, which you can also call yourself):

  <!-- snippet: configuration-merge -->
  ```java
  ResilienceRegistry registry = ResilienceConfig.load()
          .applyTo(ResilienceRegistry.builder()
                  .retry("external", r -> r.maxAttempts(9).maxDuration(Duration.ofSeconds(5))))
          .build();
  // registry.retryConfig("external"): maxAttempts 4 (the file wins), maxDuration 5s (only the code sets it)
  ```

- `retryInstance(...)` and the other `*Instance` calls made on the builder after `applyTo` beat the external `instances.*` keys.
- `retryDefaults(...)` and the other `*Defaults` calls made after `applyTo` **replace** the external defaults of that policy instead of merging with them.

## Validation and errors

`build()` validates everything against the built-in defaults: unknown keys, values that don't parse, unknown exception classes, bad `extends`, invalid names and combinations that cannot work together (for example a `maxDelay` shorter than `delay`). Every problem is collected into one `ConfigException` (an `IllegalArgumentException`, with the list in `problems()`), each with its code, its key, its source (file and line, environment variable or system property) and the closest valid key. This file has three mistakes:

```properties
configs.external.circuit-breaker.wait-in-opne=10s
defaults.retry.delay=fast
configs.batch.extends=extrenal
```

<!-- snippet: configuration-errors -->
```java
assertThatThrownBy(() -> ResilienceConfig.builder().classpath(BROKEN).build())
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("[R4C-CFG-001]")
        .hasMessageContaining("Did you mean 'configs.external.circuit-breaker.wait-in-open'?")
        .hasMessageContaining("[R4C-CFG-002]")
        .hasMessageContaining("[R4C-CFG-007]");
```

The message, abridged:

```text
Invalid resilience4cata configuration (3 problems):
  [R4C-CFG-001] unknown key 'configs.external.circuit-breaker.wait-in-opne' (classpath:…/broken.properties, line 1). Did you mean 'configs.external.circuit-breaker.wait-in-open'? …
  [R4C-CFG-002] 'defaults.retry.delay' (classpath:…/broken.properties, line 2) = "fast" is not a valid duration: …
  [R4C-CFG-007] 'configs.batch.extends' (classpath:…/broken.properties, line 3) extends config 'extrenal', which is not defined in external configuration …
```

Instances only exist once services are wrapped, so `build()` cannot know whether an `instances.<id>` key names a real one. Call `config.verifyInstances(registry)` after wrapping your services: it fails with [`R4C-CFG-013`](../errors.md#r4c-cfg-013) and the closest id for every override that matches no instance. Combinations that involve both an instance key and the annotation's values (for example an instance `delay` longer than the annotation's `maxDelay`) are checked when the instance is created ([`R4C-REG-003`](../errors.md#r4c-reg-003)).

Every `R4C-CFG` code is explained in the [error reference](../errors.md#runtime-external-configuration-r4c-cfg).

## Inspecting the result

`config.describe()` lists every effective value and where it came from, one per line:

```text
active profiles: []
defaults.retry.delay = 50ms  <- 'defaults.retry.delay' (classpath:resilience4cata.properties, line 4)
configs.batch.retry.maxAttempts = 4  <- 'configs.external.retry.maxAttempts' (classpath:resilience4cata.properties, line 8)
instances.shipping.circuitBreaker.failureRateThreshold = 30  <- 'instances.shipping.circuitBreaker.failureRateThreshold' (classpath:resilience4cata.properties, line 17)
…
```

`activeProfiles()`, `configNames()` and `instanceIds()` return the parts one by one. Once services are wrapped, `registry.snapshot()` shows the resolved configuration of every instance ([observability](observability.md)).

## Limits

- No hot reload and no `reconfigure()`: the configuration is immutable once the registry is built.
- No YAML or other formats; read them yourself into a `Properties` and pass it with `properties(description, props)`.
- `@Fallback`, classifiers, `name` and `config` are not configurable externally.
- There is no way to lock an instance against external overrides: `instances.<id>.*` always beats the annotation.
