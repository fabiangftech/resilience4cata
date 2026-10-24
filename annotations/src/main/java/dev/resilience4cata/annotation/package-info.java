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
 *
 * <p>The annotations go on interfaces, applied with {@code Resilient.of(Interface.class, impl, registry)},
 * or on non-final classes, applied with {@code Resilient.create(TheClass.class, registry, args...)} through
 * a generated subclass. A class is processed only if it declares an annotation itself; it then also inherits
 * the annotations of its interfaces and superclasses, with this precedence, lowest first: interface,
 * interface method, class, class method. In a class, calls the object makes to itself are intercepted too.
 */
package dev.resilience4cata.annotation;
