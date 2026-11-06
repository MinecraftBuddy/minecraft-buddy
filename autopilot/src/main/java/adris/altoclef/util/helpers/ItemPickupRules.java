package adris.altoclef.util.helpers;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

// the one place that decides if a drop is worth walking to. the rule is: items in water are not, unless we can grab
// them without getting wet. the decision only talks to Terrain so it can be tested without a world.
public final class ItemPickupRules {

    // vanilla grabs items inside the player box grown by 1 horizontally and 0.5 vertically. player is 0.6 wide and
    // 1.8 tall, items are 0.25 across. these are those numbers added up, plus a hair so edge cases favour grabbing
    private static final double REACH_XZ = 1.0 + 0.3 + 0.125;
    private static final double REACH_BELOW = 0.5 + 0.25;
    private static final double REACH_ABOVE = 0.5 + 1.8;

    // the pickup radius is about a block, this is slack for the tick between the packet and us moving on
    private static final double COLLECT_RANGE = 4;

    private ItemPickupRules() {
    }

    // the drop we were chasing is gone and we are standing right there = it went into the bag. gone while we are far away is
    // a despawn or somebody else's loot, that one is still the search's problem
    public static boolean collected(boolean gone, double distance) {
        return gone && distance <= COLLECT_RANGE;
    }

    // an empty slot, or a stack of the same thing with space left, takes at least some of a drop. counts[i] == 0 is an
    // empty slot. the tracker's own "can fit" wants the WHOLE stack to land under the max whatever flag you pass it,
    // so a 10 cobble drop next to a 60 cobble stack in a full bag never counted
    public static boolean hasRoom(int[] counts, boolean[] sameKind, int maxStack) {
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] == 0 || (sameKind[i] && counts[i] < maxStack)) return true;
        }
        return false;
    }

    public enum Room { FITS, MAKE_ROOM, GIVE_UP }

    // a drop that fits even partly is picked up as is (vanilla takes what fits and leaves the rest lying there). only a
    // drop with no room at all needs a slot freed, and with nothing to throw that never happens, so we ban the drop
    // instead of standing on it with EnsureFree doing nothing for ever
    public static Room room(boolean fitsEvenPartly, boolean canMakeRoom) {
        if (fitsEvenPartly) return Room.FITS;
        return canMakeRoom ? Room.MAKE_ROOM : Room.GIVE_UP;
    }

    // armor we would put on is worth a slot even when the bag is all things we keep: the death pile only gets one look, and
    // the helmet is the thing we came back for. the slot comes from the last resort pick
    public static boolean canMakeRoom(boolean canMakeRoom, boolean wearable, boolean lastResortThere) {
        return canMakeRoom || (wearable && lastResortThere);
    }

    public interface Terrain {
        // any water at all, source, flowing or waterlogged
        boolean water(int x, int y, int z);

        // something you can stand on top of and it is not a liquid
        boolean solid(int x, int y, int z);

        // nothing to collide with and no liquid, so a body fits here
        boolean open(int x, int y, int z);

        // burns whoever stands on it. off by default, only the lava exit cares and this interface has a lot of fakes
        default boolean magma(int x, int y, int z) {
            return false;
        }
    }

    // any lava at all, source or flowing
    public interface Lava {
        boolean at(int x, int y, int z);
    }

    // a drop in lava, on lava, or with lava on any side of it is not worth the walk. the body is 0.6 wide and the pickup
    // circle is a block out from it, so lava beside it is where we would be standing or falling into. two levels of
    // neighbours: the drop's own (a ledge with lava next to it) and the one below, because a drop lying on a lake shore
    // sits one up from the lava surface and that is the one block step off into the lake
    public static boolean nextToLava(double x, double y, double z, Lava lava) {
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        for (int dy = 0; dy >= -1; dy--) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (lava.at(bx + dx, by + dy, bz + dz)) return true;
                }
            }
        }
        return false;
    }

    // lava somewhere around the drop is only a reason to leave it when every spot we could grab it from is also next to
    // lava. a drop on a lake shore with solid dry ground behind it is a normal pickup, a drop on a one wide strip or an
    // island in the lake is not. the spots are the ones the water rule uses (feet, pickup reach), and they have to pass
    // nextToLava themselves
    public static boolean lavaBlocksPickup(double x, double y, double z, Terrain terrain, Lava lava) {
        if (!nextToLava(x, y, z, lava)) return false;
        return !canGrabFromDryLand(terrain, x, y, z, lava);
    }

    public static boolean lavaBlocksPickup(ItemEntity item) {
        Level level = item.level();
        return lavaBlocksPickup(item.getX(), item.getY(), item.getZ(), terrain(level), lava(level));
    }

    public static Lava lava(Level level) {
        return (x, y, z) -> level.getFluidState(new BlockPos(x, y, z)).is(FluidTags.LAVA);
    }

    public static Terrain terrain(Level level) {
        return new Terrain() {
            @Override
            public boolean water(int x, int y, int z) {
                return level.getFluidState(new BlockPos(x, y, z)).is(FluidTags.WATER);
            }

            @Override
            public boolean solid(int x, int y, int z) {
                BlockPos pos = new BlockPos(x, y, z);
                BlockState state = level.getBlockState(pos);
                return state.getFluidState().isEmpty() && state.isFaceSturdy(level, pos, Direction.UP);
            }

            @Override
            public boolean magma(int x, int y, int z) {
                return level.getBlockState(new BlockPos(x, y, z)).is(Blocks.MAGMA_BLOCK);
            }

            @Override
            public boolean open(int x, int y, int z) {
                BlockPos pos = new BlockPos(x, y, z);
                BlockState state = level.getBlockState(pos);
                return state.getFluidState().isEmpty() && state.getCollisionShape(level, pos).isEmpty();
            }
        };
    }

    // what a swim is worth: the eyes are what the stronghold hunt runs on (thrown over open ocean they land well off a bridge,
    // where no dry spot is within reach), and the rest is a death pile that sank or a kit nobody remakes in a minute. the pickup
    // water watchdog still drops any of these that we are not getting closer to
    private static final Set<String> SWIM_FOR = Set.of(
            "ender_eye", "ender_pearl", "blaze_rod", "diamond_pickaxe", "iron_sword", "diamond_sword", "shield", "water_bucket",
            "iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots",
            "diamond_helmet", "diamond_chestplate", "diamond_leggings", "diamond_boots");

    public static boolean worthSwimming(String itemPath) {
        return SWIM_FOR.contains(itemPath);
    }

    public static boolean worthSwimming(ItemEntity drop) {
        return worthSwimming(BuiltInRegistries.ITEM.getKey(drop.getItem().getItem()).getPath());
    }

    public static boolean isPickupSafe(ItemEntity item) {
        BlockPos at = item.blockPosition();
        Terrain terrain = terrain(item.level());
        // isInWater is the entity's own idea, the fluid check covers the tick where the two disagree
        boolean inWater = item.isInWater() || terrain.water(at.getX(), at.getY(), at.getZ());
        return isPickupSafe(item.getX(), item.getY(), item.getZ(), inWater, terrain);
    }

    public static boolean isPickupSafe(double x, double y, double z, boolean inWater, Terrain terrain) {
        if (!inWater) {
            return true;
        }
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        return isWadeable(terrain, bx, by, bz) || canGrabFromDryLand(terrain, x, y, z, null);
    }

    // a puddle one block deep over a floor. you walk through it, no swimming involved. swamps and rain are full of these
    private static boolean isWadeable(Terrain t, int bx, int by, int bz) {
        return t.water(bx, by, bz) && t.solid(bx, by - 1, bz) && t.open(bx, by + 1, bz);
    }

    // is there a spot we can stand on, dry, with the item inside pickup range of it. a lava lookup makes the spot count
    // only when it is not next to lava itself (null = the water question, lava does not matter)
    private static boolean canGrabFromDryLand(Terrain t, double x, double y, double z, Lava lava) {
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        for (int sx = bx - 1; sx <= bx + 1; sx++) {
            if (Math.abs(x - (sx + 0.5)) > REACH_XZ) continue;
            for (int sz = bz - 1; sz <= bz + 1; sz++) {
                if (Math.abs(z - (sz + 0.5)) > REACH_XZ) continue;
                for (int sy = by - 3; sy <= by + 1; sy++) {
                    // feet at sy, the item has to sit between "reach below the feet" and "reach above the head"
                    if (y < sy - REACH_BELOW || y > sy + REACH_ABOVE) continue;
                    if (standable(t, sx, sy, sz) && (lava == null || !nextToLava(sx + 0.5, sy + 0.5, sz + 0.5, lava))) return true;
                }
            }
        }
        return false;
    }

    private static boolean standable(Terrain t, int x, int y, int z) {
        return t.solid(x, y - 1, z) && t.open(x, y, z) && t.open(x, y + 1, z);
    }
}
