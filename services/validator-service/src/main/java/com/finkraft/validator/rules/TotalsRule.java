package com.finkraft.validator.rules;

import com.finkraft.canonical.AspError;
import com.finkraft.canonical.CanonicalInvoice;
import com.finkraft.canonical.InvoiceLine;
import com.finkraft.canonical.InvoiceTotals;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Totals equal the sums of the lines; gross = net + VAT. */
@Component
public class TotalsRule implements ValidationRule {

    @Override
    public String id() {
        return "FK-R007";
    }

    @Override
    public List<AspError> check(CanonicalInvoice inv) {
        List<AspError> errors = new ArrayList<>();
        InvoiceTotals t = inv.totals();
        if (t == null || inv.lines() == null || inv.lines().stream().anyMatch(Objects::isNull)) {
            return errors;
        }
        List<InvoiceLine> lines = inv.lines();
        if (t.netTotal() != null && lines.stream().allMatch(l -> l.lineNet() != null)) {
            BigDecimal net = lines.stream().map(InvoiceLine::lineNet).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (!Amounts.equal(net, t.netTotal())) {
                errors.add(error("totals.netTotal", "netTotal should be " + Amounts.round(net).toPlainString() + " (sum of lineNet)"));
            }
        }
        if (t.vatTotal() != null && lines.stream().allMatch(l -> l.lineVat() != null)) {
            BigDecimal vat = lines.stream().map(InvoiceLine::lineVat).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (!Amounts.equal(vat, t.vatTotal())) {
                errors.add(error("totals.vatTotal", "vatTotal should be " + Amounts.round(vat).toPlainString() + " (sum of lineVat)"));
            }
        }
        if (t.grossTotal() != null && t.netTotal() != null && t.vatTotal() != null
                && !Amounts.equal(t.netTotal().add(t.vatTotal()), t.grossTotal())) {
            errors.add(error("totals.grossTotal", "grossTotal should be "
                    + Amounts.round(t.netTotal().add(t.vatTotal())).toPlainString() + " (netTotal + vatTotal)"));
        }
        return errors;
    }
}
