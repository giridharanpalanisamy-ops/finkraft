package com.finkraft.events.payload;

import java.util.UUID;

public record ValidationRequested(UUID invoiceId, int revision) {}
