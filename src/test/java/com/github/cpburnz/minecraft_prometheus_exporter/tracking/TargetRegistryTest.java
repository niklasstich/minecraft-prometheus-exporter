package com.github.cpburnz.minecraft_prometheus_exporter.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TargetRegistryTest {

    @TempDir
    Path directory;

    private static final Target.Anchor ANCHOR = new Target.Anchor(-1, 10, 64, 20, -1);

    @Test
    void saveReloadPreservesIdentitySettingsAndAllocationAfterRemoval() throws Exception {
        TargetRegistry registry = TargetRegistry.open(directory);
        Target first = registry.discover(Target.Kind.LSC, ANCHOR, "First");
        assertFalse(first.enabled());
        assertEquals("lsc-1", first.id());
        registry.configure(first.id(), "Selected", true);
        assertEquals(
            "Selected",
            registry.discover(Target.Kind.LSC, ANCHOR, "Changed discovery name")
                .name());
        registry.close();

        registry = TargetRegistry.open(directory);
        assertTrue(
            registry.get(first.id())
                .enabled());
        assertEquals(
            ANCHOR,
            registry.get(first.id())
                .anchor());
        registry.remove(first.id());
        registry.close();
        registry = TargetRegistry.open(directory);
        assertEquals(
            "lsc-2",
            registry.discover(Target.Kind.LSC, ANCHOR, "Replacement")
                .id());
    }

    @Test
    void worldsPartsAndKindsHaveIndependentIdentities() throws Exception {
        TargetRegistry one = TargetRegistry.open(directory.resolve("one"));
        TargetRegistry two = TargetRegistry.open(directory.resolve("two"));
        assertEquals(
            "ae2-1",
            one.discover(Target.Kind.AE2, ANCHOR, "A")
                .id());
        assertEquals(
            "ae2-2",
            one.discover(Target.Kind.AE2, new Target.Anchor(-1, 10, 64, 20, 6), "Part")
                .id());
        assertEquals(
            "lsc-1",
            one.discover(Target.Kind.LSC, ANCHOR, "LSC")
                .id());
        assertEquals(
            "ae2-1",
            two.discover(Target.Kind.AE2, ANCHOR, "B")
                .id());
        assertFalse(
            two.get("ae2-1")
                .enabled());
    }

    @Test
    void teamsPersistNativeUuidWithoutBlockAnchor() throws Exception {
        UUID uuid = UUID.randomUUID();
        TargetRegistry registry = TargetRegistry.open(directory);
        Target team = registry.discoverTeam(uuid, "Offline team");
        registry.configure(team.id(), team.name(), true);
        Target reloaded = TargetRegistry.open(directory)
            .get(team.id());
        assertEquals(uuid, reloaded.teamId());
        assertTrue(reloaded.enabled());
        assertEquals(
            1,
            registry.list()
                .size());
        assertEquals(
            team.id(),
            registry.discoverTeam(uuid, "Renamed team")
                .id());
    }

    @Test
    void invalidFilesAreRetainedAndRejected() throws Exception {
        Path file = directory.resolve("prometheus-exporter-targets.json");
        for (String invalid : new String[] { "{broken", "{\"version\":2}",
            "{\"version\":1,\"nextIds\":{\"LSC\":0,\"AE2\":1},\"targets\":[]}" }) {
            Files.write(file, invalid.getBytes(StandardCharsets.UTF_8));
            assertThrows(IOException.class, () -> TargetRegistry.open(directory));
            assertEquals(invalid, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        }
    }

    @Test
    void failedSaveLeavesMemoryAndFileUnchanged() throws Exception {
        Path blocked = directory.resolve("blocked");
        Files.write(blocked, new byte[] { 1 });
        TargetRegistry registry = TargetRegistry.open(blocked);
        assertThrows(IOException.class, () -> registry.discover(Target.Kind.LSC, ANCHOR, "A"));
        assertTrue(
            registry.list()
                .isEmpty());
        assertEquals(1, Files.size(blocked));
    }

    @Test
    void readsRequireOwningThreadAndSnapshotsAreImmutable() throws Exception {
        TargetRegistry registry = TargetRegistry.open(directory);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                registry.list();
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        other.start();
        other.join();
        assertTrue(failure.get() instanceof IllegalStateException);
        assertThrows(
            UnsupportedOperationException.class,
            () -> registry.list()
                .clear());
        registry.close();
        assertThrows(IllegalStateException.class, registry::list);
    }
}
