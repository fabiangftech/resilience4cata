package dev.resilience4cata.micronaut.fixtures;

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

@Factory
public class Currencies {

    @Singleton
    @Named("euro")
    Money euro() {
        return new Money("EUR");
    }

    @Singleton
    @Named("dollar")
    Money dollar() {
        return new Money("USD");
    }
}
