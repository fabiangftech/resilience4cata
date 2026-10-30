package dev.resilience4cata.spring.proxies;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/** The fix of {@link ClientUsingPostProcessor}: the client is looked up when it is used, after every post-processor. */
public class LazyClientPostProcessor implements BeanPostProcessor {

    public final ObjectProvider<EarlyClient> client;

    public LazyClientPostProcessor(ObjectProvider<EarlyClient> client) {
        this.client = client;
    }
}
