package com.github.cpburnz.minecraft_prometheus_exporter.integrations.ae2;

import com.github.cpburnz.minecraft_prometheus_exporter.tracking.InstrumentationStore;

/** Implemented only by the guarded beta-3 CPU mixin. */
public interface Ae2CpuAccess {

    Object prometheus$originalRequest();

    long prometheus$requestRemaining();

    void prometheus$bind(InstrumentationStore store, InstrumentationStore.Key key);
}
