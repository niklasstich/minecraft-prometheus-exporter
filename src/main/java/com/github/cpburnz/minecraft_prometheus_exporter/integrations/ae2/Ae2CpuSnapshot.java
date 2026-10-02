package com.github.cpburnz.minecraft_prometheus_exporter.integrations.ae2;

import com.github.cpburnz.minecraft_prometheus_exporter.tracking.Target;

/** Server-thread transfer object. The binding token must never enter a published metric snapshot. */
@com.github.bsideup.jabel.Desugar
public record Ae2CpuSnapshot(Object token, String name, Target.Anchor anchor, boolean busy, long requestTotal,
    long requestRemaining, long progressTotal, long progressRemaining, Resource resource) {

    @com.github.bsideup.jabel.Desugar
    public record Resource(String type, String id, String metadata, String nbt, String displayName) {}
}
