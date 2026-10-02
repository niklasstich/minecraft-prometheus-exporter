package com.github.cpburnz.minecraft_prometheus_exporter.integrations.lsc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.cpburnz.minecraft_prometheus_exporter.collectors.Sampler;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.IntegrationAdapter;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.Target;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.TargetRegistry;

import io.prometheus.client.Collector;

/** Snapshot-only exposition; the mod owns registration and stop/unload cleanup. */
public final class LscCollector extends Sampler {

    private final TargetRegistry registry;
    private final LscAdapter adapter;

    public LscCollector(TargetRegistry registry, LscAdapter adapter, int intervalTicks) {
        super("lsc", intervalTicks);
        if (intervalTicks < 1) throw new IllegalArgumentException("Positive interval required");
        this.registry = registry;
        this.adapter = adapter;
    }

    @Override
    protected List<MetricFamilySamples> sample() {
        adapter.prune();
        Map<String, MetricFamilySamples> families = new LinkedHashMap<>();
        for (Target target : registry.list()) {
            if (target.kind() != Target.Kind.LSC || !target.enabled()) continue;
            IntegrationAdapter.Resolution<LscSnapshot> resolution = adapter.resolve(target);
            boolean available = resolution.status() == IntegrationAdapter.Status.AVAILABLE;
            add(
                families,
                "mc_lsc_available",
                Type.GAUGE,
                "Selected controller available.",
                target.id(),
                available ? 1 : 0);
            if (!available) continue;
            LscSnapshot s = resolution.value();
            Target.Anchor a = target.anchor();
            family(families, "mc_lsc_info", Type.GAUGE, "Configured LSC location and name.").samples.add(
                new MetricFamilySamples.Sample(
                    "mc_lsc_info",
                    java.util.Arrays.asList("target_id", "dim_id", "x", "y", "z", "name"),
                    java.util.Arrays.asList(
                        target.id(),
                        Integer.toString(a.dimension()),
                        Integer.toString(a.x()),
                        Integer.toString(a.y()),
                        Integer.toString(a.z()),
                        target.name()),
                    1));
            add(families, "mc_lsc_formed", Type.GAUGE, "Controller structure formed.", target.id(), s.formed() ? 1 : 0);
            add(families, "mc_lsc_wireless", Type.GAUGE, "Wireless mode enabled.", target.id(), s.wireless() ? 1 : 0);
            add(
                families,
                "mc_lsc_energy_stored_eu",
                Type.GAUGE,
                "Stored energy in EU.",
                target.id(),
                s.stored()
                    .doubleValue());
            if (s.formed()) {
                add(
                    families,
                    "mc_lsc_energy_capacity_eu",
                    Type.GAUGE,
                    "Formed capacity in EU.",
                    target.id(),
                    s.capacity()
                        .doubleValue());
                add(
                    families,
                    "mc_lsc_charge_ratio",
                    Type.GAUGE,
                    "Stored EU divided by formed capacity.",
                    target.id(),
                    s.chargeRatio());
                add(
                    families,
                    "mc_lsc_passive_loss_eu_per_tick",
                    Type.GAUGE,
                    "Nominal passive loss per running tick in EU.",
                    target.id(),
                    s.passiveLossPerTick());
                for (int i = 0; i < LscSnapshot.TIERS.size(); i++) {
                    family(
                        families,
                        "mc_lsc_capacitors",
                        Type.GAUGE,
                        "Formed capacitor inventory by metadata tier.").samples.add(
                            new Collector.MetricFamilySamples.Sample(
                                "mc_lsc_capacitors",
                                java.util.Arrays.asList("target_id", "tier"),
                                java.util.Arrays.asList(target.id(), LscSnapshot.TIERS.get(i)),
                                s.capacitors()
                                    .get(i)));
                }
            }
            add(
                families,
                "mc_lsc_energy_input_eu_total",
                Type.COUNTER,
                "Actual running-tick input including successful wireless transfers, EU.",
                target.id(),
                s.totals()
                    .input()
                    .doubleValue());
            add(
                families,
                "mc_lsc_energy_output_eu_total",
                Type.COUNTER,
                "Actual running-tick output including successful wireless transfers, EU.",
                target.id(),
                s.totals()
                    .output()
                    .doubleValue());
            add(
                families,
                "mc_lsc_energy_loss_eu_total",
                Type.COUNTER,
                "Passive energy actually lost during running ticks, EU.",
                target.id(),
                s.totals()
                    .loss()
                    .doubleValue());
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

    private static MetricFamilySamples family(Map<String, MetricFamilySamples> families, String name, Type type,
        String help) {
        // Prometheus counter family names omit the _total suffix; samples retain it.
        String familyName = type == Type.COUNTER ? name.substring(0, name.length() - 6) : name;
        return families.computeIfAbsent(familyName, n -> new MetricFamilySamples(n, type, help, new ArrayList<>()));
    }

    private static void add(Map<String, MetricFamilySamples> families, String name, Type type, String help, String id,
        double value) {
        family(families, name, type, help).samples.add(
            new MetricFamilySamples.Sample(
                name,
                Collections.singletonList("target_id"),
                Collections.singletonList(id),
                value));
    }
}
