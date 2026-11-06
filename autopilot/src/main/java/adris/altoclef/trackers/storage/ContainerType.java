package adris.altoclef.trackers.storage;

import adris.altoclef.util.slots.ChestSlot;
import adris.altoclef.util.slots.FurnaceSlot;
import adris.altoclef.util.slots.Slot;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import org.apache.commons.lang3.NotImplementedException;

public enum ContainerType {
    CHEST, ENDER_CHEST, SHULKER, FURNACE, BREWING, MISC, EMPTY;

    // can a resource task go and take things out of this kind of container. a furnace's slots are somebody's smelt in
    // progress (the raw iron we just loaded), not loot: the 39 iron need once pulled its own three ore back out of the
    // furnace it was cooking in. whoever is smelting collects the output through the smelt task or FurnaceWatch
    public boolean lootable() {
        return this != FURNACE;
    }

    public static ContainerType getFromBlock(Block block) {
        if (block instanceof ChestBlock) {
            return CHEST;
        }
        if (block instanceof AbstractFurnaceBlock) {
            return FURNACE;
        }
        if (block.equals(Blocks.ENDER_CHEST)) {
            return ENDER_CHEST;
        }
        if (block instanceof ShulkerBoxBlock) {
            return SHULKER;
        }
        if (block instanceof BrewingStandBlock) {
            return BREWING;
        }
        if (block instanceof BarrelBlock || block instanceof DispenserBlock || block instanceof HopperBlock) {
            return MISC;
        }
        return EMPTY;
    }

    public static boolean screenHandlerMatches(ContainerType type, AbstractContainerMenu handler) {
        switch (type) {
            case CHEST, ENDER_CHEST -> {
                return handler instanceof ChestMenu;
            }
            case SHULKER -> {
                return handler instanceof ShulkerBoxMenu;
            }
            case FURNACE -> {
                return handler instanceof AbstractFurnaceMenu;
            }
            case BREWING -> {
                return handler instanceof BrewingStandMenu;
            }
            case MISC -> {
                return handler instanceof DispenserMenu || handler instanceof ChestMenu;
            }
            case EMPTY -> {
                return false;
            }
            default -> throw new NotImplementedException("Missed this chest type: " + type);
        }
    }

    public static boolean screenHandlerMatches(ContainerType type) {
        if (Minecraft.getInstance().player != null) {
            AbstractContainerMenu h = Minecraft.getInstance().player.containerMenu;
            if (h != null)
                return screenHandlerMatches(type, h);
        }
        return false;
    }

    public static boolean screenHandlerMatchesAny() {
        return screenHandlerMatches(CHEST) ||
                screenHandlerMatches(SHULKER) ||
                screenHandlerMatches(FURNACE);
    }

    public static boolean slotTypeMatches(ContainerType type, Slot slot) {
        switch (type) {
            case CHEST, ENDER_CHEST, SHULKER -> {
                return slot instanceof ChestSlot;
            }
            case FURNACE -> {
                return slot instanceof FurnaceSlot;
            }
            case BREWING -> throw new NotImplementedException("Brewing slots not implemented yet.");
            case MISC -> {
                return true;
            }
            default -> throw new NotImplementedException("Missed this chest type: " + type);
        }
    }
}
