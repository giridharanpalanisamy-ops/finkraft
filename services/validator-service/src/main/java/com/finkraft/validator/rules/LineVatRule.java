package com.finkraft.validator.rules;

import com.finkraft.canonical.AspError;
import com.finkraft.canonical.CanonicalInvoice;
import com.finkraft.canonical.InvoiceLine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** lineVat = lineNet x vatRate / 100 (scale 2, HALF_UP); vatRate is a percentage. */
@Component
public class LineVatRule implements ValidationRule {

    @Override
    public String id() {
        return "FK-R006";
    }

    @Override
    public List<AspError> check(CanonicalInvoice inv) {
        List<AspError> errors = new ArrayList<>();
        if (inv.lines() == null) {
            return errors;
        }
        for (int i = 0; i < inv.lines().size(); i++) {
            InvoiceLine l = inv.lines().get(i);
            if (l == null || l.lineNet() == null || l.vatRate() == null || l.lineVat() == null) {
                continue;
            }
            BigDecimal expected = Amounts.round(l.lineNet().multiply(l.vatRate()).divide(Amounts.HUNDRED));
            if (!Amounts.equal(expected, l.lineVat())) {
                errors.add(error("lines[" + i + "].lineVat",
                        "lineVat " + l.lineVat().toPlainString() + " should be " + expected.toPlainString() + " (lineNet x vatRate)"));
            }
        }
        return errors;
    }
}
