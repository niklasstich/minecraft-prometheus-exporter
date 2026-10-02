package com.github.cpburnz.minecraft_prometheus_exporter.tracking;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.github.cpburnz.minecraft_prometheus_exporter.ModCompat;
import com.github.cpburnz.minecraft_prometheus_exporter.mixins.LateMixinLoader;

class IntegrationFoundationTest {

    @Test
    void absentOptionalModsDoNotLoadHooks() {
        assertTrue(
            new LateMixinLoader().getMixins(Collections.emptySet())
                .isEmpty());
    }

    @Test
    void onlyPinnedVersionsAreSupported() {
        assertTrue(ModCompat.GregTech.supportsVersion("5.09.54.132"));
        assertFalse(ModCompat.GregTech.supportsVersion("5.09.54.133"));
        assertTrue(ModCompat.AppliedEnergistics2.supportsVersion("rv3-beta-1050-GTNH"));
        assertFalse(ModCompat.GTNHLib.supportsVersion(null));
    }

    @Test
    void unavailableResolutionHasNoLiveValue() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new IntegrationAdapter.Resolution<>(IntegrationAdapter.Status.UNAVAILABLE, new Object(), "unloaded"));
        assertThrows(
            IllegalArgumentException.class,
            () -> new IntegrationAdapter.Resolution<>(IntegrationAdapter.Status.AVAILABLE, null, "loaded"));
    }
}
