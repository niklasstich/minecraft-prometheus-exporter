package com.github.cpburnz.minecraft_prometheus_exporter.integrations.ae2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.github.cpburnz.minecraft_prometheus_exporter.collectors.Sampler;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.IntegrationAdapter.Status;

/** Immutable snapshot exposition. Reader and resolver execute exclusively during server-tick sampling. */
public final class Ae2NetworkCollector extends Sampler {

    private final Ae2GridResolver resolver;
    private final Function<Object, Ae2NetworkSnapshot> reader;

    public Ae2NetworkCollector(Ae2GridResolver resolver, int interval) {
        this(resolver, interval, Ae2NetworkReader::read);
    }

    public Ae2NetworkCollector(Ae2GridResolver resolver, int interval, Function<Object, Ae2NetworkSnapshot> reader) {
        super("ae2_network", interval);
        if (interval < 1) throw new IllegalArgumentException("Positive interval required");
        this.resolver = resolver;
        this.reader = reader;
    }

    @Override
    protected List<MetricFamilySamples> sample() {
        Map<String, MetricFamilySamples> families = new LinkedHashMap<>();
        for (Ae2GridResolver.ResolvedGrid r : resolver.resolveSelections()) {
            String id = r.target()
                .id();
            boolean available = r.resolution()
                .status() == Status.AVAILABLE;
            Ae2NetworkSnapshot s = null;
            if (available && r.aliasOf()
                .isEmpty()) try {
                    s = reader.apply(
                        r.resolution()
                            .value());
                } catch (RuntimeException | LinkageError e) {
                    available = false;
                }
            add(families, "available", id, available ? 1 : 0);
            add(
                families,
                "alias",
                id,
                r.aliasOf()
                    .isEmpty() ? 0 : 1);
            if (!r.aliasOf()
                .isEmpty())
                row(
                    families,
                    "alias_info",
                    Arrays.asList("target_id", "canonical_target_id"),
                    Arrays.asList(id, r.aliasOf()),
                    1);
            if (s == null) continue;
            com.github.cpburnz.minecraft_prometheus_exporter.tracking.Target.Anchor a = r.target()
                .anchor();
            row(
                families,
                "info",
                Arrays.asList("target_id", "dim_id", "x", "y", "z", "part", "name"),
                Arrays.asList(
                    id,
                    "" + a.dimension(),
                    "" + a.x(),
                    "" + a.y(),
                    "" + a.z(),
                    "" + a.part(),
                    r.target()
                        .name()),
                1);
            add(families, "power_average_ae_per_tick", id, s.averagePower());
            add(families, "power_idle_ae_per_tick", id, s.idlePower());
            for (Map.Entry<String, Integer> d : s.devices()
                .entrySet())
                row(
                    families,
                    "devices",
                    Arrays.asList("target_id", "kind"),
                    Arrays.asList(id, d.getKey()),
                    d.getValue());
            row(families, "controller_state", Arrays.asList("target_id", "state"), Arrays.asList(id, s.state()), 1);
            add(families, "channels_enabled", id, s.channelsEnabled() ? 1 : 0);
            add(families, "controller_exterior_faces", id, s.exteriorFaces());
            add(families, "controller_capacity_valid", id, s.capacityValid() ? 1 : 0);
            if (s.channelsEnabled() && !s.state()
                .equals("creative")) add(families, "controller_channels_capacity", id, s.capacity());
            if (s.channelsEnabled()) add(families, "controller_channels_used", id, s.usedChannels());
            if (s.capacityValid()) add(families, "controller_channels_available", id, s.availableChannels());
            for (Map.Entry<String, Long> f : s.faceUsage()
                .entrySet())
                row(
                    families,
                    "controller_face_channels_used",
                    Arrays.asList("target_id", "face"),
                    Arrays.asList(id, f.getKey()),
                    f.getValue());
        }
        List<MetricFamilySamples> result = new ArrayList<>();
        for (MetricFamilySamples f : families.values()) result.add(
            new MetricFamilySamples(f.name, f.type, f.help, Collections.unmodifiableList(new ArrayList<>(f.samples))));
        return Collections.unmodifiableList(result);
    }

    private static void add(Map<String, MetricFamilySamples> f, String name, String id, double value) {
        row(f, name, Collections.singletonList("target_id"), Collections.singletonList(id), value);
    }

    private static void row(Map<String, MetricFamilySamples> families, String suffix, List<String> names,
        List<String> values, double value) {
        String name = "mc_ae2_network_" + suffix;
        MetricFamilySamples f = families.computeIfAbsent(
            name,
            n -> new MetricFamilySamples(n, Type.GAUGE, "Selected AE2 network " + suffix + ".", new ArrayList<>()));
        f.samples.add(new MetricFamilySamples.Sample(name, names, values, value));
    }
}
