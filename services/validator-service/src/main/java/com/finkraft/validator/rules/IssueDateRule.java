package com.finkraft.validator.rules;

import com.finkraft.canonical.AspError;
import com.finkraft.canonical.CanonicalInvoice;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/** The issue date may not be later than today in the UAE. */
@Component
public class IssueDateRule implements ValidationRule {

    private final Clock uaeClock;

    public IssueDateRule(Clock uaeClock) {
        this.uaeClock = uaeClock;
    }

    @Override
    public String id() {
        return "FK-R003";
    }

    @Override
    public List<AspError> check(CanonicalInvoice inv) {
        if (inv.issueDate() != null && inv.issueDate().isAfter(LocalDate.now(uaeClock))) {
            return List.of(error("issueDate", "Issue date " + inv.issueDate() + " is in the future"));
        }
        return List.of();
    }
}
