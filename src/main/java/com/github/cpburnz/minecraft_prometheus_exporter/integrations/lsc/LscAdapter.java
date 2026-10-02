package com.github.cpburnz.minecraft_prometheus_exporter.integrations.lsc;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;

import com.github.cpburnz.minecraft_prometheus_exporter.ModCompat;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.InstrumentationStore;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.IntegrationAdapter;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.Target;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.TargetRegistry;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import kekztech.common.tileentities.MTELapotronicSuperCapacitor;

/** Construct through create() only; the optional GregTech classes remain behind the release guard. */
public final class LscAdapter implements IntegrationAdapter<LscSnapshot> {

    public interface Host extends LscAccess {

        Object token();

        BigInteger stored();

        BigInteger capacity();

        boolean formed();

        boolean wireless();

        long passiveLoss();
    }

    public interface Backend {

        List<Discovered> discover(int dimension);

        Host resolve(Target.Anchor anchor);
    }

    private final TargetRegistry registry;
    private final InstrumentationStore store;
    private final Backend backend;
    private final java.util.Set<InstrumentationStore.Key> bindings = new java.util.HashSet<>();

    public LscAdapter(TargetRegistry registry, InstrumentationStore store, Backend backend) {
        this.registry = registry;
        this.store = store;
        this.backend = backend;
    }

    public static LscAdapter create(TargetRegistry registry, InstrumentationStore store) {
        return ModCompat.GregTech.isSupported() ? new LscAdapter(registry, store, new MinecraftBackend()) : null;
    }

    @Override
    public List<Discovered> discoverLoaded(int dimension) {
        registry.checkThread();
        List<Discovered> result = new ArrayList<>(backend.discover(dimension));
        result.sort(
            Comparator.comparingInt(
                (Discovered d) -> d.anchor()
                    .x())
                .thenComparingInt(
                    d -> d.anchor()
                        .y())
                .thenComparingInt(
                    d -> d.anchor()
                        .z()));
        return Collections.unmodifiableList(result);
    }

    @Override
    public Resolution<LscSnapshot> resolve(Target target) {
        registry.checkThread();
        InstrumentationStore.Key key = new InstrumentationStore.Key(target.id(), "controller");
        if (target.kind() != Target.Kind.LSC || target.anchor()
            .part() != -1) return unavailable(key, Status.UNSUPPORTED, "Expected an LSC tile anchor");
        Target selected = registry.get(target.id());
        if (selected == null || !selected.equals(target) || !selected.enabled())
            return unavailable(key, Status.DISABLED, "Selection disabled or stale");
        try {
            Host host = backend.resolve(target.anchor());
            if (host == null) return unavailable(key, Status.UNAVAILABLE, "Controller missing or unloaded");
            if (!store.bind(key, host.token())) return unavailable(key, Status.UNAVAILABLE, "Accounting inactive");
            bindings.add(key);
            host.prometheus$select(target.id());
            boolean formed = host.formed();
            int[] inventory = host.prometheus$capacitors();
            if (!formed) Arrays.fill(inventory, 0);
            List<Integer> counts = new ArrayList<>();
            for (int count : inventory) counts.add(count);
            return new Resolution<>(
                Status.AVAILABLE,
                new LscSnapshot(
                    host.stored(),
                    host.capacity(),
                    formed,
                    host.wireless(),
                    host.passiveLoss(),
                    counts,
                    store.read(key)),
                "");
        } catch (RuntimeException | LinkageError e) {
            return unavailable(key, Status.ERROR, e.toString());
        }
    }

    @Override
    public Resolution<?> inspect(Target target) {
        registry.checkThread();
        if (target.kind() != Target.Kind.LSC || target.anchor()
            .part() != -1) return new Resolution<>(Status.UNSUPPORTED, null, "Expected an LSC tile anchor");
        try {
            boolean available = backend.resolve(target.anchor()) != null;
            return new Resolution<>(
                available ? Status.AVAILABLE : Status.UNAVAILABLE,
                available ? Boolean.TRUE : null,
                available ? "" : "Controller missing or unloaded");
        } catch (RuntimeException | LinkageError e) {
            return new Resolution<>(Status.ERROR, null, e.toString());
        }
    }

    private Resolution<LscSnapshot> unavailable(InstrumentationStore.Key key, Status status, String detail) {
        store.unbind(key);
        bindings.remove(key);
        return new Resolution<>(status, null, detail);
    }

    /** Run at snapshot refresh to remove deleted/disabled selections from bookkeeping. */
    public void prune() {
        registry.checkThread();
        store.prune();
        bindings.removeIf(key -> {
            Target t = registry.get(key.targetId());
            return t == null || !t.enabled();
        });
    }

    @Override
    public void clear() {
        registry.checkThread();
        for (InstrumentationStore.Key key : bindings) store.unbind(key);
        bindings.clear();
    }

    private static final class MinecraftBackend implements Backend {

        private MTELapotronicSuperCapacitor controller(TileEntity tile) {
            if (!(tile instanceof IGregTechTileEntity)) return null;
            IGregTechTileEntity base = (IGregTechTileEntity) tile;
            if (base.isDead() || !(base.getMetaTileEntity() instanceof MTELapotronicSuperCapacitor)) return null;
            return (MTELapotronicSuperCapacitor) base.getMetaTileEntity();
        }

        public List<Discovered> discover(int dimension) {
            World world = DimensionManager.getWorld(dimension);
            if (world == null) return Collections.emptyList();
            List<Discovered> result = new ArrayList<>();
            for (Object value : new ArrayList<>(world.loadedTileEntityList)) {
                TileEntity tile = (TileEntity) value;
                if (!tile.isInvalid() && controller(tile) != null) result.add(
                    new Discovered(
                        new Target.Anchor(dimension, tile.xCoord, tile.yCoord, tile.zCoord, -1),
                        "Lapotronic Supercapacitor"));
            }
            return result;
        }

        public Host resolve(Target.Anchor a) {
            World world = DimensionManager.getWorld(a.dimension());
            if (world == null || a.y() < 0
                || a.y() >= world.getHeight()
                || !world.getChunkProvider()
                    .chunkExists(a.x() >> 4, a.z() >> 4))
                return null;
            TileEntity tile = world.getTileEntity(a.x(), a.y(), a.z());
            if (tile == null || tile.isInvalid()) return null;
            MTELapotronicSuperCapacitor lsc = controller(tile);
            if (!(lsc instanceof LscAccess)) return null;
            LscAccess access = (LscAccess) lsc;
            return new Host() {

                public Object token() {
                    return lsc;
                }

                public BigInteger stored() {
                    return lsc.getStored();
                }

                public BigInteger capacity() {
                    return lsc.getEnergyCapacity();
                }

                public boolean formed() {
                    return lsc.mMachine;
                }

                public boolean wireless() {
                    return lsc.isWireless_mode();
                }

                public long passiveLoss() {
                    return lsc.getPassiveDischargeAmount();
                }

                public int[] prometheus$capacitors() {
                    return access.prometheus$capacitors();
                }

                public void prometheus$select(String id) {
                    access.prometheus$select(id);
                }
            };
        }
    }
}
