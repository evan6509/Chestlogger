package com.chestlogger.csv;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class InventoryDiff {
    private InventoryDiff() { }

    public record Change<T>(T item, int delta) { }

    public static <T> List<Change<T>> between(Map<T, Integer> before, Map<T, Integer> after) {
        var keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        List<Change<T>> changes = new ArrayList<>();
        for (T key : keys) {
            int delta = after.getOrDefault(key, 0) - before.getOrDefault(key, 0);
            if (delta != 0) changes.add(new Change<>(key, delta));
        }
        return changes;
    }
}
