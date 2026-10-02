package com.github.cpburnz.minecraft_prometheus_exporter.mixins.powerfails;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

@Mixin(targets = "gregtech.common.data.GTPowerfailTracker$PowerfailData$DimensionInfo", remap = false)
public interface DimensionInfoAccess {

    @Accessor("byCoord")
    Long2ObjectOpenHashMap<?> prometheus$records();
}
