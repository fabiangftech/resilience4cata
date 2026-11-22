# 博客

介绍 resilience4cata 如何构建、测量了什么以及如何用好它的文章。

## resilience4cata 1.0.0-RC4：你自己的舱壁隔离不再打开熔断器 :id=resilience4cata-100-rc4-your-own-bulkhead-no-longer-opens-the-breaker

*2026 年 11 月 14 日*

试点运行发现，在冷启动时，熔断器会因方法自身已满的舱壁隔离（bulkhead）而打开，而后端没有任何失败。为什么这是一个陷阱，RC4 中的规则，以及在新代码上的试点运行。[阅读文章](release-candidate-4.md)

## 在 ARM 和 x86 上测试无锁代码 :id=testing-lock-free-code-on-arm-as-well-as-x86

*2026 年 11 月 10 日*

并发测试现在在每次合并后都会在 ARM 和 x86 上运行：为什么仅靠 x86 无法发现被削弱的内存顺序，各自三次运行显示了什么，以及为什么它们发现的那个竞态在 x86 上同样常见。[阅读文章](jcstress-on-arm-and-x86.md)

## 当对冲请求争用同一个服务时 :id=hedging-when-the-hedges-compete-for-the-same-service

*2026 年 11 月 5 日*

对一个共享服务模拟 `@Hedge`，负载从 30 % 到 95 %：对冲请求何时能削减长尾，何时会压垮服务，以及预算、自适应限流和取消带来的变化。[阅读文章](hedging-under-contention.md)

## resilience4cata 1.0.0-RC2：在 1.0 之前试用 :id=resilience4cata-100-rc2-try-it-before-10

*2026 年 11 月 4 日*

1.0 的 API 已冻结：候选版本（RC）在这里意味着什么，一路上改了什么，如何试用，以及哪些反馈仍可能改变 1.0.0。[阅读文章](release-candidate.md)

## 弹性注解在 Spring、Micronaut 和 Quarkus 中的开销 :id=what-a-resilience-annotation-costs-in-spring-micronaut-and-quarkus

*2026 年 11 月 1 日*

每个框架交给应用的 bean，与 Resilience4j 的切面和拦截器以及 SmallRye Fault Tolerance 的对比测量，外加 resilience4cata 没有胜出的六个场景以及对对冲请求所做的改进。[阅读文章](framework-annotations.md)

## 1.9 ns 的熔断器：在编译期生成的弹性代码 :id=a-19-ns-circuit-breaker-resilience-generated-at-compile-time

*2026 年 10 月 27 日*

为什么装饰器在每次调用时都有开销，注解处理器转而写出的是什么，以及 resilience4cata 与 Resilience4j 2.4.0 的对比，包括持平的场景和注意事项。[阅读文章](compile-time-resilience.md)
