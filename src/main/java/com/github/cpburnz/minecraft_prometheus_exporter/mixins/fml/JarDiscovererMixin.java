package com.github.cpburnz.minecraft_prometheus_exporter.mixins.fml;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import cpw.mods.fml.common.discovery.JarDiscoverer;

/** Java module descriptors contain no Forge mods and cannot be parsed by FML's legacy ASM. */
@Mixin(value = JarDiscoverer.class, remap = false)
public abstract class JarDiscovererMixin {

    @ModifyArg(
        method = "discover",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/regex/Pattern;matcher(Ljava/lang/CharSequence;)Ljava/util/regex/Matcher;"),
        index = 0,
        remap = false,
        require = 1)
    private CharSequence prometheus$skipModuleDescriptor(CharSequence name) {
        String entry = name.toString();
        return entry.equals("module-info.class") || entry.endsWith("/module-info.class") ? "__MACOSX_module_descriptor"
            : name;
    }
}
