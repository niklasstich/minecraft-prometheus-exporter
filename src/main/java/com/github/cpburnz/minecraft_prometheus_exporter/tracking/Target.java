package com.github.cpburnz.minecraft_prometheus_exporter.tracking;

import java.util.Objects;
import java.util.UUID;

/** Immutable configuration only; never contains a world, tile, grid or team object. */
@com.github.bsideup.jabel.Desugar
public record Target(String id, Kind kind, Anchor anchor, UUID teamId, String name, boolean enabled) {

    public enum Kind {
        LSC,
        AE2,
        POWERFAILS
    }

    /** part: -1 for a tile, 0..5 for a face, 6 for the internal cable-bus part. */
    @com.github.bsideup.jabel.Desugar
    public record Anchor(int dimension, int x, int y, int z, int part) {

        public Anchor {
            if (part < -1 || part > 6) throw new IllegalArgumentException("Invalid part side");
        }
    }

    public Target {
        Objects.requireNonNull(id);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(name);
        if (name.length() > 256) throw new IllegalArgumentException("Target name exceeds 256 characters");
        if (kind == Kind.POWERFAILS) {
            if (anchor != null || teamId == null || !id.equals("powerfails-" + teamId))
                throw new IllegalArgumentException("Powerfail selections require a GTNHLib team UUID");
        } else {
            if (anchor == null || teamId != null
                || !id.matches(
                    kind.name()
                        .toLowerCase(java.util.Locale.ROOT) + "-[1-9][0-9]*"))
                throw new IllegalArgumentException("Invalid block target identity");
        }
    }

    public Target withSettings(String name, boolean enabled) {
        return new Target(id, kind, anchor, teamId, name, enabled);
    }
}
