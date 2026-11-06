package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.util.helpers.ItemHelper;
import baritone.api.utils.Dimension;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

// the pure half of "was that new player instance a death". a respawn swaps the LocalPlayer and so does a portal, so the swap
// alone says nothing. three things can say it did: the death packet (the stash), our own tick catching the player dying, or
// the old instance, which keeps its zero health after the swap. a dimension change is not a signal either way: a nether death
// respawns in the overworld, and a portal is a swap with all three quiet
public final class DeathRules {

    private DeathRules() {
    }

    // where the record of the death comes from, best first
    public enum Source {
        // the death packet: the earliest sample, taken before the body could slide or the damage source expire
        STASH,
        // our own tick saw the player dying and wrote it down
        TICK,
        // nobody saw it, the old instance is dead though
        OLD_INSTANCE,
        // a portal
        NONE
    }

    public static Source source(boolean tickSawDeath, boolean stashed, boolean oldInstanceDead) {
        if (stashed) {
            return Source.STASH;
        }
        if (tickSawDeath) {
            return Source.TICK;
        }
        return oldInstanceDead ? Source.OLD_INSTANCE : Source.NONE;
    }

    public static boolean seen(boolean tickSawDeath, boolean stashed, boolean oldInstanceDead) {
        return source(tickSawDeath, stashed, oldInstanceDead) != Source.NONE;
    }

    // ---- is the pile worth the walk

    // deaths this close together are the same spot: the same cave, the same ravine
    public static final double SAME_SPOT_BLOCKS = 48;
    // earlier deaths at one spot after which the next pile there is not worth a third
    public static final int KILLS_AT_A_SPOT = 2;
    // a bag with only a pick in it is worth a short walk and not a long one, the wood and cobble are minutes away anywhere
    public static final double NEAR_BLOCKS = 300;
    // an iron pick is three ingots, less than that is not a pile
    public static final int IRON_WORTH = 3;

    private static final Item[] IRON_OR_BETTER = {
            Items.IRON_PICKAXE, Items.IRON_AXE, Items.IRON_SWORD, Items.IRON_SHOVEL, Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE,
            Items.DIAMOND_SWORD, Items.DIAMOND_SHOVEL, Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS,
            Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS
    };
    private static final Item[] RUN_ITEMS = {
            Items.ENDER_EYE, Items.ENDER_PEARL, Items.BLAZE_ROD, Items.BLAZE_POWDER, Items.OBSIDIAN, Items.DIAMOND, Items.BUCKET,
            Items.WATER_BUCKET, Items.LAVA_BUCKET, Items.FLINT_AND_STEEL
    };
    private static final Item[] EYE_PARTS = {Items.BLAZE_ROD, Items.BLAZE_POWDER, Items.ENDER_PEARL, Items.ENDER_EYE};
    private static final Item[] PICKS ={Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE};

    // what the bag held the last time we saw it alive, which is what the pile is made of. it is read every tick and kept: the
    // body that shows up after the respawn has an empty bag, and the old one is not guaranteed to still have its slots
    // eyeParts: rods, powder, pearls or eyes, the only things a far nether pile is worth a second walk for (NetherTripRules.defersFor)
    public record Worth(int iron, int gear, boolean pick, int food, boolean run, boolean eyeParts) {

        public Worth(int iron, int gear, boolean pick, int food, boolean run) {
            this(iron, gear, pick, food, run, false);
        }

        // iron to smelt, iron tools or armor, something the run cannot remake in a minute, or enough food to skip a hunt
        public boolean rich(int foodLine) {
            return iron >= IRON_WORTH || gear > 0 || run || food >= foodLine;
        }

        public boolean worthIt(double blocks, int foodLine) {
            return rich(foodLine) || (pick && blocks <= NEAR_BLOCKS);
        }

        // what was in it, in a few words for the log
        public String words() {
            List<String> parts = new ArrayList<>();
            if (iron > 0) {
                parts.add(iron + " iron");
            }
            if (gear > 0) {
                parts.add(gear + " iron or better " + (gear == 1 ? "piece" : "pieces"));
            }
            if (run) {
                parts.add("run items");
            }
            if (pick) {
                parts.add("a pick");
            }
            if (food > 0) {
                parts.add(food + " food");
            }
            return parts.isEmpty() ? "nothing much" : String.join(", ", parts);
        }
    }

    public static Worth worth(GamerFacts f) {
        int gear = 0;
        for (Item item : IRON_OR_BETTER) {
            if (f.count(item) > 0 || f.armorEquipped(item)) {
                gear++;
            }
        }
        return new Worth(f.count(Items.RAW_IRON, Items.IRON_INGOT), gear, f.count(PICKS) > 0, f.foodUnits(), runItems(f),
                f.count(EYE_PARTS) > 0);
    }

    // what the later phases are made of: a late bag with no iron tool in it is not an empty one
    private static boolean runItems(GamerFacts f) {
        return f.count(RUN_ITEMS) > 0 || f.count(ItemHelper.BED) > 0;
    }

    // earlier deaths (same dimension) within SAME_SPOT_BLOCKS of this one. the caller hands in the ones since this run was started
    public static int killedHere(List<RunState.Death> before, Dimension dimension, double x, double z) {
        int n = 0;
        for (RunState.Death d : before) {
            if (d.dimension == null || !d.dimension.equals(dimension.name())) {
                continue;
            }
            if (sameSpot(d.x, d.z, x, z)) {
                n++;
            }
        }
        return n;
    }

    // how far we walk for a pile worth it (eyes, a kit, iron, a week of food). the plain reach is for a bag that held little,
    // a pile like this is the run itself and worth a long walk. a reach of 0 stays 0
    public static final int RICH_REACH = 3000;

    public static int reachFor(Worth worth, int foodLine, int reach) {
        return reach > 0 && worth != null && worth.rich(foodLine) ? Math.max(reach, RICH_REACH) : reach;
    }

    // null = go back for it, else why not, in the words the log gets. a null `worth` is a bag we never got to see: go.
    // dying again at the pile we were going back for is the loop to stop, however rich the new one is, and so is a spot that
    // already killed us KILLS_AT_A_SPOT times. one old death there is fine: a pile recovered in between is not a loop
    public static String refuse(boolean sameDimension, double blocks, int reach, Worth worth, int foodLine, int killedBefore,
                                boolean diedAtThePile, boolean darkAndBare) {
        if (!sameDimension) {
            return "it is in another dimension";
        }
        if (diedAtThePile) {
            return "it killed us on the way back for the last pile";
        }
        if (killedBefore >= KILLS_AT_A_SPOT) {
            return "this spot has killed us " + (killedBefore + 1) + " times";
        }
        if (blocks > reach) {
            return Math.round(blocks) + " blocks is further than the " + reach + " we walk";
        }
        // a pick is not worth a night on the surface with no armor, a rich pile still is
        if (darkAndBare && worth != null && worth.pick() && !worth.rich(foodLine)) {
            return "it is night and we have no armor for a pick";
        }
        if (worth != null && !worth.worthIt(blocks, foodLine)) {
            return "the bag was nearly empty (" + worth.words() + ")";
        }
        return null;
    }

    // a rich pile still passes refuse() at night with no armor, but the walk is a bad bet in the dark with no armor: the pile waits for
    // the dawn (or armor) instead of being dropped, items do not age while nobody has the chunk loaded. a pile that is still inside
    // the ticking area is loaded, so it despawns while we wait: that one is fetched now
    public static boolean waitsForDay(boolean darkAndBare, Worth worth, int foodLine, boolean pileUnloaded) {
        return darkAndBare && pileUnloaded && worth != null && worth.rich(foodLine);
    }

    // square on purpose, the loaded area is a square of chunks
    public static boolean pileUnloaded(double dx, double dz, int tickingBlocks) {
        return Math.max(Math.abs(dx), Math.abs(dz)) > tickingBlocks;
    }

    public static String waiting(double blocks, Worth worth) {
        return "death: night and no armor, the pile (" + Math.round(blocks) + " blocks, " + worth.words() + ") waits for the day";
    }

    public static boolean sameSpot(double x1, double z1, double x2, double z2) {
        double dx = x1 - x2;
        double dz = z1 - z2;
        return dx * dx + dz * dz <= SAME_SPOT_BLOCKS * SAME_SPOT_BLOCKS;
    }

    public static String going(double blocks, Worth worth) {
        return "death: going back for the pile, " + Math.round(blocks) + " blocks" + (worth == null ? "" : ", " + worth.words());
    }

    public static String leaving(String why) {
        return "death: leaving the pile, " + why;
    }
}
