package com.finkraft.management.invoice;

/** Request examples shown in Swagger UI. */
final class ApiExamples {

    static final String INVOICE = """
            {
              "sellerTrn": "100000000000003",
              "buyerTrn": "100000000000011",
              "invoiceNumber": "INV-2026-0001",
              "issueDate": "2026-10-01",
              "currency": "AED",
              "lines": [
                {
                  "description": "Consulting",
                  "quantity": 2,
                  "unitPrice": 50.00,
                  "vatCategory": "S",
                  "vatRate": 5.00,
                  "lineNet": 100.00,
                  "lineVat": 5.00
                }
              ],
              "totals": { "netTotal": 100.00, "vatTotal": 5.00, "grossTotal": 105.00 }
            }""";

    static final String BULK_JSON = "[" + INVOICE + "]";

    static final String BULK_CSV = """
            sellerTrn,buyerTrn,invoiceNumber,issueDate,currency,description,quantity,unitPrice,vatCategory,vatRate,lineNet,lineVat,netTotal,vatTotal,grossTotal
            100000000000003,100000000000011,INV-2026-0002,2026-10-01,AED,Consulting,2,50.00,S,5.00,100.00,5.00,100.00,5.00,105.00""";

    static final String REVISION_WITH_INVOICE = "{ \"invoice\": " + INVOICE + " }";

    static final String REVISION_WITH_SUGGESTION = "{ \"suggestionId\": \"3fa85f64-5717-4562-b3fc-2c963f66afa6\" }";

    private ApiExamples() {}
}
