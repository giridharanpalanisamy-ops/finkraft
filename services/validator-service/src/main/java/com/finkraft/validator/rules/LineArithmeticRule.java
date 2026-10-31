package com.finkraft.validator.rules;

import com.finkraft.canonical.AspError;
import com.finkraft.canonical.CanonicalInvoice;
import com.finkraft.canonical.InvoiceLine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** lineNet = quantity x unitPrice (scale 2, HALF_UP). */
@Component
public class LineArithmeticRule implements ValidationRule {

    @Override
    public String id() {
        return "FK-R005";
    }

    @Override
    public List<AspError> check(CanonicalInvoice inv) {
        List<AspError> errors = new ArrayList<>();
        if (inv.lines() == null) {
            return errors;
        }
        for (int i = 0; i < inv.lines().size(); i++) {
            InvoiceLine l = inv.lines().get(i);
            if (l == null || l.quantity() == null || l.unitPrice() == null || l.lineNet() == null) {
                continue;
            }
            BigDecimal expected = Amounts.round(l.quantity().multiply(l.unitPrice()));
            if (!Amounts.equal(expected, l.lineNet())) {
                errors.add(error("lines[" + i + "].lineNet",
                        "lineNet " + l.lineNet().toPlainString() + " should be " + expected.toPlainString() + " (quantity x unitPrice)"));
            }
        }
        return errors;
    }
}
