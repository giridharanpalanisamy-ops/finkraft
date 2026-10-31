package com.finkraft.validator.rules;

import com.finkraft.canonical.AspError;
import com.finkraft.canonical.CanonicalInvoice;
import com.finkraft.canonical.InvoiceLine;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RequiredFieldsRule implements ValidationRule {

    @Override
    public String id() {
        return "FK-R001";
    }

    @Override
    public List<AspError> check(CanonicalInvoice inv) {
        List<AspError> errors = new ArrayList<>();
        require(errors, inv.sellerTrn(), "sellerTrn");
        require(errors, inv.buyerTrn(), "buyerTrn");
        require(errors, inv.invoiceNumber(), "invoiceNumber");
        require(errors, inv.issueDate(), "issueDate");
        require(errors, inv.currency(), "currency");
        if (inv.lines() == null || inv.lines().isEmpty()) {
            errors.add(error("lines", "At least one line is required"));
        } else {
            for (int i = 0; i < inv.lines().size(); i++) {
                InvoiceLine l = inv.lines().get(i);
                String p = "lines[" + i + "].";
                if (l == null) {
                    errors.add(error("lines[" + i + "]", "Line is empty"));
                    continue;
                }
                require(errors, l.description(), p + "description");
                require(errors, l.quantity(), p + "quantity");
                require(errors, l.unitPrice(), p + "unitPrice");
                require(errors, l.vatCategory(), p + "vatCategory");
                require(errors, l.vatRate(), p + "vatRate");
                require(errors, l.lineNet(), p + "lineNet");
                require(errors, l.lineVat(), p + "lineVat");
            }
        }
        if (inv.totals() == null) {
            errors.add(error("totals", "Totals are required"));
        } else {
            require(errors, inv.totals().netTotal(), "totals.netTotal");
            require(errors, inv.totals().vatTotal(), "totals.vatTotal");
            require(errors, inv.totals().grossTotal(), "totals.grossTotal");
        }
        return errors;
    }

    private void require(List<AspError> errors, Object value, String field) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            errors.add(error(field, field + " is required"));
        }
    }
}
