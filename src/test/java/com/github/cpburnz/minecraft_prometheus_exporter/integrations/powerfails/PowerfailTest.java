package com.github.cpburnz.minecraft_prometheus_exporter.integrations.powerfails;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.cpburnz.minecraft_prometheus_exporter.tracking.IntegrationAdapter;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.Target;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.TargetRegistry;

import io.prometheus.client.Collector.MetricFamilySamples;

class PowerfailTest {

    @TempDir
    Path directory;

    private static final class Backend implements PowerfailAdapter.Backend {

        final Map<UUID, PowerfailSnapshot> data = new HashMap<>();
        int reads;

        public List<PowerfailAdapter.TeamSummary> teams() {
            List<PowerfailAdapter.TeamSummary> teams = new ArrayList<>();
            data.forEach((id, s) -> teams.add(new PowerfailAdapter.TeamSummary(id, s.teamName())));
            return teams;
        }

        public PowerfailSnapshot read(UUID id) {
            reads++;
            return data.get(id);
        }
    }

    private static PowerfailSnapshot.Row row(int dim, int x, long time) {
        return new PowerfailSnapshot.Row(dim, x, 64, -2, 123, "Machine", time);
    }

    private static List<MetricFamilySamples.Sample> rows(PowerfailCollector c, String name) {
        return c.collect()
            .stream()
            .filter(f -> f.name.equals(name))
            .findFirst()
            .map(f -> f.samples)
            .orElse(Collections.emptyList());
    }

    @Test
    void discoveryIsSortedReadOnlyAndIndependentOfSelections() throws Exception {
        TargetRegistry registry = TargetRegistry.open(directory);
        Backend backend = new Backend();
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        backend.data.put(second, new PowerfailSnapshot("Second", Collections.emptyList()));
        backend.data.put(first, new PowerfailSnapshot("First", Collections.emptyList()));
        List<PowerfailAdapter.TeamSummary> teams = new PowerfailAdapter(registry, backend).discoverTeams();
        assertEquals(
            Arrays.asList(first, second),
            Arrays.asList(
                teams.get(0)
                    .id(),
                teams.get(1)
                    .id()));
        assertThrows(UnsupportedOperationException.class, teams::clear);
        assertTrue(
            registry.list()
                .isEmpty());
        assertEquals(0, backend.reads);
    }

    @Test
    void pendingRowsRepeatClearRenameAndOfflineDimensions() throws Exception {
        TargetRegistry registry = TargetRegistry.open(directory);
        UUID id = UUID.randomUUID();
        Target t = registry.discoverTeam(id, "Team");
        registry.configure(t.id(), t.name(), true);
        Backend backend = new Backend();
        List<PowerfailSnapshot.Row> mutable = new ArrayList<>(
            Arrays.asList(row(-123, 1, 1234567), row(999, 2, 4567890)));
        backend.data.put(id, new PowerfailSnapshot("Offline", mutable));
        mutable.clear();
        PowerfailCollector c = new PowerfailCollector(registry, new PowerfailAdapter(registry, backend), 20);
        c.refresh();
        assertEquals(2, rows(c, "mc_team_powerfails_pending").get(0).value);
        assertEquals(1234.567, rows(c, "mc_team_powerfail_last_occurrence_timestamp_seconds").get(0).value);
        assertEquals(
            Arrays.asList("team_id", "dim_id", "x", "y", "z", "machine_type"),
            rows(c, "mc_team_powerfail_last_occurrence_timestamp_seconds").get(0).labelNames);
        assertEquals(1, rows(c, "mc_powerfail_machine_info").size());
        int reads = backend.reads;
        c.collect();
        assertEquals(reads, backend.reads);
        List<MetricFamilySamples> prior = c.collect();
        backend.data.put(id, new PowerfailSnapshot("Renamed", Arrays.asList(row(-123, 1, 9876543))));
        c.refresh();
        assertEquals(1, rows(c, "mc_team_powerfails_pending").get(0).value);
        assertEquals(9876.543, rows(c, "mc_team_powerfail_last_occurrence_timestamp_seconds").get(0).value);
        assertEquals(
            2,
            prior.stream()
                .filter(f -> f.name.equals("mc_team_powerfail_last_occurrence_timestamp_seconds"))
                .findFirst()
                .get().samples.size());
        assertEquals("Renamed", rows(c, "mc_powerfail_team_info").get(0).labelValues.get(1));
        backend.data.put(id, new PowerfailSnapshot("Renamed", Collections.emptyList()));
        c.refresh();
        assertEquals(0, rows(c, "mc_team_powerfails_pending").get(0).value);
        assertTrue(rows(c, "mc_team_powerfail_last_occurrence_timestamp_seconds").isEmpty());
        assertThrows(
            UnsupportedOperationException.class,
            () -> c.collect()
                .clear());
    }

    @Test
    void membershipTransferMergeDeletionAndDisabledSelection() throws Exception {
        TargetRegistry registry = TargetRegistry.open(directory);
        Backend b = new Backend();
        UUID a = UUID.randomUUID(), other = UUID.randomUUID();
        Target t = registry.discoverTeam(a, "A");
        PowerfailAdapter adapter = new PowerfailAdapter(registry, b);
        assertEquals(
            IntegrationAdapter.Status.DISABLED,
            adapter.resolve(t)
                .status());
        assertEquals(0, b.reads);
        registry.configure(t.id(), t.name(), true);
        t = registry.get(t.id());
        b.data.put(a, new PowerfailSnapshot("A", Arrays.asList(row(0, 1, 1000))));
        PowerfailCollector c = new PowerfailCollector(registry, adapter, 20);
        c.refresh();
        // Member transfer moves native data; the unselected destination is never sampled.
        b.data.put(other, b.data.get(a));
        b.data.put(a, new PowerfailSnapshot("A", Collections.emptyList()));
        c.refresh();
        assertEquals(0, rows(c, "mc_team_powerfails_pending").get(0).value);
        // A consumed/deleted UUID remains configured, with no automatic selection migration.
        b.data.remove(a);
        c.refresh();
        assertEquals(0, rows(c, "mc_team_powerfails_available").get(0).value);
        assertTrue(rows(c, "mc_team_powerfails_pending").isEmpty());
        assertNotNull(registry.get(t.id()));
        registry.configure(t.id(), t.name(), false);
        c.refresh();
        assertTrue(
            c.collect()
                .isEmpty());
    }

    @Test
    void absentNotificationDataErrorDoesNotFreezeHealthyTeam() throws Exception {
        TargetRegistry registry = TargetRegistry.open(directory);
        UUID broken = UUID.randomUUID(), healthy = UUID.randomUUID();
        for (UUID id : Arrays.asList(broken, healthy)) {
            Target t = registry.discoverTeam(id, "Team");
            registry.configure(t.id(), t.name(), true);
        }
        PowerfailAdapter.Backend backend = new PowerfailAdapter.Backend() {

            public List<PowerfailAdapter.TeamSummary> teams() {
                return Collections.emptyList();
            }

            public PowerfailSnapshot read(UUID id) {
                if (id.equals(broken)) throw new IllegalStateException("Missing tracker");
                return new PowerfailSnapshot("Healthy", Collections.emptyList());
            }
        };
        PowerfailCollector c = new PowerfailCollector(registry, new PowerfailAdapter(registry, backend), 20);
        c.refresh();
        assertEquals(2, rows(c, "mc_team_powerfails_available").size());
        assertEquals(1, rows(c, "mc_team_powerfails_pending").size());
    }
}
