package com.finkraft.adapter;

import com.finkraft.canonical.AspError;
import java.util.List;

public record SubmitResult(SubmitOutcome outcome, String aspRef, String rawStatus, List<AspError> errors, String rawBody) {

    public static SubmitResult of(SubmitOutcome outcome, String rawBody) {
        return new SubmitResult(outcome, null, null, List.of(), rawBody);
    }
}
