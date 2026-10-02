package com.github.cpburnz.minecraft_prometheus_exporter.tracking;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.github.cpburnz.minecraft_prometheus_exporter.ModCompat;
import com.github.cpburnz.minecraft_prometheus_exporter.mixinloading.LateMixinLoader;
import com.google.gson.JsonParser;

class IntegrationFoundationTest {

    @Test
    void lateLoaderIsOutsideReservedMixinPackages() throws Exception {
        for (String config : new String[] { "mixins.prometheus_exporter.json",
            "mixins.prometheus_exporter.late.json" }) {
            try (InputStream stream = getClass().getResourceAsStream("/" + config);
                InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                String mixinPackage = new JsonParser().parse(reader)
                    .getAsJsonObject()
                    .get("package")
                    .getAsString();
                assertFalse(
                    LateMixinLoader.class.getName()
                        .startsWith(mixinPackage + "."));
            }
        }
    }

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
