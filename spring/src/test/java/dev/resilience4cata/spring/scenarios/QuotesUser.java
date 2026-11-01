package dev.resilience4cata.spring.scenarios;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Every way of injecting the implementations of an annotated interface. */
@Component
public class QuotesUser {

    public final Quotes primary;
    public final Quotes backup;
    public final List<Quotes> all;
    public final Map<String, Quotes> byName;
    public final ObjectProvider<Quotes> provider;

    public QuotesUser(Quotes primary, @Qualifier("backup") Quotes backup, List<Quotes> all, Map<String, Quotes> byName,
            ObjectProvider<Quotes> provider) {
        this.primary = primary;
        this.backup = backup;
        this.all = all;
        this.byName = byName;
        this.provider = provider;
    }
}
