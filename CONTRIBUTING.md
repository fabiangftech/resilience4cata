# Contributing to resilience4cata

Thanks for your interest. Bug reports, questions, documentation fixes and code are all welcome.

## Questions, bugs and ideas

- **Questions and ideas:** [GitHub Discussions](https://github.com/fabiangftech/resilience4cata/discussions).
- **Bugs:** [open an issue](https://github.com/fabiangftech/resilience4cata/issues/new/choose) with the `R4C-…` error code if there is one, the annotation or configuration involved, and the versions of Java and resilience4cata. A small failing example helps most.
- **Security problems:** please do not open a public issue; use [a private vulnerability report](https://github.com/fabiangftech/resilience4cata/security/advisories/new) instead.

## Building

You need a JDK 17 or newer to run Gradle; the build compiles with Java 21 (and Java 25 for the `micronaut` module) through toolchains, which Gradle finds on your machine.

```bash
./gradlew build                                  # compile, test and javadoc every module
./gradlew :core:test --tests 'dev.resilience4cata.SomeTest'   # one test class
./gradlew :examples:test                         # end-to-end tests against real generated code
./gradlew :site:serve                            # preview the documentation site on http://localhost:3000
```

Benchmarks (`:benchmark:jmh`) and concurrency stress tests (`:stress:jcstress`) are described in [CLAUDE.md](CLAUDE.md), which is also the most complete description of how the code fits together.

## Pull requests

- Keep each pull request to one change, and say why it is needed.
- **Tests:** new behaviour comes with tests. Races are tested deterministically through package-private seams, and lock-free code with jcstress.
- **Hot paths:** no allocation on success paths, no locks, no clock reads unless the feature needs them. If you touch one, include JMH numbers before and after (single-thread and four threads); the allocation gate in CI checks bytes per call.
- **Errors:** every error has a stable `R4C-…` code with a section in both `docs/en/errors.md` and `docs/es/errors.md`.
- **Documentation is bilingual:** a change to a page under `docs/en/` needs the same change under `docs/es/`. Code blocks on the site are tested: they come from regions of files under `examples/src/test` (see `DocsTest`).
- **Performance claims** in docs must cite a measured result in `docs/en/benchmarks.md`.
- The build runs with `-Xlint:all -Werror`; generated code must compile without warnings.

## License

By contributing you agree that your contributions are licensed under the [Apache License 2.0](LICENSE).
