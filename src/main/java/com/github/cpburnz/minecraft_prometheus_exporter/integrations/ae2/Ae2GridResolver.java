package com.github.cpburnz.minecraft_prometheus_exporter.integrations.ae2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.github.cpburnz.minecraft_prometheus_exporter.ModCompat;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.IntegrationAdapter;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.Target;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.TargetRegistry;

/** Server-thread API. Grid tokens are transient and must never be persisted or used by HTTP threads. */
public final class Ae2GridResolver implements IntegrationAdapter<Object> {

    public interface Backend {

        Object resolve(Target.Anchor anchor);

        List<Candidate> discover(int dimension);
    }

    @com.github.bsideup.jabel.Desugar
    public record Candidate(Target.Anchor anchor, Object grid) {}

    @com.github.bsideup.jabel.Desugar
    public record ResolvedGrid(Target target, Resolution<Object> resolution, String aliasOf) {}

    public static final Comparator<Target.Anchor> ANCHORS = Comparator.comparingInt(Target.Anchor::dimension)
        .thenComparingInt(Target.Anchor::x)
        .thenComparingInt(Target.Anchor::y)
        .thenComparingInt(Target.Anchor::z)
        .thenComparingInt(Target.Anchor::part);
    private final TargetRegistry registry;
    private final Backend backend;

    public Ae2GridResolver(TargetRegistry registry, Backend backend) {
        this.registry = registry;
        this.backend = backend;
    }

    public static Ae2GridResolver create(TargetRegistry registry) {
        return ModCompat.AppliedEnergistics2.isSupported() ? new Ae2GridResolver(registry, new Ae2MinecraftBackend())
            : null;
    }

    @Override
    public Resolution<Object> resolve(Target target) {
        registry.checkThread();
        if (target.kind() != Target.Kind.AE2) return new Resolution<>(Status.UNSUPPORTED, null, "Expected AE2 anchor");
        if (!target.equals(registry.get(target.id())) || !target.enabled())
            return new Resolution<>(Status.DISABLED, null, "Disabled or stale selection");
        return inspect(target);
    }

    @Override
    public Resolution<Object> inspect(Target target) {
        registry.checkThread();
        if (target.kind() != Target.Kind.AE2) return new Resolution<>(Status.UNSUPPORTED, null, "Expected AE2 anchor");
        try {
            Object grid = backend.resolve(target.anchor());
            return new Resolution<>(
                grid == null ? Status.UNAVAILABLE : Status.AVAILABLE,
                grid,
                grid == null ? "Anchor missing or unloaded" : "");
        } catch (RuntimeException | LinkageError e) {
            return new Resolution<>(Status.ERROR, null, e.toString());
        }
    }

    /**
     * Re-resolves every enabled anchor; lowest numeric configured ID owns a merged grid. Splits need no cached state.
     */
    public List<ResolvedGrid> resolveSelections() {
        registry.checkThread();
        List<Target> selected = new ArrayList<>();
        for (Target t : registry.list()) if (t.kind() == Target.Kind.AE2 && t.enabled()) selected.add(t);
        selected.sort(
            Comparator.comparingLong(
                t -> Long.parseLong(
                    t.id()
                        .substring(4))));
        Map<Object, String> owners = new IdentityHashMap<>();
        List<ResolvedGrid> result = new ArrayList<>();
        for (Target t : selected) {
            Resolution<Object> r = resolve(t);
            String alias = "";
            if (r.status() == Status.AVAILABLE) {
                String owner = owners.get(r.value());
                if (owner == null) owners.put(r.value(), t.id());
                else alias = owner;
            }
            result.add(new ResolvedGrid(t, r, alias));
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public List<Discovered> discoverLoaded(int dimension) {
        registry.checkThread();
        List<Candidate> candidates = new ArrayList<>(backend.discover(dimension));
        candidates.sort(Comparator.comparing(Candidate::anchor, ANCHORS));
        // Keep every existing anchor; choose one deterministic anchor for newly encountered grids.
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        for (Target t : registry.list()) if (t.kind() == Target.Kind.AE2) {
            try {
                Object grid = backend.resolve(t.anchor());
                if (grid != null) seen.put(grid, true);
            } catch (RuntimeException | LinkageError ignored) {}
        }
        List<Discovered> result = new ArrayList<>();
        for (Candidate c : candidates) if (c.grid() != null && seen.put(c.grid(), true) == null)
            result.add(new Discovered(c.anchor(), "AE2 network"));
        return Collections.unmodifiableList(result);
    }

    @Override
    public void clear() {
        registry.checkThread();
    }
}
