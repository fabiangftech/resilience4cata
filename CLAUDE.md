# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

resilience4cata is an annotation-driven resilience library for plain Java 21+ (no Spring/CDI/AspectJ), designed to have lower overhead than Resilience4j. The project is in **phase 1: research and design**. There is no Java code or Gradle build yet; the repo only contains documentation under `docs/`.

Start with `docs/en/README.md` (decisions and open questions), then `docs/en/features.md` and `docs/en/architecture.md`. The detailed research behind them is in `docs/en/research/01–05`.

## Decisions to respect

- **Java 21 baseline** (`--release 21`), tested on 21 and 25. Do not use APIs that are preview or newer than 21 (e.g. `StructuredTaskScope`, the Class-File API) in published modules.
- **Gradle with Kotlin DSL**, convention plugins in an included build, version catalog in `gradle/libs.versions.toml`.
- **Modules have single-word names**: `annotations`, `core`, `processor`, `config`, `micrometer`, `testkit`, `bom`, plus unpublished `benchmark`, `stress`, `examples`. Never name a module `test` (clashes with Gradle's `test` task). Published artifactIds get the `resilience4cata-` prefix.
- **Annotations are handled by a compile-time annotation processor (`processor`) that generates plain Java source.** No runtime proxies, bytecode generation, AspectJ or agents in `core`.
- **`core` and `annotations` have zero external dependencies** (JDK only; JSpecify is the one open exception). `processor` is only ever an `annotationProcessor` dependency. Generated code may only use `core`'s exported generated-code API.
- **Generated code must be straight-line and per method**, calling the allocation-free permit protocol in `core` (acquire / onSuccess / onFailure). Do not route generated code through a shared lambda executor: our JMH run showed it doubles the overhead once several services share it (see `docs/en/research/02-annotation-mechanisms.md` §3).
- **Hot paths**: no allocation on success paths, no locks and no `synchronized` (pinning on JDK 21), one clock-read pair per call, preallocated stackless rejection exceptions, events allocated only when someone listens. Configuration is resolved once at instance creation, never per call.
- Default policy order, outermost first: `Fallback → Retry|Hedge → CircuitBreaker → RateLimiter → AdaptiveLimit → Timeout → Bulkhead → method`.

## Documentation rules

- Docs are plain Markdown, **bilingual**: `docs/en/**` and `docs/es/**` must have the same file tree. English is the source of truth; any change to an English doc must be mirrored in the Spanish one. Code and identifiers stay in English in both.
- `docs/` is a Docsify site, not a Gradle module. Sidebars are `docs/en/_sidebar.md` and `docs/es/_sidebar.md`; add new pages there. Keep Markdown tool-neutral (CommonMark/GFM plus Mermaid fences).
- Research docs tag claims as verified, measured or inferred. Keep that discipline, and never state a performance number for resilience4cata that has not been measured.
- Preview the site locally: `python3 -m http.server -d docs 3000` (or `npx docsify-cli serve docs`).
