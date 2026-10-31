package com.finkraft.events.payload;

import java.util.UUID;

public record SubmissionRequested(UUID invoiceId, String aspId) {}
