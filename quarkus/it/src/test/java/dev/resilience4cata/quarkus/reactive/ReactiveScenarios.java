package dev.resilience4cata.quarkus.reactive;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The reactive beans over HTTP, on the system clock: {@link ReactiveSmokeTest} runs these on the JVM, and
 * {@code ReactiveSmokeIT} against the packaged application, a native image with {@code testNative}.
 */
abstract class ReactiveScenarios {

    @Test
    void aMonoMethodOfAnAnnotatedClassIsRetried() {
        given().queryParam("failures", 2).get("/reactive/quotes/a").then().statusCode(200).body(equalTo("a=42"));
        assertThat(calls()).isEqualTo(3);
    }

    @Test
    void aMonoMethodThatKeepsFailingGivesUpAfterItsAttempts() {
        given().queryParam("failures", 10).get("/reactive/quotes/a").then().statusCode(500);
        assertThat(calls()).isEqualTo(3);
    }

    @Test
    void aMonoThatNeverAnswersTimesOutIntoItsFallback() {
        given().get("/reactive/slow-quotes/a").then().statusCode(200)
                .body(equalTo("a late: CallTimeoutException"));
    }

    @Test
    void aFluxMethodIsRetriedBeforeItsFirstElementAndStreamsEveryTick() {
        assertThat(events("/reactive/ticks/a", 2)).containsExactly("a tick 1", "a tick 2", "a tick 3");
        assertThat(calls()).isEqualTo(3);
    }

    @Test
    void aMonoMethodOfAWrappedInterfaceIsRetried() {
        given().queryParam("failures", 2).get("/reactive/rates/eur").then().statusCode(200)
                .body(equalTo("eur=42"));
        assertThat(calls()).isEqualTo(3);
    }

    @Test
    void aFluxMethodOfAWrappedInterfaceFallsBackToItsOwnStream() {
        assertThat(events("/reactive/rate-ticks/eur", 10)).containsExactly("eur closed");
        assertThat(calls()).isEqualTo(2);
    }

    @Test
    void aFluxMethodOfAWrappedInterfaceStreamsEveryTick() {
        assertThat(events("/reactive/rate-ticks/eur", 0)).containsExactly("eur tick 1", "eur tick 2", "eur tick 3");
        assertThat(calls()).isEqualTo(1);
    }

    private static List<String> events(String path, int failures) {
        String body = given().queryParam("failures", failures).accept("text/event-stream").get(path).then()
                .statusCode(200).extract().asString();
        return body.lines().filter(line -> line.startsWith("data:")).map(line -> line.substring(5).strip()).toList();
    }

    private static int calls() {
        return Integer.parseInt(given().get("/reactive/calls").then().statusCode(200).extract().asString());
    }
}
