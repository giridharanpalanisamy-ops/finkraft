package com.finkraft.events.payload;

import java.util.UUID;

public record PollDue(UUID invoiceId, String aspId, String aspRef) {}
