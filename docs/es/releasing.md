# Publicar una versión

Cómo se publica resilience4cata en Maven Central. Todo usa los plugins propios de Gradle `maven-publish` y `signing`; no hay plugins de publicación de terceros.

## Qué se publica

| artifactId | Contenido |
|---|---|
| `resilience4cata-annotations` | jar, fuentes, Javadoc, POM, metadatos de módulo de Gradle |
| `resilience4cata-core` | lo mismo; depende de `org.jspecify:jspecify` (scope `compile`) |
| `resilience4cata-processor` | lo mismo; depende de `resilience4cata-annotations` (scope `runtime`, para el processor path) |
| `resilience4cata-config` | lo mismo; depende de `resilience4cata-core` (`compile`) |
| `resilience4cata-testkit` | lo mismo; depende de `resilience4cata-core` (`compile`) |
| `resilience4cata-bom` | solo POM y metadatos de módulo: restricciones sobre los cinco módulos anteriores, en la misma versión |

groupId `dev.resilience4cata`. Cada jar lleva `LICENSE` y `NOTICE` (Apache-2.0) en `META-INF`, y los archivos son reproducibles (sin marcas de tiempo, orden fijo). `examples`, `benchmark` y `stress` no se publican.

## Cómo está organizado el build

- **`r4c.publish`** (`conventions/.../PublishConventionPlugin.java`), aplicado por cada módulo publicado: una publicación `mavenJava` con los campos de POM que exige Central (name, description tomada del `description` del módulo, url, licencia, developer, scm, issue tracker), la firma, y un repositorio de archivos `centralStaging` en `build/central-staging`.
- **`r4c.central`** (`CentralConventionPlugin.java`), aplicado por el `build.gradle` raíz:
  - `centralBundle` borra `build/central-staging`, publica ahí todos los módulos, lo verifica y lo comprime en `build/central-bundle.zip`. La verificación falla con una versión `-SNAPSHOT`, un módulo ausente, un jar de fuentes o de Javadoc ausente, o cualquier archivo sin su firma `.asc` y sus checksums `.md5`/`.sha1`. Solo entran los archivos de la versión publicada (sin `maven-metadata.xml`).
  - `centralUpload` envía el zip a la Publisher API del Central Portal (`POST /api/v1/publisher/upload`, `publishingType=USER_MANAGED`). Nada depende de esta tarea, así que `build` nunca sube nada, y falla sin token. Central valida un deployment `USER_MANAGED` y luego lo deja esperando en el portal hasta que alguien pulse **Publish**.
- Sin clave no se firma, así que `./gradlew build` y `./gradlew publishToMavenLocal` no necesitan secretos.

Propiedades de Gradle (o variables de entorno `ORG_GRADLE_PROJECT_<nombre>`):

| Propiedad | La usa | Significado |
|---|---|---|
| `signingInMemoryKey` | firma | Clave secreta en ASCII armor (`gpg --armor --export-secret-keys <id>`) |
| `signingInMemoryKeyPassword` | firma | Su passphrase |
| `signingInMemoryKeyId` | firma | Opcional: id de una subclave de firma |
| `centralPortalUsername`, `centralPortalPassword` | `centralUpload` | User token del Central Portal |
| `r4c.scmRepository` | POM | `owner/repositorio` de GitHub, en `gradle.properties` |
| `r4c.developerId`, `r4c.developerName` | POM | Entrada de developer, en `gradle.properties` |

Guarda los secretos en `~/.gradle/gradle.properties` o en el entorno, nunca en el repositorio. Con el configuration cache activo, Gradle guarda los valores de las propiedades en su caché (cifrada) bajo `.gradle/`; usa `--no-configuration-cache` al firmar en una máquina compartida.

## Configuración inicial (una sola vez)

1. Crea una cuenta en [central.sonatype.com](https://central.sonatype.com).
2. Verifica el namespace `dev.resilience4cata`: en el portal, **Namespaces → Add namespace**; agrega la clave de verificación que muestra como registro DNS TXT en `resilience4cata.dev` y pulsa **Verify**.
3. Genera un user token (**View Account → Generate User Token**). Sus dos partes son `centralPortalUsername` y `centralPortalPassword`.
4. Crea una clave de firma y publica la parte pública en un keyserver que Central consulte (por ejemplo `keys.openpgp.org` o `keyserver.ubuntu.com`):

   ```bash
   gpg --quick-gen-key "resilience4cata releases <you@example.com>" rsa4096 sign 2y
   gpg --keyserver keys.openpgp.org --send-keys <key id>
   gpg --armor --export-secret-keys <key id>     # valor de signingInMemoryKey
   ```

5. Para el workflow de GitHub, crea el environment `production` (Settings → Environments; el job de release lo declara) y agrégale los secretos `SIGNING_KEY`, `SIGNING_KEY_PASSWORD` y, para subir desde CI, `CENTRAL_PORTAL_USERNAME` y `CENTRAL_PORTAL_PASSWORD`. Restringe el environment a los tags `v*` para que solo un tag de release pueda leerlos.
6. Pon en `gradle.properties` el repositorio real de GitHub en `r4c.scmRepository` (y las propiedades de developer).

## Pasos de una versión

1. Fija la versión en `gradle.properties`: `version=0.1.0`. Haz commit (`release 0.1.0`) y el tag: `git tag v0.1.0`; luego sube el commit y el tag.
2. Arma el bundle, de una de dos formas:
   - en CI: ejecuta el workflow **release** (`.github/workflows/release.yml`) sobre el tag `v0.1.0`. Compila, firma, verifica, guarda `central-bundle.zip` como artefacto del workflow y lo sube si están los secretos del token; o
   - en local: `./gradlew build centralBundle --no-configuration-cache` con las propiedades de firma definidas.
3. Sube el bundle, si CI no lo hizo: `./gradlew centralUpload -x centralBundle`, o en el portal **Publish Component** y elige `build/central-bundle.zip`.
4. En el portal, abre **Deployments**, espera el estado *Validated*, revisa los archivos y pulsa **Publish** (o **Drop** para descartarlo). Los artefactos llegan a Maven Central en unos 30 minutos.
5. Pasa a la siguiente versión de desarrollo: `version=0.2.0-SNAPSHOT`, commit.
6. Cuando la versión ya aparezca en Maven Central, actualiza la versión escrita en la documentación y en el `README.md` raíz (snippets de build del sitio, portadas, changelog): `grep -rn "0\.1\.0" README.md docs/en docs/es`.

Para revisar un bundle sin publicar nada, ármalo con una clave desechable y una versión de release:

```bash
gpg --quick-gen-key "throwaway <throwaway@example.invalid>" rsa3072 sign 1d
ORG_GRADLE_PROJECT_signingInMemoryKey="$(gpg --armor --export-secret-keys throwaway@example.invalid)" \
ORG_GRADLE_PROJECT_signingInMemoryKeyPassword=<passphrase> \
./gradlew centralBundle -Pversion=0.1.0 --no-configuration-cache
unzip -l build/central-bundle.zip
```
