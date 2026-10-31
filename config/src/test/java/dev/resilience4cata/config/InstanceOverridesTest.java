package dev.resilience4cata.config;

import dev.resilience4cata.generated.Policies;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.resilience4cata.RateLimiterConfig;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.RetryConfig;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Per-instance overrides: {@code instances.<id>.<policy>.<attribute>}. */
class InstanceOverridesTest {

    private static final String GET = "com.acme.Api#get(java.lang.String)";

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

    private static RetryConfig retry(ResilienceRegistry registry, String instanceId, String name) {
        return Policies.retry(registry, instanceId, name, "", null, false).config();
    }

    // ---- resolution ----

    @Test
    void overridesAnInstanceByNameOrByQuotedDerivedId() {
        ResilienceRegistry registry = text("""
                instances.payments.retry.maxAttempts=7
                instances."com.acme.Api#get(java.lang.String)".retry.delay=15ms
                instances."com.acme.Api#get(java.lang.String)".timeout.value=2s
                """).build().toRegistry();
        assertThat(retry(registry, "com.acme.Payments#pay()", "payments").maxAttempts()).isEqualTo(7);
        assertThat(retry(registry, GET, "").delay()).isEqualTo(Duration.ofMillis(15));
        assertThat(Policies.timeout(registry, GET, "", "", null, false).config().timeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(retry(registry, "com.acme.Api#other()", "")).isEqualTo(RetryConfig.DEFAULTS);
    }

    @Test
    void instanceOverridesBeatTheNamedConfigAndTheAnnotation() {
        ResilienceRegistry registry = text("""
                defaults.retry.maxAttempts=2
                configs.external.retry.maxAttempts=3
                configs.external.retry.delay=1s
                instances.payments.retry.maxAttempts=9
                """).build().toRegistry();
        RetryConfig config = Policies.retry(registry, "A#m()", "payments", "external",
                r -> r.maxAttempts(5).maxDelay(Duration.ofSeconds(30)), false).config();
        assertThat(config.maxAttempts()).isEqualTo(9);
        assertThat(config.delay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(config.maxDelay()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void anInstanceWithANameIsNotAddressedByItsDerivedId() {
        ResilienceRegistry registry = text("instances.\"A#m()\".retry.maxAttempts=9").build().toRegistry();
        assertThat(retry(registry, "A#m()", "payments").maxAttempts()).isEqualTo(3);
        assertThat(retry(registry, "A#m()", "").maxAttempts()).isEqualTo(9);
    }

    @Test
    void programmaticInstanceOverridesAfterApplyToWin() {
        ResilienceRegistry registry = text("""
                instances.payments.retry.maxAttempts=9
                instances.payments.retry.delay=1ms
                """).build().applyTo(ResilienceRegistry.builder())
                .retryInstance("payments", r -> r.maxAttempts(4)).build();
        RetryConfig config = retry(registry, "A#m()", "payments");
        assertThat(config.maxAttempts()).isEqualTo(4);
        assertThat(config.delay()).isEqualTo(Duration.ofMillis(1));
    }

    @Test
    void everyPolicyCanBeOverridden() {
        ResilienceRegistry registry = text("""
                instances.x.circuit-breaker.minimum-calls=3
                instances.x.rate-limiter.permits=4
                instances.x.bulkhead.max-concurrent=5
                instances.x.timeout.enabled=false
                instances.x.retry.enabled=false
                """).build().toRegistry();
        assertThat(Policies.circuitBreaker(registry, "A#m()", "x", "", null, false).config().minimumCalls()).isEqualTo(3);
        assertThat(Policies.rateLimiter(registry, "A#m()", "x", "", null, false).config().permits()).isEqualTo(4);
        assertThat(Policies.bulkhead(registry, "A#m()", "x", "", null, false).config().maxConcurrent()).isEqualTo(5);
        assertThat(Policies.timeout(registry, "A#m()", "x", "", null, false).config().enabled()).isFalse();
        assertThat(retry(registry, "A#m()", "x").enabled()).isFalse();
    }

    // ---- sources and profiles ----

    @Test
    void keysAcceptPrefixesAndKebabCase() {
        ResilienceRegistry registry = text("""
                resilience4cata.instances."com.acme.Api#get(java.lang.String)".retry.max-attempts=6
                r4c.instances.pay-ments_2.RETRY.MaxAttempts=5
                """).build().toRegistry();
        assertThat(retry(registry, GET, "").maxAttempts()).isEqualTo(6);
        assertThat(retry(registry, "A#m()", "pay-ments_2").maxAttempts()).isEqualTo(5);
    }

    @Test
    void systemPropertiesCanUseQuotedIds() {
        Properties props = new Properties();
        props.setProperty("r4c.instances.\"" + GET + "\".retry.maxAttempts", "8");
        props.setProperty("r4c.instances.\"com.acme.Api#list(java.lang.String[],int)\".retry.maxAttempts", "2");
        ResilienceRegistry registry = ResilienceConfig.builder().systemProperties(props).build().toRegistry();
        assertThat(retry(registry, GET, "").maxAttempts()).isEqualTo(8);
        assertThat(retry(registry, "com.acme.Api#list(java.lang.String[],int)", "").maxAttempts()).isEqualTo(2);
    }

    @Test
    void environmentVariablesAddressInstanceNames() {
        ResilienceRegistry registry = ResilienceConfig.builder().environment(Map.of(
                "R4C_INSTANCES_PAYMENTS_API_RETRY_MAXATTEMPTS", "5",
                "R4C_INSTANCES_PAYMENTS_CIRCUIT_BREAKER_WAIT_IN_OPEN", "3s",
                "R4C_PROFILES_EU_INSTANCES_PAYMENTS_BULKHEAD_MAXCONCURRENT", "2",
                "R4C_PROFILES_ACTIVE", "eu")).build().toRegistry();
        assertThat(retry(registry, "A#m()", "payments-api").maxAttempts()).isEqualTo(5);
        assertThat(Policies.circuitBreaker(registry, "A#m()", "payments", "", null, false).config().waitInOpen())
                .isEqualTo(Duration.ofSeconds(3));
        assertThat(Policies.bulkhead(registry, "A#m()", "payments", "", null, false).config().maxConcurrent()).isEqualTo(2);
    }

    @Test
    void unknownInstanceEnvironmentVariableExplainsWhichIdsAreAddressable() {
        String message = failure(ResilienceConfig.builder().environment(Map.of(
                "R4C_INSTANCES_PAYMENTS_RETRY_MAXATEMPTS", "5"))).problems().get(0);
        assertThat(message).startsWith("[R4C-CFG-001] unknown environment variable")
                .contains("Did you mean 'R4C_INSTANCES_PAYMENTS_RETRY_MAXATTEMPTS'?")
                .contains("R4C_INSTANCES_<NAME>_<POLICY>_<ATTRIBUTE>")
                .contains("in a file or a system property");
    }

    @Test
    void sourcePrecedenceAppliesToInstanceKeys() {
        Properties system = new Properties();
        system.setProperty("r4c.instances.p.retry.maxAttempts", "4");
        ResilienceConfig.Builder builder = text("""
                instances.p.retry.maxAttempts=2
                instances.p.retry.delay=1ms
                """).environment(Map.of("R4C_INSTANCES_P_RETRY_MAXATTEMPTS", "3", "R4C_INSTANCES_P_RETRY_DELAY", "2ms"));
        RetryConfig fromEnv = retry(builder.build().toRegistry(), "A#m()", "p");
        assertThat(fromEnv.maxAttempts()).isEqualTo(3);
        assertThat(fromEnv.delay()).isEqualTo(Duration.ofMillis(2));
        RetryConfig fromSystem = retry(builder.systemProperties(system).build().toRegistry(), "A#m()", "p");
        assertThat(fromSystem.maxAttempts()).isEqualTo(4);
        assertThat(fromSystem.delay()).isEqualTo(Duration.ofMillis(2));
    }

    @Test
    void activeProfilesOverrideInstanceKeysOfTheSameSource() {
        ResilienceConfig.Builder builder = text("""
                instances.p.retry.maxAttempts=2
                profiles.test.instances.p.retry.maxAttempts=1
                profiles.test.instances."com.acme.Api#get(java.lang.String)".rate-limiter.permits=1
                """).environment(Map.of("R4C_INSTANCES_P_RETRY_MAXATTEMPTS", "6"));
        ResilienceRegistry base = builder.build().toRegistry();
        assertThat(retry(base, "A#m()", "p").maxAttempts()).isEqualTo(6);
        assertThat(Policies.rateLimiter(base, GET, "", "", null, false).config()).isEqualTo(RateLimiterConfig.DEFAULTS);
        ResilienceRegistry test = builder.profiles("test").build().toRegistry();
        assertThat(retry(test, "A#m()", "p").maxAttempts()).as("the environment variable still wins").isEqualTo(6);
        ResilienceRegistry fileOnly = text("""
                instances.p.retry.maxAttempts=2
                profiles.test.instances.p.retry.maxAttempts=1
                """).profiles("test").build().toRegistry();
        assertThat(retry(fileOnly, "A#m()", "p").maxAttempts()).isEqualTo(1);
        assertThat(Policies.rateLimiter(test, GET, "", "", null, false).config().permits()).isEqualTo(1);
    }

    @Test
    void quotedAndPlainSpellingsAreTheSameKey() {
        assertThat(problem("""
                instances.payments.retry.maxAttempts=2
                instances."payments".retry.max-attempts=3
                """)).startsWith("[R4C-CFG-005] 'instances.payments.retry.maxAttempts' is set twice");
    }

    // ---- key validation ----

    @Test
    void unquotedDerivedIdSuggestsTheQuotedKey() {
        assertThat(problem("instances.com.acme.Api#get(java.lang.String).retry.maxAttempts=2"))
                .startsWith("[R4C-CFG-012] malformed key 'instances.com.acme.Api#get(java.lang.String).retry"
                        + ".maxAttempts' (test.properties, line 1): an instance id that contains '.' must be quoted")
                .endsWith("Did you mean 'instances.\"com.acme.Api#get(java.lang.String)\".retry.maxAttempts'?");
        assertThat(problem("profiles.test.instances.a.b.timeout.value=1s"))
                .contains("Did you mean 'profiles.test.instances.\"a.b\".timeout.value'?");
    }

    @Test
    void malformedQuotesAreReported() {
        assertThat(problem("instances.\"A#m().retry.delay=1s")).startsWith("[R4C-CFG-012]").contains("unclosed");
        assertThat(problem("instances.\"A\"x.retry.delay=1s")).startsWith("[R4C-CFG-012]")
                .contains("quoted as a whole");
        assertThat(problem("instances.a\"b\".retry.delay=1s")).startsWith("[R4C-CFG-012]");
        assertThat(problem("instances.\"\".retry.delay=1s")).startsWith("[R4C-CFG-012]").contains("empty");
        assertThat(problem("instances.\"\\ x\".retry.delay=1s")).startsWith("[R4C-CFG-012]").contains("whitespace");
        assertThat(problem("configs.\"external\".retry.delay=1s")).startsWith("[R4C-CFG-012]")
                .contains("only an instance id may be quoted");
        assertThat(problem("instances.a.\"retry\".delay=1s")).startsWith("[R4C-CFG-012]");
    }

    @Test
    void incompleteOrUnknownInstanceKeys() {
        assertThat(problem("instances.p.retry=1")).startsWith("[R4C-CFG-001] incomplete key")
                .contains("instances.<id>.<policy>.<attribute>");
        assertThat(problem("instances.p.retry.maxAtempts=1")).startsWith("[R4C-CFG-001]")
                .contains("Did you mean 'instances.p.retry.maxAttempts'?");
        assertThat(problem("instances.p.retyr.delay=1s")).contains("Did you mean 'instances.p.retry.delay'?");
        assertThat(problem("instances.p.fallback.x=1")).contains("Fallbacks have no external configuration");
        assertThat(problem("instanses.p.retry.delay=1s")).contains("Did you mean 'instances.p.retry.delay'?");
        assertThat(problem("profiles.a.instancs.p.retry.delay=1s"))
                .contains("Did you mean 'profiles.a.instances.p.retry.delay'?");
    }

    // ---- value validation ----

    @Test
    void invalidValuesAreReportedWithTheirSource() {
        assertThat(problem("instances.p.retry.maxAttempts=x")).startsWith("[R4C-CFG-002] 'instances.p.retry"
                + ".maxAttempts' (test.properties, line 1) has an invalid value \"x\"");
        assertThat(problem("instances.p.retry.maxAttempts=0")).startsWith("[R4C-CFG-006] invalid retry instance 'p':"
                + " maxAttempts must be at least 1").contains("(test.properties, line 1)");
        assertThat(problem("instances.\"A#m()\".timeout.value=0")).startsWith("[R4C-CFG-006] invalid timeout"
                + " instance 'A#m()'");
    }

    @Test
    void combinationsAreCheckedOnlyWhenTheInstanceSetsEveryAttributeInvolved() {
        // The instance's base (its annotation) may set maxDelay or permits: not an error at build time.
        assertThatCode(() -> text("instances.p.retry.delay=20s").build()).doesNotThrowAnyException();
        assertThatCode(() -> text("instances.p.rate-limiter.period=1ns").build()).doesNotThrowAnyException();
        assertThat(problem("""
                instances.p.retry.delay=5s
                instances.p.retry.maxDelay=1s
                instances.p.retry.maxAttempts=2
                """)).startsWith("[R4C-CFG-006] invalid retry instance 'p': maxDelay (PT1S) must not be shorter"
                + " than delay (PT5S)").contains("line 1").contains("line 2").doesNotContain("line 3");
        assertThat(problem("""
                instances.p.rate-limiter.period=1ns
                instances.p.rate-limiter.permits=2
                """)).startsWith("[R4C-CFG-006] invalid rateLimiter instance 'p'");
        assertThat(failure(text("""
                instances.p.retry.maxAttempts=0
                instances.q.retry.maxAttempts=0
                instances.q.retry.multiplier=0.5
                """)).problems()).hasSize(3);
    }

    @Test
    void invalidCombinationWithTheBaseFailsWhenTheInstanceIsCreated() {
        ResilienceRegistry registry = text("instances.p.retry.delay=20s").build().toRegistry();
        assertThatIllegalArgumentException().isThrownBy(() -> retry(registry, "A#m()", "p"))
                .withMessageContaining("Invalid retry config for instance 'p'")
                .withMessageContaining("instances.p.retry.*");
        assertThat(Policies.retry(registry, "A#m()", "p", "", r -> r.maxDelay(Duration.ofMinutes(1)), false).config().delay())
                .isEqualTo(Duration.ofSeconds(20));
    }

    // ---- diagnostics ----

    @Test
    void describeListsInstanceOverrides() {
        ResilienceConfig config = text("""
                instances."com.acme.Api#get(java.lang.String)".retry.max-attempts=2
                instances.payments.timeout.value=1s
                """).build();
        assertThat(config.describe()).isEqualTo("""
                active profiles: []
                instances."com.acme.Api#get(java.lang.String)".retry.maxAttempts = 2  <- 'instances."com.acme.Api#get\
                (java.lang.String)".retry.max-attempts' (test.properties, line 1)
                instances.payments.timeout.value = 1s  <- 'instances.payments.timeout.value' (test.properties, line 2)""");
        assertThat(config.instanceIds()).containsExactly(GET, "payments");
    }

    @Test
    void verifyInstancesReportsOverridesThatMatchNoInstance() {
        ResilienceConfig config = text("""
                instances.payments.retry.maxAttempts=2
                instances.paymnets.retry.maxAttempts=2
                instances.payments.bulkhead.maxConcurrent=2
                instances."com.acme.Api#get(java.lang.Strin)".retry.delay=1ms
                """).build();
        ResilienceRegistry registry = config.toRegistry();
        Policies.retry(registry, "A#m()", "payments", "", null, false);
        Policies.retry(registry, GET, "", "", null, false);
        Policies.circuitBreaker(registry, "B#m()", "orders", "", null, false);

        ConfigException e = org.assertj.core.api.Assertions.catchThrowableOfType(ConfigException.class,
                () -> config.verifyInstances(registry));
        assertThat(e.problems()).hasSize(3);
        assertThat(e.problems().get(0)).isEqualTo("[R4C-CFG-013] 'instances.\"com.acme.Api#get(java.lang.Strin)\""
                + ".retry.delay' (test.properties, line 4) sets retry instance 'com.acme.Api#get(java.lang.Strin)', but"
                + " this registry has no retry instance with that id. Did you mean"
                + " '\"com.acme.Api#get(java.lang.String)\"'? Known retry instances: [" + GET + ", payments]");
        assertThat(e.problems().get(1)).startsWith("[R4C-CFG-013] 'instances.payments.bulkhead.maxConcurrent'")
                .contains("('payments' is a retry instance: per-instance overrides cannot add a policy")
                .contains("Known bulkhead instances: []");
        assertThat(e.problems().get(2)).contains("retry instance 'paymnets'").contains("Did you mean 'payments'?");
    }

    @Test
    void verifyInstancesPassesWhenEveryOverrideIsUsed() {
        ResilienceConfig config = text("instances.orders.circuitBreaker.minimumCalls=2").build();
        ResilienceRegistry registry = config.toRegistry();
        Policies.circuitBreaker(registry, "B#m()", "orders", "", null, false);
        assertThatCode(() -> config.verifyInstances(registry)).doesNotThrowAnyException();
        assertThatCode(() -> text("").build().verifyInstances(registry)).doesNotThrowAnyException();
    }

    @Test
    void verifyInstancesReportsOverridesThatALockedAnnotationIgnores() {
        ResilienceConfig config = text("""
                instances.payments.retry.maxAttempts=9
                instances.payments.retry.delay=7ms
                instances.payments.retry.jitter=none
                """).build();
        ResilienceRegistry registry = config.toRegistry();
        RetryConfig resolved = Policies.retry(registry, "A#m()", "payments", "",
                r -> r.maxAttempts(5).jitter(RetryConfig.Jitter.NONE), true).config();

        assertThat(resolved.maxAttempts()).isEqualTo(5);
        assertThat(resolved.delay()).isEqualTo(Duration.ofMillis(7));
        ConfigException e = org.assertj.core.api.Assertions.catchThrowableOfType(ConfigException.class,
                () -> config.verifyInstances(registry));
        assertThat(e.problems()).containsExactly("[R4C-CFG-014] 'instances.payments.retry.maxAttempts'"
                + " (test.properties, line 1) sets maxAttempts = 9 on retry instance 'payments', but its annotation"
                + " sets maxAttempts and is locked (locked = true), so the override is ignored and the instance uses "
                + resolved + ". Remove the key, or remove locked = true from the annotation.");
    }

    @Test
    void verifyInstancesPassesWhenALockedInstanceUsesEveryOverride() {
        ResilienceConfig config = text("instances.payments.retry.delay=7ms").build();
        ResilienceRegistry registry = config.toRegistry();
        Policies.retry(registry, "A#m()", "payments", "", r -> r.maxAttempts(5), true);
        assertThatCode(() -> config.verifyInstances(registry)).doesNotThrowAnyException();
    }
}
