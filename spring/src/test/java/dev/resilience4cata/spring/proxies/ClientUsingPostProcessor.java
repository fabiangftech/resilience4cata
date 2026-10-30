package dev.resilience4cata.spring.proxies;

import org.springframework.beans.factory.config.BeanPostProcessor;

/** An application's post-processor that depends on an annotated interface's bean, which Spring creates early for it. */
public class ClientUsingPostProcessor implements BeanPostProcessor {

    public final EarlyClient client;

    public ClientUsingPostProcessor(EarlyClient client) {
        this.client = client;
    }
}
