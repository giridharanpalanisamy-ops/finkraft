package com.finkraft.validator;

import com.finkraft.canonical.AspError;
import com.finkraft.canonical.CanonicalInvoice;
import com.finkraft.validator.rules.ValidationRule;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/** Runs every ValidationRule bean, in rule-id order so the error list is deterministic. */
@Component
public class InvoiceValidator {

    private final List<ValidationRule> rules;

    public InvoiceValidator(List<ValidationRule> rules) {
        this.rules = rules.stream().sorted(Comparator.comparing(ValidationRule::id)).toList();
    }

    public List<AspError> validate(CanonicalInvoice invoice) {
        return rules.stream().flatMap(r -> r.check(invoice).stream()).toList();
    }
}
