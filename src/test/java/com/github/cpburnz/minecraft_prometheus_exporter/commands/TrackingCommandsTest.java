package com.github.cpburnz.minecraft_prometheus_exporter.commands;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.cpburnz.minecraft_prometheus_exporter.integrations.ae2.Ae2GridResolver;
import com.github.cpburnz.minecraft_prometheus_exporter.integrations.powerfails.PowerfailAdapter;
import com.github.cpburnz.minecraft_prometheus_exporter.integrations.powerfails.PowerfailSnapshot;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.IntegrationAdapter;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.Target;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.TargetRegistry;

class TrackingCommandsTest {

    @TempDir
    Path directory;

    @Test
    void discoveryPersistsDisabledIdsAndSettingsAcrossRepeatedListsAndRestart() throws Exception {
        TargetRegistry registry = TargetRegistry.open(directory);
        AtomicInteger scans = new AtomicInteger();
        IntegrationAdapter<Object> adapter = new IntegrationAdapter<Object>() {

            public List<Discovered> discoverLoaded(int dimension) {
                scans.incrementAndGet();
                return Collections.singletonList(new Discovered(new Target.Anchor(dimension, 1, 64, 2, -1), "LSC"));
            }

            public Resolution<Object> resolve(Target target) {
                return new Resolution<>(Status.UNAVAILABLE, null, "Unloaded");
            }

            public void clear() {}
        };
        TrackingCommands commands = new TrackingCommands(registry, adapter, null, null);
        assertTrue(
            commands.execute(new String[] { "lsc", "list" }, 7)
                .get(0)
                .contains("lsc-1"));
        assertFalse(
            registry.get("lsc-1")
                .enabled());
        commands.execute(new String[] { "lsc", "enable", "lsc-1" }, 7);
        Target enabled = registry.get("lsc-1");
        commands.execute(new String[] { "lsc", "enable", "lsc-1" }, 7);
        assertSame(enabled, registry.get("lsc-1"));
        commands.execute(new String[] { "lsc", "list" }, 7);
        assertEquals(2, scans.get());
        assertEquals(
            1,
            registry.list()
                .size());
        assertTrue(
            commands.execute(new String[] { "lsc", "status" }, 7)
                .get(0)
                .contains("UNAVAILABLE"));
        assertEquals(2, scans.get());
        registry.close();
        registry = TargetRegistry.open(directory);
        assertTrue(
            registry.get("lsc-1")
                .enabled());
        commands = new TrackingCommands(registry, null, null, null);
        commands.execute(new String[] { "lsc", "disable", "lsc-1" }, null);
        Target disabled = registry.get("lsc-1");
        commands.execute(new String[] { "lsc", "disable", "lsc-1" }, null);
        assertSame(disabled, registry.get("lsc-1"));
        assertThrows(
            IllegalArgumentException.class,
            () -> new TrackingCommands(TargetRegistry.open(directory), null, null, null)
                .execute(new String[] { "lsc", "enable", "lsc-1" }, null));
    }

    @Test
    void syntaxConsoleDimensionsAndMultipartAnchorsAreValidatedBeforeMutation() throws Exception {
        TargetRegistry registry = TargetRegistry.open(directory);
        TrackingCommands commands = new TrackingCommands(registry, null, null, null);
        for (String[] args : Arrays.asList(
            new String[] { "lsc" },
            new String[] { "lsc", "list", "extra" },
            new String[] { "lsc", "add", "1", "2", "3", "6" },
            new String[] { "ae2", "add", "1", "2", "3", "7" },
            new String[] { "ae2", "add", "bad", "2", "3" },
            new String[] { "powerfails", "add", "1", "2", "3" },
            new String[] { "ae2", "list", "--dim", "1", "extra" }))
            assertThrows(IllegalArgumentException.class, () -> commands.execute(args, 0));
        assertThrows(IllegalArgumentException.class, () -> commands.execute(new String[] { "ae2", "list" }, null));
        assertEquals(
            0,
            registry.list()
                .size());
        commands.execute(new String[] { "ae2", "add", "1", "64", "3", "6", "--dim", "-1" }, null);
        assertEquals(
            new Target.Anchor(-1, 1, 64, 3, 6),
            registry.get("ae2-1")
                .anchor());
        commands.execute(new String[] { "ae2", "add", "1", "64", "3", "6" }, -1);
        assertEquals(
            1,
            registry.list()
                .size());
        assertTrue(
            commands.execute(new String[] { "ae2", "status", "--dim", "-1" }, null)
                .toString()
                .contains("part=6"));
        assertThrows(
            IllegalArgumentException.class,
            () -> commands.execute(new String[] { "lsc", "disable", "ae2-1" }, 0));
    }

    @Test
    void ae2DiscoverySeparatesSubnetsPreservesPartsAndReportsMergedAliases() throws Exception {
        TargetRegistry registry = TargetRegistry.open(directory);
        Object grid = new Object();
        Object subnet = new Object();
        Target.Anchor one = new Target.Anchor(0, 1, 64, 1, 2);
        Target.Anchor two = new Target.Anchor(0, 1, 64, 1, 3);
        Ae2GridResolver resolver = new Ae2GridResolver(registry, new Ae2GridResolver.Backend() {

            public Object resolve(Target.Anchor anchor) {
                return anchor.equals(two) ? subnet : grid;
            }

            public List<Ae2GridResolver.Candidate> discover(int dimension) {
                return Arrays.asList(
                    new Ae2GridResolver.Candidate(one, grid),
                    new Ae2GridResolver.Candidate(two, subnet),
                    new Ae2GridResolver.Candidate(new Target.Anchor(0, 3, 64, 1, -1), grid));
            }
        });
        TrackingCommands commands = new TrackingCommands(registry, null, resolver, null);
        commands.execute(new String[] { "ae2", "list" }, 0);
        commands.execute(new String[] { "ae2", "list" }, 0);
        assertEquals(
            2,
            registry.list()
                .size());
        assertTrue(
            commands.execute(new String[] { "ae2", "status" }, 0)
                .get(0)
                .contains("disabled availability=AVAILABLE"));
        commands.execute(new String[] { "ae2", "add", "9", "64", "1" }, 0);
        commands.execute(new String[] { "ae2", "enable", "ae2-1" }, 0);
        commands.execute(new String[] { "ae2", "enable", "ae2-3" }, 0);
        assertTrue(
            commands.execute(new String[] { "ae2", "status" }, 0)
                .toString()
                .contains("alias-of=ae2-1"));
    }

    @Test
    void powerfailsUseNativeUuidAndListOfflineTeamsAlongsideDeletedSelections() throws Exception {
        TargetRegistry registry = TargetRegistry.open(directory);
        UUID team = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        registry.discoverTeam(deleted, "Deleted");
        PowerfailAdapter adapter = new PowerfailAdapter(registry, new PowerfailAdapter.Backend() {

            public List<PowerfailAdapter.TeamSummary> teams() {
                return Collections.singletonList(new PowerfailAdapter.TeamSummary(team, "Offline team"));
            }

            public PowerfailSnapshot read(UUID id) {
                return id.equals(team) ? new PowerfailSnapshot("Offline team", Collections.emptyList()) : null;
            }
        });
        TrackingCommands commands = new TrackingCommands(registry, null, null, adapter);
        String listing = commands.execute(new String[] { "powerfails", "list" }, null)
            .toString();
        assertTrue(listing.contains("GTNHLib"));
        assertTrue(listing.contains("Deleted"));
        assertTrue(listing.contains("UNAVAILABLE"));
        commands.execute(new String[] { "powerfails", "enable", team.toString() }, null);
        assertTrue(
            registry.get("powerfails-" + team)
                .enabled());
        commands.execute(new String[] { "powerfails", "disable", "powerfails-" + team }, null);
        assertFalse(
            registry.get("powerfails-" + team)
                .enabled());
    }
}
