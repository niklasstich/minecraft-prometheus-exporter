package com.github.cpburnz.minecraft_prometheus_exporter.integrations.powerfails;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.github.cpburnz.minecraft_prometheus_exporter.collectors.Sampler;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.IntegrationAdapter;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.Target;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.TargetRegistry;

/** Immutable tick snapshots; HTTP collection performs no integration access. */
public final class PowerfailCollector extends Sampler {

    private final TargetRegistry registry;
    private final PowerfailAdapter adapter;

    public PowerfailCollector(TargetRegistry registry, PowerfailAdapter adapter, int intervalTicks) {
        super("powerfails", intervalTicks);
        if (intervalTicks < 1) throw new IllegalArgumentException("Positive interval required");
        this.registry = registry;
        this.adapter = adapter;
    }

    protected List<MetricFamilySamples> sample() {
        Map<String, MetricFamilySamples> families = new LinkedHashMap<>();
        Set<List<String>> machineInfo = new HashSet<>();
        for (Target target : registry.list()) {
            if (target.kind() != Target.Kind.POWERFAILS || !target.enabled()) continue;
            IntegrationAdapter.Resolution<PowerfailSnapshot> result = adapter.resolve(target);
            String id = target.teamId()
                .toString();
            boolean available = result.status() == IntegrationAdapter.Status.AVAILABLE;
            add(
                families,
                "mc_team_powerfails_available",
                "Selected GTNHLib team tracker available.",
                Arrays.asList("team_id"),
                Arrays.asList(id),
                available ? 1 : 0);
            if (!available) continue;
            PowerfailSnapshot snapshot = result.value();
            add(
                families,
                "mc_team_powerfails_pending",
                "Distinct pending tracker records, across all dimensions.",
                Arrays.asList("team_id"),
                Arrays.asList(id),
                snapshot.rows()
                    .size());
            add(
                families,
                "mc_powerfail_team_info",
                "GTNHLib team display name.",
                Arrays.asList("team_id", "team_name"),
                Arrays.asList(id, snapshot.teamName()),
                1);
            for (PowerfailSnapshot.Row row : snapshot.rows()) {
                String type = Integer.toString(row.machineId());
                add(
                    families,
                    "mc_team_powerfail_last_occurrence_timestamp_seconds",
                    "Latest pending failure occurrence, Unix seconds.",
                    Arrays.asList("team_id", "dim_id", "x", "y", "z", "machine_type"),
                    Arrays.asList(
                        id,
                        Integer.toString(row.dimension()),
                        Integer.toString(row.x()),
                        Integer.toString(row.y()),
                        Integer.toString(row.z()),
                        type),
                    row.latestMillis() / 1000.0);
                if (machineInfo.add(Arrays.asList(type, row.machineName()))) add(
                    families,
                    "mc_powerfail_machine_info",
                    "GregTech MetaTileEntity display name.",
                    Arrays.asList("machine_type", "machine_name"),
                    Arrays.asList(type, row.machineName()),
                    1);
            }
        }
        List<MetricFamilySamples> result = new ArrayList<>();
        for (MetricFamilySamples f : families.values()) result.add(
            new MetricFamilySamples(f.name, f.type, f.help, Collections.unmodifiableList(new ArrayList<>(f.samples))));
        return Collections.unmodifiableList(result);
    }

    private static void add(Map<String, MetricFamilySamples> families, String name, String help, List<String> labels,
        List<String> values, double value) {
        MetricFamilySamples f = families
            .computeIfAbsent(name, n -> new MetricFamilySamples(n, Type.GAUGE, help, new ArrayList<>()));
        f.samples.add(
            new MetricFamilySamples.Sample(
                name,
                Collections.unmodifiableList(new ArrayList<>(labels)),
                Collections.unmodifiableList(new ArrayList<>(values)),
                value));
    }
}
