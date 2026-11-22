# 配置

借助 `resilience4cata-config` 模块，运维人员可以通过 properties 文件、环境变量和系统属性重新调整同一份已编译代码。

在 `core` 旁边添加 `implementation 'dev.resilience4cata:resilience4cata-config'`（Maven：默认作用域）；参见[安装](../start/installation.md)。所有键都列在[配置参考](../reference/configuration.md)中。

## 第一个文件 :id=a-first-file

在类路径上放一个 `resilience4cata.properties`（例如在 `src/main/resources` 中）。下面这个文件重新调整了后面的客户端：

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

加载一次，把它应用到注册表构建器上，然后照常包装你的服务：

<!-- snippet: configuration-load -->
```java
ResilienceConfig config = ResilienceConfig.load();   // resilience4cata.properties, R4C_* variables, -Dr4c.* properties
ResilienceRegistry registry = config.applyTo(ResilienceRegistry.builder()).build();
ShippingClient shipping = Resilient.of(ShippingClient.class, new HttpShippingClient(), registry);
config.verifyInstances(registry);   // fails if an instances.<id> key matches no instance
```

使用这个文件，`track` 最多尝试 4 次，间隔 50 ms（无抖动），它的熔断器 `shipping` 在 20 次调用后失败率达到 30% 时打开（注解的 `minimumCalls = 20` 优先于命名配置的 50），然后等待 10 s。`cancel` 尝试 2 次，并在 3 s 后超时，而不是注解中的 1 s。

`ResilienceConfig.load()` 使用线程的上下文类加载器，按以下顺序读取：类路径资源 `resilience4cata.properties`（如果存在）、环境变量和系统属性。`config.toRegistry()` 是 `config.applyTo(ResilienceRegistry.builder()).build()` 的快捷方式。

## 配置源 :id=sources

要自己选择配置源，请使用构建器：

<!-- snippet: configuration-builder -->
```java
ResilienceConfig config = ResilienceConfig.builder()
        .optionalClasspath("resilience4cata.properties")
        .optionalFile(Path.of("/etc/shop/resilience4cata.properties"))   // skipped if missing
        .environment()                                                     // R4C_… variables
        .systemProperties()                                                // -Dr4c.… properties
        .build();
```

| 方法 | 读取内容 |
|---|---|
| `classpath(name)` / `optionalClasspath(name)` | 一个类路径资源；第一种形式在资源缺失时失败（[`R4C-CFG-009`](../errors.md#r4c-cfg-009)） |
| `file(path)` / `optionalFile(path)` | 一个文件；规则相同 |
| `properties(props)` / `properties(description, props)` | 你构建的 `java.util.Properties`；描述会出现在错误消息中 |
| `properties(description, map, origins)` | 一个键值映射，附带每个键的设置位置（`origins`），错误消息中显示它而不是描述。适用于跟踪来源的框架，例如 Spring |
| `environment()` / `environment(map)` | 环境变量（`R4C_…`），或由它们组成的映射 |
| `systemProperties()` / `systemProperties(props)` | 系统属性（`r4c.…`），或当作系统属性使用的 `Properties` |
| `profiles(names...)` | 激活的 profile；覆盖 `profiles.active` |
| `classLoader(loader)` | 用于资源以及值中所指定异常类的类加载器 |
| `precedenceByOrder()` | 按添加顺序而不是按种类为配置源排序（见下文） |

文件使用 `.properties` 语法，以 UTF-8 读取。不支持 YAML，也不支持重新加载：配置只读取一次。

当同一个键来自多个配置源时，较强的配置源胜出：**类路径资源 < 文件和 `Properties` < 环境变量 < 系统属性**。同一种类的两个配置源之间，后添加的胜出。使用 `precedenceByOrder()` 时，无论种类如何，后添加的都胜出：[Spring Boot 集成](spring.md)用它来保持 Spring 的优先级。在同一个配置源内，激活的 profile 下的键优先于不带 profile 的同一个键；但较强的配置源仍然胜出，因此运维人员设置的环境变量或系统属性优先于文件中的 profile 键。

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

## 键 :id=keys

```properties
defaults.<policy>.<attribute>=…                 # registry-wide defaults
configs.<name>.<policy>.<attribute>=…           # a named config, used with config = "<name>"
configs.<name>.extends=<other>                  # start from every policy of another named config
instances.<id>.<policy>.<attribute>=…           # one instance, by its name or derived id
profiles.<profile>.<any key above>=…            # applies only while <profile> is active
profiles.active=<profile>,<profile>             # comma-separated; later profiles win
```

- **策略：**`retry`、`hedge`、`circuitBreaker`、`rateLimiter`、`adaptiveLimit`、`timeout`、`bulkhead`。没有 `fallback` 键。
- **属性** 就是注解的属性，名称相同（`maxAttempts`、`waitInOpen` 等）。超时的时长是 `timeout.value`。`name`、`config`、`classifier` 和 `onExhaustedResult` 只能在注解中设置。
- **写法：**camelCase 和 kebab-case 都接受（`circuitBreaker.waitInOpen` = `circuit-breaker.wait-in-open`）。在同一个配置源中把同一个键设置两次（即使使用两种写法）是错误（[`R4C-CFG-005`](../errors.md#r4c-cfg-005)）。
- **前缀：**任何键都可以以 `r4c.` 或 `resilience4cata.` 开头。在系统属性中前缀是**必需的**，没有前缀的系统属性会被忽略。
- 配置和 profile 的**名称**：由单个连字符分隔的小写字母和数字（`slow-external`）。`default` 是为配置保留的，`active` 是为 profile 保留的（[`R4C-CFG-004`](../errors.md#r4c-cfg-004)）。
- **`extends`** 只能指向外部配置中定义的配置，且不得形成循环（[`R4C-CFG-007`](../errors.md#r4c-cfg-007)）。继承链从根开始应用。
- **实例 id** 是策略的 `name`，或派生的 `<type>#<method>(<parameter types>)`（[id 如何构建](../start/how-it-works.md#the-registry-and-instance-ids)）。它们区分大小写。包含字母、数字、`_` 和 `-` 以外字符的 id 要放在双引号中：`instances."com.acme.Api#get(java.lang.String)".retry.maxAttempts=5`（[`R4C-CFG-012`](../errors.md#r4c-cfg-012)）。

### 值 :id=values

| 类型 | 语法 | 示例 |
|---|---|---|
| 时长 | 数字加 `ns`、`us`、`ms`、`s`、`m`、`h` 或 `d`；ISO-8601 时长；或 `0`。不允许小数、符号或空格 | `250ms`、`2s`、`PT2S`、`0` |
| 整数、小数 | 普通数字 | `5`、`25`、`2.5` |
| 布尔值 | `true` 或 `false` | `retry.enabled=false` |
| 枚举 | 不区分大小写的常量 | `exponential`、`full`、`time` |
| 异常列表 | 以逗号分隔的二进制类名，用配置的类加载器加载 | `java.io.IOException,java.util.concurrent.TimeoutException` |

时长语法与注解中的相同。

## 环境变量 :id=environment-variables

取出键，转换为大写，把每个不是字母或数字的字符替换为 `_`，再加上前缀 `R4C_`（或 `RESILIENCE4CATA_`）：

| 键 | 环境变量 |
|---|---|
| `configs.external.retry.maxAttempts` | `R4C_CONFIGS_EXTERNAL_RETRY_MAXATTEMPTS` |
| `configs.slow-external.timeout.value` | `R4C_CONFIGS_SLOW_EXTERNAL_TIMEOUT_VALUE` |
| `instances.shipping.circuitBreaker.waitInOpen` | `R4C_INSTANCES_SHIPPING_CIRCUITBREAKER_WAITINOPEN` 或 `R4C_INSTANCES_SHIPPING_CIRCUIT_BREAKER_WAIT_IN_OPEN` |
| `profiles.active` | `R4C_PROFILES_ACTIVE` |

策略名和属性名中的单词可以连写，也可以用 `_` 分隔。一个变量必须恰好对应一个键；当 `_` 既可能代表 `.` 也可能代表 `-` 且有两个键都符合时，就是错误（[`R4C-CFG-010`](../errors.md#r4c-cfg-010)）：请改在文件或系统属性中设置该键。只有当实例名由小写字母、数字和连字符组成时，才能从环境变量中指定实例（`R4C_INSTANCES_PAYMENTS_API_RETRY_MAXATTEMPTS` 设置 `instances.payments-api.retry.maxAttempts`）；派生 id 需要文件或系统属性，例如 `-Dr4c.instances."com.acme.Api#get(java.lang.String)".retry.maxAttempts=5`。

## Profile :id=profiles

profile 把只在某些环境中生效的键归为一组。`profiles.<profile>.` 下的键替换不带 profile 的同一个键；它们本身并不增加一个优先级层次。

<!-- snippet: configuration-profiles -->
```java
ResilienceConfig config = ResilienceConfig.builder()
        .classpath("resilience4cata.properties")
        .profiles("test")                         // or profiles.active=test, or R4C_PROFILES_ACTIVE=test
        .build();
// config.activeProfiles() == [test]; configs.external.retry.maxAttempts is now 1
```

可以同时激活多个 profile：`profiles.active=test,local`。当两个激活的 profile 设置同一个键时，后面的胜出。

## 优先级 :id=precedence

对于每个实例的每个属性，优先级从低到高：

1. 内置默认值（每个[策略页面](../policies/README.md)都列出了它们）。
2. `defaults.<policy>.*`，或代码中的 `retryDefaults(...)` 及同类方法。
3. 注解通过 `config = "…"` 选择的命名配置：先是代码中的 `retry("<name>", ...)`，然后是 `configs.<name>.*`，逐个属性（`extends` 链从根开始）。
4. 注解自身的属性。
5. `instances.<id>.*`，或代码中的 `retryInstance(id, ...)` 及同类方法，但带有 `locked = true` 的注解的属性除外（[见下文](#locking-an-instance)）。

因此，**注解优先于命名配置**，**实例键优先于注解**：运维人员无需重新编译即可重新调整单个方法。按实例的覆盖在实例创建时应用一次。

代码与外部配置按如下方式组合：

- 在两边都定义的命名配置会被合并，外部的值逐个属性地胜出，与调用顺序无关（`applyTo` 用 `retryOverlay(...)` 及同类方法注册它们，你也可以自己调用这些方法）：

  <!-- snippet: configuration-merge -->
  ```java
  ResilienceRegistry registry = ResilienceConfig.load()
          .applyTo(ResilienceRegistry.builder()
                  .retry("external", r -> r.maxAttempts(9).maxDuration(Duration.ofSeconds(5))))
          .build();
  // registry.retryConfig("external"): maxAttempts 4 (the file wins), maxDuration 5s (only the code sets it)
  ```

- 在 `applyTo` 之后在构建器上调用的 `retryInstance(...)` 及其他 `*Instance` 调用优先于外部的 `instances.*` 键。
- 在 `applyTo` 之后调用的 `retryDefaults(...)` 及其他 `*Defaults` 调用会**替换**该策略的外部默认值，而不是与之合并。

## 锁定实例 :id=locking-an-instance

若要保留运维人员不得修改的值，请在注解上添加 `locked = true`。这样，注解所设置的属性（直接设置、在类型级别设置或通过[组合注解](bundles.md)设置）会优先于 `instances.<id>.*` 键和 `retryInstance(...)` 调用；注解未设置的属性仍然可以被覆盖：

<!-- snippet: configuration-locked-client -->
```java
public interface PaymentsClient {

    @CircuitBreaker(name = "payments", failureRateThreshold = 30, locked = true)
    String charge(String order);
}
```

<!-- snippet: configuration-locked -->
```java
Properties ops = new Properties();
ops.setProperty("instances.payments.circuitBreaker.failureRateThreshold", "80");   // locked: ignored
ops.setProperty("instances.payments.circuitBreaker.waitInOpen", "20s");            // not in the annotation: applied
ResilienceConfig config = ResilienceConfig.builder().properties("ops.properties", ops).build();
ResilienceRegistry registry = config.toRegistry();
PaymentsClient payments = Resilient.of(PaymentsClient.class, order -> "paid", registry);
// registry.circuitBreaker("payments").config(): failureRateThreshold 30, waitInOpen 20s
assertThatThrownBy(() -> config.verifyInstances(registry))
        .hasMessageContaining("[R4C-CFG-014] 'instances.payments.circuitBreaker.failureRateThreshold'");
```

被忽略的覆盖不会悄无声息：注册表在创建实例时记录一条警告（`System.Logger`，名称 `dev.resilience4cata.ResilienceRegistry`），`config.verifyInstances(registry)` 报告每个被忽略的键及其来源（[`R4C-CFG-014`](../errors.md#r4c-cfg-014)），`registry.lockedOverrides()` 列出相关的实例。方法可以用 `locked = false` 解锁其类型所锁定的内容。命名配置和默认值不受影响：注解本来就优先于它们。对于 [pipeline](pipeline.md)，`Pipeline.Builder.locked()` 锁定在其各步骤上设置的值。

## 校验与错误 :id=validation-and-errors

`build()` 根据内置默认值校验所有内容：未知的键、无法解析的值、未知的异常类、错误的 `extends`、无效的名称以及无法共同生效的组合（例如比 `delay` 更短的 `maxDelay`）。所有问题都被收集到一个 `ConfigException` 中（一个 `IllegalArgumentException`，列表在 `problems()` 中），每个问题都带有错误码、键、来源（文件和行号、环境变量或系统属性）以及最接近的有效键。下面这个文件有三处错误：

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

消息（节选）：

```text
Invalid resilience4cata configuration (3 problems):
  [R4C-CFG-001] unknown key 'configs.external.circuit-breaker.wait-in-opne' (classpath:…/broken.properties, line 1). Did you mean 'configs.external.circuit-breaker.wait-in-open'? …
  [R4C-CFG-002] 'defaults.retry.delay' (classpath:…/broken.properties, line 2) = "fast" is not a valid duration: …
  [R4C-CFG-007] 'configs.batch.extends' (classpath:…/broken.properties, line 3) extends config 'extrenal', which is not defined in external configuration …
```

实例只有在服务被包装后才存在，因此 `build()` 无法知道某个 `instances.<id>` 键是否指向真实的实例。请在包装服务之后调用 `config.verifyInstances(registry)`：对于每个不匹配任何实例的覆盖，它都会以 [`R4C-CFG-013`](../errors.md#r4c-cfg-013) 失败，并给出最接近的 id。同时涉及实例键和注解值的组合（例如比注解的 `maxDelay` 更长的实例 `delay`）在实例创建时检查（[`R4C-REG-003`](../errors.md#r4c-reg-003)）。

每个 `R4C-CFG` 错误码都在[错误参考](../errors.md#runtime-external-configuration-r4c-cfg)中有解释。

## 查看结果 :id=inspecting-the-result

`config.describe()` 列出每个生效的值及其来源，每行一个：

```text
active profiles: []
defaults.retry.delay = 50ms  <- 'defaults.retry.delay' (classpath:resilience4cata.properties, line 4)
configs.batch.retry.maxAttempts = 4  <- 'configs.external.retry.maxAttempts' (classpath:resilience4cata.properties, line 8)
instances.shipping.circuitBreaker.failureRateThreshold = 30  <- 'instances.shipping.circuitBreaker.failureRateThreshold' (classpath:resilience4cata.properties, line 17)
…
```

`activeProfiles()`、`configNames()` 和 `instanceIds()` 逐一返回各个部分。服务被包装之后，`registry.snapshot()` 会显示每个实例解析后的配置（[可观测性](observability.md)）。

## 限制 :id=limits

- 没有热重载，也没有 `reconfigure()`：注册表构建完成后，配置就不可变。
- 不支持 YAML 或其他格式；请自行把它们读入 `Properties`，再通过 `properties(description, props)` 传入。
- `@Fallback`、分类器、`name` 和 `config` 无法在外部配置。
- `instances.<id>.*` 优先于注解，除非注解设置了 `locked = true`（[锁定实例](#locking-an-instance)）。
