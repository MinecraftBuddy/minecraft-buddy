package adris.altoclef.tasks.speedrun.gamer;

import net.minecraft.world.item.Items;

// pure: raw gold for the piglin helmet, dug in IRON so the nether is not entered bare. the same clock as DiamondHunt, its seconds
// live in RunState and the need is gone for the rest of the run once it gives up. the need only exists while a gold ore is already
// in the loaded chunks (GamerFacts.goldOreKnown): with none known the mining task would just wander, and that is the cost this skips
public final class GoldHunt {
    // the catalogue name of the dig
    public static final String NEED = "raw_gold";
    // how far from us (in blocks, per axis) an ore still counts as known
    public static final int RADIUS = 48;
    // the helmet's ingots, the target PiglinGold.gate crafts from
    public static final int TARGET = PiglinGold.HELMET_INGOTS;

    private GoldHunt() {
    }

    // nothing gold on us, none in the bag, and not the gold for a helmet either, and the dig has not been given up
    public static boolean owed(GamerFacts f) {
        return !f.goldHuntGaveUp() && !PiglinGold.worn(f) && !PiglinGold.holdsPiece(f) && PiglinGold.available(f) < TARGET;
    }

    // the kit need, null when there is none. raw gold in the bag counts towards the target already (available), so the need is what
    // we hold plus what is missing
    public static KitNeed need(GamerFacts f) {
        if (!owed(f) || !f.goldOreKnown()) {
            return null;
        }
        return new KitNeed(NEED, f.count(Items.RAW_GOLD) + TARGET - PiglinGold.available(f));
    }

    // the dig's clock, only on ticks the kit runner ran it (same shape as DiamondHunt.tick). out of time: no more gold this run
    public static DiamondHunt.Step tick(RunState s, double lastAge, boolean dug, double age, double minutes) {
        if (!dug || s.goldHuntGaveUp) {
            return new DiamondHunt.Step(-1, false);
        }
        s.goldHuntSeconds = DiamondHunt.add(s.goldHuntSeconds, lastAge, age);
        if (s.goldHuntSeconds >= minutes * 60) {
            s.goldHuntGaveUp = true;
            return new DiamondHunt.Step(-1, true);
        }
        return new DiamondHunt.Step(age, false);
    }

    // the need the kit runner is on is the gold dig
    public static boolean digging(KitNeed running) {
        return running != null && NEED.equals(running.catalogueName());
    }
}
