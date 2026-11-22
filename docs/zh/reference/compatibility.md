# 兼容性

从 1.0.0 起，在同一主版本内升级时哪些内容保持可用，以及次版本中可能新增哪些内容。

resilience4cata 遵循[语义化版本](https://semver.org/spec/v2.0.0.html)。在 1.0.0 之前，次版本可能更改 API，[更新日志](../changelog.md)会列出每一项不兼容的更改。从 1.0.0 起，同一主版本的次版本和补丁版本会保持本页所列的一切兼容；只有新的主版本才能破坏它们。

## Java API :id=java-api

API 是指已发布模块所导出的包中的每个 public 和 protected 成员（`dev.resilience4cata`、`dev.resilience4cata.event`、`dev.resilience4cata.config`、`dev.resilience4cata.testkit` 等；Spring Boot 为 `dev.resilience4cata.spring`；Quarkus 为 `dev.resilience4cata.quarkus`；Micronaut 的 jar 没有 `module-info`，为 `dev.resilience4cata.micronaut` 的 public 类型）。在一个主版本内，它保持二进制兼容和源码兼容。构建会在每次更改时检查这一点：它把每个模块与上一个版本对比，从 1.0.0 起，遇到不兼容的更改就会失败。

不属于 API 的内容：

- 标记为 `@Internal` 的成员：它们是 public 的，只是为了让 resilience4cata 的另一个模块或 `ServiceLoader` 能够访问它们。
- 标记为 `@Experimental` 的成员：在稳定下来之前，它们可能在任何次版本中更改。目前没有任何内容标记为 `@Experimental`。
- 模块不导出的包，如 `dev.resilience4cata.internal`，以及仅为框架需要而导出或设为 public 的包：Spring Boot 的 `dev.resilience4cata.spring.autoconfigure`（导出它是为了让 `exclude` 能引用它）和 Quarkus 的 `dev.resilience4cata.quarkus.runtime`（扩展的 bean 和配置根）。对于自动配置，保持不变的是类名（用于 `exclude`）、bean 名称和 `resilience4cata.spring.*` 属性名。
- `dev.resilience4cata.generated` 和 `dev.resilience4cata.reactor.generated`：它们只为生成的代码而导出。不要在你自己的代码中调用它们。

## 生成的代码 :id=generated-code

处理器 `1.a` 生成的代码可以在任何 `b >= a` 的 core（以及 reactor）`1.b` 上运行。因此，用 resilience4cata 编译的库，在使用它的应用于同一主版本内升级 resilience4cata 后仍能继续工作。反方向不受支持：在更旧的运行时上，生成的构造函数会立即以 [R4C-RT-021](../errors.md#r4c-rt-021) 失败。导入 BOM 可以让版本保持一致。生成的源码文本本身不属于契约，可能在任何版本中更改。

## Java 之外 :id=beyond-java

以下内容在一个主版本内同样保持不变：

- 注解及其属性，以及[配置键](configuration.md)及其环境变量形式。
- 各集成的属性名：`resilience4cata.spring.*`、`resilience4cata.micronaut.*` 和 `quarkus.resilience4cata.*`。
- 指标名称和标签，在 Micrometer 和 OpenTelemetry 模块中相同（[指标名称](../guides/observability.md#metric-names)）。名称保持点分形式，每个 meter 都保留其 `name` 标签，熔断器的比率保持为百分比。
- Spring Boot 和 Micronaut 端点的 JSON，即 `ResilienceSnapshot.toMap()`（[快照](../guides/observability.md#snapshots)）。
- 诸如 `R4C-ATR-001` 的错误码。错误码从不重新编号或重复使用，已停用的错误码在[错误参考](../errors.md)中保留其章节。消息文本可能在任何版本中改进，所以请按错误码匹配，而不是按文本匹配。

## 如何检查 :id=how-it-is-checked

每次对 resilience4cata 的更改，CI 都会运行以下检查：

- **Java API**：用 japicmp 把每个已发布模块与上一个版本进行对比。
- **生成的代码**：端到端测试使用 Maven Central 上上一个版本的处理器、注解和模块来编译，并在新模块上运行。
- **配置文件**：一个为上一个版本编写的文件，使用了该版本所知的每个键（每个策略的每个属性、带 `extends` 的命名配置、实例、一个 profile），由 Maven Central 上的该版本和新代码分别读取。新代码必须不报告任何问题、不记录任何警告，并且必须把每项配置解析为相同的值。
- **本页上的其他一切**：两个指标模块中的指标名称、类型、单位和标签，端点的 JSON，配置键及其环境变量和值，各集成的属性名（以及 Spring Boot 的自动配置和 bean 名称），以及错误码，都会与仓库 [`contract`](https://github.com/fabiangftech/resilience4cata/tree/main/contract) 目录中的文件进行对比。次版本可以在其中添加条目；删除或更改条目会让构建失败。

## 次版本中可能新增的内容 :id=growth

新增是兼容的，因此次版本可能做以下任何事情。请编写能够容忍这些变化的代码：

- 可能新增**枚举常量**（`CircuitBreakerState`、`RetryEvent.Reason`、`Classifier.Outcome` 等）。在对它们的 `switch` 中保留一个 `default` 分支。
- 可能新增**事件类型**，现有的事件 record 也可能新增组件。在对事件的 `switch` 中保留一个 `default` 分支，并通过访问器读取事件，而不是用解构它们的 record 模式。新增组件的 record 会保留其之前的构造函数，因此创建事件的代码（例如监听器的测试）仍能编译。
- 新增策略时，**sealed 类型**可能新增子类型：`PolicyConfig` 新增一种配置，`CallRejectedException` 新增一种拒绝，`ResilienceEvent` 新增一个事件接口。在对它们的 `switch` 中保留一个 `default` 分支。
- handle（`CircuitBreakerHandle` 等）和 `FallbackContext` 可能新增**方法**。这些接口由库来实现；不要自己实现或 mock 它们。
- 以后新增的 **`Ticker` 方法**将是 `default` 方法，因此你自己的实现仍能编译。
- 可能新增**注解属性**，并且总是带有默认值。
- **快照**可能新增组件，端点的 JSON 可能新增键。与事件一样，快照 record 会保留其之前的构造函数。忽略你不认识的键。
- 可能新增**指标**、错误码和配置键。
