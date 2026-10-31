package com.finkraft.validator.rules;

import com.finkraft.canonical.AspError;
import com.finkraft.canonical.CanonicalInvoice;
import java.util.List;

/**
 * One deterministic rule: the same invoice always gives the same errors. Rules skip fields that
 * are missing; FK-R001 reports those.
 */
public interface ValidationRule {

    String id();

    List<AspError> check(CanonicalInvoice invoice);

    default AspError error(String field, String message) {
        return new AspError(id(), field, id(), message);
    }
}
