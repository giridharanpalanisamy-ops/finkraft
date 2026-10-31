package com.finkraft.validator.rules;

import com.finkraft.canonical.AspError;
import com.finkraft.canonical.CanonicalInvoice;
import java.util.Currency;
import java.util.List;
import org.springframework.stereotype.Component;

/** The currency is a valid ISO 4217 code. */
@Component
public class CurrencyRule implements ValidationRule {

    @Override
    public String id() {
        return "FK-R004";
    }

    @Override
    public List<AspError> check(CanonicalInvoice inv) {
        if (inv.currency() == null) {
            return List.of();
        }
        try {
            if (inv.currency().matches("[A-Z]{3}") && Currency.getInstance(inv.currency()) != null) {
                return List.of();
            }
        } catch (IllegalArgumentException e) {
            // not a known code
        }
        return List.of(error("currency", inv.currency() + " is not an ISO 4217 currency code"));
    }
}
