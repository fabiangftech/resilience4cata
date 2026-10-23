/**
 * Annotations that declare resilience policies on methods and types.
 *
 * <p>Attribute conventions:
 * <ul>
 *   <li>Every attribute defaults to an "unset" value ({@code -1}, {@code ""}, {@code {}},
 *       {@code void.class} or {@code INHERIT}). An unset attribute never overrides a value from a
 *       named configuration or an outer annotation; real defaults live in the configuration layer.</li>
 *   <li>Durations are strings such as {@code "250ms"}, {@code "2s"}, {@code "1m"} or {@code "PT2S"}.
 *       The annotation processor rejects invalid values at compile time.</li>
 *   <li>{@code enabled} is tri-state: {@code ""} (inherit), {@code "true"} or {@code "false"}.</li>
 *   <li>A method-level annotation overrides a type-level one attribute by attribute.</li>
 * </ul>
 */
package dev.resilience4cata.annotation;
