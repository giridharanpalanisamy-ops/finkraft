package com.finkraft.scenario;

import static com.finkraft.scenario.FinkraftStack.MOCK_A;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** Control API of mock-asp-a. */
final class MockA {

    private MockA() {}

    static void faults(FinkraftStack stack, Map<String, Object> faults) {
        Api.post(stack.url(MOCK_A) + "/_control/faults", faults);
    }

    static void reset(FinkraftStack stack) {
        Api.post(stack.url(MOCK_A) + "/_control/reset", Map.of());
    }

    static JsonNode stats(FinkraftStack stack) {
        return Api.get(stack.url(MOCK_A) + "/_control/stats").body();
    }

    /** posts received and submissions accepted for one invoice number. */
    static JsonNode invoice(FinkraftStack stack, String number) {
        return Api.get(stack.url(MOCK_A) + "/_control/invoices/" + number).body();
    }
}
