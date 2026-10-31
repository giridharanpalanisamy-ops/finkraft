package com.finkraft.canonical;

import static org.assertj.core.api.Assertions.assertThat;

import com.finkraft.canonical.DuplicateRule.Create;
import com.finkraft.canonical.DuplicateRule.Existing;
import com.finkraft.canonical.DuplicateRule.NewRevision;
import com.finkraft.canonical.DuplicateRule.RejectDuplicateNumber;
import com.finkraft.canonical.DuplicateRule.Replay;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DuplicateRuleTest {

    private final UUID existingId = UUID.randomUUID();
    private final UUID otherId = UUID.randomUUID();

    @Test
    void sameNumberSameHashIsAReplay() {
        assertThat(DuplicateRule.decide(new Existing(existingId, "h1", InvoiceState.COMPLETED), "h1", null))
                .isEqualTo(new Replay(existingId));
    }

    @ParameterizedTest
    @EnumSource(value = InvoiceState.class, names = {"VALIDATION_FAILED", "REJECTED"}, mode = EnumSource.Mode.EXCLUDE)
    void sameNumberNewHashIsRejectedWhileTheExistingInvoiceIsLive(InvoiceState state) {
        assertThat(DuplicateRule.decide(new Existing(existingId, "h1", state), "h2", null))
                .isEqualTo(new RejectDuplicateNumber(existingId));
    }

    @ParameterizedTest
    @EnumSource(value = InvoiceState.class, names = {"VALIDATION_FAILED", "REJECTED"})
    void sameNumberNewHashBecomesARevisionAfterFailureOrRejection(InvoiceState state) {
        assertThat(DuplicateRule.decide(new Existing(existingId, "h1", state), "h2", null))
                .isEqualTo(new NewRevision(existingId));
    }

    @Test
    void newNumberKnownHashIsCreatedAsPossibleDuplicate() {
        assertThat(DuplicateRule.decide(null, "h1", otherId)).isEqualTo(new Create(otherId));
    }

    @Test
    void newNumberNewHashIsCreated() {
        assertThat(DuplicateRule.decide(null, "h1", null)).isEqualTo(new Create(null));
    }
}
