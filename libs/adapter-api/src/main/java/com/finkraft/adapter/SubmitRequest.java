package com.finkraft.adapter;

import com.finkraft.canonical.CanonicalInvoice;
import java.util.UUID;

public record SubmitRequest(UUID invoiceId, String clientRef, CanonicalInvoice invoice, AspCredentials credentials) {}
