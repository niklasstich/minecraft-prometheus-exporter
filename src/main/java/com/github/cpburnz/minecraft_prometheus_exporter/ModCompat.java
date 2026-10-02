package com.github.cpburnz.minecraft_prometheus_exporter;

import java.util.function.Supplier;

import cpw.mods.fml.common.Loader;

public enum ModCompat {

    ServerUtilities("serverutilities"),
    GregTech("gregtech", "5.09.54.132"),
    AppliedEnergistics2("appliedenergistics2", "rv3-beta-1050-GTNH"),
    AE2FluidCraft("ae2fc", "1.5.106-gtnh"),
    ThaumicEnergistics("thaumicenergistics", "1.7.60-GTNH"),
    GTNHLib("gtnhlib", "0.11.46");

    public final String modid;
    private final Supplier<Boolean> supplier;
    private Boolean loaded;
    private String supportedVersion;

    ModCompat(String modid) {
        this.modid = modid;
        this.supplier = null;
    }

    ModCompat(String modid, String version) {
        this(modid);
        this.supportedVersion = version;
    }

    /** Fail closed for instrumentation against unverified versions. */
    public boolean isSupported() {
        cpw.mods.fml.common.ModContainer container = Loader.instance()
            .getIndexedModList()
            .get(modid);
        return container != null && supportsVersion(container.getVersion());
    }

    public boolean supportsVersion(String version) {
        return supportedVersion != null && supportedVersion.equals(version);
    }

    ModCompat(Supplier<Boolean> supplier) {
        this.supplier = supplier;
        this.modid = null;
    }

    public boolean isLoaded() {
        if (loaded == null) {
            if (supplier != null) {
                loaded = supplier.get();
            } else if (modid != null) {
                loaded = Loader.isModLoaded(modid);
            } else loaded = false;
        }
        return loaded;
    }
}
