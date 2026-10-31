package com.finkraft.adapters;

import com.finkraft.adapter.AspAdapter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Adapter lookup by asp_id. */
public class AspAdapters {

    private final Map<String, AspAdapter> byId;

    public AspAdapters(List<AspAdapter> adapters) {
        this.byId = adapters.stream().collect(Collectors.toUnmodifiableMap(AspAdapter::aspId, Function.identity()));
    }

    public AspAdapter get(String aspId) {
        AspAdapter adapter = byId.get(aspId);
        if (adapter == null) {
            throw new IllegalArgumentException("No adapter for ASP " + aspId);
        }
        return adapter;
    }
}
