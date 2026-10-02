package com.github.cpburnz.minecraft_prometheus_exporter.integrations.ae2;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import com.github.cpburnz.minecraft_prometheus_exporter.tracking.Target;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.pathing.ControllerState;
import appeng.api.networking.pathing.IPathingGrid;
import appeng.core.AEConfig;
import appeng.core.features.AEFeature;

/** Reads only the selected grid's nodes; never follows storage buses or any other grid. */
public final class Ae2NetworkReader {

    public static Ae2NetworkSnapshot read(Object token) {
        IGrid grid = (IGrid) token;
        Map<String, Integer> devices = new LinkedHashMap<>();
        Set<Object> hosts = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<IGridConnection> routes = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Target.Anchor> controllers = new HashSet<>();
        Map<String, Long> faceUsage = new LinkedHashMap<>();
        boolean creative = false;
        boolean validRoutes = true;
        long used = 0;
        for (IGridNode node : grid.getNodes()) {
            Object host = node.getMachine();
            if (host != null && hosts.add(host))
                devices.merge(Ae2NetworkSnapshot.classify(host.getClass()), 1, Integer::sum);
            if (!isController(host)) continue;
            TileEntity controller = (TileEntity) host;
            creative |= hasType(host, "appeng.tile.networking.TileCreativeEnergyController");
            Target.Anchor anchor = new Target.Anchor(
                node.getWorld().provider.dimensionId,
                controller.xCoord,
                controller.yCoord,
                controller.zCoord,
                -1);
            controllers.add(anchor);
            for (IGridConnection connection : node.getConnections()) {
                if (isController(
                    connection.getOtherSide(node)
                        .getMachine())
                    || !routes.add(connection)) continue;
                int count = connection.getUsedChannels();
                used += count;
                ForgeDirection direction = connection.getDirection(node);
                if (!connection.hasDirection() || direction == ForgeDirection.UNKNOWN || count < 0) validRoutes = false;
                String key = anchor.dimension() + ":"
                    + anchor.x()
                    + ":"
                    + anchor.y()
                    + ":"
                    + anchor.z()
                    + ":"
                    + direction.ordinal();
                faceUsage.merge(key, (long) count, Long::sum);
            }
        }
        IPathingGrid path = grid.getCache(IPathingGrid.class);
        IEnergyGrid energy = grid.getCache(IEnergyGrid.class);
        boolean channels = AEConfig.instance.isFeatureEnabled(AEFeature.Channels);
        String state = !channels ? "channels_disabled"
            : creative ? "creative"
                : path.isNetworkBooting() ? "booting"
                    : path.getControllerState()
                        .name()
                        .toLowerCase(java.util.Locale.ROOT);
        long faces = Ae2NetworkSnapshot.exteriorFaces(controllers);
        boolean valid = Ae2NetworkSnapshot.validCapacity(
            channels,
            creative,
            path.isNetworkBooting(),
            path.getControllerState() == ControllerState.CONTROLLER_ONLINE,
            validRoutes,
            faces,
            used,
            faceUsage.values());
        return new Ae2NetworkSnapshot(
            devices,
            energy.getAvgPowerUsage(),
            energy.getIdlePowerUsage(),
            state,
            channels,
            faces,
            used,
            valid,
            faceUsage);
    }

    private static boolean isController(Object host) {
        return hasType(host, "appeng.tile.networking.TileController");
    }

    private static boolean hasType(Object host, String name) {
        if (host == null) return false;
        for (Class<?> c = host.getClass(); c != null; c = c.getSuperclass()) if (c.getName()
            .equals(name)) return true;
        return false;
    }

    private Ae2NetworkReader() {}
}
