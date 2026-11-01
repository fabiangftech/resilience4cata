package dev.resilience4cata.micronaut.scenarios;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.List;

/** Injects both notifiers by their qualifiers, and all of them. */
@Singleton
@Requires(property = "test.scenarios", value = "qualifiers")
public class Notifications {

    public final Notifier email;
    public final Notifier sms;
    public final List<Notifier> all;

    public Notifications(@Email Notifier email, @Named("sms") Notifier sms, List<Notifier> all) {
        this.email = email;
        this.sms = sms;
        this.all = all;
    }
}
