package com.finkraft.validator.rules;

import com.finkraft.canonical.AspError;
import com.finkraft.canonical.CanonicalInvoice;
import com.finkraft.canonical.InvoiceLine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * VAT category and rate are a permitted pair (UNCL5305 codes): S standard 5%, Z zero-rated 0%,
 * E exempt 0%, O out of scope 0%.
 */
@Component
public class VatCategoryRule implements ValidationRule {

    static final Map<String, BigDecimal> PERMITTED = Map.of(
            "S", new BigDecimal("5"),
            "Z", BigDecimal.ZERO,
            "E", BigDecimal.ZERO,
            "O", BigDecimal.ZERO);

    @Override
    public String id() {
        return "FK-R008";
    }

    @Override
    public List<AspError> check(CanonicalInvoice inv) {
        List<AspError> errors = new ArrayList<>();
        if (inv.lines() == null) {
            return errors;
        }
        for (int i = 0; i < inv.lines().size(); i++) {
            InvoiceLine l = inv.lines().get(i);
            if (l == null || l.vatCategory() == null || l.vatRate() == null) {
                continue;
            }
            BigDecimal rate = PERMITTED.get(l.vatCategory());
            if (rate == null) {
                errors.add(error("lines[" + i + "].vatCategory", "Unknown VAT category " + l.vatCategory()));
            } else if (rate.compareTo(l.vatRate()) != 0) {
                errors.add(error("lines[" + i + "].vatRate", "VAT category " + l.vatCategory() + " requires rate "
                        + rate.toPlainString() + ", not " + l.vatRate().toPlainString()));
            }
        }
        return errors;
    }
}
