/**
 * Spring Boot 4 auto-configuration for resilience4cata: a {@link dev.resilience4cata.ResilienceRegistry}
 * bean configured from the application's {@code resilience4cata.*} properties, generated subclasses
 * instantiated in place of annotated classes, beans of annotated interfaces wrapped with their generated
 * class, and optional Micrometer metrics, a health indicator and a read-only actuator endpoint. Nothing
 * runs on the call path: the protection is the generated code's.
 */
@SuppressWarnings({"requires-automatic", "requires-transitive-automatic"}) // Spring's jars are automatic modules
@org.jspecify.annotations.NullMarked
module dev.resilience4cata.spring {
    requires transitive dev.resilience4cata.config;
    requires transitive spring.boot.autoconfigure;
    requires transitive spring.boot;
    requires transitive spring.context;
    requires transitive spring.beans;
    requires transitive spring.core;
    requires spring.aop;
    requires org.apache.commons.logging;
    requires static transitive dev.resilience4cata.micrometer;
    requires static transitive micrometer.core;
    requires static transitive spring.boot.health;
    requires static transitive spring.boot.actuator;
    requires static transitive spring.boot.actuator.autoconfigure;
    requires static com.fasterxml.jackson.annotation; // read by javac on Boot's health classes

    exports dev.resilience4cata.spring;
    exports dev.resilience4cata.spring.autoconfigure;
    // Spring instantiates the auto-configuration's package-private post-processors and failure analyzers.
    opens dev.resilience4cata.spring.autoconfigure to spring.core, spring.beans, spring.context;
}
