package com.github.cpburnz.minecraft_prometheus_exporter.integrations.lsc;

/** Implemented by the version-guarded late mixin. */
public interface LscAccess {

    int[] prometheus$capacitors();

    void prometheus$select(String targetId);
}
