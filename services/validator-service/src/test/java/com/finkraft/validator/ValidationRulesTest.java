package com.finkraft.validator;

import static org.assertj.core.api.Assertions.assertThat;

import com.finkraft.canonical.AspError;
import com.finkraft.canonical.CanonicalInvoice;
import com.finkraft.canonical.InvoiceLine;
import com.finkraft.canonical.InvoiceTotals;
import com.finkraft.validator.rules.CurrencyRule;
import com.finkraft.validator.rules.IssueDateRule;
import com.finkraft.validator.rules.LineArithmeticRule;
import com.finkraft.validator.rules.LineVatRule;
import com.finkraft.validator.rules.RequiredFieldsRule;
import com.finkraft.validator.rules.TotalsRule;
import com.finkraft.validator.rules.TrnRule;
import com.finkraft.validator.rules.VatCategoryRule;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValidationRulesTest {

    /** 2026-10-31 in Dubai. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-31T10:00:00Z"), ZoneId.of("Asia/Dubai"));

    private final InvoiceValidator validator = new InvoiceValidator(List.of(
            new RequiredFieldsRule(), new TrnRule(), new IssueDateRule(CLOCK), new CurrencyRule(),
            new LineArithmeticRule(), new LineVatRule(), new TotalsRule(), new VatCategoryRule()));

    static InvoiceLine line(String qty, String price, String cat, String rate, String net, String vat) {
        return new InvoiceLine("Item", d(qty), d(price), cat, d(rate), d(net), d(vat));
    }

    static CanonicalInvoice invoice(List<InvoiceLine> lines, InvoiceTotals totals) {
        return new CanonicalInvoice("100000000000003", "100000000000011", "INV-1", LocalDate.of(2026, 10, 1), "AED", lines, totals);
    }

    static CanonicalInvoice valid() {
        return invoice(List.of(line("2", "50", "S", "5", "100", "5"), line("3", "0.335", "Z", "0", "1.01", "0")),
                new InvoiceTotals(d("101.01"), d("5"), d("106.01")));
    }

    static BigDecimal d(String s) {
        return s == null ? null : new BigDecimal(s);
    }

    private List<String> ruleIds(CanonicalInvoice inv) {
        return validator.validate(inv).stream().map(AspError::rule).distinct().toList();
    }

    @Test
    void aValidInvoicePassesEveryRule() {
        assertThat(validator.validate(valid())).isEmpty();
    }

    @Test
    void sameInputAlwaysGivesTheSameErrors() {
        CanonicalInvoice bad = new CanonicalInvoice("123", null, "INV-1", LocalDate.of(2027, 1, 1), "XYZ",
                List.of(line("2", "50", "S", "7", "99", "5")), new InvoiceTotals(d("1"), d("1"), d("3")));
        assertThat(validator.validate(bad)).isEqualTo(validator.validate(bad)).isNotEmpty();
    }

    @Test
    void fkR001RequiredFields() {
        CanonicalInvoice inv = new CanonicalInvoice(null, " ", "INV-1", null, null, List.of(), null);
        List<AspError> errors = new RequiredFieldsRule().check(inv);
        assertThat(errors).extracting(AspError::field)
                .containsExactlyInAnyOrder("sellerTrn", "buyerTrn", "issueDate", "currency", "lines", "totals");
        assertThat(errors).allSatisfy(e -> assertThat(e.rule()).isEqualTo("FK-R001"));
        assertThat(new RequiredFieldsRule().check(invoice(List.of(new InvoiceLine("x", null, d("1"), "S", d("5"), d("1"), d("0.05"))),
                new InvoiceTotals(d("1"), d("0.05"), d("1.05"))))).extracting(AspError::field).containsExactly("lines[0].quantity");
    }

    @Test
    void fkR002TrnIsFifteenDigits() {
        CanonicalInvoice inv = new CanonicalInvoice("10000000000000", "10000000000001A", "INV-1", LocalDate.of(2026, 10, 1), "AED",
                valid().lines(), valid().totals());
        assertThat(new TrnRule().check(inv)).extracting(AspError::field).containsExactly("sellerTrn", "buyerTrn");
        assertThat(new TrnRule().check(valid())).isEmpty();
    }

    @Test
    void fkR003IssueDateNotInTheFutureInUaeTime() {
        IssueDateRule rule = new IssueDateRule(CLOCK);
        assertThat(rule.check(withDate(LocalDate.of(2026, 10, 31)))).isEmpty();
        assertThat(rule.check(withDate(LocalDate.of(2026, 11, 1)))).hasSize(1);
        // 23:30 UTC on Oct 31 is already Nov 1 in Dubai
        IssueDateRule late = new IssueDateRule(Clock.fixed(Instant.parse("2026-10-31T23:30:00Z"), ZoneId.of("Asia/Dubai")));
        assertThat(late.check(withDate(LocalDate.of(2026, 11, 1)))).isEmpty();
    }

    @Test
    void fkR004CurrencyIsIso4217() {
        assertThat(new CurrencyRule().check(withCurrency("AED"))).isEmpty();
        assertThat(new CurrencyRule().check(withCurrency("USD"))).isEmpty();
        assertThat(new CurrencyRule().check(withCurrency("XYZ"))).hasSize(1);
        assertThat(new CurrencyRule().check(withCurrency("aed"))).hasSize(1);
    }

    @Test
    void fkR005LineNetIsQuantityTimesUnitPriceHalfUp() {
        // 3 x 0.335 = 1.005 -> 1.01 HALF_UP
        assertThat(new LineArithmeticRule().check(valid())).isEmpty();
        CanonicalInvoice bad = invoice(List.of(line("3", "0.335", "Z", "0", "1.00", "0")), valid().totals());
        assertThat(new LineArithmeticRule().check(bad)).extracting(AspError::field).containsExactly("lines[0].lineNet");
    }

    @Test
    void fkR006LineVatIsLineNetTimesRate() {
        CanonicalInvoice ok = invoice(List.of(line("1", "10.10", "S", "5", "10.10", "0.51")), new InvoiceTotals(d("10.10"), d("0.51"), d("10.61")));
        assertThat(new LineVatRule().check(ok)).isEmpty(); // 0.505 -> 0.51
        CanonicalInvoice bad = invoice(List.of(line("1", "10.10", "S", "5", "10.10", "0.50")), ok.totals());
        assertThat(new LineVatRule().check(bad)).extracting(AspError::field).containsExactly("lines[0].lineVat");
    }

    @Test
    void fkR007TotalsEqualTheSumOfLines() {
        assertThat(new TotalsRule().check(valid())).isEmpty();
        CanonicalInvoice bad = invoice(valid().lines(), new InvoiceTotals(d("100"), d("6"), d("106")));
        assertThat(new TotalsRule().check(bad)).extracting(AspError::field).containsExactly("totals.netTotal", "totals.vatTotal");
        CanonicalInvoice badGross = invoice(valid().lines(), new InvoiceTotals(d("101.01"), d("5"), d("106.00")));
        assertThat(new TotalsRule().check(badGross)).extracting(AspError::field).containsExactly("totals.grossTotal");
    }

    @Test
    void fkR008VatCategoryAndRateArePermitted() {
        assertThat(new VatCategoryRule().check(valid())).isEmpty();
        CanonicalInvoice bad = invoice(List.of(line("1", "1", "S", "0", "1", "0"), line("1", "1", "X", "5", "1", "0.05")), valid().totals());
        assertThat(new VatCategoryRule().check(bad)).extracting(AspError::field)
                .containsExactly("lines[0].vatRate", "lines[1].vatCategory");
    }

    @Test
    void errorsCarryRuleIdFieldAndMessage() {
        AspError e = validator.validate(withCurrency("XYZ")).get(0);
        assertThat(e.rule()).isEqualTo("FK-R004");
        assertThat(e.code()).isEqualTo("FK-R004");
        assertThat(e.field()).isEqualTo("currency");
        assertThat(e.message()).contains("XYZ");
        assertThat(ruleIds(withCurrency("XYZ"))).containsExactly("FK-R004");
    }

    private static CanonicalInvoice withDate(LocalDate date) {
        CanonicalInvoice v = valid();
        return new CanonicalInvoice(v.sellerTrn(), v.buyerTrn(), v.invoiceNumber(), date, v.currency(), v.lines(), v.totals());
    }

    private static CanonicalInvoice withCurrency(String currency) {
        CanonicalInvoice v = valid();
        return new CanonicalInvoice(v.sellerTrn(), v.buyerTrn(), v.invoiceNumber(), v.issueDate(), currency, v.lines(), v.totals());
    }
}
