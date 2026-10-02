package com.github.cpburnz.minecraft_prometheus_exporter.integrations.ae2;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.github.cpburnz.minecraft_prometheus_exporter.tracking.Target;

@com.github.bsideup.jabel.Desugar
public record Ae2NetworkSnapshot(Map<String, Integer> devices, double averagePower, double idlePower, String state,
    boolean channelsEnabled, long exteriorFaces, long usedChannels, boolean capacityValid,
    Map<String, Long> faceUsage) {

    public Ae2NetworkSnapshot {
        devices = Collections.unmodifiableMap(new LinkedHashMap<>(devices));
        faceUsage = Collections.unmodifiableMap(new LinkedHashMap<>(faceUsage));
    }

    public long capacity() {
        return exteriorFaces * 32;
    }

    public long availableChannels() {
        return capacity() - usedChannels;
    }

    public static long exteriorFaces(Set<Target.Anchor> blocks) {
        long faces = 0;
        int[][] offsets = { { 0, -1, 0 }, { 0, 1, 0 }, { 0, 0, -1 }, { 0, 0, 1 }, { -1, 0, 0 }, { 1, 0, 0 } };
        for (Target.Anchor b : blocks) for (int[] d : offsets)
            if (!blocks.contains(new Target.Anchor(b.dimension(), b.x() + d[0], b.y() + d[1], b.z() + d[2], -1)))
                faces++;
        return faces;
    }

    /** Most-specific optional addon types first; no addon classes are linked by the exporter. */
    public static String classify(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) names.add(c.getName());
        return classifyNames(names);
    }

    static String classifyNames(List<String> names) {
        String[][] types = { { "com.glodblock.github.common.tile.TileFluidInterface", "dual_interface" },
            { "com.glodblock.github.common.parts.PartFluidInterface", "dual_interface" },
            { "com.glodblock.github.common.parts.PartFluidP2PInterface", "dual_interface" },
            { "com.glodblock.github.common.parts.PartFluidImportBus", "fluid_import_bus" },
            { "com.glodblock.github.common.parts.PartFluidExportBus", "fluid_export_bus" },
            { "thaumicenergistics.common.parts.PartEssentiaImportBus", "essentia_import_bus" },
            { "thaumicenergistics.common.parts.PartEssentiaExportBus", "essentia_export_bus" },
            { "appeng.tile.misc.TileInterface", "item_interface" },
            { "appeng.parts.misc.PartInterface", "item_interface" },
            { "appeng.parts.p2p.PartP2PInterface", "item_interface" },
            { "appeng.parts.automation.PartImportBus", "item_import_bus" },
            { "appeng.parts.automation.PartExportBus", "item_export_bus" } };
        for (String[] t : types) if (names.contains(t[0])) return t[1];
        return "other";
    }

    static boolean validCapacity(boolean channels, boolean creative, boolean booting, boolean online,
        boolean validRoutes, long faces, long used, Collection<Long> faceUsage) {
        if (!channels || creative || booting || !online || !validRoutes || used < 0 || used > faces * 32) return false;
        for (long count : faceUsage) if (count < 0 || count > 32) return false;
        return true;
    }
}
