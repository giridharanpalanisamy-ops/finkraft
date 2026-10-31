package com.finkraft.events.payload;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public record FixSuggested(UUID invoiceId, int revision, JsonNode suggestion) {}
