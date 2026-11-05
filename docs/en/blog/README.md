# Blog

Articles about how resilience4cata is built, what it measures, and how to use it well.

## Hedging when the hedges compete for the same service

*5 November 2026*

A simulation of `@Hedge` against one shared service, at loads from 30 % to 95 %: when hedging cuts the tail, when it collapses the service, and what the budget, the adaptive limit and cancellation change. [Read the article](hedging-under-contention.md)

## resilience4cata 1.0.0-RC2: try it before 1.0

*4 November 2026*

The API is frozen for 1.0: what a release candidate means here, what changed on the way, how to try it, and which feedback can still change 1.0.0. [Read the article](release-candidate.md)

## What a resilience annotation costs in Spring, Micronaut and Quarkus

*1 November 2026*

The beans each framework hands to an application, measured against Resilience4j's aspects and interceptors and SmallRye Fault Tolerance, plus the six cases resilience4cata did not win and what was done about the hedge. [Read the article](framework-annotations.md)

## A 1.9 ns circuit breaker: resilience generated at compile time

*27 October 2026*

Why decorators cost something on every call, what an annotation processor writes instead, and how resilience4cata compares with Resilience4j 2.4.0, ties and caveats included. [Read the article](compile-time-resilience.md)
