package com.github.cpburnz.minecraft_prometheus_exporter.integrations.ae2;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;

import com.github.cpburnz.minecraft_prometheus_exporter.tracking.Target;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.me.cluster.implementations.CraftingCPUCluster;

/** Optional AE classes are reached only after the resolver's version guard. */
public final class Ae2CpuReader {

    private Ae2CpuReader() {}

    public static List<Ae2CpuSnapshot> read(Object token) {
        List<Ae2CpuSnapshot> result = new ArrayList<>();
        ICraftingGrid crafting = ((IGrid) token).getCache(ICraftingGrid.class);
        for (ICraftingCPU cpu : crafting.getCpus()) {
            if (!(cpu instanceof CraftingCPUCluster) || !(cpu instanceof Ae2CpuAccess))
                throw new IllegalStateException("CPU instrumentation unavailable");
            CraftingCPUCluster cluster = (CraftingCPUCluster) cpu;
            Target.Anchor anchor = null;
            Iterator<?> tiles = cluster.getTiles();
            while (tiles.hasNext()) {
                TileEntity tile = (TileEntity) tiles.next();
                Target.Anchor candidate = new Target.Anchor(
                    tile.getWorldObj().provider.dimensionId,
                    tile.xCoord,
                    tile.yCoord,
                    tile.zCoord,
                    -1);
                if (anchor == null || Ae2GridResolver.ANCHORS.compare(candidate, anchor) < 0) anchor = candidate;
            }
            if (anchor == null) continue;
            Ae2CpuAccess access = (Ae2CpuAccess) cpu;
            IAEStack<?> request = (IAEStack<?>) access.prometheus$originalRequest();
            result.add(
                new Ae2CpuSnapshot(
                    cpu,
                    cpu.getName(),
                    anchor,
                    cpu.isBusy(),
                    request == null ? 0 : request.getStackSize(),
                    access.prometheus$requestRemaining(),
                    cpu.getStartItemCount(),
                    cpu.getRemainingItemCount(),
                    request == null ? null : resource(request)));
        }
        return result;
    }

    static Ae2CpuSnapshot.Resource resource(IAEStack<?> stack) {
        if (stack instanceof IAEFluidStack) {
            IAEFluidStack fluid = (IAEFluidStack) stack;
            return new Ae2CpuSnapshot.Resource(
                "fluid",
                fluid.getFluid()
                    .getName(),
                "",
                fingerprint(fluid.getFluidStack().tag),
                fluid.getFluidStack()
                    .getLocalizedName());
        }
        // No direct ThE dependency: its native serialized aspect tag excludes the changing amount.
        if (stack.getClass()
            .getName()
            .equals("thaumicenergistics.common.storage.AEEssentiaStack")) {
            NBTTagCompound tag = new NBTTagCompound();
            stack.writeToNBT(tag);
            return new Ae2CpuSnapshot.Resource(
                "essentia",
                tag.getString("AspectTag"),
                "",
                "",
                tag.getString("AspectTag"));
        }
        if (stack instanceof IAEItemStack) {
            ItemStack item = ((IAEItemStack) stack).getItemStack();
            return new Ae2CpuSnapshot.Resource(
                "item",
                Item.itemRegistry.getNameForObject(item.getItem()),
                Integer.toString(item.getItemDamage()),
                fingerprint(item.getTagCompound()),
                item.getDisplayName());
        }
        return new Ae2CpuSnapshot.Resource(
            "unknown",
            stack.getClass()
                .getName(),
            "",
            "",
            "Unknown resource");
    }

    static String fingerprint(NBTTagCompound tag) {
        if (tag == null) return "";
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hashTag(hash, tag);
            byte[] digest = hash.digest();
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void hashTag(MessageDigest hash, NBTBase tag) {
        hash.update(tag.getId());
        if (tag instanceof NBTTagCompound) {
            NBTTagCompound compound = (NBTTagCompound) tag;
            for (String key : new TreeSet<String>(compound.func_150296_c())) {
                hashText(hash, key);
                hashTag(hash, compound.getTag(key));
            }
            hash.update((byte) 0);
        } else if (tag instanceof NBTTagList) {
            NBTTagList list = (NBTTagList) tag.copy();
            hashText(hash, Integer.toString(list.tagCount()));
            for (int i = list.tagCount() - 1; i >= 0; i--) hashTag(hash, list.removeTag(i));
        } else if (tag instanceof NBTTagByteArray) {
            byte[] bytes = ((NBTTagByteArray) tag).func_150292_c();
            hashText(hash, Integer.toString(bytes.length));
            hash.update(bytes);
        } else hashText(hash, tag.toString());
    }

    private static void hashText(MessageDigest hash, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        hash.update(
            Integer.toString(bytes.length)
                .getBytes(StandardCharsets.UTF_8));
        hash.update((byte) ':');
        hash.update(bytes);
    }
}
