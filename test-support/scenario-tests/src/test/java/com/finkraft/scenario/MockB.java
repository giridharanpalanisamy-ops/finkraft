package com.finkraft.scenario;

import static com.finkraft.scenario.FinkraftStack.MOCK_B;

import java.util.Map;

/** Control API of mock-asp-b. */
final class MockB {

    private MockB() {}

    static void faults(FinkraftStack stack, Map<String, Object> faults) {
        Api.post(stack.url(MOCK_B) + "/_control/faults", faults);
    }

    static void reset(FinkraftStack stack) {
        Api.post(stack.url(MOCK_B) + "/_control/reset", Map.of());
    }

    static int posts(FinkraftStack stack, String number) {
        return Api.get(stack.url(MOCK_B) + "/_control/invoices/" + number).body().get("posts").asInt();
    }
}
