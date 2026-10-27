package dev.resilience4cata.micronaut.usability;

import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.context.annotation.Requires;

/** One entry per backend under {@code usability-backends.<name>}: plain configuration, no policies. */
@EachProperty("usability-backends")
@Requires(property = "test.usability", value = "each-bean")
public class BackendConfig {

    private final String name;
    private String url;

    public BackendConfig(@Parameter String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}
