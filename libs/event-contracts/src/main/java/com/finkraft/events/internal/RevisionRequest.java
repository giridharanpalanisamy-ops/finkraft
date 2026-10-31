package com.finkraft.events.internal;

import com.finkraft.canonical.CanonicalInvoice;
import java.util.UUID;

/** Body of POST /internal/invoices/{id}/revisions. suggestionId is set when approving a fix suggestion. */
public record RevisionRequest(CanonicalInvoice invoice, UUID suggestionId) {}
