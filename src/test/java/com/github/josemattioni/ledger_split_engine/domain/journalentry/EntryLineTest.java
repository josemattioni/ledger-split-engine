package com.github.josemattioni.ledger_split_engine.domain.journalentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class EntryLineTest {

    @Test
    void acceptsValidLine() {
        EntryLine line = new EntryLine(1, "acquirer_fee", 100L);

        assertThat(line.lineNumber()).isEqualTo(1);
        assertThat(line.accountCode()).isEqualTo("acquirer_fee");
        assertThat(line.amountCents()).isEqualTo(100L);
    }

    @Test
    void rejectsLineNumberZero() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EntryLine(0, "acquirer_fee", 100L))
                .withMessageContaining("line_number must be at least 1");
    }

    @Test
    void rejectsNegativeLineNumber() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EntryLine(-1, "acquirer_fee", 100L))
                .withMessageContaining("line_number must be at least 1");
    }

    @Test
    void rejectsNullAccountCode() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EntryLine(1, null, 100L))
                .withMessageContaining("account_code must not be null");
    }
}
