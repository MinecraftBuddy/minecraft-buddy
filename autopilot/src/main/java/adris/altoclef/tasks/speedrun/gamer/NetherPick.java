package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

// pure: "the bag has no pick that works, can it make one right now?". the answer is a tier or none, and a tier is only ever
// chosen when the bag already holds EVERY input for it. the catalogue goes and collects what a bag is short of, and there is
// no cobblestone and no oak in the nether, so a craft that starts one short is a long walk to nowhere
public final class NetherPick {
    public enum Tier {
        NONE,
        IRON,
        STONE,
        WOODEN,
        // swords only, a pick from the bag never goes up to diamond
        DIAMOND
    }

    // one pipeline, two tools: a sword head is 2 materials and one stick, a pick head is 3 and two
    public enum Kind {
        PICKAXE,
        SWORD
    }

    // what the recipes are made of, stems and hyphae folded into logs and crimson/warped planks into planks (ItemHelper)
    public record Bag(int ingots, int cobble, int planks, int sticks, int logs, boolean table, int diamonds) {
        public Bag(int ingots, int cobble, int planks, int sticks, int logs, boolean table) {
            this(ingots, cobble, planks, sticks, logs, table, 0);
        }

        // planks if every log were cut up
        int woodPlanks() {
            return planks + PLANKS_PER_LOG * logs;
        }
    }

    static final int HEAD = 3;
    static final int SWORD_HEAD = 2;
    public static final int STICKS = 2;
    static final int TABLE_PLANKS = 4;
    public static final int PLANKS_PER_LOG = 4;
    // 2 planks make 4 sticks
    static final int STICKS_PER_CRAFT = 4;
    static final int PLANKS_PER_STICK_CRAFT = 2;

    private static final Item[] PICKS = {Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE,
            Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE};

    private static final Item[] SWORDS = {Items.WOODEN_SWORD, Items.STONE_SWORD, Items.IRON_SWORD, Items.DIAMOND_SWORD,
            Items.NETHERITE_SWORD};

    private NetherPick() {
    }

    public static boolean isSword(Item item) {
        for (Item s : SWORDS) {
            if (s == item) {
                return true;
            }
        }
        return false;
    }

    // a sword that is not about to break. count() still holds the worn ones (the planner sizes its targets off them), so the
    // worn ones come off here. golden is not in the list, nobody fights an enderman with 32 uses
    public static boolean hasUsableSword(GamerFacts f) {
        return f.count(SWORDS) - f.wornSwords() > 0;
    }

    public static Item swordItem(Tier t) {
        return switch (t) {
            case WOODEN -> Items.WOODEN_SWORD;
            case STONE -> Items.STONE_SWORD;
            case IRON -> Items.IRON_SWORD;
            case DIAMOND -> Items.DIAMOND_SWORD;
            case NONE -> null;
        };
    }

    static int head(Kind k) {
        return k == Kind.SWORD ? SWORD_HEAD : HEAD;
    }

    public static int sticksNeeded(Kind k) {
        return k == Kind.SWORD ? 1 : STICKS;
    }

    // a pick that is not worn out (GamerFacts.count leaves those out, they are 'spent')
    public static boolean hasUsablePick(GamerFacts f) {
        return f.count(PICKS) > 0;
    }

    public static Bag bagOf(GamerFacts f) {
        return new Bag(f.count(Items.IRON_INGOT), f.count(Items.COBBLESTONE), f.count(ItemHelper.PLANKS), f.count(Items.STICK),
                f.count(ItemHelper.LOG), f.has(Items.CRAFTING_TABLE) || f.stations().table().heldForPlanner(), f.count(Items.DIAMOND));
    }

    // iron, then stone, then wood. NONE = we hold a pick or the bag cannot make one
    public static Tier choose(GamerFacts f) {
        return hasUsablePick(f) ? Tier.NONE : choose(bagOf(f));
    }

    public static Tier choose(Bag b) {
        return choose(b, Kind.PICKAXE);
    }

    // sword: diamond, then iron, then cobble (no blackstone, no planks). pick: iron, stone, wood
    public static Tier choose(Bag b, Kind k) {
        return choose(b, k, t -> false);
    }

    // skip = tiers we must not make. a worn sword we still hold is the same item type as a fresh one, and the hand and the
    // craft target both go by type, so the fresh one would never be the one swung (or never be made at all)
    public static Tier choose(Bag b, Kind k, java.util.function.Predicate<Tier> skip) {
        Tier[] order = k == Kind.SWORD ? new Tier[]{Tier.DIAMOND, Tier.IRON, Tier.STONE}
                : new Tier[]{Tier.IRON, Tier.STONE, Tier.WOODEN};
        for (Tier t : order) {
            if (!skip.test(t) && covered(b, t, k)) {
                return t;
            }
        }
        return Tier.NONE;
    }

    // every input of the craft is in the bag: the head material, the two sticks (or the planks for them) and a table (or the
    // planks for one). this is the one check both the choice and the running task go by
    public static boolean covered(Bag b, Tier t) {
        return covered(b, t, Kind.PICKAXE);
    }

    public static boolean covered(Bag b, Tier t, Kind k) {
        int have = switch (t) {
            case IRON -> b.ingots();
            case STONE -> b.cobble();
            case WOODEN -> b.woodPlanks();
            case DIAMOND -> b.diamonds();
            case NONE -> 0;
        };
        // (wood only makes a pick, diamond only a sword)
        boolean exists = t != Tier.NONE && !(k == Kind.SWORD && t == Tier.WOODEN) && !(k == Kind.PICKAXE && t == Tier.DIAMOND);
        if (!exists || have < head(k)) {
            return false;
        }
        int wood = woodNeeded(b, t, k);
        return wood <= b.woodPlanks();
    }

    // planks the craft eats out of the wood pile: the sticks it lacks, the table if there is none, the head of a wooden pick
    public static int woodNeeded(Bag b, Tier t) {
        return woodNeeded(b, t, Kind.PICKAXE);
    }

    public static int woodNeeded(Bag b, Tier t, Kind k) {
        int sticksShort = Math.max(0, sticksNeeded(k) - b.sticks());
        int wood = PLANKS_PER_STICK_CRAFT * ((sticksShort + STICKS_PER_CRAFT - 1) / STICKS_PER_CRAFT);
        if (!b.table()) {
            wood += TABLE_PLANKS;
        }
        if (t == Tier.WOODEN && k == Kind.PICKAXE) {
            wood += HEAD;
        }
        return wood;
    }

    // the table leaves the bag the tick it goes down and the station book only knows it a moment later. in that gap the bag reads
    // "no table", the need grows by four planks and the craft would either give up with the table on the ground or make a second
    // one. so for a few seconds after the pick stage was running a missing table is taken as still there
    public static final double TABLE_GRACE_SECONDS = 3;

    public static Bag graceTable(Bag b, boolean wasPicking, double secondsSincePick) {
        if (b.table() || !wasPicking || secondsSincePick > TABLE_GRACE_SECONDS) {
            return b;
        }
        return new Bag(b.ingots(), b.cobble(), b.planks(), b.sticks(), b.logs(), true, b.diamonds());
    }

    public enum Stage {
        // an input is short (the bag changed under us): drop the whole thing, never go and fetch it
        ABORT,
        // cut the logs/stems we hold into the planks the rest of the craft eats
        PLANKS,
        STICKS,
        TABLE,
        PICK
    }

    // which craft is next. each one only turns what the bag holds into something the next one wants, and covered() stays true
    // through all of them (planks into sticks takes 2 off the need as well as off the pile), so ABORT only ever means a real shortage
    public static Stage stage(Bag b, Tier t) {
        return stage(b, t, Kind.PICKAXE);
    }

    public static Stage stage(Bag b, Tier t, Kind k) {
        if (!covered(b, t, k)) {
            return Stage.ABORT;
        }
        if (b.logs() > 0 && b.planks() < woodNeeded(b, t, k)) {
            return Stage.PLANKS;
        }
        if (b.sticks() < sticksNeeded(k)) {
            return Stage.STICKS;
        }
        if (!b.table()) {
            return Stage.TABLE;
        }
        return Stage.PICK;
    }

    public static String catalogueName(Tier t) {
        return catalogueName(t, Kind.PICKAXE);
    }

    public static String catalogueName(Tier t, Kind k) {
        return t.name().toLowerCase() + (k == Kind.SWORD ? "_sword" : "_pickaxe");
    }

    public static Item item(Tier t) {
        return switch (t) {
            case IRON -> Items.IRON_PICKAXE;
            case STONE -> Items.STONE_PICKAXE;
            case WOODEN -> Items.WOODEN_PICKAXE;
            case DIAMOND, NONE -> null;
        };
    }

    // the one-line report for "no pick and no way to make one", so a bad stay can be read off the log
    public static String shortLine(Bag b) {
        return "nether: no pickaxe and nothing to make one from (ingots " + b.ingots() + ", cobble " + b.cobble() + ", planks "
                + b.woodPlanks() + ", sticks " + b.sticks() + ", table " + (b.table() ? "yes" : "no") + ")";
    }

    public static String swordLine(Tier t) {
        return "nether: sword is nearly gone, crafting " + t.name().toLowerCase() + " from the bag";
    }

    public static String craftLine(Tier t) {
        return "nether: no pickaxe left, crafting " + t.name().toLowerCase() + " from the bag";
    }
}
