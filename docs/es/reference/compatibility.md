# Compatibilidad

Qué sigue funcionando al actualizar dentro de una misma versión mayor, desde 1.0.0, y qué puede crecer en una versión menor.

resilience4cata sigue [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Hasta 1.0.0 una versión menor puede cambiar la API, y el [changelog](../changelog.md) lista cada cambio incompatible. Desde 1.0.0, las versiones menores y de parche de una misma mayor mantienen compatible todo lo que describe esta página; solo una nueva versión mayor puede romperlo.

## La API de Java :id=java-api

La API son todos los miembros públicos y protegidos de los paquetes que exporta un módulo publicado (`dev.resilience4cata`, `dev.resilience4cata.event`, `dev.resilience4cata.config`, `dev.resilience4cata.testkit`, etc.; para Spring Boot, `dev.resilience4cata.spring`). Dentro de una versión mayor se mantiene compatible a nivel binario y de código fuente. El build lo comprueba en cada cambio: compara cada módulo con la última release y, desde 1.0.0, falla ante un cambio incompatible.

No forman parte de la API:

- Los miembros marcados `@Internal`: son públicos solo para que otro módulo de resilience4cata, o el `ServiceLoader`, pueda alcanzarlos.
- Los miembros marcados `@Experimental`: pueden cambiar en cualquier versión menor hasta que se asienten. Hoy no hay nada marcado `@Experimental`.
- Los paquetes que un módulo no exporta, como `dev.resilience4cata.internal` y `dev.resilience4cata.spring.autoconfigure` de Spring Boot. De la autoconfiguración se mantienen los nombres de las clases (para `exclude`), los nombres de los beans y los nombres de las propiedades `resilience4cata.spring.*`.
- `dev.resilience4cata.generated` y `dev.resilience4cata.reactor.generated`: se exportan solo para el código generado. No los llames desde tu código.

## Código generado :id=generated-code

El código generado por el procesador `1.a` funciona sobre core (y reactor) `1.b` para todo `b >= a`. Por eso, una librería compilada con resilience4cata sigue funcionando cuando la aplicación que la usa actualiza resilience4cata dentro de la misma versión mayor. La dirección contraria no está soportada: sobre un runtime más antiguo, el constructor generado falla de inmediato con [R4C-RT-021](../errors.md#r4c-rt-021). Importar el BOM mantiene las versiones alineadas. El texto del código fuente generado no forma parte del contrato y puede cambiar en cualquier versión.

## Más allá de Java :id=beyond-java

Esto también se mantiene dentro de una versión mayor:

- Las anotaciones y sus atributos, y las [claves de configuración](configuration.md) con sus formas como variable de entorno.
- Los nombres de propiedades de las integraciones: `resilience4cata.spring.*`, `resilience4cata.micronaut.*` y `quarkus.resilience4cata.*`.
- Los nombres y tags de las métricas, iguales en los módulos de Micrometer y OpenTelemetry ([Nombres de métricas](../guides/observability.md#metric-names)). Los nombres siguen separados por puntos, cada meter conserva su tag `name` y las tasas del circuit breaker siguen siendo porcentajes.
- El JSON de los endpoints de Spring Boot y Micronaut, que es `ResilienceSnapshot.toMap()` ([Snapshots](../guides/observability.md#snapshots)).
- Los códigos de error como `R4C-ATR-001`. Un código nunca se renumera ni se reutiliza, y un código retirado conserva su sección en la [referencia de errores](../errors.md). El texto de un mensaje puede mejorar en cualquier versión, así que compara por el código, no por el texto.

## Qué puede crecer en una versión menor :id=growth

Añadir es compatible, así que una versión menor puede hacer cualquiera de estas cosas. Escribe código que lo tolere:

- **Constantes de enums** nuevas (`CircuitBreakerState`, `RetryEvent.Reason`, `Classifier.Outcome` y otros). Deja una rama `default` en un `switch` sobre ellos.
- **Tipos de eventos** nuevos, y componentes nuevos en los records de eventos existentes. Deja una rama `default` en un `switch` sobre eventos, y lee los eventos con sus accessors, no con record patterns que los descompongan.
- **Métodos** nuevos en los handles (`CircuitBreakerHandle` y los demás) y en `FallbackContext`. La librería implementa estas interfaces; no las implementes ni las mockees.
- **Métodos de `Ticker`**: los que se añadan serán métodos `default`, así que tu propia implementación sigue compilando.
- **Atributos de anotaciones** nuevos, siempre con valor por defecto.
- **Snapshots** con componentes nuevos, y claves nuevas en el JSON de los endpoints. Ignora las claves que no conozcas.
- **Métricas**, códigos de error y claves de configuración nuevos.
