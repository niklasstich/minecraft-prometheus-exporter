package com.github.cpburnz.minecraft_prometheus_exporter.integrations.lsc;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.cpburnz.minecraft_prometheus_exporter.tracking.InstrumentationStore;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.IntegrationAdapter;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.Target;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.TargetRegistry;

class LscTest {

    @Test
    void failingTargetDoesNotFreezeOtherTargets() throws Exception {
        TargetRegistry registry = TargetRegistry.open(directory);
        Target broken = registry.discover(Target.Kind.LSC, new Target.Anchor(0, 1, 2, 3, -1), "Broken");
        Target healthy = registry.discover(Target.Kind.LSC, new Target.Anchor(0, 4, 2, 3, -1), "Healthy");
        registry.configure(broken.id(), broken.name(), true);
        registry.configure(healthy.id(), healthy.name(), true);
        InstrumentationStore store = new InstrumentationStore(registry);
        store.start();
        FakeHost host = new FakeHost();
        LscAdapter.Backend backend = new LscAdapter.Backend() {

            public List<IntegrationAdapter.Discovered> discover(int dimension) {
                return Collections.emptyList();
            }

            public LscAdapter.Host resolve(Target.Anchor anchor) {
                if (anchor.x() == 1) throw new IllegalStateException("Broken target");
                return host;
            }
        };
        LscAdapter adapter = new LscAdapter(registry, store, backend);
        assertEquals(
            IntegrationAdapter.Status.ERROR,
            adapter.resolve(registry.get(broken.id()))
                .status());
        LscCollector collector = new LscCollector(registry, adapter, 20);
        collector.refresh();
        List<io.prometheus.client.Collector.MetricFamilySamples.Sample> availability = collector.collect()
            .stream()
            .filter(f -> f.name.equals("mc_lsc_available"))
            .findFirst()
            .get().samples;
        assertEquals(2, availability.size());
        assertEquals(0, availability.get(0).value);
        assertEquals(1, availability.get(1).value);
        assertTrue(
            collector.collect()
                .stream()
                .anyMatch(f -> f.name.equals("mc_lsc_energy_stored_eu")));
    }

    @TempDir
    Path directory;
    static final BigInteger HUGE = BigInteger.ONE.shiftLeft(120);

    @Test
    void simultaneousFlowsAndFailedWirelessAttempt() {
        LscAccounting tick = new LscAccounting();
        tick.beforeRebalance(100, 40);
        tick.wireless(HUGE.negate(), false);
        tick.loss(BigInteger.valueOf(90), 10);
        assertArrayEquals(
            new BigInteger[] { BigInteger.valueOf(100), BigInteger.valueOf(40), BigInteger.TEN },
            tick.finish(Long.MIN_VALUE, -100));
    }

    @Test
    void successfulWirelessRetainsPrecisionDespiteUpstreamNarrowing() {
        LscAccounting tick = new LscAccounting();
        tick.beforeRebalance(5, 7);
        tick.wireless(HUGE.negate(), true);
        tick.loss(HUGE, 3);
        assertArrayEquals(
            new BigInteger[] { HUGE.add(BigInteger.valueOf(5)), BigInteger.valueOf(7), BigInteger.valueOf(3) },
            tick.finish(-1, 7));
        LscAccounting output = new LscAccounting();
        output.beforeRebalance(5, 7);
        output.wireless(HUGE, true);
        output.loss(BigInteger.ZERO, 0);
        assertEquals(HUGE.add(BigInteger.valueOf(7)), output.finish(5, -1)[1]);
    }

    @Test
    void depletedStoreLosesOnlyAvailableEnergyAndRejectsNegativeWiredAccounting() {
        LscAccounting tick = new LscAccounting();
        tick.loss(BigInteger.valueOf(-8), 10);
        assertEquals(BigInteger.valueOf(2), tick.finish(0, 0)[2]);
        assertNull(tick.finish(-1, 0));
        tick.loss(BigInteger.ZERO, -1);
        assertNull(tick.finish(0, 0));
    }

    private static final class FakeHost implements LscAdapter.Host {

        boolean formed = true;

        public Object token() {
            return this;
        }

        public BigInteger stored() {
            return HUGE;
        }

        public BigInteger capacity() {
            return HUGE.multiply(BigInteger.valueOf(4));
        }

        public boolean formed() {
            return formed;
        }

        public boolean wireless() {
            return true;
        }

        public long passiveLoss() {
            return 10;
        }

        public int[] prometheus$capacitors() {
            return new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 };
        }

        public void prometheus$select(String id) {}
    }

    private static final class FakeBackend implements LscAdapter.Backend {

        FakeHost host = new FakeHost();
        int calls;

        public List<IntegrationAdapter.Discovered> discover(int dimension) {
            return Collections.emptyList();
        }

        public LscAdapter.Host resolve(Target.Anchor anchor) {
            calls++;
            return host;
        }
    }

    @Test
    void snapshotsMixedTiersInactiveTicksUnloadReplacementAndDisable() throws Exception {
        TargetRegistry registry = TargetRegistry.open(directory);
        Target target = registry.discover(Target.Kind.LSC, new Target.Anchor(0, 1, 2, 3, -1), "LSC");
        InstrumentationStore store = new InstrumentationStore(registry);
        store.start();
        FakeBackend backend = new FakeBackend();
        LscAdapter adapter = new LscAdapter(registry, store, backend);
        assertEquals(
            IntegrationAdapter.Status.DISABLED,
            adapter.resolve(target)
                .status());
        assertEquals(0, backend.calls);
        registry.configure(target.id(), target.name(), true);
        target = registry.get(target.id());
        LscSnapshot snapshot = adapter.resolve(target)
            .value();
        assertEquals(HUGE, snapshot.stored());
        assertEquals(0.25, snapshot.chargeRatio());
        assertEquals(
            7,
            snapshot.capacitors()
                .get(LscSnapshot.TIERS.indexOf("EV")));
        assertEquals(
            6,
            snapshot.capacitors()
                .get(LscSnapshot.TIERS.indexOf("None")));
        assertThrows(
            UnsupportedOperationException.class,
            () -> snapshot.capacitors()
                .set(0, 100));
        InstrumentationStore.Key key = new InstrumentationStore.Key(target.id(), "controller");
        FakeHost old = backend.host;
        store.add(key, old, HUGE, BigInteger.TEN, BigInteger.ONE, BigInteger.ZERO);
        assertEquals(
            HUGE,
            adapter.resolve(target)
                .value()
                .totals()
                .input());
        // Sampling inactive controllers cannot extrapolate the previous running tick.
        assertEquals(
            HUGE,
            adapter.resolve(target)
                .value()
                .totals()
                .input());
        backend.host = new FakeHost();
        assertEquals(
            BigInteger.ZERO,
            adapter.resolve(target)
                .value()
                .totals()
                .input());
        assertFalse(store.add(key, old, HUGE, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO));
        backend.host = null;
        assertEquals(
            IntegrationAdapter.Status.UNAVAILABLE,
            adapter.resolve(target)
                .status());
        assertNull(store.read(key));
        backend.host = old;
        old.formed = false;
        assertEquals(
            Collections.nCopies(10, 0),
            adapter.resolve(target)
                .value()
                .capacitors());
        adapter.clear();
        assertNull(store.read(key));
    }

    @Test
    void collectorPublishesImmutableSnapshotsAndRemovesUnavailableAndDisabledRows() throws Exception {
        TargetRegistry registry = TargetRegistry.open(directory);
        Target t = registry.discover(Target.Kind.LSC, new Target.Anchor(0, 1, 2, 3, -1), "LSC");
        registry.configure(t.id(), t.name(), true);
        InstrumentationStore store = new InstrumentationStore(registry);
        store.start();
        FakeBackend backend = new FakeBackend();
        LscCollector collector = new LscCollector(registry, new LscAdapter(registry, store, backend), 20);
        collector.refresh();
        assertTrue(
            collector.collect()
                .stream()
                .anyMatch(f -> f.name.equals("mc_lsc_energy_capacity_eu")));
        assertThrows(
            UnsupportedOperationException.class,
            () -> collector.collect()
                .clear());
        backend.host = null;
        int calls = backend.calls;
        collector.collect();
        assertEquals(calls, backend.calls);
        collector.refresh();
        assertEquals(
            1,
            collector.collect()
                .size());
        assertEquals(
            0,
            collector.collect()
                .get(0).samples.get(0).value);
        registry.configure(t.id(), t.name(), false);
        collector.refresh();
        assertTrue(
            collector.collect()
                .isEmpty());
    }

    @Test
    void unformedControllersOmitStaleCapacityAndInventory() throws Exception {
        TargetRegistry registry = TargetRegistry.open(directory);
        Target t = registry.discover(Target.Kind.LSC, new Target.Anchor(0, 1, 2, 3, -1), "LSC");
        registry.configure(t.id(), t.name(), true);
        InstrumentationStore store = new InstrumentationStore(registry);
        store.start();
        FakeBackend backend = new FakeBackend();
        backend.host.formed = false;
        LscCollector collector = new LscCollector(registry, new LscAdapter(registry, store, backend), 20);
        collector.refresh();
        assertFalse(
            collector.collect()
                .stream()
                .anyMatch(f -> f.name.equals("mc_lsc_capacitors") || f.name.equals("mc_lsc_energy_capacity_eu")));
    }
}
