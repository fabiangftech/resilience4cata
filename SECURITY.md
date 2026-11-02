# Security policy

## Supported versions

| Version | Supported |
|---|---|
| 1.x, latest minor | yes: security fixes, released as a patch of the latest minor |
| older 1.x minors | upgrade to the latest minor; it stays compatible ([compatibility](https://resilience4cata.dev/en/reference/compatibility)) |
| 0.x | no: upgrade to 1.x |

Before 1.0.0 is released, only the latest 0.x version gets fixes.

## Reporting a vulnerability

Please report it privately, not in a public issue: on GitHub, open the repository's **Security** tab and choose
**Report a vulnerability** (private vulnerability reporting). Include the affected version, what an attacker can
do, and a way to reproduce it if you have one.

The maintainer aims to answer within seven days. Once a fix is ready, it is released and the report is published as a
GitHub security advisory, crediting you unless you prefer otherwise.

## Scope

resilience4cata runs inside your application and opens no network port of its own. Reports are welcome in
particular for:

- the Spring Boot and Micronaut endpoints and the Quarkus Dev UI, which expose policy state (read-only);
- external configuration (`resilience4cata-config`): sources, profiles and the values it accepts;
- the annotation processor, which runs in your build and generates code from your sources.
