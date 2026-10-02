package com.github.cpburnz.minecraft_prometheus_exporter.tracking;

import java.util.List;

/**
 * Implementations live in integration-specific packages and are constructed only after version checks.
 * All methods run on the server thread. Discovery enumerates loaded hosts only.
 * resolve must check loaded chunks before tile lookup and must never load a chunk.
 */
public interface IntegrationAdapter<T> {

    enum Status {
        AVAILABLE,
        UNAVAILABLE,
        DISABLED,
        UNSUPPORTED,
        ERROR
    }

    @com.github.bsideup.jabel.Desugar
    record Resolution<T> (Status status, T value, String detail) {

        public Resolution {
            if (status == null || detail == null || (status == Status.AVAILABLE) != (value != null))
                throw new IllegalArgumentException("Availability and resolved value disagree");
        }
    }

    @com.github.bsideup.jabel.Desugar
    record Discovered(Target.Anchor anchor, String name) {}

    List<Discovered> discoverLoaded(int dimension);

    Resolution<T> resolve(Target target);

    /** Read-only command probe, including disabled targets; must not bind instrumentation. */
    default Resolution<?> inspect(Target target) {
        return resolve(target);
    }

    /** Discard world/grid/tile references on exporter stop and world unload. */
    void clear();
}
