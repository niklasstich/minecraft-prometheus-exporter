package com.github.cpburnz.minecraft_prometheus_exporter.integrations.ae2;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.cpburnz.minecraft_prometheus_exporter.tracking.IntegrationAdapter;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.Target;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.TargetRegistry;

class Ae2NetworkTest {

    @TempDir
    Path directory;

    private Target.Anchor anchor(int x, int part) {
        return new Target.Anchor(0, x, 64, 0, part);
    }

    @Test
    void mergesSplitsDisabledAndUnavailableAreReevaluated() throws Exception {
        TargetRegistry registry = TargetRegistry.open(directory);
        Map<Target.Anchor, Object> grids = new HashMap<>();
        Object first = new Object(), second = new Object();
        Target a = registry.discover(Target.Kind.AE2, anchor(1, -1), "A");
        Target b = registry.discover(Target.Kind.AE2, anchor(2, 6), "B");
        registry.configure(a.id(), a.name(), true);
        registry.configure(b.id(), b.name(), true);
        grids.put(a.anchor(), first);
        grids.put(b.anchor(), first);
        Ae2GridResolver resolver = resolver(registry, grids, Collections.emptyList());
        assertEquals(
            a.id(),
            resolver.resolveSelections()
                .get(1)
                .aliasOf());
        grids.put(b.anchor(), second);
        assertEquals(
            "",
            resolver.resolveSelections()
                .get(1)
                .aliasOf());
        grids.remove(a.anchor());
        assertEquals(
            IntegrationAdapter.Status.UNAVAILABLE,
            resolver.resolveSelections()
                .get(0)
                .resolution()
                .status());
        registry.configure(b.id(), b.name(), false);
        assertEquals(
            1,
            resolver.resolveSelections()
                .size());
        assertEquals(
            IntegrationAdapter.Status.DISABLED,
            resolver.resolve(registry.get(b.id()))
                .status());
    }

    @Test
    void discoveryKeepsSubnetsSeparateAndPreservesExistingAnchors() throws Exception {
        TargetRegistry registry = TargetRegistry.open(directory);
        Object main = new Object(), subnet = new Object();
        Map<Target.Anchor, Object> grids = new HashMap<>();
        grids.put(anchor(3, 6), main);
        List<Ae2GridResolver.Candidate> candidates = Arrays.asList(
            new Ae2GridResolver.Candidate(anchor(2, 0), subnet),
            new Ae2GridResolver.Candidate(anchor(1, 6), main),
            new Ae2GridResolver.Candidate(anchor(2, 6), main));
        Ae2GridResolver resolver = resolver(registry, grids, candidates);
        assertEquals(
            2,
            resolver.discoverLoaded(0)
                .size());
        assertEquals(
            anchor(1, 6),
            resolver.discoverLoaded(0)
                .get(0)
                .anchor());
        registry.discover(Target.Kind.AE2, anchor(3, 6), "Chosen");
        assertEquals(
            1,
            resolver.discoverLoaded(0)
                .size());
        assertEquals(
            anchor(2, 0),
            resolver.discoverLoaded(0)
                .get(0)
                .anchor());
    }

    @Test
    void exteriorIncludesUnusedObstructedFacesAndExcludesInternalFaces() {
        Set<Target.Anchor> blocks = new HashSet<>();
        blocks.add(anchor(0, -1));
        assertEquals(6, Ae2NetworkSnapshot.exteriorFaces(blocks));
        blocks.add(anchor(1, -1));
        assertEquals(10, Ae2NetworkSnapshot.exteriorFaces(blocks));
        blocks.add(new Target.Anchor(1, 0, 64, 0, -1));
        assertEquals(16, Ae2NetworkSnapshot.exteriorFaces(blocks));
    }

    @Test
    void addonClassificationIsExclusiveAndDoesNotLoadAddonClasses() {
        assertEquals(
            "dual_interface",
            Ae2NetworkSnapshot.classifyNames(
                Arrays.asList(
                    "com.glodblock.github.common.parts.PartFluidInterface",
                    "appeng.parts.misc.PartInterface")));
        assertEquals(
            "item_interface",
            Ae2NetworkSnapshot.classifyNames(Collections.singletonList("appeng.tile.misc.TileInterface")));
        assertEquals(
            "essentia_import_bus",
            Ae2NetworkSnapshot
                .classifyNames(Collections.singletonList("thaumicenergistics.common.parts.PartEssentiaImportBus")));
        assertEquals("other", Ae2NetworkSnapshot.classify(Object.class));
        assertEquals(
            "dual_interface",
            Ae2NetworkSnapshot.classifyNames(
                Arrays.asList(
                    "com.glodblock.github.common.parts.PartFluidP2PInterface",
                    "appeng.parts.p2p.PartP2PInterface")));
    }

    @Test
    void invalidModesAndRoutesNeverProvideAvailableCapacity() {
        assertTrue(valid(true, false, false, true, true, 32));
        assertFalse(valid(false, false, false, true, true, 0));
        assertFalse(valid(true, true, false, true, true, 0));
        assertFalse(valid(true, false, true, true, true, 0));
        assertFalse(valid(true, false, false, false, true, 0));
        assertFalse(valid(true, false, false, true, false, 0));
        assertFalse(valid(true, false, false, true, true, 33));
        assertFalse(valid(true, false, false, true, true, -1));
    }

    private boolean valid(boolean channels, boolean creative, boolean booting, boolean online, boolean routes,
        long count) {
        return Ae2NetworkSnapshot
            .validCapacity(channels, creative, booting, online, routes, 6, count, Collections.singletonList(count));
    }

    @Test
    void snapshotsRemoveStaleRowsAndIsolateReaderFailures() throws Exception {
        TargetRegistry registry = TargetRegistry.open(directory);
        Object good = new Object(), bad = new Object();
        Target a = registry.discover(Target.Kind.AE2, anchor(1, -1), "Good");
        Target b = registry.discover(Target.Kind.AE2, anchor(2, -1), "Bad");
        registry.configure(a.id(), a.name(), true);
        registry.configure(b.id(), b.name(), true);
        Map<Target.Anchor, Object> grids = new HashMap<>();
        grids.put(a.anchor(), good);
        grids.put(b.anchor(), bad);
        Ae2NetworkCollector collector = new Ae2NetworkCollector(
            resolver(registry, grids, Collections.emptyList()),
            20,
            token -> {
                if (token == bad) throw new IllegalStateException("Broken");
                return new Ae2NetworkSnapshot(
                    Collections.singletonMap("item_interface", 2),
                    10,
                    3,
                    "controller_online",
                    true,
                    6,
                    8,
                    true,
                    Collections.singletonMap("0:1:64:0:0", 8L));
            });
        collector.refresh();
        assertTrue(
            collector.collect()
                .stream()
                .anyMatch(f -> f.name.endsWith("channels_available")));
        grids.clear();
        collector.refresh();
        assertFalse(
            collector.collect()
                .stream()
                .anyMatch(f -> f.name.endsWith("channels_available")));
        assertEquals(
            2,
            collector.collect()
                .stream()
                .filter(f -> f.name.endsWith("_available"))
                .findFirst()
                .get().samples.size());
    }

    private Ae2GridResolver resolver(TargetRegistry registry, Map<Target.Anchor, Object> grids,
        List<Ae2GridResolver.Candidate> candidates) {
        return new Ae2GridResolver(registry, new Ae2GridResolver.Backend() {

            public Object resolve(Target.Anchor a) {
                return grids.get(a);
            }

            public List<Ae2GridResolver.Candidate> discover(int dimension) {
                return candidates;
            }
        });
    }
}
