# resilience4cata 1.0.0-RC2：在 1.0 之前试用

resilience4cata 的 API 已冻结：1.0.0-RC2 已发布到 Maven Central，除非你的反馈表明有必须修改的地方，否则 1.0.0 将以相同的 API 发布。

*2026 年 11 月 4 日 · 作者 [fabiangftech](https://github.com/fabiangftech)，resilience4cata 的作者*

## 候选版本在这里意味着什么 :id=what-a-release-candidate-means-here

到目前为止，每个次版本都可能改变 API，[变更日志](../changelog.md)会列出破坏了什么。从 1.0.0 开始这种情况将停止：同一主版本的次版本和补丁版本会保持[兼容性页面](../reference/compatibility.md)所列的一切继续可用，包括 Java API、由较旧处理器生成的代码、配置键、指标名称、端点的 JSON 以及错误码。

候选版本是以低成本修改这些内容的最后机会。构建已经把 API 当作冻结的：每个发布的模块都会与 Maven Central 上的 1.0.0-RC2 进行比较，不兼容的变更会让构建失败。如果你试用候选版本时觉得有什么别扭，比如名称、默认值、错误信息或缺少的扩展点，现在仍然可以修复。

## resilience4cata 是什么 :id=what-resilience4cata-is

resilience4cata 是一个面向 Java 21+ 的弹性库：重试、熔断器、限流器、超时、舱壁隔离（bulkhead）、降级（fallback），外加对冲请求和自适应并发限流。你在接口或类上加注解，注解处理器会在编译期在它旁边写出一个普通的 Java 类。没有代理、切面或 agent。生成的方法直接调用每个策略，一次成功的同步调用不分配任何内存。

> [!MEASURED]
> AWS `c7g.xlarge`（Graviton3），单线程，每次调用的 ns 数，基于 1.0.0-RC1 的代码（RC2 只改动了框架集成），与使用相同设置的 Resilience4j 2.4.0 对比。来自[首页中关于候选版本的数据行](../benchmarks.md#release-candidate)。

| 调用 | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|
| 重试，第一次尝试成功 | **1.4 ns**, 0 B | 22.9 ns, 0 B |
| 熔断器，每次调用都成功 | **1.9 ns**, 0 B | 89.9 ns, 0 B |
| 同步超时 | **23.6 ns**, 0 B | 8907.6 ns, 183 B |
| 舱壁隔离，有空闲槽位 | **17.3 ns**, 0 B | 20.1 ns, 0 B |

四个线程、每次调用都成功时，熔断器保持在 1.9 ns，而 Resilience4j 的则升至 849.5 ns。[第一篇文章](compile-time-resilience.md)解释了差异从何而来。

## 通往 1.0 的路上改了什么 :id=what-changed-on-the-way-to-10

在 API 冻结之前，四位评审者审阅了这个库：一位 API 设计者、一位性能评审者、一位新手，以及一位正在决定是否采用它的人。他们的发现以及对每一项的处理都在 [1.0 计划](../plan-1.0.md)中。简而言之：

- **最后的 API 变更。** 其中两项是不兼容的，且很小：`CircuitBreakerEvent.CallNotPermitted` 现在叫 `Rejected`，与其他策略的拒绝事件一致；两个对冲请求常量不再是公开的。承载调用结果的类型接受可空类型，这对 Kotlin 和 NullAway 用户有帮助。
- **把承诺写下来。** [兼容性页面](../reference/compatibility.md)说明了在一个主版本内什么保持兼容，以及什么可能在次版本中扩展，例如新的枚举常量、新的事件 record 组件或新的策略。
- **修复了四个竞态。** 它们分别在带 `maxWait` 的舱壁隔离、自适应限流、限流器和同步超时中。其中三个先被复现出来，靠的是重放交错执行的测试或 jcstress，这些测试都经过了变异检查；限流器的那个只能通过阅读代码来确认。CI 只在 x86 上运行 jcstress，所以还在 Graviton3 上运行了一次：全部 65 个测试在 2598 种配置下通过。
- **文档。** 新增了一份 [HTTP 调用的配方](../guides/http-calls.md)，带有重试和熔断器，并针对真实服务器做了测试；快速入门中新增了一个展示熔断器打开的步骤。[迁移指南](../migrate/from-resilience4j.md)现在把 Resilience4j 的指标名称对应到我们的指标名称。
- **RC2：缺少注解处理器会报错。** 注解是 `CLASS` 保留策略，所以如果处理器没有运行，没有任何东西会察觉，bean 会在不受保护的情况下运行。现在 Spring Boot 会以 [R4C-SPR-006](../errors.md#r4c-spr-006) 让启动失败，Micronaut 会以 [R4C-MN-007](../errors.md#r4c-mn-007) 让 bean 创建失败，Quarkus 会以 [R4C-QK-008](../errors.md#r4c-qk-008) 让构建失败。每条消息都会指出类型以及需要添加的依赖。

完整列表见变更日志：[RC2](../changelog.md#1.0.0-rc2) 和 [RC1](../changelog.md#1.0.0-rc1)。

## 试一试 :id=try-it

<!-- tabs:start -->

#### **Gradle**

<!-- not-compiled: 构建配置 -->
```groovy
dependencies {
    implementation 'dev.resilience4cata:resilience4cata-all:1.0.0-RC2'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor:1.0.0-RC2'
}
```

#### **Gradle (Kotlin)**

<!-- not-compiled: 构建配置 -->
```kotlin
dependencies {
    implementation("dev.resilience4cata:resilience4cata-all:1.0.0-RC2")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor:1.0.0-RC2")
}
```

#### **Maven**

<!-- not-compiled: 构建配置 -->
```xml
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-all</artifactId>
  <version>1.0.0-RC2</version>
</dependency>

<!-- in the maven-compiler-plugin <configuration> -->
<annotationProcessorPaths>
  <path>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-processor</artifactId>
    <version>1.0.0-RC2</version>
  </path>
</annotationProcessorPaths>
```

<!-- tabs:end -->

本站各页面的构建片段中仍然显示 0.13.0；请改写为 `1.0.0-RC2`。然后，[快速入门](../start/quickstart.md)大约五分钟就能保护一个不稳定的调用。对于应用，请从 [Spring Boot](../guides/spring.md)、[Micronaut](../guides/micronaut.md) 或 [Quarkus](../guides/quarkus.md) 的指南开始。如果你目前在使用 Resilience4j，[迁移指南](../migrate/from-resilience4j.md)会把它的注册表、配置、装饰器和 Spring 注解对应到 resilience4cata。

## 什么最有帮助 :id=what-helps-most

任何你希望在 1.0 之后修改、却因为会破坏兼容性而无法修改的东西：

- 让你感到意外的名称、默认值或注解属性；
- 没有告诉你该怎么做的错误信息；
- 没有像指南所说那样工作的框架设置；
- 你需要却不得不绕过去的东西。

请在 GitHub 上提交 [issue](https://github.com/fabiangftech/resilience4cata/issues) 或发起[讨论](https://github.com/fabiangftech/resilience4cata/discussions)。"我试了，直接就能用"也是有用的反馈。安全问题请通过私下报告提交，详见 [SECURITY.md](https://github.com/fabiangftech/resilience4cata/blob/main/SECURITY.md)。

## 接下来 :id=what-comes-next

如果没有需要修改的，1.0.0 将以相同的 API 发布，兼容性承诺随之开始生效。如果确实有需要修改的，会先再发布一个候选版本。
