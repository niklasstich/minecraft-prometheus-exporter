package com.github.cpburnz.minecraft_prometheus_exporter;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.cpburnz.minecraft_prometheus_exporter.collectors.CollectorScheduler;
import com.github.cpburnz.minecraft_prometheus_exporter.integrations.ae2.Ae2GridResolver;
import com.github.cpburnz.minecraft_prometheus_exporter.integrations.lsc.LscAdapter;
import com.github.cpburnz.minecraft_prometheus_exporter.integrations.powerfails.PowerfailAdapter;
import com.github.cpburnz.minecraft_prometheus_exporter.integrations.powerfails.PowerfailSnapshot;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.InstrumentationStore;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.IntegrationAdapter;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.Target;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.TargetRegistry;

import io.prometheus.client.CollectorRegistry;

class TrackingLifecycleTest {

    @TempDir
    Path directory;

    @Test
    void supportedCollectorsPublishNewSelectionsAndCanRestart() throws Exception {
        TargetRegistry registry = TargetRegistry.open(directory);
        InstrumentationStore store = new InstrumentationStore(registry);
        PrometheusExporterMod mod = new PrometheusExporterMod();
        set(mod, "targets", registry);
        set(mod, "instrumentation", store);
        LscAdapter lsc = new LscAdapter(registry, store, new LscAdapter.Backend() {

            public List<IntegrationAdapter.Discovered> discover(int dimension) {
                return Collections.emptyList();
            }

            public LscAdapter.Host resolve(Target.Anchor anchor) {
                return null;
            }
        });
        Ae2GridResolver ae2 = new Ae2GridResolver(registry, new Ae2GridResolver.Backend() {

            public Object resolve(Target.Anchor anchor) {
                return null;
            }

            public List<Ae2GridResolver.Candidate> discover(int dimension) {
                return Collections.emptyList();
            }
        });
        PowerfailAdapter powerfails = new PowerfailAdapter(registry, new PowerfailAdapter.Backend() {

            public List<PowerfailAdapter.TeamSummary> teams() {
                return Collections.emptyList();
            }

            public PowerfailSnapshot read(UUID team) {
                return new PowerfailSnapshot("Team", Collections.emptyList());
            }
        });
        CollectorRegistry.defaultRegistry.clear();
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                CollectorScheduler scheduler = new CollectorScheduler();
                set(mod, "scheduler", scheduler);
                CollectorScheduler.Instance = scheduler;
                store.start();
                mod.initTrackingCollectors(lsc, ae2, powerfails);
                set(mod, "is_running", true);
                assertEquals(
                    java.util.Arrays.asList("lsc", "ae2_network", "ae2_cpu", "powerfails"),
                    scheduler.samplers()
                        .stream()
                        .map(s -> s.name)
                        .collect(Collectors.toList()));
                scheduler.refreshAll();
                if (attempt == 0) {
                    assertFalse(
                        CollectorRegistry.defaultRegistry.metricFamilySamples()
                            .hasMoreElements());
                    Target l = registry.discover(Target.Kind.LSC, new Target.Anchor(0, 1, 64, 0, -1), "LSC");
                    Target a = registry.discover(Target.Kind.AE2, new Target.Anchor(0, 2, 64, 0, -1), "AE2");
                    Target p = registry.discoverTeam(UUID.randomUUID(), "Team");
                    for (Target target : new Target[] { l, a, p }) registry.configure(target.id(), target.name(), true);
                }
                for (int tick = 0; tick < 20; tick++) scheduler.tick();
                assertEquals(
                    0.0,
                    CollectorRegistry.defaultRegistry
                        .getSampleValue("mc_lsc_available", new String[] { "target_id" }, new String[] { "lsc-1" }));
                assertEquals(
                    0.0,
                    CollectorRegistry.defaultRegistry.getSampleValue(
                        "mc_ae2_network_available",
                        new String[] { "target_id" },
                        new String[] { "ae2-1" }));
                assertEquals(
                    0.0,
                    CollectorRegistry.defaultRegistry.getSampleValue(
                        "mc_ae2_cpu_available",
                        new String[] { "target_id" },
                        new String[] { "ae2-1" }));
                assertTrue(
                    Collections.list(CollectorRegistry.defaultRegistry.metricFamilySamples())
                        .stream()
                        .anyMatch(f -> f.name.equals("mc_team_powerfails_pending")));
                mod.stopExporter();
                assertTrue(
                    scheduler.samplers()
                        .isEmpty());
                assertNull(CollectorScheduler.Instance);
                assertFalse(
                    CollectorRegistry.defaultRegistry.metricFamilySamples()
                        .hasMoreElements());
            }
        } finally {
            if (mod.isExporterRunning()) mod.stopExporter();
            CollectorRegistry.defaultRegistry.clear();
            CollectorScheduler.Instance = null;
            store.stop();
            registry.close();
        }
    }

    private static void set(PrometheusExporterMod mod, String name, Object value) throws Exception {
        Field field = PrometheusExporterMod.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(mod, value);
    }
}
