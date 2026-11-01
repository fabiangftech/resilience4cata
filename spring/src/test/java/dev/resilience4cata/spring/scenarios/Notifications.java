package dev.resilience4cata.spring.scenarios;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Injects both notifiers by their qualifiers. */
@Component
public class Notifications {

    public final Notifier email;
    public final Notifier sms;

    public Notifications(@Email Notifier email, @Qualifier("sms") Notifier sms) {
        this.email = email;
        this.sms = sms;
    }
}
