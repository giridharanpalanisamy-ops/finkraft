package com.finkraft.events.internal;

import java.util.UUID;

public record ErrorResponse(String error, String message, UUID invoiceId) {}
