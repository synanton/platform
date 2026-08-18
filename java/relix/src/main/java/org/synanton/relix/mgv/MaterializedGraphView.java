package org.synanton.relix.mgv;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

public class MaterializedGraphView {

    public record View(Object payload, Instant refreshedAt) {}

    private final ConcurrentHashMap<String, View> views = new ConcurrentHashMap<>();
    private final Duration maxLag;

    public MaterializedGraphView(Duration maxLag) {
        this.maxLag = maxLag;
    }

    public void put(String key, Object payload, Instant refreshedAt) {
        views.put(key, new View(payload, refreshedAt));
    }

    public View lookup(String key, Instant now) {
        View view = views.get(key);
        if (view == null) {
            return null;
        }
        if (Duration.between(view.refreshedAt(), now).compareTo(maxLag) > 0) {
            return null;
        }
        return view;
    }
}
