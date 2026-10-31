package com.finkraft.adapters;

import com.finkraft.adapter.AspCapabilities;
import com.finkraft.adapter.StatusMode;
import java.util.List;
import java.util.Map;

/**
 * Static facts about each supported ASP. Services that need to know an ASP's status mode
 * (invoice-handler, scheduler-service) read it here without building adapters.
 */
public final class AspCatalog {

    public static final String ASP_A = "asp-a";
    public static final String ASP_B = "asp-b";

    public static final AspCapabilities ASP_A_CAPABILITIES = new AspCapabilities(StatusMode.POLL, true, 10, true);
    public static final AspCapabilities ASP_B_CAPABILITIES = new AspCapabilities(StatusMode.WEBHOOK, false, 0, false);

    private static final Map<String, AspCapabilities> CAPABILITIES =
            Map.of(ASP_A, ASP_A_CAPABILITIES, ASP_B, ASP_B_CAPABILITIES);

    private AspCatalog() {}

    public static AspCapabilities capabilities(String aspId) {
        AspCapabilities caps = CAPABILITIES.get(aspId);
        if (caps == null) {
            throw new IllegalArgumentException("Unknown ASP: " + aspId);
        }
        return caps;
    }

    public static boolean isKnown(String aspId) {
        return CAPABILITIES.containsKey(aspId);
    }

    public static boolean isPoll(String aspId) {
        return capabilities(aspId).statusMode() == StatusMode.POLL;
    }

    public static List<String> aspIds(StatusMode mode) {
        return CAPABILITIES.entrySet().stream()
                .filter(e -> e.getValue().statusMode() == mode)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }
}
