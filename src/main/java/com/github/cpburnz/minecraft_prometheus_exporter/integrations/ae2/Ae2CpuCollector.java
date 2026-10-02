package com.github.cpburnz.minecraft_prometheus_exporter.integrations.ae2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.github.cpburnz.minecraft_prometheus_exporter.collectors.Sampler;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.InstrumentationStore;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.IntegrationAdapter.Status;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.Target;

/** Bind event counters on the server thread; HTTP sees immutable numeric/label snapshots only. */
public final class Ae2CpuCollector extends Sampler {

    private final Ae2GridResolver resolver;
    private final InstrumentationStore store;
    private final Function<Object, List<Ae2CpuSnapshot>> reader;
    private final Map<InstrumentationStore.Key, Object> bindings = new HashMap<>();

    public Ae2CpuCollector(Ae2GridResolver resolver, InstrumentationStore store, int interval) {
        this(resolver, store, interval, Ae2CpuReader::read);
    }

    public Ae2CpuCollector(Ae2GridResolver resolver, InstrumentationStore store, int interval,
        Function<Object, List<Ae2CpuSnapshot>> reader) {
        super("ae2_cpu", interval);
        if (interval < 1) throw new IllegalArgumentException("Positive interval required");
        this.resolver = resolver;
        this.store = store;
        this.reader = reader;
    }

    @Override
    protected List<MetricFamilySamples> sample() {
        Map<String, MetricFamilySamples> families = new LinkedHashMap<>();
        Set<InstrumentationStore.Key> live = new HashSet<>();
        for (Ae2GridResolver.ResolvedGrid grid : resolver.resolveSelections()) {
            if (!grid.aliasOf()
                .isEmpty()) continue;
            String id = grid.target()
                .id();
            List<Ae2CpuSnapshot> cpus = null;
            if (grid.resolution()
                .status() == Status.AVAILABLE) try {
                    cpus = reader.apply(
                        grid.resolution()
                            .value());
                } catch (RuntimeException | LinkageError ignored) {}
            row(
                families,
                "available",
                Collections.singletonList("target_id"),
                Collections.singletonList(id),
                cpus == null ? 0 : 1);
            if (cpus == null) continue;
            for (Ae2CpuSnapshot cpu : cpus) {
                Target.Anchor a = cpu.anchor();
                List<String> names = Arrays.asList("target_id", "cpu_name", "cpu_dim_id", "cpu_x", "cpu_y", "cpu_z");
                List<String> values = Arrays
                    .asList(id, cpu.name(), "" + a.dimension(), "" + a.x(), "" + a.y(), "" + a.z());
                InstrumentationStore.Key key = new InstrumentationStore.Key(
                    id,
                    a.dimension() + ":" + a.x() + ":" + a.y() + ":" + a.z() + ":" + cpu.name());
                if (!store.bind(key, cpu.token())) continue;
                Object previous = bindings.put(key, cpu.token());
                if (previous != null && previous != cpu.token()) detach(previous);
                if (cpu.token() instanceof Ae2CpuAccess) ((Ae2CpuAccess) cpu.token()).prometheus$bind(store, key);
                live.add(key);
                row(families, "busy", names, values, cpu.busy() ? 1 : 0);
                row(
                    families,
                    "progress_completed_total",
                    names,
                    values,
                    store.read(key)
                        .progress()
                        .doubleValue());
                if (!cpu.busy()) continue;
                row(families, "request_total", names, values, cpu.requestTotal());
                row(families, "request_remaining", names, values, cpu.requestRemaining());
                row(families, "progress_total", names, values, cpu.progressTotal());
                row(families, "progress_remaining", names, values, cpu.progressRemaining());
                if (cpu.resource() != null) {
                    Ae2CpuSnapshot.Resource resource = cpu.resource();
                    List<String> infoNames = new ArrayList<>(names), infoValues = new ArrayList<>(values);
                    infoNames.addAll(Arrays.asList("resource_type", "resource_id", "metadata", "nbt", "display_name"));
                    infoValues.addAll(
                        Arrays.asList(
                            resource.type(),
                            resource.id(),
                            resource.metadata(),
                            resource.nbt(),
                            resource.displayName()));
                    row(families, "request_info", infoNames, infoValues, 1);
                }
            }
        }
        for (InstrumentationStore.Key key : new HashSet<>(bindings.keySet())) if (!live.contains(key)) {
            Object token = bindings.remove(key);
            // A rename can bind the same live CPU under its new key before pruning the old key.
            if (!bindings.containsValue(token)) detach(token);
            store.unbind(key);
        }
        List<MetricFamilySamples> result = new ArrayList<>();
        for (MetricFamilySamples family : families.values()) result.add(
            new MetricFamilySamples(
                family.name,
                family.type,
                family.help,
                Collections.unmodifiableList(new ArrayList<>(family.samples))));
        return Collections.unmodifiableList(result);
    }

    /** Called before stopping instrumentation or closing the world registry, and on world unload. */
    public void clear() {
        for (Map.Entry<InstrumentationStore.Key, Object> entry : bindings.entrySet()) {
            detach(entry.getValue());
            store.unbind(entry.getKey());
        }
        bindings.clear();
    }

    private static void detach(Object token) {
        if (token instanceof Ae2CpuAccess) ((Ae2CpuAccess) token).prometheus$bind(null, null);
    }

    private static void row(Map<String, MetricFamilySamples> families, String suffix, List<String> names,
        List<String> values, double value) {
        String name = "mc_ae2_cpu_" + suffix;
        MetricFamilySamples family = families.computeIfAbsent(
            name,
            n -> new MetricFamilySamples(
                n,
                suffix.endsWith("_total") && suffix.equals("progress_completed_total") ? Type.COUNTER : Type.GAUGE,
                "Selected AE2 CPU " + suffix + ".",
                new ArrayList<>()));
        family.samples.add(new MetricFamilySamples.Sample(name, names, values, value));
    }
}
