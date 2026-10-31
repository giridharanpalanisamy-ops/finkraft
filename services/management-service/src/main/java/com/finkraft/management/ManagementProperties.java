package com.finkraft.management;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** finkraft.* keys used by management-service; values live in management-service.yml. */
@ConfigurationProperties("finkraft")
public record ManagementProperties(Admin admin, Bulk bulk, InvoiceHandler invoiceHandler) {

    public record Admin(String apiKey) {}

    public record Bulk(int maxRows) {}

    public record InvoiceHandler(String url) {}
}
