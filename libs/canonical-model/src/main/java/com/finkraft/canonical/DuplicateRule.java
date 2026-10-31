package com.finkraft.canonical;

import java.util.UUID;

/** The duplicate decision table applied when an invoice is uploaded (spec section 6). */
public final class DuplicateRule {

    /** The invoice already stored under the same seller and invoice number. */
    public record Existing(UUID id, String contentHash, InvoiceState state) {}

    public sealed interface Decision {}

    /** Same number, same content: return the existing invoice, emit nothing. */
    public record Replay(UUID existingId) implements Decision {}

    /** Same number, different content, existing invoice still live. */
    public record RejectDuplicateNumber(UUID existingId) implements Decision {}

    /** Same number, different content, existing invoice failed or was rejected. */
    public record NewRevision(UUID existingId) implements Decision {}

    /** New number. possibleDuplicateOf is set when another invoice has the same content. */
    public record Create(UUID possibleDuplicateOf) implements Decision {}

    private DuplicateRule() {}

    /**
     * @param sameNumber the invoice with this seller and number, or null
     * @param sameHashOther another invoice of this seller with the same content hash, or null
     */
    public static Decision decide(Existing sameNumber, String newHash, UUID sameHashOther) {
        if (sameNumber == null) {
            return new Create(sameHashOther);
        }
        if (sameNumber.contentHash().equals(newHash)) {
            return new Replay(sameNumber.id());
        }
        if (sameNumber.state() == InvoiceState.VALIDATION_FAILED || sameNumber.state() == InvoiceState.REJECTED) {
            return new NewRevision(sameNumber.id());
        }
        return new RejectDuplicateNumber(sameNumber.id());
    }
}
