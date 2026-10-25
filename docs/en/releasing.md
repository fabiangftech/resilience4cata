# Releasing

How resilience4cata is published to Maven Central. Everything uses Gradle's own `maven-publish` and `signing` plugins; there is no third-party publishing plugin.

## What is published

| artifactId | Contents |
|---|---|
| `resilience4cata-annotations` | jar, sources, Javadoc, POM, Gradle module metadata |
| `resilience4cata-core` | same; depends on `org.jspecify:jspecify` (`compile` scope) |
| `resilience4cata-processor` | same; depends on `resilience4cata-annotations` (`runtime` scope, for the processor path) |
| `resilience4cata-config` | same; depends on `resilience4cata-core` (`compile`) |
| `resilience4cata-micrometer` | same; depends on `resilience4cata-core` and `io.micrometer:micrometer-core` (`compile`) |
| `resilience4cata-testkit` | same; depends on `resilience4cata-core` (`compile`) |
| `resilience4cata-opentelemetry` | same; depends on `resilience4cata-core` and `io.opentelemetry:opentelemetry-api` (`compile`) |
| `resilience4cata-junit` | same; depends on `resilience4cata-testkit` and `org.junit.jupiter:junit-jupiter-api` 5.10.5 (`compile`) |
| `resilience4cata-bom` | POM and module metadata only: constraints on every module above, at the same version |

groupId `dev.resilience4cata`. Every jar carries `LICENSE` and `NOTICE` (Apache-2.0) in `META-INF`, and archives are reproducible (no file timestamps, fixed order). `examples`, `benchmark` and `stress` are not published.

## How the build is organized

- **`r4c.publish`** (`conventions/.../PublishConventionPlugin.java`), applied by each published module: one `mavenJava` publication with the POM fields Central requires (name, description from the module's `description`, url, license, developer, scm, issue tracker), signing, and a `centralStaging` file repository at `build/central-staging`.
- **`r4c.central`** (`CentralConventionPlugin.java`), applied by the root `build.gradle`:
  - `centralBundle` deletes `build/central-staging`, publishes every module into it, checks it and zips it into `build/central-bundle.zip`. The check fails on a `-SNAPSHOT` version, a missing module, a missing sources or Javadoc jar, or any file without its `.asc` signature and `.md5`/`.sha1` checksums. Only the release version's files go in (no `maven-metadata.xml`).
  - `centralUpload` posts the zip to the Central Portal Publisher API (`POST /api/v1/publisher/upload`, `publishingType=USER_MANAGED`). Nothing depends on it, so `build` never uploads, and it fails without a token. A `USER_MANAGED` deployment is validated by Central and then waits in the portal until someone presses **Publish**.
- `opentelemetry` reports its version as the OpenTelemetry instrumentation scope version: `processResources` writes the build's `version` into `dev/resilience4cata/opentelemetry/version.properties`, so a release (including `-Pversion=…`) needs no manual step.
- Signing is skipped when no key is set, so `./gradlew build` and `./gradlew publishToMavenLocal` need no secrets.

Gradle properties (or `ORG_GRADLE_PROJECT_<name>` environment variables):

| Property | Used by | Meaning |
|---|---|---|
| `signingInMemoryKey` | signing | ASCII-armored secret key (`gpg --armor --export-secret-keys <id>`) |
| `signingInMemoryKeyPassword` | signing | Its passphrase |
| `signingInMemoryKeyId` | signing | Optional: a signing subkey's id |
| `centralPortalUsername`, `centralPortalPassword` | `centralUpload` | Central Portal user token |
| `r4c.scmRepository` | POM | GitHub `owner/repository`, in `gradle.properties` |
| `r4c.developerId`, `r4c.developerName` | POM | Developer entry, in `gradle.properties` |

Keep secrets in `~/.gradle/gradle.properties` or the environment, never in the repository. With the configuration cache on, Gradle stores property values in its (encrypted) cache under `.gradle/`; pass `--no-configuration-cache` when signing on a shared machine.

## One-time setup

1. Create an account at [central.sonatype.com](https://central.sonatype.com).
2. Verify the namespace `dev.resilience4cata`: in the portal, **Namespaces → Add namespace**, then add the verification key it shows as a DNS TXT record on `resilience4cata.dev` and press **Verify**.
3. Generate a user token (**View Account → Generate User Token**). Its two parts are `centralPortalUsername` and `centralPortalPassword`.
4. Create a signing key and publish the public part to a keyserver Central checks (for example `keys.openpgp.org` or `keyserver.ubuntu.com`):

   ```bash
   gpg --quick-gen-key "resilience4cata releases <you@example.com>" rsa4096 sign 2y
   gpg --keyserver keys.openpgp.org --send-keys <key id>
   gpg --armor --export-secret-keys <key id>     # value of signingInMemoryKey
   ```

5. For the GitHub workflow, create the environment `production` (Settings → Environments; the release job declares it) and add to it the secrets `SIGNING_KEY`, `SIGNING_KEY_PASSWORD`, and, to upload from CI, `CENTRAL_PORTAL_USERNAME` and `CENTRAL_PORTAL_PASSWORD`. Restrict the environment to `v*` tags so only a release tag can read them.
6. Set `r4c.scmRepository` (and the developer properties) in `gradle.properties` to the real GitHub repository.

## Release steps

1. Set the release version in `gradle.properties`: `version=0.1.0`. Commit (`release 0.1.0`) and tag: `git tag v0.1.0`, then push the commit and the tag.
2. Build the bundle, either:
   - in CI: run the **release** workflow (`.github/workflows/release.yml`) on tag `v0.1.0`. It builds, signs, checks, archives `central-bundle.zip` as a workflow artifact, and uploads it if the token secrets are set; or
   - locally: `./gradlew build centralBundle --no-configuration-cache` with the signing properties set.
3. Upload, if CI did not: `./gradlew centralUpload -x centralBundle`, or in the portal **Publish Component**, choose `build/central-bundle.zip`.
4. In the portal, open **Deployments**, wait for *Validated*, review the files, then press **Publish** (or **Drop** to discard it). Artifacts reach Maven Central within about 30 minutes.
5. Bump to the next development version: `version=0.2.0-SNAPSHOT`, commit.
6. Once the release is visible on Maven Central, update the version written in the docs and the root `README.md` (build snippets on the site, landing pages, changelog): `grep -rn "0\.1\.0" README.md docs/en docs/es`.

To check a bundle without releasing anything, build it with a throwaway key and a release version:

```bash
gpg --quick-gen-key "throwaway <throwaway@example.invalid>" rsa3072 sign 1d
ORG_GRADLE_PROJECT_signingInMemoryKey="$(gpg --armor --export-secret-keys throwaway@example.invalid)" \
ORG_GRADLE_PROJECT_signingInMemoryKeyPassword=<passphrase> \
./gradlew centralBundle -Pversion=0.1.0 --no-configuration-cache
unzip -l build/central-bundle.zip
```
