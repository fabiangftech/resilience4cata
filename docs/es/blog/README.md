# Blog

Artículos sobre cómo está construido resilience4cata, qué mide y cómo usarlo bien.

## Hedging cuando los hedges compiten por el mismo servicio

*5 de noviembre de 2026*

Una simulación de `@Hedge` contra un único servicio compartido, con cargas del 30 % al 95 %: cuándo el hedging recorta la cola, cuándo colapsa el servicio, y qué cambian el presupuesto, el límite adaptativo y la cancelación. [Leer el artículo](hedging-under-contention.md)

## resilience4cata 1.0.0-RC2: pruébalo antes de la 1.0

*4 de noviembre de 2026*

La API está congelada para la 1.0: qué significa aquí un release candidate, qué cambió en el camino, cómo probarlo y qué feedback todavía puede cambiar la 1.0.0. [Leer el artículo](release-candidate.md)

## Lo que cuesta una anotación de resiliencia en Spring, Micronaut y Quarkus

*1 de noviembre de 2026*

Los beans que cada framework entrega a una aplicación, medidos contra los aspectos e interceptores de Resilience4j y SmallRye Fault Tolerance, más los seis casos que resilience4cata no ganó y lo que se hizo con el hedge. [Leer el artículo](framework-annotations.md)

## Un circuit breaker de 1.9 ns: resiliencia generada al compilar

*27 de octubre de 2026*

Por qué los decoradores cuestan algo en cada llamada, qué escribe en su lugar un procesador de anotaciones, y cómo se compara resilience4cata con Resilience4j 2.4.0, empates y salvedades incluidos. [Leer el artículo](compile-time-resilience.md)
