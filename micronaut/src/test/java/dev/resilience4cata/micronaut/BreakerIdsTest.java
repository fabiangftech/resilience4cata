package dev.resilience4cata.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code down-when-open} is a list property: a derived id split at its commas is joined back. */
class BreakerIdsTest {

    @Test
    void partsOfADerivedIdAreJoinedBackAndOtherIdsStaySeparate() {
        assertThat(MicronautSettings.breakerIds(List.of("payments", "com.acme.Charges#charge(java.lang.String",
                " int)", "*", "com.acme.Stock#count()", "")))
                .containsExactly("payments", "com.acme.Charges#charge(java.lang.String,int)", "*", "com.acme.Stock#count()");
    }
}
