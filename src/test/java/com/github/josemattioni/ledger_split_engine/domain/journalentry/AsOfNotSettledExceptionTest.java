package com.github.josemattioni.ledger_split_engine.domain.journalentry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AsOfNotSettledExceptionTest {

    @Test
    void theMessageStatesTheSixtySecondWindow() {
        assertThat(new AsOfNotSettledException().getMessage())
                .contains("as_of")
                .contains("60 seconds")
                .contains("database's current time");
    }

    @Test
    void itIsNotAnIllegalArgumentExceptionSoTheWebLayerDoesNotTakeItForAMalformedRequest() {
        assertThat(new AsOfNotSettledException()).isNotInstanceOf(IllegalArgumentException.class);
    }
}
