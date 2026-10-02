package com.github.cpburnz.minecraft_prometheus_exporter.mixins.powerfails;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import gregtech.common.data.GTPowerfailTracker;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

@Mixin(value = GTPowerfailTracker.PowerfailData.class, remap = false)
public interface PowerfailDataAccess {

    @Accessor("byWorld")
    Int2ObjectOpenHashMap<?> prometheus$worlds();
}
