package com.finkraft.validator.rules;

import com.finkraft.canonical.AspError;
import com.finkraft.canonical.CanonicalInvoice;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** A UAE TRN is 15 digits. */
@Component
public class TrnRule implements ValidationRule {

    private static final Pattern TRN = Pattern.compile("\\d{15}");

    @Override
    public String id() {
        return "FK-R002";
    }

    @Override
    public List<AspError> check(CanonicalInvoice inv) {
        List<AspError> errors = new ArrayList<>();
        if (inv.sellerTrn() != null && !TRN.matcher(inv.sellerTrn()).matches()) {
            errors.add(error("sellerTrn", "Seller TRN must be 15 digits"));
        }
        if (inv.buyerTrn() != null && !TRN.matcher(inv.buyerTrn()).matches()) {
            errors.add(error("buyerTrn", "Buyer TRN must be 15 digits"));
        }
        return errors;
    }
}
