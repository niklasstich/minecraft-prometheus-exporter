package com.github.cpburnz.minecraft_prometheus_exporter.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstrumentationStoreTest {

    @TempDir
    Path directory;

    @Test
    void onlySelectedBoundTargetsAccumulateAndRebindResets() throws Exception {
        TargetRegistry registry = TargetRegistry.open(directory);
        Target target = registry.discover(Target.Kind.LSC, new Target.Anchor(0, 1, 2, 3, -1), "LSC");
        InstrumentationStore store = new InstrumentationStore(registry);
        InstrumentationStore.Key key = new InstrumentationStore.Key(target.id(), "controller");
        Object token = new Object();
        store.start();
        assertFalse(store.bind(key, token));
        registry.configure(target.id(), target.name(), true);
        assertTrue(store.bind(key, token));
        BigInteger huge = BigInteger.ONE.shiftLeft(100);
        assertTrue(store.add(key, token, huge, BigInteger.ONE, BigInteger.TEN, BigInteger.ZERO));
        assertEquals(
            huge,
            store.read(key)
                .input());
        assertFalse(store.add(key, token, BigInteger.valueOf(-1), huge, huge, huge));
        assertEquals(
            BigInteger.ONE,
            store.read(key)
                .output());
        assertFalse(store.add(key, new Object(), huge, huge, huge, huge));
        assertTrue(store.bind(key, new Object()));
        assertEquals(
            BigInteger.ZERO,
            store.read(key)
                .input());
    }

    @Test
    void disableReenableUnloadAndExporterRestartClearRuntimeOnly() throws Exception {
        TargetRegistry registry = TargetRegistry.open(directory);
        Target target = registry.discover(Target.Kind.AE2, new Target.Anchor(0, 1, 2, 3, 6), "AE2");
        registry.configure(target.id(), target.name(), true);
        InstrumentationStore store = new InstrumentationStore(registry);
        InstrumentationStore.Key key = new InstrumentationStore.Key(target.id(), "cpu@0,1,2,3");
        Object token = new Object();
        store.start();
        store.bind(key, token);
        store.add(key, token, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.TEN);
        registry.configure(target.id(), target.name(), false);
        registry.configure(target.id(), target.name(), true);
        assertNull(store.read(key));
        store.bind(key, token);
        store.clearDimensionBindings();
        assertNull(store.read(key));
        store.bind(key, token);
        store.stop();
        assertFalse(store.bind(key, token));
        store.start();
        assertNull(store.read(key));
        assertTrue(
            registry.get(target.id())
                .enabled());
    }

    @Test
    void identityStateIsBoundedAndUnbindFreesCapacity() throws Exception {
        TargetRegistry registry = TargetRegistry.open(directory);
        Target target = registry.discover(Target.Kind.AE2, new Target.Anchor(0, 1, 2, 3, -1), "AE2");
        registry.configure(target.id(), target.name(), true);
        InstrumentationStore store = new InstrumentationStore(registry);
        store.start();
        Object token = new Object();
        for (int i = 0; i < InstrumentationStore.MAX_IDENTITIES_PER_TARGET; i++) {
            assertTrue(store.bind(new InstrumentationStore.Key(target.id(), Integer.toString(i)), token));
        }
        InstrumentationStore.Key extra = new InstrumentationStore.Key(target.id(), "extra");
        assertFalse(store.bind(extra, token));
        store.unbind(new InstrumentationStore.Key(target.id(), "0"));
        assertTrue(store.bind(extra, token));
    }
}
