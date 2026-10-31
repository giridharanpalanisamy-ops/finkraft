package com.finkraft.canonical;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * SHA-256 hex over the canonical JSON form of an invoice's business content: keys sorted,
 * decimals as plain strings, dates ISO-8601. The invoice number is excluded so that the duplicate
 * rule can detect "new number, same content" (see docs/ASSUMPTIONS.md).
 */
public final class ContentHash {

    private ContentHash() {}

    public static String of(CanonicalInvoice invoice) {
        return sha256(canonicalJson(invoice));
    }

    static String canonicalJson(CanonicalInvoice raw) {
        CanonicalInvoice inv = Canonicalizer.normalize(raw);
        Map<String, Object> root = new TreeMap<>();
        root.put("sellerTrn", inv.sellerTrn());
        root.put("buyerTrn", inv.buyerTrn());
        root.put("issueDate", inv.issueDate() == null ? null : inv.issueDate().toString());
        root.put("currency", inv.currency());
        List<Object> lines = new ArrayList<>();
        if (inv.lines() != null) {
            for (InvoiceLine l : inv.lines()) {
                Map<String, Object> line = new TreeMap<>();
                line.put("description", l.description());
                line.put("quantity", str(l.quantity()));
                line.put("unitPrice", str(l.unitPrice()));
                line.put("vatCategory", l.vatCategory());
                line.put("vatRate", str(l.vatRate()));
                line.put("lineNet", str(l.lineNet()));
                line.put("lineVat", str(l.lineVat()));
                lines.add(line);
            }
        }
        root.put("lines", lines);
        InvoiceTotals t = inv.totals();
        if (t != null) {
            Map<String, Object> totals = new TreeMap<>();
            totals.put("netTotal", str(t.netTotal()));
            totals.put("vatTotal", str(t.vatTotal()));
            totals.put("grossTotal", str(t.grossTotal()));
            root.put("totals", totals);
        } else {
            root.put("totals", null);
        }
        try {
            return CanonicalJson.mapper().writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String str(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
