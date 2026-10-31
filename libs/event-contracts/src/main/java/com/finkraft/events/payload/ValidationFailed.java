package com.finkraft.events.payload;

import com.finkraft.canonical.AspError;
import java.util.List;
import java.util.UUID;

public record ValidationFailed(UUID invoiceId, int revision, List<AspError> errors) {}
