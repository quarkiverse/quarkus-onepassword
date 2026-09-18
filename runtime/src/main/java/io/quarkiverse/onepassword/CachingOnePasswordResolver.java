package io.quarkiverse.onepassword;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Per-config-instance cache. Serializes lookups to avoid concurrent desktop prompts. */
public final class CachingOnePasswordResolver implements OnePasswordResolver {
    private final OnePasswordResolver delegate;
    private final int capacity;
    private final Map<String, String> cache = new LinkedHashMap<>(16, 0.75f, true);

    public CachingOnePasswordResolver(OnePasswordResolver delegate, int capacity) {
        this.delegate = Objects.requireNonNull(delegate);
        if (capacity < 1) throw new IllegalArgumentException("Cache capacity must be positive");
        this.capacity = capacity;
    }

    @Override
    public synchronized String resolve(String reference) {
        CliOnePasswordResolver.validateReference(reference);
        String value = cache.get(reference);
        if (value != null) return value;
        value = Objects.requireNonNull(delegate.resolve(reference), "Resolver returned null");
        if (cache.size() == capacity) cache.remove(cache.keySet().iterator().next());
        cache.put(reference, value);
        return value;
    }
}
