package com.github.cpburnz.minecraft_prometheus_exporter.mixins.lsc;

import java.math.BigInteger;
import java.util.UUID;

import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.github.cpburnz.minecraft_prometheus_exporter.PrometheusExporterMod;
import com.github.cpburnz.minecraft_prometheus_exporter.integrations.lsc.LscAccess;
import com.github.cpburnz.minecraft_prometheus_exporter.integrations.lsc.LscAccounting;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.InstrumentationStore;

import gregtech.common.misc.WirelessNetworkManager;
import kekztech.common.tileentities.MTELapotronicSuperCapacitor;

@Mixin(value = MTELapotronicSuperCapacitor.class, remap = false)
public abstract class LscMixin implements LscAccess {

    @Shadow
    @Final
    private int[] capacitors;
    @Shadow
    private long inputLastTick;
    @Shadow
    private long outputLastTick;
    @Shadow
    private long passiveDischargeAmount;
    @Unique
    private String prometheus$targetId;
    @Unique
    private LscAccounting prometheus$tick;

    public int[] prometheus$capacitors() {
        return capacitors.clone();
    }

    public void prometheus$select(String id) {
        prometheus$targetId = id;
    }

    @Inject(method = "onRunningTick", at = @At("HEAD"), remap = false, require = 1)
    private void prometheus$begin(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        InstrumentationStore store = PrometheusExporterMod.INSTANCE == null ? null
            : PrometheusExporterMod.INSTANCE.instrumentation();
        prometheus$tick = prometheus$targetId == null || store == null
            || store.read(new InstrumentationStore.Key(prometheus$targetId, "controller")) == null ? null
                : new LscAccounting();
    }

    @Inject(method = "rebalance", at = @At("HEAD"), remap = false, require = 1)
    private void prometheus$rebalance(CallbackInfoReturnable<Integer> cir) {
        if (prometheus$tick != null) prometheus$tick.beforeRebalance(inputLastTick, outputLastTick);
    }

    @Redirect(
        method = "rebalance",
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/common/misc/WirelessNetworkManager;addEUToGlobalEnergyMap(Ljava/util/UUID;Ljava/math/BigInteger;)Z"),
        remap = false,
        require = 1)
    private boolean prometheus$wireless(UUID owner, BigInteger transfer) {
        boolean success = WirelessNetworkManager.addEUToGlobalEnergyMap(owner, transfer);
        if (prometheus$tick != null) prometheus$tick.wireless(transfer, success);
        return success;
    }

    @Redirect(
        method = "onRunningTick",
        at = @At(value = "INVOKE", target = "Ljava/math/BigInteger;add(Ljava/math/BigInteger;)Ljava/math/BigInteger;"),
        remap = false,
        require = 1)
    private BigInteger prometheus$loss(BigInteger stored, BigInteger delta) {
        BigInteger result = stored.add(delta);
        if (prometheus$tick != null) prometheus$tick.loss(result, passiveDischargeAmount);
        return result;
    }

    @Inject(method = "onRunningTick", at = @At("RETURN"), remap = false, require = 1)
    private void prometheus$finish(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        LscAccounting tick = prometheus$tick;
        prometheus$tick = null;
        InstrumentationStore store = PrometheusExporterMod.INSTANCE == null ? null
            : PrometheusExporterMod.INSTANCE.instrumentation();
        if (tick == null || store == null || !cir.getReturnValue()) return;
        BigInteger[] flow = tick.finish(inputLastTick, outputLastTick);
        if (flow != null) store.add(
            new InstrumentationStore.Key(prometheus$targetId, "controller"),
            this,
            flow[0],
            flow[1],
            flow[2],
            BigInteger.ZERO);
    }
}
