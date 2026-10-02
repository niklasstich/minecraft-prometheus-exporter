package com.github.cpburnz.minecraft_prometheus_exporter.mixins;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.github.cpburnz.minecraft_prometheus_exporter.ModCompat;
import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;

/** Late registration checks Forge mod versions before loading optional integration classes. */
@LateMixin
public final class LateMixinLoader implements ILateMixinLoader {

    @Override
    public String getMixinConfig() {
        return "mixins.prometheus_exporter.late.json";
    }

    @Override
    public List<String> getMixins(Set<String> loadedMods) {
        List<String> result = new ArrayList<>();
        append(result, loadedMods, ModCompat.GregTech, "lsc");
        append(result, loadedMods, ModCompat.AppliedEnergistics2, "ae2");
        if (loadedMods.contains(ModCompat.GTNHLib.modid) && ModCompat.GTNHLib.isSupported()) {
            append(result, loadedMods, ModCompat.GregTech, "powerfails");
        }
        return result;
    }

    private void append(List<String> result, Set<String> loadedMods, ModCompat mod, String feature) {
        if (!loadedMods.contains(mod.modid) || !mod.isSupported()) return;
        String resource = "/mixins/prometheus_exporter/" + feature + ".list";
        try (InputStream stream = getClass().getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("Missing mixin manifest " + resource);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String name = line.trim();
                    if (name.isEmpty() || name.startsWith("#")) continue;
                    if (!name.matches(feature + "\\.[A-Za-z0-9_.$]+")) {
                        throw new IllegalStateException("Mixin outside owned feature package: " + name);
                    }
                    result.add(name);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read " + resource, e);
        }
    }
}
