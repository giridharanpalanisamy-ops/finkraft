package com.finkraft.events.internal;

import com.finkraft.canonical.CanonicalInvoice;
import java.util.UUID;

/** Body of POST /internal/invoices (management-service to invoice-handler). */
public record CreateInvoiceRequest(UUID tenantId, UUID sellerId, CanonicalInvoice invoice) {}
