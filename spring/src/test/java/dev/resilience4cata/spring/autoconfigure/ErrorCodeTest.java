package dev.resilience4cata.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.spring.Runners;
import dev.resilience4cata.spring.TransactionTest;
import dev.resilience4cata.spring.broken.AnnotatedConfiguration;
import dev.resilience4cata.spring.broken.TwoFacedClient;
import dev.resilience4cata.spring.fixtures.Money;
import dev.resilience4cata.spring.fixtures.PricingService;
import dev.resilience4cata.spring.proxies.Backend;
import dev.resilience4cata.spring.proxies.BackendEarlyClient;
import dev.resilience4cata.spring.proxies.ClientUsingPostProcessor;
import dev.resilience4cata.spring.tx.RetriedLedger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Every Spring integration error code is unique, documented in both languages, and reported at startup. */
class ErrorCodeTest {

    private static final Pattern HEADING = Pattern.compile("(?m)^### (R4C-[A-Z]+-\\d{3})$");
    private static final Pattern CODE = Pattern.compile("\\[(R4C-SPR-\\d{3})] ");

    private final ApplicationContextRunner runner = Runners.runner();

    @Test
    void codesAreUniqueAndWellFormed() {
        List<String> codes = Arrays.stream(ErrorCode.values()).map(ErrorCode::code).toList();
        assertThat(codes).doesNotHaveDuplicates().allMatch(c -> c.matches("R4C-SPR-\\d{3}"));
    }

    @Test
    void everyCodeIsDocumentedInEnglishAndSpanish() throws IOException {
        for (String language : List.of("en", "es")) {
            String page = Files.readString(Path.of("..", "docs", language, "errors.md"));
            Set<String> documented = HEADING.matcher(page).results().map(m -> m.group(1)).collect(Collectors.toSet());
            assertThat(documented).as(language)
                    .containsAll(Arrays.stream(ErrorCode.values()).map(ErrorCode::code).toList());
        }
    }

    @Test
    void everyCodeIsReported() {
        Set<String> reported = new TreeSet<>();
        reported.addAll(codes(runner.withUserConfiguration(UnprotectedBean.class)));
        reported.addAll(codes(runner.withUserConfiguration(AnnotatedConfiguration.class)));
        reported.addAll(codes(runner.withBean(TwoFacedClient.class, TwoFacedClient::new)));
        reported.addAll(codes(runner.withUserConfiguration(TransactionTest.Transactions.class)
                .withBean(RetriedLedger.class)));
        reported.addAll(codes(runner.withBean(Backend.class).withBean(BackendEarlyClient.class)
                .withBean(ClientUsingPostProcessor.class)));

        assertThat(reported).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(ErrorCode.values()).map(ErrorCode::code).toList());
    }

    /** The codes in the messages of the startup failure and its causes. */
    static Set<String> codes(ApplicationContextRunner failing) {
        Set<String> codes = new TreeSet<>();
        failing.run(context -> {
            assertThat(context).hasFailed();
            for (Throwable t = context.getStartupFailure(); t != null; t = t.getCause()) {
                Matcher m = CODE.matcher(String.valueOf(t.getMessage()));
                while (m.find()) {
                    codes.add(m.group(1));
                }
            }
        });
        return codes;
    }

    /** R4C-SPR-001: a {@code @Bean} method that returns the annotated class itself. */
    @Configuration(proxyBeanMethods = false)
    static class UnprotectedBean {

        @Bean
        PricingService pricing(ResilienceRegistry registry) {
            assertThat(registry).isNotNull();
            return new PricingService(new Money("EUR"), 1);
        }
    }
}
