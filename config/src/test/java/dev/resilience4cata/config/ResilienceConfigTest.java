package dev.resilience4cata.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.BulkheadConfig;
import dev.resilience4cata.CircuitBreakerConfig;
import dev.resilience4cata.RateLimiterConfig;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.RetryConfig;
import dev.resilience4cata.TimeoutConfig;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResilienceConfigTest {

    static final class Boom extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static ResilienceConfig.Builder text(String text) {
        return ResilienceConfig.builder().text("test.properties", text);
    }

    private static ConfigException failure(ResilienceConfig.Builder builder) {
        try {
            builder.build();
        } catch (ConfigException e) {
            return e;
        }
        throw new AssertionError("expected a ConfigException");
    }

    private static String problem(String text) {
        ConfigException e = failure(text(text));
        assertThat(e.problems()).hasSize(1);
        return e.problems().get(0);
    }

    // ---- end to end ----

    @Test
    void buildsARegistryWithEveryPolicy() {
        ResilienceRegistry registry = text("""
                defaults.retry.maxAttempts=4
                defaults.retry.jitter=none
                configs.external.retry.delay=200ms
                configs.external.retry.backoff=fixed
                configs.external.retry.retryOn=java.io.IOException, java.util.concurrent.TimeoutException
                configs.external.retry.abortOn=dev.resilience4cata.config.ResilienceConfigTest$Boom
                configs.external.retry.maxDuration=PT5S
                configs.external.retry.multiplier=1.5
                configs.external.retry.maxDelay=1s
                configs.external.retry.enabled=TRUE
                configs.external.circuitBreaker.failureRateThreshold=50
                configs.external.circuitBreaker.slowCallRateThreshold=75.5
                configs.external.circuitBreaker.slowCallDuration=2s
                configs.external.circuitBreaker.windowType=time
                configs.external.circuitBreaker.windowSize=30
                configs.external.circuitBreaker.minimumCalls=10
                configs.external.circuitBreaker.waitInOpen=10s
                configs.external.circuitBreaker.halfOpenCalls=3
                configs.external.circuitBreaker.maxWaitInHalfOpen=1m
                configs.external.circuitBreaker.recordOn=java.io.IOException
                configs.external.circuitBreaker.ignoreOn=dev.resilience4cata.config.ResilienceConfigTest.Boom
                configs.external.timeout.value=3s
                configs.external.bulkhead.maxConcurrent=7
                configs.external.bulkhead.maxWait=50ms
                configs.external.rateLimiter.permits=100
                configs.external.rateLimiter.period=1m
                configs.external.rateLimiter.maxWait=0
                defaults.timeout.enabled=false
                """).build().toRegistry();

        assertExternalRetry(registry);
        assertExternalCircuitBreaker(registry);
        assertThat(registry.timeoutConfig("external")).isEqualTo(new TimeoutConfig(false, Duration.ofSeconds(3)));
        assertThat(registry.timeoutConfig("")).isEqualTo(new TimeoutConfig(false, TimeoutConfig.DEFAULTS.timeout()));
        assertThat(registry.bulkheadConfig("external")).isEqualTo(new BulkheadConfig(true, 7, Duration.ofMillis(50)));
        assertThat(registry.rateLimiterConfig("external"))
                .isEqualTo(new RateLimiterConfig(true, 100, Duration.ofMinutes(1), Duration.ZERO));
        assertThat(registry.bulkheadConfig("default")).isEqualTo(BulkheadConfig.DEFAULTS);
    }

    private static void assertExternalRetry(ResilienceRegistry registry) {
        RetryConfig retry = registry.retryConfig("external");
        assertThat(retry.maxAttempts()).isEqualTo(4);
        assertThat(retry.jitter()).isEqualTo(RetryConfig.Jitter.NONE);
        assertThat(retry.delay()).isEqualTo(Duration.ofMillis(200));
        assertThat(retry.backoff()).isEqualTo(RetryConfig.Backoff.FIXED);
        assertThat(retry.retryOn()).containsExactly(IOException.class, TimeoutException.class);
        assertThat(retry.abortOn()).containsExactly(Boom.class);
        assertThat(retry.maxDuration()).isEqualTo(Duration.ofSeconds(5));
        assertThat(retry.multiplier()).isEqualTo(1.5);
        assertThat(retry.maxDelay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(registry.retryConfig("default").maxAttempts()).isEqualTo(4);
    }

    private static void assertExternalCircuitBreaker(ResilienceRegistry registry) {
        CircuitBreakerConfig cb = registry.circuitBreakerConfig("external");
        assertThat(cb.failureRateThreshold()).isEqualTo(50f);
        assertThat(cb.slowCallRateThreshold()).isEqualTo(75.5f);
        assertThat(cb.slowCallDuration()).isEqualTo(Duration.ofSeconds(2));
        assertThat(cb.windowType()).isEqualTo(CircuitBreakerConfig.WindowType.TIME);
        assertThat(cb.windowSize()).isEqualTo(30);
        assertThat(cb.minimumCalls()).isEqualTo(10);
        assertThat(cb.waitInOpen()).isEqualTo(Duration.ofSeconds(10));
        assertThat(cb.halfOpenCalls()).isEqualTo(3);
        assertThat(cb.maxWaitInHalfOpen()).isEqualTo(Duration.ofMinutes(1));
        assertThat(cb.recordOn()).containsExactly(IOException.class);
        assertThat(cb.ignoreOn()).containsExactly(Boom.class);
    }

    @Test
    void emptyConfigurationChangesNothing() {
        ResilienceRegistry registry = ResilienceConfig.builder().build().toRegistry();
        assertThat(registry.retryConfig("")).isEqualTo(RetryConfig.DEFAULTS);
        assertThat(registry.circuitBreakerConfig("")).isEqualTo(CircuitBreakerConfig.DEFAULTS);
    }

    @Test
    void namedConfigStartsFromExternalDefaults() {
        ResilienceRegistry registry = text("""
                defaults.retry.maxAttempts=5
                configs.external.retry.delay=1s
                """).build().toRegistry();
        assertThat(registry.retryConfig("external").maxAttempts()).isEqualTo(5);
        assertThat(registry.retryConfig("external").delay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(registry.retryConfig("external").abortOn()).isEmpty();
    }

    @Test
    void namedConfigIsRegisteredOnlyForThePoliciesItSets() {
        ResilienceRegistry registry = text("configs.external.retry.delay=1s").build().toRegistry();
        assertThatIllegalArgumentException().isThrownBy(() -> registry.timeoutConfig("external"))
                .withMessageContaining("Unknown timeout config 'external'");
    }

    @Test
    void relaxedBindingAcceptsKebabCaseAndPrefixes() {
        ResilienceRegistry registry = text("""
                resilience4cata.configs.external.circuit-breaker.wait-in-open=5s
                r4c.configs.external.retry.max-attempts=2
                configs.external.RATE-LIMITER.Permits=9
                """).build().toRegistry();
        assertThat(registry.circuitBreakerConfig("external").waitInOpen()).isEqualTo(Duration.ofSeconds(5));
        assertThat(registry.retryConfig("external").maxAttempts()).isEqualTo(2);
        assertThat(registry.rateLimiterConfig("external").permits()).isEqualTo(9);
    }

    @Test
    void appliesToAnExistingBuilderAlongsideProgrammaticConfigs() {
        ResilienceRegistry.Builder builder = ResilienceRegistry.builder()
                .retry("internal", r -> r.maxAttempts(9));
        ResilienceRegistry registry = text("configs.external.retry.maxAttempts=2").build().applyTo(builder).build();
        assertThat(registry.retryConfig("internal").maxAttempts()).isEqualTo(9);
        assertThat(registry.retryConfig("external").maxAttempts()).isEqualTo(2);
    }

    @Test
    void namedConfigInCodeAndExternallyIsMergedWithExternalValuesWinning() {
        ResilienceConfig config = text("configs.external.retry.maxAttempts=2").build();
        ResilienceRegistry before = config.applyTo(ResilienceRegistry.builder()
                .retry("external", r -> r.maxAttempts(9).delay(Duration.ofMillis(7)))).build();
        ResilienceRegistry after = config.applyTo(ResilienceRegistry.builder())
                .retry("external", r -> r.maxAttempts(9).delay(Duration.ofMillis(7))).build();
        for (ResilienceRegistry registry : List.of(before, after)) {
            assertThat(registry.retryConfig("external").maxAttempts()).isEqualTo(2);
            assertThat(registry.retryConfig("external").delay()).isEqualTo(Duration.ofMillis(7));
        }
    }

    @Test
    void mergedNamedConfigKeepsInheritanceAndProfiles() {
        ResilienceRegistry registry = text("""
                configs.base.circuitBreaker.minimumCalls=4
                configs.external.extends=base
                configs.external.circuitBreaker.windowSize=8
                profiles.test.configs.external.circuitBreaker.windowSize=6
                """).profiles("test").build().applyTo(ResilienceRegistry.builder()
                        .circuitBreaker("external", cb -> cb.minimumCalls(30).windowSize(50).waitInOpen(Duration.ZERO)))
                .build();
        CircuitBreakerConfig external = registry.circuitBreakerConfig("external");
        assertThat(external.minimumCalls()).isEqualTo(4);
        assertThat(external.windowSize()).isEqualTo(6);
        assertThat(external.waitInOpen()).isEqualTo(Duration.ZERO);
    }

    @Test
    void mergedNamedConfigThatBecomesInvalidFailsAtRegistryBuild() {
        ResilienceRegistry.Builder builder = text("configs.external.retry.delay=2s").build()
                .applyTo(ResilienceRegistry.builder().retry("external", r -> r.maxDelay(Duration.ofSeconds(1))));
        assertThatIllegalArgumentException().isThrownBy(builder::build)
                .withMessageContaining("Invalid retry config 'external'").withMessageContaining("maxDelay");
    }

    // ---- extends ----

    @Test
    void extendsInheritsEveryPolicyAndOverridesAttributeByAttribute() {
        ResilienceRegistry registry = text("""
                configs.external.circuitBreaker.failureRateThreshold=50
                configs.external.retry.delay=200ms
                configs.external.retry.maxAttempts=4
                configs.batch.extends=external
                configs.batch.timeout.value=30s
                configs.batch.retry.maxAttempts=2
                configs.nightly.extends=batch
                """).build().toRegistry();
        assertThat(registry.circuitBreakerConfig("batch").failureRateThreshold()).isEqualTo(50f);
        assertThat(registry.retryConfig("batch").delay()).isEqualTo(Duration.ofMillis(200));
        assertThat(registry.retryConfig("batch").maxAttempts()).isEqualTo(2);
        assertThat(registry.timeoutConfig("batch").timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(registry.retryConfig("nightly")).isEqualTo(registry.retryConfig("batch"));
        assertThat(registry.timeoutConfig("nightly").timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(registry.retryConfig("external").maxAttempts()).isEqualTo(4);
        assertThatIllegalArgumentException().isThrownBy(() -> registry.timeoutConfig("external"));
    }

    @Test
    void extendsUnknownConfigSuggestsTheClosest() {
        String p = problem("""
                configs.external.retry.delay=1s
                configs.batch.extends=extrenal
                """);
        assertThat(p).startsWith("[R4C-CFG-007] 'configs.batch.extends' (test.properties, line 2)")
                .contains("extends config 'extrenal'").contains("Did you mean 'external'?");
    }

    @Test
    void extendsUnknownConfigWithoutACloseNameStillReadsAsASentence() {
        String p = problem("""
                configs.external.retry.delay=1s
                configs.batch.extends=zzz
                """);
        assertThat(p).endsWith("which is not defined in external configuration. Known configs: [batch, external]");
    }

    @Test
    void extendsCycleIsReportedOnce() {
        ConfigException e = failure(text("""
                configs.a.extends=b
                configs.b.extends=c
                configs.c.extends=a
                configs.d.extends=a
                """));
        assertThat(e.problems()).hasSize(1);
        assertThat(e.problems().get(0)).startsWith("[R4C-CFG-007] configs extend each other in a cycle: a -> b -> c -> a")
                .contains("line 1").contains("line 2").contains("line 3");
        assertThat(problem("configs.a.extends=a")).contains("cannot extend itself");
    }

    // ---- profiles ----

    @Test
    void activeProfilesOverrideDefaultsAndConfigsInOrder() {
        String file = """
                defaults.retry.maxAttempts=3
                configs.external.retry.delay=200ms
                profiles.test.defaults.retry.maxAttempts=1
                profiles.test.configs.external.retry.delay=1ms
                profiles.eu.configs.external.retry.delay=5ms
                profiles.eu.configs.regional.timeout.value=9s
                """;
        ResilienceRegistry none = text(file).build().toRegistry();
        assertThat(none.retryConfig("external").delay()).isEqualTo(Duration.ofMillis(200));
        assertThatIllegalArgumentException().isThrownBy(() -> none.timeoutConfig("regional"));

        ResilienceConfig both = text(file + "profiles.active=test, eu\n").build();
        assertThat(both.activeProfiles()).containsExactly("test", "eu");
        ResilienceRegistry registry = both.toRegistry();
        assertThat(registry.retryConfig("").maxAttempts()).isEqualTo(1);
        assertThat(registry.retryConfig("external").maxAttempts()).isEqualTo(1);
        assertThat(registry.retryConfig("external").delay()).isEqualTo(Duration.ofMillis(5));
        assertThat(registry.timeoutConfig("regional").timeout()).isEqualTo(Duration.ofSeconds(9));

        ResilienceRegistry reversed = text(file).profiles("eu", "test").build().toRegistry();
        assertThat(reversed.retryConfig("external").delay()).isEqualTo(Duration.ofMillis(1));
    }

    @Test
    void programmaticProfilesOverrideTheKey() {
        ResilienceConfig config = text("""
                profiles.active=test
                profiles.test.defaults.retry.maxAttempts=1
                """).profiles().build();
        assertThat(config.activeProfiles()).isEmpty();
        assertThat(config.toRegistry().retryConfig("").maxAttempts()).isEqualTo(3);
    }

    @Test
    void profileCanChangeExtends() {
        ResilienceRegistry registry = text("""
                configs.slow.timeout.value=10s
                configs.fast.timeout.value=1s
                configs.client.extends=slow
                profiles.test.configs.client.extends=fast
                profiles.active=test
                """).build().toRegistry();
        assertThat(registry.timeoutConfig("client").timeout()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void profileKeyFromAFileBeatsBaseKeyFromTheEnvironment() {
        ResilienceRegistry registry = text("""
                profiles.active=test
                profiles.test.configs.external.retry.maxAttempts=1
                """).environment(Map.of("R4C_CONFIGS_EXTERNAL_RETRY_MAXATTEMPTS", "7")).build().toRegistry();
        assertThat(registry.retryConfig("external").maxAttempts()).isEqualTo(1);
    }

    @Test
    void activeProfilesCanComeFromTheEnvironment() {
        ResilienceConfig config = text("profiles.prod.defaults.bulkhead.maxConcurrent=100")
                .environment(Map.of("R4C_PROFILES_ACTIVE", "prod")).build();
        assertThat(config.activeProfiles()).containsExactly("prod");
        assertThat(config.toRegistry().bulkheadConfig("").maxConcurrent()).isEqualTo(100);
    }

    @Test
    void unknownActiveProfileIsOnlyAWarning() {
        assertThat(text("defaults.retry.maxAttempts=2").profiles("nope").build().activeProfiles())
                .containsExactly("nope");
    }

    @Test
    void invalidProfileNames() {
        assertThat(problem("profiles.active=Test")).startsWith("[R4C-CFG-004] invalid profile name 'Test'")
                .contains("for example 'test'");
        assertThat(problem("profiles.active.retry=1")).contains("'active' is reserved");
        assertThat(failure(ResilienceConfig.builder().profiles("bad_name")).problems().get(0))
                .contains("invalid profile name 'bad_name'").contains("'bad-name'");
    }

    // ---- sources and precedence ----

    @Test
    void sourcePrecedenceIsClasspathFileEnvironmentSystemProperties(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("app.properties");
        Files.writeString(file, """
                configs.external.retry.maxAttempts=2
                configs.external.retry.delay=2ms
                configs.external.timeout.value=2s
                """);
        Properties system = new Properties();
        system.setProperty("r4c.configs.external.retry.maxAttempts", "4");
        system.setProperty("unrelated.property", "ignored");
        ResilienceRegistry registry = ResilienceConfig.builder()
                .systemProperties(system)
                .environment(Map.of("R4C_CONFIGS_EXTERNAL_RETRY_MAXATTEMPTS", "3",
                        "R4C_CONFIGS_EXTERNAL_RETRY_DELAY", "3ms", "PATH", "/bin"))
                .file(file)
                .classpath("dev/resilience4cata/config/test-resilience4cata.properties")
                .build().toRegistry();
        assertThat(registry.retryConfig("external").maxAttempts()).isEqualTo(4);
        assertThat(registry.retryConfig("external").delay()).isEqualTo(Duration.ofMillis(3));
        assertThat(registry.timeoutConfig("external").timeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(registry.bulkheadConfig("external").maxConcurrent()).isEqualTo(11);
    }

    @Test
    void laterSourceOfTheSameKindWins() {
        Properties first = new Properties();
        first.setProperty("defaults.retry.maxAttempts", "2");
        Properties second = new Properties();
        second.setProperty("defaults.retry.maxAttempts", "5");
        assertThat(ResilienceConfig.builder().properties(first).properties("second", second).build().toRegistry()
                .retryConfig("").maxAttempts()).isEqualTo(5);
    }

    @Test
    void missingSources(@TempDir Path dir) {
        ResilienceConfig.builder().optionalFile(dir.resolve("nope.properties")).optionalClasspath("nope.properties")
                .build();
        ConfigException e = failure(ResilienceConfig.builder().file(dir.resolve("nope.properties"))
                .classpath("nope.properties"));
        assertThat(e.problems()).hasSize(2);
        assertThat(e.problems().get(0)).startsWith("[R4C-CFG-009] cannot read classpath:nope.properties: not found");
        assertThat(e.problems().get(1)).startsWith("[R4C-CFG-009] cannot read file ").endsWith("not found");
    }

    @Test
    void filesAreUtf8AndErrorsNameTheFileAndLine(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("bad.properties");
        Files.writeString(file, "# café\n\ndefaults.retry.maxAtempts=2\n", StandardCharsets.UTF_8);
        String p = failure(ResilienceConfig.builder().file(file)).problems().get(0);
        assertThat(p).contains("'defaults.retry.maxAtempts' (file " + file.toAbsolutePath() + ", line 3)")
                .contains("Did you mean 'defaults.retry.maxAttempts'?");

        Files.write(file, new byte[] {'a', '=', (byte) 0xC3});
        assertThat(failure(ResilienceConfig.builder().file(file)).problems().get(0)).contains("not valid UTF-8");
    }

    @Test
    void loadReadsTheDefaultResourceWhenPresent() {
        ResilienceConfig config = ResilienceConfig.load();
        assertThat(config.configNames()).contains("from-default-resource");
        assertThat(config.toRegistry().timeoutConfig("from-default-resource").timeout())
                .isEqualTo(Duration.ofMillis(1500));
    }

    @Test
    void sameKeyTwiceInOneSourceIsAnError() {
        assertThat(problem("""
                defaults.retry.maxAttempts=2
                defaults.retry.max-attempts=3
                """)).isEqualTo("[R4C-CFG-005] 'defaults.retry.maxAttempts' is set twice in test.properties: by"
                + " 'defaults.retry.maxAttempts' (test.properties, line 1) and by 'defaults.retry.max-attempts'"
                + " (test.properties, line 2)");
        ConfigException e = failure(ResilienceConfig.builder().environment(Map.of(
                "R4C_DEFAULTS_RETRY_MAXATTEMPTS", "2", "RESILIENCE4CATA_DEFAULTS_RETRY_MAX_ATTEMPTS", "3")));
        assertThat(e.problems().get(0)).startsWith("[R4C-CFG-005]").contains("environment variables");
    }

    // ---- environment variables ----

    @Test
    void environmentVariableMapping() {
        ResilienceRegistry registry = ResilienceConfig.builder().environment(Map.of(
                "R4C_CONFIGS_EXTERNAL_RETRY_MAXATTEMPTS", "5",
                "R4C_CONFIGS_EXTERNAL_CIRCUIT_BREAKER_WAIT_IN_OPEN", "7s",
                "R4C_CONFIGS_EXTERNAL_CIRCUITBREAKER_FAILURERATETHRESHOLD", "20",
                "R4C_CONFIGS_SLOW_EXTERNAL_EXTENDS", "external",
                "R4C_CONFIGS_SLOW_EXTERNAL_TIMEOUT_VALUE", "10s",
                "RESILIENCE4CATA_DEFAULTS_RATELIMITER_PERMITS", "12",
                "R4C_PROFILES_EU_WEST_CONFIGS_EXTERNAL_BULKHEAD_MAXCONCURRENT", "3",
                "R4C_PROFILES_ACTIVE", "eu-west",
                "HOME", "/home/x")).build().toRegistry();
        assertThat(registry.retryConfig("external").maxAttempts()).isEqualTo(5);
        assertThat(registry.circuitBreakerConfig("external").waitInOpen()).isEqualTo(Duration.ofSeconds(7));
        assertThat(registry.circuitBreakerConfig("external").failureRateThreshold()).isEqualTo(20f);
        assertThat(registry.retryConfig("slow-external").maxAttempts()).isEqualTo(5);
        assertThat(registry.timeoutConfig("slow-external").timeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(registry.rateLimiterConfig("").permits()).isEqualTo(12);
        assertThat(registry.bulkheadConfig("external").maxConcurrent()).isEqualTo(3);
    }

    @Test
    void configNamedLikeAPolicyStillParses() {
        ResilienceRegistry registry = ResilienceConfig.builder().environment(Map.of(
                "R4C_CONFIGS_RETRY_FAST_RETRY_DELAY", "1ms",
                "R4C_CONFIGS_TIMEOUT_TIMEOUT_VALUE", "4s")).build().toRegistry();
        assertThat(registry.retryConfig("retry-fast").delay()).isEqualTo(Duration.ofMillis(1));
        assertThat(registry.timeoutConfig("timeout").timeout()).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void ambiguousEnvironmentVariable() {
        // A profile or config name containing the word "configs" makes the split ambiguous.
        ConfigException e = failure(ResilienceConfig.builder().environment(Map.of(
                "R4C_PROFILES_A_CONFIGS_CONFIGS_B_RETRY_DELAY", "1s")));
        assertThat(e.problems().get(0)).startsWith("[R4C-CFG-010]")
                .contains("profiles.a.configs.configs-b.retry.delay")
                .contains("profiles.a-configs.configs.b.retry.delay");
    }

    @Test
    void unknownEnvironmentVariableSuggestsTheClosest() {
        ConfigException e = failure(ResilienceConfig.builder().environment(Map.of(
                "R4C_CONFIGS_EXTERNAL_RETRY_MAXATEMPTS", "5")));
        assertThat(e.problems().get(0))
                .startsWith("[R4C-CFG-001] unknown environment variable 'R4C_CONFIGS_EXTERNAL_RETRY_MAXATEMPTS'")
                .contains("Did you mean 'R4C_CONFIGS_EXTERNAL_RETRY_MAXATTEMPTS'?");
        assertThat(failure(ResilienceConfig.builder().environment(Map.of("R4C_WHATEVER", "1"))).problems().get(0))
                .contains("for example R4C_CONFIGS_EXTERNAL_RETRY_MAXATTEMPTS");
    }

    @Test
    void environmentValuesAreValidatedWithTheirOrigin() {
        assertThat(failure(ResilienceConfig.builder().environment(Map.of("R4C_DEFAULTS_RETRY_DELAY", "fast")))
                .problems().get(0)).isEqualTo("[R4C-CFG-002] environment variable 'R4C_DEFAULTS_RETRY_DELAY' = \"fast\""
                        + " is not a valid duration: unrecognized format. Use " + Durations.GRAMMAR + ".");
    }

    @Test
    void systemPropertiesRequireThePrefix() {
        Properties system = new Properties();
        system.setProperty("resilience4cata.defaults.retry.maxAttempts", "6");
        system.setProperty("defaults.retry.maxAttempts", "7");
        assertThat(ResilienceConfig.builder().systemProperties(system).build().toRegistry().retryConfig("")
                .maxAttempts()).isEqualTo(6);
        system.setProperty("r4c.defaults.retry.maxAttempt", "1");
        assertThat(failure(ResilienceConfig.builder().systemProperties(system)).problems().get(0))
                .isEqualTo("[R4C-CFG-001] unknown key system property 'r4c.defaults.retry.maxAttempt'."
                        + " Did you mean 'r4c.defaults.retry.maxAttempts'? Attributes of retry: enabled,"
                        + " maxAttempts, delay, backoff, multiplier, maxDelay, jitter, maxDuration, retryOn, abortOn, budgetPercent, budgetMinRetriesPerSecond, budgetWindow");
    }

    // ---- key validation ----

    @Test
    void unknownKeysSuggestTheClosestValidKey() {
        assertThat(problem("configs.external.circuit-breaker.wait-in-opne=10s"))
                .contains("unknown key 'configs.external.circuit-breaker.wait-in-opne' (test.properties, line 1)")
                .contains("Did you mean 'configs.external.circuit-breaker.wait-in-open'?");
        assertThat(problem("configs.external.circuitBraker.waitInOpen=10s"))
                .contains("unknown policy 'circuitBraker'")
                .contains("Did you mean 'configs.external.circuitBreaker.waitInOpen'?");
        assertThat(problem("defualts.retry.delay=1s")).contains("Did you mean 'defaults.retry.delay'?");
        assertThat(problem("configs.external.fallback.method=x")).contains("Fallbacks have no external configuration");
        assertThat(problem("defaults.retry=1")).contains("incomplete key").contains("defaults.<policy>.<attribute>");
        assertThat(problem("configs.external.retry.delay.extra=1")).contains("incomplete key");
        assertThat(problem("configs..retry.delay=1s")).contains("empty segment");
        assertThat(problem("something=1")).startsWith("[R4C-CFG-001] unknown key 'something'");
    }

    @Test
    void configNamesAreValidated() {
        assertThat(problem("configs.slowExternal.retry.delay=1s"))
                .startsWith("[R4C-CFG-004] invalid config name 'slowExternal'")
                .contains("(for example 'slow-external')");
        assertThat(problem("configs.default.retry.delay=1s")).contains("'default' is reserved");
        assertThat(problem("configs.a.extends=Bad Name")).contains("invalid config name 'Bad Name'");
    }

    // ---- value validation ----

    @Test
    void invalidValuesNameTheKeyAndSource() {
        assertThat(problem("defaults.retry.maxAttempts=three")).isEqualTo("[R4C-CFG-002] 'defaults.retry.maxAttempts'"
                + " (test.properties, line 1) has an invalid value \"three\": expected an integer");
        assertThat(problem("defaults.retry.enabled=yes")).contains("expected true or false");
        assertThat(problem("defaults.retry.multiplier=1,5")).contains("expected a decimal number");
        assertThat(problem("defaults.circuitBreaker.failureRateThreshold=NaN")).contains("expected a decimal number");
        assertThat(problem("defaults.circuitBreaker.failureRateThreshold=1e40")).contains("too large");
        assertThat(problem("defaults.retry.backoff=exponentail"))
                .contains("expected one of fixed, exponential. Did you mean 'exponential'?");
        assertThat(problem("defaults.retry.delay=2 s")).contains("is not a valid duration");
        assertThat(problem("defaults.retry.retryOn=java.io.IOException,,java.lang.Exception"))
                .contains("empty class name");
    }

    @Test
    void enumsAreCaseInsensitive() {
        ResilienceRegistry registry = text("""
                defaults.retry.jitter=Equal
                defaults.circuitBreaker.windowType=TIME
                """).build().toRegistry();
        assertThat(registry.retryConfig("").jitter()).isEqualTo(RetryConfig.Jitter.EQUAL);
        assertThat(registry.circuitBreakerConfig("").windowType()).isEqualTo(CircuitBreakerConfig.WindowType.TIME);
    }

    @Test
    void exceptionClassesAreValidated() {
        assertThat(problem("defaults.retry.retryOn=IOException"))
                .startsWith("[R4C-CFG-003] 'defaults.retry.retryOn' (test.properties, line 1): class 'IOException'"
                        + " not found").endsWith("Did you mean 'java.io.IOException'?");
        assertThat(problem("defaults.retry.retryOn=com.acme.Missing")).startsWith("[R4C-CFG-003]")
                .contains("class 'com.acme.Missing' not found");
        assertThat(problem("defaults.retry.retryOn=java.lang.String"))
                .contains("'java.lang.String' is not an exception");
        assertThat(problem("defaults.retry.retryOn=NoSuchThing")).contains("Use the fully qualified name");
        assertThat(text("defaults.retry.retryOn=").build().toRegistry().retryConfig("").retryOn()).isEmpty();
    }

    @Test
    void exceptionClassesUseTheConfiguredClassLoader() {
        List<String> requested = new ArrayList<>();
        String hidden = Boom.class.getName();
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                requested.add(name);
                if (name.equals(hidden)) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };
        ResilienceRegistry registry = text("defaults.retry.abortOn=java.net.SocketTimeoutException")
                .classLoader(loader).build().toRegistry();
        assertThat(requested).contains("java.net.SocketTimeoutException");
        assertThat(registry.retryConfig("").abortOn()).containsExactly(SocketTimeoutException.class);
        assertThat(text("defaults.retry.abortOn=" + hidden).build().toRegistry().retryConfig("").abortOn())
                .containsExactly(Boom.class);
        assertThat(failure(text("defaults.retry.abortOn=" + hidden).classLoader(loader)).problems().get(0))
                .contains("class '" + hidden + "' not found");
    }

    @Test
    void invalidCombinationsNameTheKeysInvolved() {
        assertThat(problem("""
                configs.external.retry.delay=5s
                configs.external.retry.maxDelay=1s
                configs.external.retry.maxAttempts=2
                """)).startsWith("[R4C-CFG-006] invalid retry config 'external': maxDelay (PT1S) must not be shorter"
                + " than delay (PT5S)").contains("line 1").contains("line 2").doesNotContain("line 3");
        assertThat(problem("defaults.timeout.value=0")).startsWith("[R4C-CFG-006] invalid timeout defaults:")
                .contains("'defaults.timeout.value' (test.properties, line 1)");
        assertThat(problem("defaults.retry.maxAttempts=0")).contains("maxAttempts must be at least 1");
    }

    @Test
    void invalidInheritedValueIsReportedForTheConfigThatBreaks() {
        assertThat(problem("""
                configs.base.retry.delay=5s
                configs.child.extends=base
                configs.child.retry.maxDelay=1s
                """)).startsWith("[R4C-CFG-006] invalid retry config 'child'");
    }

    @Test
    void everyProblemIsReportedAtOnce() {
        ConfigException e = failure(text("""
                defaults.retry.maxAttempts=x
                defaults.retry.delai=1s
                configs.Bad.retry.delay=1s
                """).environment(Map.of("R4C_NOPE", "1")));
        assertThat(e.problems()).hasSize(4);
        assertThat(e.getMessage()).startsWith("Invalid resilience4cata configuration (4 problems):\n  [R4C-CFG-");
        assertThat(e).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- diagnostics and immutability ----

    @Test
    void describeShowsEveryEffectiveValueWithItsOrigin() {
        ResilienceConfig config = text("""
                defaults.retry.maxAttempts=3
                configs.external.retry.delay=200ms
                configs.batch.extends=external
                """).environment(Map.of("R4C_CONFIGS_BATCH_TIMEOUT_VALUE", "30s")).build();
        assertThat(config.describe()).isEqualTo("""
                active profiles: []
                defaults.retry.maxAttempts = 3  <- 'defaults.retry.maxAttempts' (test.properties, line 1)
                configs.batch.retry.delay = 200ms  <- 'configs.external.retry.delay' (test.properties, line 2)
                configs.batch.timeout.value = 30s  <- environment variable 'R4C_CONFIGS_BATCH_TIMEOUT_VALUE'
                configs.external.retry.delay = 200ms  <- 'configs.external.retry.delay' (test.properties, line 2)""");
        assertThat(config.configNames()).containsExactly("batch", "external");
        assertThat(config).hasToString(config.describe());
    }

    @Test
    void sourcesAreCopiedSoLaterChangesDoNotLeakIn() {
        Properties props = new Properties();
        props.setProperty("defaults.retry.maxAttempts", "2");
        ResilienceConfig.Builder builder = ResilienceConfig.builder().properties(props);
        props.setProperty("defaults.retry.maxAttempts", "9");
        ResilienceConfig config = builder.build();
        ResilienceRegistry first = config.toRegistry();
        ResilienceRegistry second = config.toRegistry();
        assertThat(first.retryConfig("").maxAttempts()).isEqualTo(2);
        assertThat(second.retryConfig("")).isEqualTo(first.retryConfig(""));
        Set<String> names = config.configNames();
        assertThatThrownBy(() -> names.add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void unreadableSourceIsReported() {
        assertThat(failure(text("a=\\u00")).problems().get(0)).startsWith("[R4C-CFG-009] cannot read test.properties")
                .contains("line 1");
    }

    @Test
    void mapPropertiesReportTheOriginOfEachKey() {
        ConfigException e = failure(ResilienceConfig.builder().properties("application",
                Map.of("resilience4cata.defaults.retry.maxAtempts", "3", "resilience4cata.defaults.retry.delay", "soon"),
                key -> key.endsWith("delay") ? "'" + key + "' (application.yml - 4:12)" : null));
        assertThat(e.problems()).anySatisfy(p -> assertThat(p).startsWith("[R4C-CFG-001]")
                        .contains("'resilience4cata.defaults.retry.maxAtempts' (application)"))
                .anySatisfy(p -> assertThat(p).startsWith("[R4C-CFG-002]").contains("(application.yml - 4:12)"));
    }

    @Test
    void sourcesRankByKindUnlessPrecedenceIsByOrder() {
        Properties system = new Properties();
        system.setProperty("resilience4cata.defaults.retry.maxAttempts", "2");
        Map<String, String> later = Map.of("resilience4cata.defaults.retry.maxAttempts", "7");

        ResilienceConfig byKind = ResilienceConfig.builder().systemProperties(system)
                .properties("later", later, key -> null).build();
        ResilienceConfig byOrder = ResilienceConfig.builder().precedenceByOrder().systemProperties(system)
                .properties("later", later, key -> null).build();

        assertThat(byKind.toRegistry().retryConfig("").maxAttempts()).isEqualTo(2);
        assertThat(byOrder.toRegistry().retryConfig("").maxAttempts()).isEqualTo(7);
    }

    @Test
    void precedenceByOrderStillLetsProfilesWin() {
        ResilienceConfig config = ResilienceConfig.builder().precedenceByOrder()
                .properties("low", Map.of("profiles.test.defaults.retry.maxAttempts", "5", "profiles.active", "test"),
                        key -> null)
                .properties("high", Map.of("defaults.retry.maxAttempts", "9"), key -> null).build();

        assertThat(config.toRegistry().retryConfig("").maxAttempts()).isEqualTo(5);
    }
}
