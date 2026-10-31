# Configuración

Con el módulo `resilience4cata-config`, operaciones puede ajustar el mismo código compilado desde archivos properties, variables de entorno y propiedades del sistema.

Agrega `implementation 'dev.resilience4cata:resilience4cata-config'` (Maven: scope por defecto) junto a `core`; ver [instalación](../start/installation.md). Todas las claves están en la [referencia de configuración](../reference/configuration.md).

## Un primer archivo

Pon un `resilience4cata.properties` en el class path (por ejemplo en `src/main/resources`). Este ajusta el cliente de abajo:

<!-- snippet: configuration-file -->
```properties
# Every retry that does not set these attributes itself
defaults.retry.delay=50ms
defaults.retry.jitter=none

# A named config, used by @Retry(config = "external") and @CircuitBreaker(config = "external")
configs.external.retry.maxAttempts=4
configs.external.circuitBreaker.minimumCalls=50
configs.external.circuitBreaker.waitInOpen=10s

# A named config that starts from another one
configs.batch.extends=external
configs.batch.timeout.value=30s

# One instance, by its name...
instances.shipping.circuitBreaker.failureRateThreshold=30
# ...or by its derived id, in double quotes
instances."dev.resilience4cata.examples.docs.configuration.ShippingClient#cancel(java.lang.String)".timeout.value=3s

# Applied only while the profile "test" is active
profiles.test.configs.external.retry.maxAttempts=1
```

<!-- snippet: configuration-client -->
```java
public interface ShippingClient {

    @Retry(config = "external")
    @CircuitBreaker(name = "shipping", config = "external", minimumCalls = 20)
    String track(String parcel);

    @Retry(maxAttempts = 2)
    @Timeout("1s")
    String cancel(String parcel);
}
```

Cárgalo una vez, aplícalo al builder del registry y luego envuelve tus servicios como siempre:

<!-- snippet: configuration-load -->
```java
ResilienceConfig config = ResilienceConfig.load();   // resilience4cata.properties, R4C_* variables, -Dr4c.* properties
ResilienceRegistry registry = config.applyTo(ResilienceRegistry.builder()).build();
ShippingClient shipping = Resilient.of(ShippingClient.class, new HttpShippingClient(), registry);
config.verifyInstances(registry);   // fails if an instances.<id> key matches no instance
```

Con este archivo, `track` hace hasta 4 intentos separados por 50 ms (sin jitter), y su breaker `shipping` se abre con una tasa de fallas del 30% después de 20 llamadas (el `minimumCalls = 20` de la anotación gana sobre el 50 de la configuración con nombre), y luego espera 10 s. `cancel` hace 2 intentos y su timeout es de 3 s en lugar del 1 s de la anotación.

`ResilienceConfig.load()` lee, en este orden, el recurso del class path `resilience4cata.properties` (si existe), el entorno y las propiedades del sistema, con el class loader de contexto del thread. `config.toRegistry()` es un atajo de `config.applyTo(ResilienceRegistry.builder()).build()`.

## Fuentes

Para elegir tú las fuentes, usa el builder:

<!-- snippet: configuration-builder -->
```java
ResilienceConfig config = ResilienceConfig.builder()
        .optionalClasspath("resilience4cata.properties")
        .optionalFile(Path.of("/etc/shop/resilience4cata.properties"))   // skipped if missing
        .environment()                                                     // R4C_… variables
        .systemProperties()                                                // -Dr4c.… properties
        .build();
```

| Método | Lee |
|---|---|
| `classpath(name)` / `optionalClasspath(name)` | Un recurso del class path; la primera forma falla ([`R4C-CFG-009`](../errors.md#r4c-cfg-009)) si no existe |
| `file(path)` / `optionalFile(path)` | Un archivo; misma regla |
| `properties(props)` / `properties(description, props)` | Un `java.util.Properties` que armaste tú; la descripción aparece en los mensajes de error |
| `properties(description, map, origins)` | Un mapa de claves y valores, con dónde se fijó cada clave (`origins`), que se muestra en los mensajes de error en lugar de la descripción. Para frameworks que registran los orígenes, como Spring |
| `environment()` / `environment(map)` | Variables de entorno (`R4C_…`), o un mapa de ellas |
| `systemProperties()` / `systemProperties(props)` | Propiedades del sistema (`r4c.…`), o un `Properties` usado como tales |
| `profiles(names...)` | Los perfiles activos; reemplaza a `profiles.active` |
| `classLoader(loader)` | El class loader para los recursos y para las clases de excepción nombradas en los valores |
| `precedenceByOrder()` | Ordena las fuentes por el orden en que se agregaron, no por su tipo (ver abajo) |

Los archivos usan la sintaxis `.properties` y se leen como UTF-8. No hay soporte de YAML ni recarga: la configuración se lee una sola vez.

Cuando la misma clave llega desde varias fuentes, gana la más fuerte: **recursos del class path < archivos y `Properties` < variables de entorno < propiedades del sistema**. Entre dos fuentes del mismo tipo, gana la última agregada. Con `precedenceByOrder()`, gana la última agregada sea cual sea su tipo: la [integración con Spring Boot](spring.md) lo usa para mantener la precedencia de Spring. Dentro de una misma fuente, una clave bajo un perfil activo gana sobre la misma clave sin perfil; una fuente más fuerte sigue ganando, así que una variable de entorno o una propiedad del sistema que pone un operador gana sobre una clave de perfil en un archivo.

<!-- snippet: configuration-sources -->
```java
Properties system = new Properties();
system.setProperty("r4c.configs.external.retry.maxAttempts", "7");   // the r4c. prefix is required here

ResilienceConfig config = ResilienceConfig.builder()
        .classpath("resilience4cata.properties")                      // maxAttempts=4
        .environment(Map.of(
                "R4C_CONFIGS_EXTERNAL_RETRY_MAXATTEMPTS", "6",
                "R4C_INSTANCES_SHIPPING_CIRCUIT_BREAKER_WAIT_IN_OPEN", "5s"))
        .systemProperties(system)
        .build();
ResilienceRegistry registry = config.applyTo(ResilienceRegistry.builder()).build();
// registry.retryConfig("external").maxAttempts() == 7: system properties > environment > files
```

## Claves

```properties
defaults.<policy>.<attribute>=…                 # registry-wide defaults
configs.<name>.<policy>.<attribute>=…           # a named config, used with config = "<name>"
configs.<name>.extends=<other>                  # start from every policy of another named config
instances.<id>.<policy>.<attribute>=…           # one instance, by its name or derived id
profiles.<profile>.<any key above>=…            # applies only while <profile> is active
profiles.active=<profile>,<profile>             # comma-separated; later profiles win
```

- **Políticas:** `retry`, `hedge`, `circuitBreaker`, `rateLimiter`, `adaptiveLimit`, `timeout`, `bulkhead`. No hay claves de `fallback`.
- **Atributos:** son los de la anotación, con los mismos nombres (`maxAttempts`, `waitInOpen`, …). La duración del timeout es `timeout.value`. `name`, `config`, `classifier` y `onExhaustedResult` solo se pueden definir en las anotaciones.
- **Escritura:** se aceptan camelCase y kebab-case (`circuitBreaker.waitInOpen` = `circuit-breaker.wait-in-open`). Definir la misma clave dos veces en una fuente, aunque sea con dos escrituras distintas, es un error ([`R4C-CFG-005`](../errors.md#r4c-cfg-005)).
- **Prefijo:** cualquier clave puede empezar con `r4c.` o `resilience4cata.`. En las propiedades del sistema el prefijo es **obligatorio**, y las propiedades del sistema sin él se ignoran.
- **Nombres** de configuraciones y perfiles: letras minúsculas y dígitos separados por guiones simples (`slow-external`). `default` está reservado para configuraciones y `active` para perfiles ([`R4C-CFG-004`](../errors.md#r4c-cfg-004)).
- **`extends`** solo puede nombrar una configuración definida en la configuración externa, y no puede formar un ciclo ([`R4C-CFG-007`](../errors.md#r4c-cfg-007)). La cadena se aplica empezando por la raíz.
- **Ids de instancia:** son el `name` de la política, o el id derivado `<type>#<method>(<parameter types>)` ([cómo se arman los ids](../start/how-it-works.md#el-registry-y-los-ids-de-instancia)). Distinguen mayúsculas de minúsculas. Un id con algo que no sean letras, dígitos, `_` y `-` va entre comillas dobles: `instances."com.acme.Api#get(java.lang.String)".retry.maxAttempts=5` ([`R4C-CFG-012`](../errors.md#r4c-cfg-012)).

### Valores

| Tipo | Sintaxis | Ejemplos |
|---|---|---|
| Duración | dígitos más `ns`, `us`, `ms`, `s`, `m`, `h` o `d`; una duración ISO-8601; o `0`. Sin decimales, signos ni espacios | `250ms`, `2s`, `PT2S`, `0` |
| Entero, decimal | Números simples | `5`, `25`, `2.5` |
| Booleano | `true` o `false` | `retry.enabled=false` |
| Enum | Constante, sin distinguir mayúsculas | `exponential`, `full`, `time` |
| Lista de excepciones | Nombres binarios de clases separados por comas, cargados con el class loader configurado | `java.io.IOException,java.util.concurrent.TimeoutException` |

La gramática de las duraciones es la misma que en las anotaciones.

## Variables de entorno

Toma la clave, pásala a mayúsculas, reemplaza cada carácter que no sea letra ni dígito por `_` y agrega el prefijo `R4C_` (o `RESILIENCE4CATA_`):

| Clave | Variable de entorno |
|---|---|
| `configs.external.retry.maxAttempts` | `R4C_CONFIGS_EXTERNAL_RETRY_MAXATTEMPTS` |
| `configs.slow-external.timeout.value` | `R4C_CONFIGS_SLOW_EXTERNAL_TIMEOUT_VALUE` |
| `instances.shipping.circuitBreaker.waitInOpen` | `R4C_INSTANCES_SHIPPING_CIRCUITBREAKER_WAITINOPEN` o `R4C_INSTANCES_SHIPPING_CIRCUIT_BREAKER_WAIT_IN_OPEN` |
| `profiles.active` | `R4C_PROFILES_ACTIVE` |

Las palabras de los nombres de políticas y atributos pueden ir juntas o separadas por `_`. Una variable debe corresponder a exactamente una clave; cuando `_` puede significar tanto `.` como `-` y encajan dos claves, es un error ([`R4C-CFG-010`](../errors.md#r4c-cfg-010)): define esa clave en un archivo o en una propiedad del sistema. Desde el entorno solo se puede apuntar a instancias cuyo nombre esté hecho de letras minúsculas, dígitos y guiones (`R4C_INSTANCES_PAYMENTS_API_RETRY_MAXATTEMPTS` define `instances.payments-api.retry.maxAttempts`); los ids derivados necesitan un archivo o una propiedad del sistema como `-Dr4c.instances."com.acme.Api#get(java.lang.String)".retry.maxAttempts=5`.

## Perfiles

Un perfil agrupa claves que se aplican solo en algunos entornos. Las claves bajo `profiles.<profile>.` reemplazan las mismas claves sin perfil; no agregan un nivel de precedencia propio.

<!-- snippet: configuration-profiles -->
```java
ResilienceConfig config = ResilienceConfig.builder()
        .classpath("resilience4cata.properties")
        .profiles("test")                         // or profiles.active=test, or R4C_PROFILES_ACTIVE=test
        .build();
// config.activeProfiles() == [test]; configs.external.retry.maxAttempts is now 1
```

Puede haber varios perfiles activos: `profiles.active=test,local`. Cuando dos perfiles activos definen la misma clave, gana el último.

## Precedencia

De menor a mayor, para cada atributo de cada instancia:

1. Los valores por defecto de la librería (cada [página de política](../policies/README.md) los lista).
2. `defaults.<policy>.*`, o `retryDefaults(...)` y similares en código.
3. La configuración con nombre que la anotación elige con `config = "…"`: primero el `retry("<name>", ...)` del código, luego `configs.<name>.*`, atributo por atributo (la cadena de `extends` empezando por la raíz).
4. Los atributos propios de la anotación.
5. `instances.<id>.*`, o `retryInstance(id, ...)` y similares en código, salvo los atributos de una anotación con `locked = true` ([abajo](#bloquear-una-instancia)).

Así que **una anotación gana sobre una configuración con nombre**, y **una clave de instancia gana sobre la anotación**: operaciones puede ajustar un método sin recompilar. Los overrides por instancia se aplican una vez, cuando se crea la instancia.

El código y la configuración externa se combinan así:

- Una configuración con nombre definida en ambos lados se combina, y los valores externos ganan atributo por atributo, sin importar el orden de las llamadas (`applyTo` los registra con `retryOverlay(...)` y sus equivalentes, que también puedes llamar tú):

  <!-- snippet: configuration-merge -->
  ```java
  ResilienceRegistry registry = ResilienceConfig.load()
          .applyTo(ResilienceRegistry.builder()
                  .retry("external", r -> r.maxAttempts(9).maxDuration(Duration.ofSeconds(5))))
          .build();
  // registry.retryConfig("external"): maxAttempts 4 (the file wins), maxDuration 5s (only the code sets it)
  ```

- `retryInstance(...)` y las demás llamadas `*Instance` hechas en el builder después de `applyTo` ganan sobre las claves externas `instances.*`.
- `retryDefaults(...)` y las demás llamadas `*Defaults` hechas después de `applyTo` **reemplazan** los valores por defecto externos de esa política en lugar de combinarse con ellos.

## Bloquear una instancia

Para conservar un valor que operaciones no debe cambiar, agrega `locked = true` a la anotación. Los atributos que fija la anotación (directamente, a nivel de tipo o con un [bundle](bundles.md)) ganan entonces sobre las claves `instances.<id>.*` y las llamadas `retryInstance(...)`; los que deja sin fijar se pueden seguir sobrescribiendo:

<!-- snippet: configuration-locked-client -->
```java
public interface PaymentsClient {

    @CircuitBreaker(name = "payments", failureRateThreshold = 30, locked = true)
    String charge(String order);
}
```

<!-- snippet: configuration-locked -->
```java
Properties ops = new Properties();
ops.setProperty("instances.payments.circuitBreaker.failureRateThreshold", "80");   // locked: ignored
ops.setProperty("instances.payments.circuitBreaker.waitInOpen", "20s");            // not in the annotation: applied
ResilienceConfig config = ResilienceConfig.builder().properties("ops.properties", ops).build();
ResilienceRegistry registry = config.toRegistry();
PaymentsClient payments = Resilient.of(PaymentsClient.class, order -> "paid", registry);
// registry.circuitBreaker("payments").config(): failureRateThreshold 30, waitInOpen 20s
assertThatThrownBy(() -> config.verifyInstances(registry))
        .hasMessageContaining("[R4C-CFG-014] 'instances.payments.circuitBreaker.failureRateThreshold'");
```

Un override ignorado no pasa en silencio: el registry escribe un aviso en el log (`System.Logger`, nombre `dev.resilience4cata.ResilienceRegistry`) al crear la instancia, `config.verifyInstances(registry)` reporta cada clave ignorada con su fuente ([`R4C-CFG-014`](../errors.md#r4c-cfg-014)), y `registry.lockedOverrides()` lista las instancias afectadas. Un método puede desbloquear lo que bloquea su tipo con `locked = false`. Las configuraciones con nombre y los valores por defecto no cambian: una anotación ya gana sobre ellos. En un [pipeline](pipeline.md), `Pipeline.Builder.locked()` bloquea los valores fijados en sus pasos.

## Validación y errores

`build()` valida todo contra los valores por defecto de la librería: claves desconocidas, valores que no se pueden interpretar, clases de excepción desconocidas, `extends` incorrectos, nombres inválidos y combinaciones que no pueden funcionar juntas (por ejemplo un `maxDelay` menor que `delay`). Todos los problemas se reúnen en una `ConfigException` (una `IllegalArgumentException`, con la lista en `problems()`), cada uno con su código, su clave, su fuente (archivo y línea, variable de entorno o propiedad del sistema) y la clave válida más parecida. Este archivo tiene tres errores:

```properties
configs.external.circuit-breaker.wait-in-opne=10s
defaults.retry.delay=fast
configs.batch.extends=extrenal
```

<!-- snippet: configuration-errors -->
```java
assertThatThrownBy(() -> ResilienceConfig.builder().classpath(BROKEN).build())
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("[R4C-CFG-001]")
        .hasMessageContaining("Did you mean 'configs.external.circuit-breaker.wait-in-open'?")
        .hasMessageContaining("[R4C-CFG-002]")
        .hasMessageContaining("[R4C-CFG-007]");
```

El mensaje, abreviado:

```text
Invalid resilience4cata configuration (3 problems):
  [R4C-CFG-001] unknown key 'configs.external.circuit-breaker.wait-in-opne' (classpath:…/broken.properties, line 1). Did you mean 'configs.external.circuit-breaker.wait-in-open'? …
  [R4C-CFG-002] 'defaults.retry.delay' (classpath:…/broken.properties, line 2) = "fast" is not a valid duration: …
  [R4C-CFG-007] 'configs.batch.extends' (classpath:…/broken.properties, line 3) extends config 'extrenal', which is not defined in external configuration …
```

Las instancias existen recién cuando se envuelven los servicios, así que `build()` no puede saber si una clave `instances.<id>` nombra una instancia real. Llama a `config.verifyInstances(registry)` después de envolver tus servicios: falla con [`R4C-CFG-013`](../errors.md#r4c-cfg-013) y el id más parecido para cada override que no corresponde a ninguna instancia. Las combinaciones que involucran a la vez una clave de instancia y los valores de la anotación (por ejemplo un `delay` de instancia mayor que el `maxDelay` de la anotación) se verifican cuando se crea la instancia ([`R4C-REG-003`](../errors.md#r4c-reg-003)).

Cada código `R4C-CFG` se explica en la [referencia de errores](../errors.md#ejecución-configuración-externa-r4c-cfg).

## Inspeccionar el resultado

`config.describe()` lista cada valor efectivo y de dónde viene, uno por línea:

```text
active profiles: []
defaults.retry.delay = 50ms  <- 'defaults.retry.delay' (classpath:resilience4cata.properties, line 4)
configs.batch.retry.maxAttempts = 4  <- 'configs.external.retry.maxAttempts' (classpath:resilience4cata.properties, line 8)
instances.shipping.circuitBreaker.failureRateThreshold = 30  <- 'instances.shipping.circuitBreaker.failureRateThreshold' (classpath:resilience4cata.properties, line 17)
…
```

`activeProfiles()`, `configNames()` e `instanceIds()` devuelven las partes una por una. Una vez envueltos los servicios, `registry.snapshot()` muestra la configuración resuelta de cada instancia ([observabilidad](observability.md)).

## Límites

- No hay recarga en caliente ni `reconfigure()`: la configuración es inmutable una vez construido el registry.
- No hay YAML ni otros formatos; léelos tú en un `Properties` y pásalo con `properties(description, props)`.
- `@Fallback`, los classifiers, `name` y `config` no se pueden configurar externamente.
- No hay forma de bloquear una instancia contra los overrides externos: `instances.<id>.*` siempre gana sobre la anotación.
