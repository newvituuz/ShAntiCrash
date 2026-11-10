package br.com.singlehope.shanticrash.checks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** The {@code options} block of a check, with per value fallbacks. */
public final class CheckOptions {
    public static final CheckOptions EMPTY = new CheckOptions(Collections.<String, Object>emptyMap());

    private final Map<String, Object> values;

    public CheckOptions(Map<String, Object> values) {
        this.values = values;
    }

    public int getInt(String key, int fallback) {
        Object value = values.get(key);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    public long getLong(String key, long fallback) {
        Object value = values.get(key);
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    public double getDouble(String key, double fallback) {
        Object value = values.get(key);
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    public boolean getBoolean(String key, boolean fallback) {
        Object value = values.get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    /** Reads YAML lists of rule maps, once per reload rather than on the packet path. */
    public List<Map<String, Object>> getMapList(String key) {
        Object value = values.get(key);
        if (!(value instanceof List)) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (item instanceof Map) {
                Map<?, ?> source = (Map<?, ?>) item;
                Map<String, Object> copy = new java.util.HashMap<>();
                for (Map.Entry<?, ?> entry : source.entrySet()) {
                    if (entry.getKey() instanceof String) {
                        copy.put((String) entry.getKey(), entry.getValue());
                    }
                }
                result.add(copy);
            }
        }
        return result;
    }

    public List<String> getStringList(String key, List<String> fallback) {
        Object value = values.get(key);
        if (!(value instanceof List)) {
            return fallback;
        }
        List<?> raw = (List<?>) value;
        List<String> result = new ArrayList<>(raw.size());
        for (Object element : raw) {
            if (element != null) {
                result.add(String.valueOf(element));
            }
        }
        return result;
    }
}
