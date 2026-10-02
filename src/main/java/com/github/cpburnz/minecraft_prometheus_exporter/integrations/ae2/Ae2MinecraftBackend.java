package com.github.cpburnz.minecraft_prometheus_exporter.integrations.ae2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.ForgeDirection;

import com.github.cpburnz.minecraft_prometheus_exporter.tracking.Target;

import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;

final class Ae2MinecraftBackend implements Ae2GridResolver.Backend {

    private Object grid(TileEntity tile, int side) {
        if (tile == null || tile.isInvalid()) return null;
        IGridNode node = null;
        if (side >= 0 && tile instanceof IPartHost) {
            IPart part = ((IPartHost) tile).getPart(ForgeDirection.getOrientation(side));
            if (part != null) node = part.getGridNode();
        } else if (side == -1 && tile instanceof IGridHost && !(tile instanceof IPartHost)) {
            node = ((IGridHost) tile).getGridNode(ForgeDirection.UNKNOWN);
        }
        return node == null ? null : node.getGrid();
    }

    @Override
    public Object resolve(Target.Anchor a) {
        World world = DimensionManager.getWorld(a.dimension());
        if (world == null || a.y() < 0
            || a.y() >= world.getHeight()
            || !world.getChunkProvider()
                .chunkExists(a.x() >> 4, a.z() >> 4))
            return null;
        return grid(world.getTileEntity(a.x(), a.y(), a.z()), a.part());
    }

    @Override
    public List<Ae2GridResolver.Candidate> discover(int dimension) {
        World world = DimensionManager.getWorld(dimension);
        if (world == null) return Collections.emptyList();
        List<Ae2GridResolver.Candidate> result = new ArrayList<>();
        for (Object value : new ArrayList<>(world.loadedTileEntityList)) {
            TileEntity tile = (TileEntity) value;
            for (int side = -1; side <= 6; side++) {
                try {
                    Object grid = grid(tile, side);
                    if (grid != null) result.add(
                        new Ae2GridResolver.Candidate(
                            new Target.Anchor(dimension, tile.xCoord, tile.yCoord, tile.zCoord, side),
                            grid));
                } catch (RuntimeException | LinkageError ignored) { /* One broken host must not prevent discovery. */ }
            }
        }
        return result;
    }
}
