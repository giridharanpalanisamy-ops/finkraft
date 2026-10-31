package com.finkraft.management.invoice;

import com.fasterxml.jackson.databind.JsonNode;
import com.finkraft.canonical.CanonicalInvoice;
import com.finkraft.canonical.CanonicalJson;
import com.finkraft.canonical.Canonicalizer;
import com.finkraft.canonical.InvoiceLine;
import com.finkraft.canonical.InvoiceTotals;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses uploads into canonical form. JSON is the canonical invoice itself. CSV carries one
 * single-line invoice per row with the header {@link #CSV_COLUMNS}.
 */
public final class InvoiceParser {

    public static final List<String> CSV_COLUMNS = List.of(
            "sellerTrn", "buyerTrn", "invoiceNumber", "issueDate", "currency", "description", "quantity",
            "unitPrice", "vatCategory", "vatRate", "lineNet", "lineVat", "netTotal", "vatTotal", "grossTotal");

    /** One parsed row: an invoice, or the reason it could not be parsed. */
    public record Row(int index, CanonicalInvoice invoice, String error) {}

    private InvoiceParser() {}

    public static CanonicalInvoice fromJson(JsonNode node) {
        try {
            return Canonicalizer.normalize(CanonicalJson.mapper().treeToValue(node, CanonicalInvoice.class));
        } catch (Exception e) {
            throw new IllegalArgumentException("Not a valid invoice: " + rootMessage(e));
        }
    }

    public static List<Row> fromJsonArray(JsonNode array) {
        if (array == null || !array.isArray()) {
            throw new IllegalArgumentException("Expected a JSON array of invoices");
        }
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            try {
                rows.add(new Row(i, fromJson(array.get(i)), null));
            } catch (IllegalArgumentException e) {
                rows.add(new Row(i, null, e.getMessage()));
            }
        }
        return rows;
    }

    public static List<Row> fromCsv(String csv) {
        List<String> lines = csv.lines().filter(l -> !l.isBlank()).toList();
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("Empty CSV");
        }
        List<String> header = splitCsvLine(lines.get(0));
        if (!header.containsAll(CSV_COLUMNS)) {
            throw new IllegalArgumentException("CSV header must contain " + String.join(",", CSV_COLUMNS));
        }
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            int index = i - 1;
            try {
                List<String> cells = splitCsvLine(lines.get(i));
                Map<String, String> v = new HashMap<>();
                for (int c = 0; c < header.size() && c < cells.size(); c++) {
                    v.put(header.get(c), cells.get(c).isBlank() ? null : cells.get(c));
                }
                InvoiceLine line = new InvoiceLine(v.get("description"), dec(v, "quantity"), dec(v, "unitPrice"),
                        v.get("vatCategory"), dec(v, "vatRate"), dec(v, "lineNet"), dec(v, "lineVat"));
                CanonicalInvoice invoice = new CanonicalInvoice(v.get("sellerTrn"), v.get("buyerTrn"), v.get("invoiceNumber"),
                        v.get("issueDate") == null ? null : LocalDate.parse(v.get("issueDate").strip()), v.get("currency"),
                        List.of(line), new InvoiceTotals(dec(v, "netTotal"), dec(v, "vatTotal"), dec(v, "grossTotal")));
                rows.add(new Row(index, Canonicalizer.normalize(invoice), null));
            } catch (Exception e) {
                rows.add(new Row(index, null, "Row " + index + ": " + rootMessage(e)));
            }
        }
        return rows;
    }

    private static BigDecimal dec(Map<String, String> v, String key) {
        String s = v.get(key);
        try {
            return s == null ? null : new BigDecimal(s.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " is not a number: " + s);
        }
    }

    /** RFC 4180 style: commas separate, double quotes enclose, "" escapes a quote. */
    static List<String> splitCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage().lines().findFirst().orElse("");
    }
}
