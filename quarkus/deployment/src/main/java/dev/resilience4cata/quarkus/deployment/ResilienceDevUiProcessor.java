package dev.resilience4cata.quarkus.deployment;

import dev.resilience4cata.quarkus.runtime.devui.ResilienceJsonRpcService;
import io.quarkus.deployment.IsLocalDevelopment;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.devui.spi.JsonRPCProvidersBuildItem;
import io.quarkus.devui.spi.page.CardPageBuildItem;
import io.quarkus.devui.spi.page.Page;

/**
 * The resilience4cata card of the Dev UI ({@code /q/dev-ui}), in dev mode only: both build steps run only under
 * {@link IsLocalDevelopment}, so a production build has neither the card nor the JsonRPC bean. The page
 * ({@code dev-ui/qwc-resilience4cata-policies.js}) shows the registry's snapshot, refreshed every two seconds
 * while it is visible, and lets the developer force open, disable or reset a circuit breaker.
 */
public class ResilienceDevUiProcessor {

    /** The guide's Dev UI section. */
    static final String GUIDE = "https://resilience4cata.dev/#/en/guides/quarkus.md?id=dev-ui";

    /** Creates the processor; Quarkus instantiates it. */
    public ResilienceDevUiProcessor() {
    }

    @BuildStep(onlyIf = IsLocalDevelopment.class)
    CardPageBuildItem card() {
        CardPageBuildItem card = new CardPageBuildItem();
        card.addPage(Page.webComponentPageBuilder()
                .title("Policies")
                .icon("font-awesome-solid:shield-halved")
                .componentLink("qwc-resilience4cata-policies.js")
                .dynamicLabelJsonRPCMethodName("getInstanceCount"));
        card.addPage(Page.externalPageBuilder("Guide")
                .icon("font-awesome-solid:book")
                .url(GUIDE, GUIDE)
                .isHtmlContent()
                .doNotEmbed());
        return card;
    }

    @BuildStep(onlyIf = IsLocalDevelopment.class)
    JsonRPCProvidersBuildItem jsonRpc() {
        return new JsonRPCProvidersBuildItem(ResilienceJsonRpcService.class);
    }
}
