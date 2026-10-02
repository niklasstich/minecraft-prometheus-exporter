package com.github.cpburnz.minecraft_prometheus_exporter.tracking;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Server-thread event accounting; only enabled, explicitly bound targets can accumulate.
 * Runtime tokens are compared by identity and never enter persistence.
 */
public final class InstrumentationStore {

    public static final int MAX_IDENTITIES_PER_TARGET = 4096;

    @com.github.bsideup.jabel.Desugar
    public record Key(String targetId, String identity) {

        public Key {
            Objects.requireNonNull(targetId);
            Objects.requireNonNull(identity);
        }
    }

    @com.github.bsideup.jabel.Desugar
    public record Totals(BigInteger input, BigInteger output, BigInteger loss, BigInteger progress) {}

    private static final Totals ZERO = new Totals(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);

    @com.github.bsideup.jabel.Desugar
    private record Entry(Object binding, Target selection, Totals totals) {}

    private final TargetRegistry registry;
    private final Map<Key, Entry> entries = new HashMap<>();
    private boolean active;

    public InstrumentationStore(TargetRegistry registry) {
        this.registry = registry;
    }

    public void start() {
        registry.checkThread();
        entries.clear();
        active = true;
    }

    public void stop() {
        registry.checkThread();
        active = false;
        entries.clear();
    }

    private boolean selected(Key key) {
        Target target = registry.get(key.targetId());
        return active && target != null && target.enabled();
    }

    /** Rebinding after unload/replacement resets counters; hooks cannot implicitly add identities. */
    public boolean bind(Key key, Object token) {
        registry.checkThread();
        Objects.requireNonNull(token);
        prune();
        if (!selected(key)) return false;
        Entry old = entries.get(key);
        if (old != null && old.binding == token && old.selection == registry.get(key.targetId())) return true;
        long count = entries.keySet()
            .stream()
            .filter(
                k -> k.targetId()
                    .equals(key.targetId()))
            .count();
        if (old == null && count >= MAX_IDENTITIES_PER_TARGET) return false;
        entries.put(key, new Entry(token, registry.get(key.targetId()), ZERO));
        return true;
    }

    /** Negative upstream overflow values reject the entire event, never becoming positive increments. */
    public boolean add(Key key, Object token, BigInteger input, BigInteger output, BigInteger loss,
        BigInteger progress) {
        registry.checkThread();
        if (!selected(key)) {
            entries.remove(key);
            return false;
        }
        Entry entry = entries.get(key);
        if (entry == null || entry.binding != token || entry.selection != registry.get(key.targetId())) return false;
        if (input.signum() < 0 || output.signum() < 0 || loss.signum() < 0 || progress.signum() < 0) return false;
        Totals t = entry.totals;
        entries.put(
            key,
            new Entry(
                token,
                entry.selection,
                new Totals(t.input.add(input), t.output.add(output), t.loss.add(loss), t.progress.add(progress))));
        return true;
    }

    public Totals read(Key key) {
        registry.checkThread();
        if (!selected(key)) {
            entries.remove(key);
            return null;
        }
        Entry entry = entries.get(key);
        if (entry != null && entry.selection != registry.get(key.targetId())) {
            entries.remove(key);
            return null;
        }
        return entry == null ? null : entry.totals;
    }

    public void unbind(Key key) {
        registry.checkThread();
        entries.remove(key);
    }

    public void clearDimensionBindings() {
        registry.checkThread();
        entries.clear();
    }

    public void prune() {
        registry.checkThread();
        entries.keySet()
            .removeIf(key -> !selected(key) || entries.get(key).selection != registry.get(key.targetId()));
    }
}
