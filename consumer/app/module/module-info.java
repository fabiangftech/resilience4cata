/** The application as a named module, set up as docs/en/start/installation.md#jpms describes. */
module com.example.consumer {
    requires dev.resilience4cata.all;

    exports com.example.consumer to dev.resilience4cata.core;
}
