package dev.resilience4cata.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.quarkus.deployment.fixtures.ReloadResource;
import dev.resilience4cata.quarkus.deployment.fixtures.ReloadService;
import dev.resilience4cata.quarkus.deployment.fixtures.ReloadService$Resilient;
import io.quarkus.test.QuarkusDevModeTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Dev mode's live reload: editing an annotated class regenerates its subclass (the new attributes apply), and code
 * that the library runs later (an async retry's next attempt, started from its timer) sees the reloaded
 * application's context class loader, not the one before the reload.
 */
class ReloadTest {

    @RegisterExtension
    static final QuarkusDevModeTest TEST = new QuarkusDevModeTest()
            .withApplicationRoot(jar -> jar.addClasses(ReloadService.class, ReloadService$Resilient.class,
                    ReloadResource.class));

    @Test
    void anEditedAnnotatedClassIsRegeneratedAndLaterAttemptsSeeTheReloadedApplication() {
        assertThat(get("/reload/attempts")).isEqualTo("2");
        assertThat(get("/reload/loader")).isEqualTo("the application's");

        TEST.modifySourceFile(ReloadService.class, source -> source.replace("@Retry(maxAttempts = 2, delay = \"1ms\"",
                "@Retry(maxAttempts = 4, delay = \"1ms\""));

        assertThat(get("/reload/attempts")).as("the regenerated subclass uses the edited maxAttempts").isEqualTo("4");
        assertThat(get("/reload/loader")).as("after the reload").isEqualTo("the application's");
    }

    private static String get(String path) {
        return RestAssured.get(path).then().statusCode(200).extract().asString();
    }
}
