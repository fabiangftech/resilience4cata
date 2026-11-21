# 配置键

`resilience4cata-config` 模块读取的每个键，及其环境变量、类型、内置默认值和有效值。

本页是参考文档；[配置指南](../guides/configuration.md)解释如何使用它。这些表格由一个测试（`config` 模块中的 `ConfigurationReferenceTest`）与代码进行核对，因此代码中某个键或默认值发生变化时，在本页跟进之前构建都会失败。

## 键的形式 :id=key-forms

每张表都列出 `defaults.<policy>.<attribute>` 形式。同样的 `<policy>.<attribute>` 部分适用于每种形式：

| 形式 | 作用于 | 示例 |
|---|---|---|
| `defaults.<policy>.<attribute>` | 该策略的每个实例 | `defaults.retry.maxAttempts=5` |
| `configs.<name>.<policy>.<attribute>` | 注解写有 `config = "<name>"` 的实例 | `configs.external.timeout.value=2s` |
| `configs.<name>.extends=<other>` | 继承另一个命名配置的所有策略 | `configs.batch.extends=external` |
| `instances.<id>.<policy>.<attribute>` | 单个实例：它的 `name`，或放在双引号中的派生 id | `instances."com.acme.Api#get(java.lang.String)".timeout.value=2s` |
| `profiles.<profile>.<any key above>` | 仅在该 profile 激活时 | `profiles.test.defaults.retry.maxAttempts=1` |
| `profiles.active=<a>,<b>` | 激活的 profile，靠后的优先 | `profiles.active=test` |

- 策略：`retry`、`hedge`、`circuitBreaker`、`rateLimiter`、`adaptiveLimit`、`timeout`、`bulkhead`。`@Fallback` 没有键。
- 策略名和属性名可以写成 camelCase 或 kebab-case：`circuit-breaker.wait-in-open` 即 `circuitBreaker.waitInOpen`。
- 配置名和 profile 名由小写字母和数字组成，以单个连字符分隔（`slow-external`）。
- 实例 id 区分大小写。除非它只由字母、数字、`_` 和 `-` 组成，否则必须放在双引号中。派生 id 为 `<type>#<method>(<parameter types>)`，参数类型为全限定、已擦除的类型，以逗号分隔且不带空格，例如 `com.acme.CatalogClient#price(java.lang.String,int)`。
- 每个键都可以以 `r4c.` 或 `resilience4cata.` 开头。在**系统属性中前缀是必需的**；其他系统属性会被忽略。
- 使用短前缀（`r4c.`，或环境变量中的 `R4C_`）的键，如果其第一段不是 `defaults`、`configs`、`instances` 或 `profiles`，也与它们都不相近，则属于其他工具并被忽略：`-Dr4c.debug=true` 或 `R4C_TOKEN` 不会让应用停止。看起来像是我们的键（`r4c.defualts.…`）仍然是错误（[R4C-CFG-001](../errors.md#r4c-cfg-001)），任何使用长前缀或不带前缀的未知键也是如此。
- 有些属性只存在于注解上，没有对应的键：`name`、`config`、`locked`、`classifier`、`onExhaustedResult`，以及 `@Fallback` 上的一切。

## 环境变量 :id=environment-variables

`R4C_`（或 `RESILIENCE4CATA_`）后跟大写的键，其中每个非字母、非数字的字符都替换为 `_`。表格列出了每个 `defaults.` 键对应的变量；其他形式遵循同样的规则：

| 键 | 变量 |
|---|---|
| `configs.external.retry.maxAttempts` | `R4C_CONFIGS_EXTERNAL_RETRY_MAXATTEMPTS` |
| `configs.slow-external.timeout.value` | `R4C_CONFIGS_SLOW_EXTERNAL_TIMEOUT_VALUE` |
| `instances.payments-api.retry.maxAttempts` | `R4C_INSTANCES_PAYMENTS_API_RETRY_MAXATTEMPTS` |
| `profiles.active` | `R4C_PROFILES_ACTIVE` |

单词也可以分开写（`R4C_DEFAULTS_CIRCUIT_BREAKER_WAIT_IN_OPEN`）。一个变量必须恰好匹配一个键（[R4C-CFG-010](../errors.md#r4c-cfg-010)）。只有当实例名称由小写字母、数字和连字符组成时，才能通过环境变量设置该实例；派生 id 需要使用文件或系统属性。

## 值 :id=values

| 类型 | 语法 |
|---|---|
| 时长 | 数字加单位：`ns`、`us`、`ms`、`s`、`m`、`h`、`d`（`250ms`、`2s`）；ISO-8601 时长（`PT2S`、`PT1M30S`）；或 `0`。不允许小数、负值和空格。语法与注解相同。 |
| int | 整数（`5`） |
| decimal | 数字，如 `50` 或 `2.5` |
| boolean | `true` 或 `false` |
| enum | 所列值之一，不区分大小写（`exponential`、`EXPONENTIAL`） |
| 类列表 | 以逗号分隔的异常类二进制名称（`java.io.IOException, java.net.SocketTimeoutException`），用配置的类加载器加载；嵌套类写作 `a.B$C` 或 `a.B.C`；为空表示没有 |

组合也会被检查：`maxDelay` 不能短于 `delay`，限流器的 `period` 必须至少为每个许可一纳秒（[R4C-CFG-006](../errors.md#r4c-cfg-006)）。

## 来源与优先级 :id=precedence

`ResilienceConfig.load()` 按从低到高的顺序读取：classpath 资源 `resilience4cata.properties`（可选）、环境变量、系统属性。使用 `ResilienceConfig.builder()` 时由你选择来源；对于同一个键，它们的排序为 classpath 资源 < 文件和 `Properties` < 环境变量 < 系统属性，同类来源中最后添加的优先。在一个来源内，处于激活 profile 下的键优先于不带 profile 的同一个键；而更强的来源甚至优先于较弱来源中的 profile 键。

然后各个值按从低到高的顺序组合：

1. 内置默认值（下表中的**默认值**列）；
2. `defaults.*`（以及代码中的 `ResilienceRegistry.Builder.*Defaults(...)`）；
3. `configs.<name>.*`，从 `extends` 链的根开始；外部键会逐个属性覆盖在代码中注册的同名配置；
4. 注解属性；
5. `instances.<id>.*`，这样运维人员无需重新编译就能重新调整某个实例，但 `locked = true` 的注解的属性除外，它们保留注解的值（[锁定实例](../guides/configuration.md#locking-an-instance)）。

一切都在创建实例时解析一次。之后不会重新加载任何内容。要检查每个 `instances.<id>` 键都对应一个实例，请在包装好你的服务后调用 `config.verifyInstances(registry)`（[R4C-CFG-013](../errors.md#r4c-cfg-013)）；它还会报告被锁定注解忽略的键（[R4C-CFG-014](../errors.md#r4c-cfg-014)）。

## 重试 :id=retry

`@Retry` 的属性。参见[重试](../policies/retry.md)。

| 键 | 环境变量 | 类型 | 默认值 | 有效值 | 含义 |
|---|---|---|---|---|---|
| `defaults.retry.enabled` | `R4C_DEFAULTS_RETRY_ENABLED` | boolean | `true` | `true`、`false` | `false` 让每次调用只运行一次，不重试。 |
| `defaults.retry.maxAttempts` | `R4C_DEFAULTS_RETRY_MAXATTEMPTS` | int | `3` | ≥ 1 | 总尝试次数，包括第一次。 |
| `defaults.retry.delay` | `R4C_DEFAULTS_RETRY_DELAY` | 时长 | `200ms` | ≥ 0 | 第一次重试之前的延迟；退避的基数。 |
| `defaults.retry.backoff` | `R4C_DEFAULTS_RETRY_BACKOFF` | enum | `exponential` | `fixed`、`exponential` | `fixed`：每次延迟都是 `delay`。`exponential`：`delay × multiplier^(n−1)`，上限为 `maxDelay`。 |
| `defaults.retry.multiplier` | `R4C_DEFAULTS_RETRY_MULTIPLIER` | decimal | `2.0` | 有限值，≥ 1 | 指数退避的增长因子。 |
| `defaults.retry.maxDelay` | `R4C_DEFAULTS_RETRY_MAXDELAY` | 时长 | `10s` | ≥ `delay` | 单次延迟的上限。 |
| `defaults.retry.jitter` | `R4C_DEFAULTS_RETRY_JITTER` | enum | `full` | `none`、`full`、`equal` | `full`：在 [0, d] 中随机。`equal`：d/2 加上随机的另一半。`none`：恰好为 d。 |
| `defaults.retry.maxDuration` | `R4C_DEFAULTS_RETRY_MAXDURATION` | 时长 | `0` | ≥ 0；`0` = 不限 | 整个操作的时间预算：如果等待下一次延迟会超出它，就放弃。 |
| `defaults.retry.retryOn` | `R4C_DEFAULTS_RETRY_RETRYON` | 类列表 | `java.lang.Exception` | 异常类 | 会被重试的失败，包括子类。默认不重试 `Error`。 |
| `defaults.retry.abortOn` | `R4C_DEFAULTS_RETRY_ABORTON` | 类列表 | — | 异常类 | 永不重试的失败；优先于 `retryOn` 和 classifier。`CallNotPermittedException`（熔断器打开）也从不重试，无论是否列出。 |
| `defaults.retry.budgetPercent` | `R4C_DEFAULTS_RETRY_BUDGETPERCENT` | decimal | `0` | 有限值，≥ 0 | 重试预算：允许的重试次数，以该实例在最近 `budgetWindow` 内调用次数的百分比表示，叠加在 `budgetMinRetriesPerSecond` 之上。大于 `0` 时启用预算；`0` 表示没有预算。 |
| `defaults.retry.budgetMinRetriesPerSecond` | `R4C_DEFAULTS_RETRY_BUDGETMINRETRIESPERSECOND` | int | `10` | ≥ 0 | 重试预算：一旦 `budgetPercent` 启用了预算，无论流量多少，`budgetWindow` 的每秒允许的重试次数。`0` 表示只使用百分比。 |
| `defaults.retry.budgetWindow` | `R4C_DEFAULTS_RETRY_BUDGETWINDOW` | 时长 | `10s` | 1s 到 1h | 重试预算：统计调用和重试的回溯时长。 |

## 对冲请求 :id=hedge

`@Hedge` 的属性。参见[对冲请求](../policies/hedge.md)。

| 键 | 环境变量 | 类型 | 默认值 | 有效值 | 含义 |
|---|---|---|---|---|---|
| `defaults.hedge.enabled` | `R4C_DEFAULTS_HEDGE_ENABLED` | boolean | `true` | `true`、`false` | `false` 让每次调用只运行一次，不发起对冲。 |
| `defaults.hedge.maxAttempts` | `R4C_DEFAULTS_HEDGE_MAXATTEMPTS` | int | `2` | 2 到 10 | 同时进行中的尝试数上限，包括第一次。 |
| `defaults.hedge.delay` | `R4C_DEFAULTS_HEDGE_DELAY` | 时长 | `1s` | ≥ 0；`0` = 同时全部发起 | 最近一次尝试在没有结果的情况下运行多久后启动下一次尝试。 |
| `defaults.hedge.hedgeOn` | `R4C_DEFAULTS_HEDGE_HEDGEON` | 类列表 | `java.lang.Exception` | 异常类 | 发生后立即启动下一次尝试的失败；未列出的失败会结束调用。 |
| `defaults.hedge.abortOn` | `R4C_DEFAULTS_HEDGE_ABORTON` | 类列表 | — | 异常类 | 立即结束调用并取消其他尝试的失败；优先于 `hedgeOn`。 |
| `defaults.hedge.budgetPercent` | `R4C_DEFAULTS_HEDGE_BUDGETPERCENT` | decimal | `10` | 有限值，≥ 0 | 对冲预算：允许的对冲次数，以该实例在最近 `budgetWindow` 内调用次数的百分比表示，叠加在 `budgetMinHedgesPerSecond` 之上。`0` 关闭预算。 |
| `defaults.hedge.budgetMinHedgesPerSecond` | `R4C_DEFAULTS_HEDGE_BUDGETMINHEDGESPERSECOND` | int | `1` | ≥ 0 | 对冲预算：无论流量多少，`budgetWindow` 的每秒允许的对冲次数。 |
| `defaults.hedge.budgetWindow` | `R4C_DEFAULTS_HEDGE_BUDGETWINDOW` | 时长 | `10s` | 1s 到 1h | 对冲预算：统计调用和对冲的回溯时长。 |

## 熔断器 :id=circuit-breaker

`@CircuitBreaker` 的属性。参见[熔断器](../policies/circuit-breaker.md)。

| 键 | 环境变量 | 类型 | 默认值 | 有效值 | 含义 |
|---|---|---|---|---|---|
| `defaults.circuitBreaker.enabled` | `R4C_DEFAULTS_CIRCUITBREAKER_ENABLED` | boolean | `true` | `true`、`false` | `false`：熔断器以 `DISABLED` 状态启动，放行每次调用且不记录任何内容。 |
| `defaults.circuitBreaker.windowType` | `R4C_DEFAULTS_CIRCUITBREAKER_WINDOWTYPE` | enum | `count` | `count`、`time` | `count`：最近 `windowSize` 次调用。`time`：最近 `windowDuration` 内的调用（每次调用都读取时钟）。 |
| `defaults.circuitBreaker.windowSize` | `R4C_DEFAULTS_CIRCUITBREAKER_WINDOWSIZE` | int | `100` | ≥ 1 | `count` 窗口中的调用数；`time` 窗口忽略它。 |
| `defaults.circuitBreaker.windowDuration` | `R4C_DEFAULTS_CIRCUITBREAKER_WINDOWDURATION` | 时长 | `60s` | 整秒，≥ `1s` | `time` 窗口的长度；`count` 窗口忽略它。 |
| `defaults.circuitBreaker.minimumCalls` | `R4C_DEFAULTS_CIRCUITBREAKER_MINIMUMCALLS` | int | `100` | ≥ 1 | 窗口中达到多少次调用后才计算比率（对计数窗口，上限为 `windowSize`）。 |
| `defaults.circuitBreaker.failureRateThreshold` | `R4C_DEFAULTS_CIRCUITBREAKER_FAILURERATETHRESHOLD` | decimal | `50` | > 0 且 ≤ 100 | 使熔断器打开的失败率（百分比）。 |
| `defaults.circuitBreaker.slowCallRateThreshold` | `R4C_DEFAULTS_CIRCUITBREAKER_SLOWCALLRATETHRESHOLD` | decimal | `100` | > 0 且 ≤ 100 | 使熔断器打开的慢调用率（百分比）。 |
| `defaults.circuitBreaker.slowCallDuration` | `R4C_DEFAULTS_CIRCUITBREAKER_SLOWCALLDURATION` | 时长 | `0` | ≥ 0；`0` = 关闭 | 比这更长的调用为慢调用。只有设置了它，才会为调用计时。 |
| `defaults.circuitBreaker.waitInOpen` | `R4C_DEFAULTS_CIRCUITBREAKER_WAITINOPEN` | 时长 | `60s` | ≥ 0 | 熔断器保持打开的时长；之后的下一次调用是半开探测调用。 |
| `defaults.circuitBreaker.halfOpenCalls` | `R4C_DEFAULTS_CIRCUITBREAKER_HALFOPENCALLS` | int | `10` | ≥ 1 | 半开状态下放行的探测调用数。 |
| `defaults.circuitBreaker.maxWaitInHalfOpen` | `R4C_DEFAULTS_CIRCUITBREAKER_MAXWAITINHALFOPEN` | 时长 | `0` | ≥ 0；`0` = 不限 | 如果探测调用尚未完成，熔断器最多可以保持半开多久，之后再次打开。 |
| `defaults.circuitBreaker.recordOn` | `R4C_DEFAULTS_CIRCUITBREAKER_RECORDON` | 类列表 | `java.lang.Throwable` | 异常类 | 记录为失败的失败。其他一切都算作成功。 |
| `defaults.circuitBreaker.ignoreOn` | `R4C_DEFAULTS_CIRCUITBREAKER_IGNOREON` | 类列表 | — | 异常类 | 既不记录为成功也不记录为失败的失败；优先于 `recordOn` 和 classifier。 |

## 限流器 :id=rate-limiter

`@RateLimiter` 的属性。参见[限流器](../policies/rate-limiter.md)。

| 键 | 环境变量 | 类型 | 默认值 | 有效值 | 含义 |
|---|---|---|---|---|---|
| `defaults.rateLimiter.enabled` | `R4C_DEFAULTS_RATELIMITER_ENABLED` | boolean | `true` | `true`、`false` | `false` 放行每次调用。 |
| `defaults.rateLimiter.permits` | `R4C_DEFAULTS_RATELIMITER_PERMITS` | int | `50` | ≥ 1 | 每个 `period` 内的调用数，也是最大突发量。 |
| `defaults.rateLimiter.period` | `R4C_DEFAULTS_RATELIMITER_PERIOD` | 时长 | `1s` | > 0 且 ≥ `permits` ns | 周期的长度；许可之间间隔 `period / permits`。 |
| `defaults.rateLimiter.maxWait` | `R4C_DEFAULTS_RATELIMITER_MAXWAIT` | 时长 | `0` | ≥ 0；`0` = 立即拒绝 | 调用在抛出 `RateLimitExceededException` 之前最多可以等待许可多久。 |

## 自适应限流 :id=adaptive-limit

`@AdaptiveLimit` 的属性，以及算法的调优参数，这些参数没有对应的注解属性（`minWindowSamples`、`smoothing`、`rttTolerance`、`longWindow`、`queueSize`、`backoffRatio`）。参见[自适应限流](../policies/adaptive-limit.md)。

| 键 | 环境变量 | 类型 | 默认值 | 有效值 | 含义 |
|---|---|---|---|---|---|
| `defaults.adaptiveLimit.enabled` | `R4C_DEFAULTS_ADAPTIVELIMIT_ENABLED` | boolean | `true` | `true`、`false` | `false` 不限制并发调用。 |
| `defaults.adaptiveLimit.algorithm` | `R4C_DEFAULTS_ADAPTIVELIMIT_ALGORITHM` | enum | `gradient2` | `gradient2`、`aimd` | `gradient2`：把每个窗口的延迟与长期平均值比较。`aimd`：繁忙窗口之后增加一个槽位，出现 drop 之后乘以 `backoffRatio`。 |
| `defaults.adaptiveLimit.initialLimit` | `R4C_DEFAULTS_ADAPTIVELIMIT_INITIALLIMIT` | int | `20` | `minLimit` 到 `maxLimit` | 第一个窗口结束之前的上限。 |
| `defaults.adaptiveLimit.minLimit` | `R4C_DEFAULTS_ADAPTIVELIMIT_MINLIMIT` | int | `1` | ≥ 1 | 上限能降到的最低值。 |
| `defaults.adaptiveLimit.maxLimit` | `R4C_DEFAULTS_ADAPTIVELIMIT_MAXLIMIT` | int | `200` | ≥ `minLimit` | 上限能升到的最高值。 |
| `defaults.adaptiveLimit.window` | `R4C_DEFAULTS_ADAPTIVELIMIT_WINDOW` | 时长 | `1s` | 10ms 到 1h | 一个窗口至少持续多久；上限在窗口结束时重新计算。 |
| `defaults.adaptiveLimit.minWindowSamples` | `R4C_DEFAULTS_ADAPTIVELIMIT_MINWINDOWSAMPLES` | int | `10` | ≥ 1 | 一个窗口结束前需要的已完成调用数；不足的窗口会再延长一个 `window`，并保留其调用。 |
| `defaults.adaptiveLimit.dropOn` | `R4C_DEFAULTS_ADAPTIVELIMIT_DROPON` | 类列表 | `dev.resilience4cata.CallTimeoutException, dev.resilience4cata.BulkheadFullException, java.util.concurrent.TimeoutException` | 异常类 | 表示依赖已过载的失败：带有 drop 的样本（`aimd` 会退避）。其他任何失败都不算样本。 |
| `defaults.adaptiveLimit.ignoreOn` | `R4C_DEFAULTS_ADAPTIVELIMIT_IGNOREON` | 类列表 | — | 异常类 | 永远不算样本的失败；优先于 `dropOn`。 |
| `defaults.adaptiveLimit.smoothing` | `R4C_DEFAULTS_ADAPTIVELIMIT_SMOOTHING` | decimal | `0.2` | > 0 且 ≤ 1 | `gradient2`：每个新估计值保留多少比例。 |
| `defaults.adaptiveLimit.rttTolerance` | `R4C_DEFAULTS_ADAPTIVELIMIT_RTTTOLERANCE` | decimal | `1.5` | 有限值，≥ 1 | `gradient2`：一个窗口的延迟可以达到长期延迟的多少倍，超过后上限就会下调。 |
| `defaults.adaptiveLimit.longWindow` | `R4C_DEFAULTS_ADAPTIVELIMIT_LONGWINDOW` | int | `600` | ≥ 1 | `gradient2`：计入长期延迟平均值的窗口数。 |
| `defaults.adaptiveLimit.queueSize` | `R4C_DEFAULTS_ADAPTIVELIMIT_QUEUESIZE` | int | `4` | ≥ 0 | `gradient2`：上限在估计的并发数之上留出的余量，以便它能够增长。仅凭延迟不会让上限降到远低于该值的两倍。 |
| `defaults.adaptiveLimit.backoffRatio` | `R4C_DEFAULTS_ADAPTIVELIMIT_BACKOFFRATIO` | decimal | `0.9` | ≥ 0.5 且 < 1 | `aimd`：出现 drop 的窗口把上限乘以的系数。 |

## 超时 :id=timeout

`@Timeout` 的属性。注解的 `value` 对应键 `timeout.value`。`@Timeout` 没有 `name`，因此 `instances.<id>.timeout.*` 总是使用派生 id。参见[超时](../policies/timeout.md)。

| 键 | 环境变量 | 类型 | 默认值 | 有效值 | 含义 |
|---|---|---|---|---|---|
| `defaults.timeout.enabled` | `R4C_DEFAULTS_TIMEOUT_ENABLED` | boolean | `true` | `true`、`false` | `false` 从不让调用超时。 |
| `defaults.timeout.value` | `R4C_DEFAULTS_TIMEOUT_VALUE` | 时长 | `1s` | > 0 | 一次尝试最多可以花费多久（`@Timeout("2s")`；代码中为 `TimeoutConfig.timeout()`）。 |

## 舱壁隔离 :id=bulkhead

`@Bulkhead` 的属性。参见[舱壁隔离](../policies/bulkhead.md)。

| 键 | 环境变量 | 类型 | 默认值 | 有效值 | 含义 |
|---|---|---|---|---|---|
| `defaults.bulkhead.enabled` | `R4C_DEFAULTS_BULKHEAD_ENABLED` | boolean | `true` | `true`、`false` | `false` 不限制并发调用。 |
| `defaults.bulkhead.maxConcurrent` | `R4C_DEFAULTS_BULKHEAD_MAXCONCURRENT` | int | `25` | ≥ 1 | 同时运行的调用数。 |
| `defaults.bulkhead.maxWait` | `R4C_DEFAULTS_BULKHEAD_MAXWAIT` | 时长 | `0` | ≥ 0；`0` = 立即拒绝 | 调用在抛出 `BulkheadFullException` 之前最多可以等待空闲槽位多久。 |

## 错误 :id=errors

配置中的所有问题会被一起报告，放在一个 `ConfigException` 中，带有 `R4C-CFG-nnn` 错误码、键、它的来源（文件和行号、环境变量或系统属性）以及最接近的有效键。参见[错误码](../errors.md#r4c-cfg-001)。
