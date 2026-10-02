package com.github.cpburnz.minecraft_prometheus_exporter.mixins.ae2;

import java.math.BigInteger;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.github.cpburnz.minecraft_prometheus_exporter.integrations.ae2.Ae2CpuAccess;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.InstrumentationStore;

import appeng.api.storage.data.IAEStack;
import appeng.me.cluster.implementations.CraftingCPUCluster;

@Mixin(value = CraftingCPUCluster.class, remap = false)
public abstract class CraftingCpuMixin implements Ae2CpuAccess {

    @Shadow
    protected CraftingCPUCluster.finalOutput finalOutput;
    @Unique
    private InstrumentationStore prometheus$store;
    @Unique
    private InstrumentationStore.Key prometheus$key;

    public Object prometheus$originalRequest() {
        IAEStack<?> original = finalOutput.getOriginalOutput();
        return original == null ? null : original.copy();
    }

    public long prometheus$requestRemaining() {
        IAEStack<?> original = finalOutput.getOriginalOutput();
        IAEStack<?> remaining = original == null ? null : finalOutput.findPrecise(original);
        return remaining == null ? 0 : Math.max(0, remaining.getStackSize());
    }

    public void prometheus$bind(InstrumentationStore store, InstrumentationStore.Key key) {
        prometheus$store = store;
        prometheus$key = key;
    }

    @Inject(method = "updateElapsedTime", at = @At("RETURN"), remap = false, require = 1)
    private void prometheus$progress(IAEStack<?> stack, CallbackInfo ci) {
        if (prometheus$store != null && prometheus$key != null && stack.getStackSize() > 0) prometheus$store.add(
            prometheus$key,
            this,
            BigInteger.ZERO,
            BigInteger.ZERO,
            BigInteger.ZERO,
            BigInteger.valueOf(stack.getStackSize()));
    }
}
