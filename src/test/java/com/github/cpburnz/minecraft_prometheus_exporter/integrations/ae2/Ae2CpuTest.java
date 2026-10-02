package com.github.cpburnz.minecraft_prometheus_exporter.integrations.ae2;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.cpburnz.minecraft_prometheus_exporter.tracking.InstrumentationStore;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.Target;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.TargetRegistry;

import io.prometheus.client.Collector.MetricFamilySamples;

class Ae2CpuTest {

    @TempDir
    Path directory;

    @Test
    void fingerprintsIncludeByteArrayContentsAndIgnoreCompoundInsertionOrder() {
        NBTTagCompound first = new NBTTagCompound();
        first.setByteArray("data", new byte[] { 1, 2, 3 });
        first.setString("name", "resource");
        NBTTagCompound equal = new NBTTagCompound();
        equal.setString("name", "resource");
        equal.setByteArray("data", new byte[] { 1, 2, 3 });
        assertEquals(Ae2CpuReader.fingerprint(first), Ae2CpuReader.fingerprint(equal));

        NBTTagCompound different = (NBTTagCompound) first.copy();
        different.setByteArray("data", new byte[] { 3, 2, 1 });
        assertNotEquals(Ae2CpuReader.fingerprint(first), Ae2CpuReader.fingerprint(different));

        NBTTagList list = new NBTTagList();
        list.appendTag(first);
        NBTTagCompound nested = new NBTTagCompound();
        nested.setTag("list", list);
        String before = Ae2CpuReader.fingerprint(nested);
        first.setByteArray("data", new byte[] { 1, 2, 4 });
        assertNotEquals(before, Ae2CpuReader.fingerprint(nested));
        assertEquals(1, list.tagCount());
    }

    static class Cpu implements Ae2CpuAccess {

        InstrumentationStore store;
        InstrumentationStore.Key key;

        public Object prometheus$originalRequest() {
            return null;
        }

        public long prometheus$requestRemaining() {
            return 0;
        }

        public void prometheus$bind(InstrumentationStore value, InstrumentationStore.Key identity) {
            store = value;
            key = identity;
        }

        void progress(long amount) {
            if (store != null)
                store.add(key, this, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.valueOf(amount));
        }
    }

    private Ae2CpuSnapshot snapshot(Cpu cpu, String name, int x, boolean busy, String type) {
        return new Ae2CpuSnapshot(
            cpu,
            name,
            new Target.Anchor(2, x, 64, 0, -1),
            busy,
            5,
            8,
            40,
            30,
            new Ae2CpuSnapshot.Resource(type, "id", "", "", "Display"));
    }

    private static List<MetricFamilySamples.Sample> rows(Ae2CpuCollector collector, String suffix) {
        for (MetricFamilySamples f : collector.collect())
            if (!f.samples.isEmpty() && f.samples.get(0).name.equals("mc_ae2_cpu_" + suffix)) return f.samples;
        return Collections.emptyList();
    }

    @Test
    void eventsSurviveCompletionMergesAndCancellationWithoutJobLabels() throws Exception {
        TargetRegistry registry = TargetRegistry.open(directory);
        Target target = registry.discover(Target.Kind.AE2, new Target.Anchor(0, 0, 64, 0, -1), "Network");
        registry.configure(target.id(), target.name(), true);
        InstrumentationStore store = new InstrumentationStore(registry);
        store.start();
        Object grid = new Object();
        Ae2GridResolver resolver = new Ae2GridResolver(registry, new Ae2GridResolver.Backend() {

            public Object resolve(Target.Anchor anchor) {
                return grid;
            }

            public List<Ae2GridResolver.Candidate> discover(int dimension) {
                return Collections.emptyList();
            }
        });
        List<Ae2CpuSnapshot> cpus = new ArrayList<>();
        Cpu cpu = new Cpu(), duplicate = new Cpu();
        cpus.add(snapshot(cpu, "Same", 1, true, "item"));
        cpus.add(snapshot(duplicate, "Same", 2, true, "fluid"));
        Ae2CpuCollector collector = new Ae2CpuCollector(resolver, store, 20, ignored -> new ArrayList<>(cpus));
        collector.refresh();
        assertEquals(2, rows(collector, "busy").size());
        assertEquals(8, rows(collector, "request_remaining").get(0).value);
        cpu.progress(4); // full insertion
        cpu.progress(2); // partial insertion, before any snapshot
        cpus.set(0, snapshot(cpu, "Same", 1, false, "item")); // completion or cancellation resets gauges only
        collector.refresh();
        assertEquals(6, rows(collector, "progress_completed_total").get(0).value);
        assertFalse(rows(collector, "progress_completed_total").get(0).labelNames.contains("resource_id"));
        assertEquals(1, rows(collector, "request_info").size());
        cpus.set(0, snapshot(cpu, "Same", 1, true, "essentia")); // next or merged job
        collector.refresh();
        cpu.progress(3);
        collector.refresh();
        assertEquals(9, rows(collector, "progress_completed_total").get(0).value);
        List<MetricFamilySamples> published = collector.collect();
        cpus.set(0, snapshot(cpu, "Renamed", 1, true, "essentia"));
        collector.refresh();
        assertEquals(0, rows(collector, "progress_completed_total").get(0).value);
        cpu.progress(1);
        collector.refresh();
        assertEquals(1, rows(collector, "progress_completed_total").get(0).value);
        assertNotSame(published, collector.collect());
        cpus.clear(); // destruction/unload drops rows and unbinds hooks
        collector.refresh();
        assertTrue(rows(collector, "busy").isEmpty());
        assertNull(cpu.store);
        registry.configure(target.id(), target.name(), false);
        collector.refresh();
        assertTrue(
            collector.collect()
                .isEmpty());
        collector.clear();
    }

    @Test
    void unavailableAndReaderErrorsDropPreviousRows() throws Exception {
        TargetRegistry registry = TargetRegistry.open(directory);
        Target target = registry.discover(Target.Kind.AE2, new Target.Anchor(0, 0, 0, 0, -1), "N");
        registry.configure(target.id(), "N", true);
        InstrumentationStore store = new InstrumentationStore(registry);
        store.start();
        Ae2GridResolver resolver = new Ae2GridResolver(registry, new Ae2GridResolver.Backend() {

            public Object resolve(Target.Anchor anchor) {
                return this;
            }

            public List<Ae2GridResolver.Candidate> discover(int dimension) {
                return Collections.emptyList();
            }
        });
        Ae2CpuCollector collector = new Ae2CpuCollector(
            resolver,
            store,
            20,
            ignored -> { throw new IllegalStateException(); });
        collector.refresh();
        assertEquals(0, rows(collector, "available").get(0).value);
        assertTrue(rows(collector, "busy").isEmpty());
    }
}
