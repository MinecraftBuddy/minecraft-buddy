package adris.altoclef.tasks.speedrun.gamer.end;

import adris.altoclef.tasks.speedrun.gamer.FoodPlan;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.Respawn;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.EndConfig;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfigs;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

// what is still missing before we walk into the End. pure over facts + RunState.endDrops (gear we left lying in the End
// counts as owned, we go and pick it up there instead of crafting it again. beds don't, see mayEnter)
public final class EndGear {
    // best first, per slot: helmet, chest, legs, boots. iron is the floor, anything worse is not worth a swap
    private static final Item[][] ARMOR_SLOTS = {
            {Items.NETHERITE_HELMET, Items.DIAMOND_HELMET, Items.IRON_HELMET},
            {Items.NETHERITE_CHESTPLATE, Items.DIAMOND_CHESTPLATE, Items.IRON_CHESTPLATE},
            {Items.NETHERITE_LEGGINGS, Items.DIAMOND_LEGGINGS, Items.IRON_LEGGINGS},
            {Items.NETHERITE_BOOTS, Items.DIAMOND_BOOTS, Items.IRON_BOOTS}
    };
    // iron is the floor here too. axes count, a sword is what the kit makes and what WeaponPick holds first
    private static final Item[] WEAPONS = {Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD,
            Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE};
    private static final Item[] PICKAXES = {Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE};

    private EndGear() {
    }

    // beds = how many short of cfg.beds, buildBlocks = how many to collect (target, not the gap) or 0 when fine,
    // armorToWear = pieces we hold that are better than what is worn on their slot (or the slot is bare)
    public record Gap(int beds, boolean weapon, boolean waterBucket, boolean pickaxe, int buildBlocks, boolean food,
                      List<Item> armorToWear) {
        public boolean none() {
            return beds == 0 && !weapon && !waterBucket && !pickaxe && buildBlocks == 0 && !food && armorToWear.isEmpty();
        }
    }

    // which gap the prep is working on, in the order the phase goes through them (beds, then gearStep's order). "none" = all there
    public static String gate(Gap gap) {
        return gate(gap, null);
    }

    // armorFloor = the piece armorFloorPiece wants made (null = none, or its clock ran out). it sits between the pickaxe and
    // the blocks, the same place gearStep runs it, and a piece already in the bag goes on before the next one gets made
    public static String gate(Gap gap, Item armorFloor) {
        if (gap.beds() > 0) return "beds";
        if (gap.weapon()) return "weapon";
        if (gap.waterBucket()) return "water_bucket";
        if (gap.pickaxe()) return "pickaxe";
        if (armorFloor != null) return gap.armorToWear().isEmpty() ? "armor_floor" : "armor";
        if (gap.buildBlocks() > 0) return "blocks";
        if (!gap.armorToWear().isEmpty()) return "armor";
        if (gap.food()) return "food";
        return "none";
    }

    // the rule the whole prep hangs off: the fight's beds, in the bag, or no End. beds in the death pile don't count (the pile may be
    // gone, and we'd still have to survive getting to it), no clock, attempt or death moves this number
    public static boolean mayEnter(int bagBeds, EndConfig cfg) {
        return bagBeds >= cfg.beds;
    }

    // the spawn bed goes down once the bag covers it too, or right away when we are by the portal anyway (a death on a long search
    // should respawn here, and the gate makes sure it gets replaced). from across the map it waits, that's a long walk back for one
    // bed with the search still to finish. nothing in the bag = nothing to place
    public static boolean spawnNow(int bagBeds, int lacking, double blocksFromPortal, double farBlocks) {
        return bagBeds >= 1 && (lacking <= 0 || blocksFromPortal <= farBlocks);
    }

    // RoomPhase's bed by the stronghold start doubles as the spawn bed, so the prep ahead of the dig buys one for it. it's a spare
    // the moment it's in the bag, and it stops the climb back up for one more bed after OPEN
    public static boolean roomBedOwed(RunState state, EndConfig cfg) {
        return cfg.placeSpawnNearPortal && !state.spawnBedGaveUp && Respawn.nothingNearStronghold(state);
    }

    // how far from the start the room bed's spare may stand. the fight's beds get any distance, this one is only an extra
    public static final int SPARE_BED_REACH = 100;

    // a spare is worth a short walk and nothing more: with the fight's beds in the bag and no wool to craft it from, it goes when
    // there's none close by or nowhere to put it. asked fresh every tick, a bed that turns up later still counts. not spawnBedGaveUp:
    // that one sticks for the run and would cost us the spawn by the portal too
    public static boolean spareBedWaived(boolean roomBedOwed, int bagBeds, int bedsRequired, int woolBeds, boolean allowedBedNear, boolean noFreeSlot) {
        return roomBedOwed && bagBeds >= bedsRequired && woolBeds == 0 && (!allowedBedNear || noFreeSlot);
    }

    public static boolean spareBedInReach(int atX, int atZ, int x, int z) {
        return Math.hypot(x - atX, z - atZ) <= SPARE_BED_REACH;
    }

    // below this a bed has no honest source: villages live up top. asked at pick time because a far chunk reads as air, so the copper
    // under a chamber bed can't be seen from here. not a DangerFilter ban, there's no placed list and it would ban our own
    private static final int LOWEST_BED_Y = 30;

    public static boolean bedTooLow(int y) {
        return y < LOWEST_BED_Y;
    }

    // what the portal and room beds may spend: only what's past the reserve
    public static int surplusBeds(int bagBeds, int reserve) {
        return Math.max(0, bagBeds - reserve);
    }

    // the beds a full bag may not throw while a run is on, 0 otherwise (the bag code asks this from far away, same deal as Respawn's hooks)
    public static int reserveBeds() {
        RunState run = Respawn.boundRun();
        if (run == null) {
            return 0;
        }
        EndConfig cfg = GamerConfigs.get().end;
        return bedTarget(cfg, wantSpawnBed(run, cfg));
    }

    // (weapon is a sword or an axe, whichever the bot made). food is the same count every other gate goes by (FoodPlan: raw meat
    // nobody can cook is worth its raw value, a smoker's pending batch counts), against the End's own floor
    // the water bucket is in the gate again: the fall bucket (mlg) protects every fling and the void edge, and the dragon flings.
    // bounded though, see wantsWater. the old collector wandered the whole budget away
    public static Gap missing(GamerFacts facts, RunState state, EndConfig cfg, FoodPlan food) {
        return missing(facts, state, cfg, food, false);
    }

    // bucketGivenUp = this END_PREP attempt already spent its clock (or found no pond) on the water bucket
    public static Gap missing(GamerFacts facts, RunState state, EndConfig cfg, FoodPlan food, boolean bucketGivenUp) {
        int bedGap = bedShortfall(facts, cfg.beds, false);
        boolean weapon = !ownedOrDropped(facts, state, cfg, WEAPONS);
        boolean water = wantsWater(ownedOrDropped(facts, state, cfg, Items.WATER_BUCKET), facts.has(Items.BUCKET), bucketGivenUp);
        boolean pickaxe = !ownedOrDropped(facts, state, cfg, PICKAXES);
        int blocks = facts.buildBlocks() < cfg.minBuildBlocks ? cfg.buildBlocks : 0;
        return new Gap(Math.max(0, bedGap), weapon, water, pickaxe, blocks, food.shortOfEndFloor(), armorToWear(facts));
    }

    // 90 s of walking and scooping, then the attempt goes in without. counted in ticks the step actually ran
    public static final int BUCKET_BUDGET_TICKS = 1800;

    // the bucket gap: only when there is an empty bucket to fill (we do not craft one for this) and no water one held or
    // lying in the End, and only until the attempt gives up on it
    public static boolean wantsWater(boolean haveOrDropped, boolean emptyBucket, boolean givenUp) {
        return !haveOrDropped && emptyBucket && !givenUp;
    }

    public static boolean bucketTimedOut(int ticksSpent) {
        return ticksSpent >= BUCKET_BUDGET_TICKS;
    }

    // beds still to get: required (+1 for the spawn bed) minus what's in the bag. <= 0 means a spare (the spawn bed is what the
    // spare is for: placing it takes one out of the inventory, so ask this again afterwards)
    public static int bedShortfall(GamerFacts facts, int bedsRequired, boolean wantSpawnBed) {
        return bedsRequired + (wantSpawnBed ? 1 : 0) - facts.bedsInBag();
    }

    private static boolean ownedOrDropped(GamerFacts facts, RunState state, EndConfig cfg, Item... items) {
        return facts.count(items) + EndRules.dropped(state, facts.gameTime(), cfg, items) > 0;
    }

    // the slots are best first, so the first piece we hold is the best one and the first one worn is what is on. a piece goes on
    // when it ranks strictly above what is worn (a diamond helmet over the iron one, the iron one then sits in the bag below it and
    // asks for nothing), or when the slot is bare. the same rule as KitPlanner.toEquip
    public static List<Item> armorToWear(GamerFacts facts) {
        List<Item> wear = new ArrayList<>();
        for (Item[] slot : ARMOR_SLOTS) {
            int worn = rank(slot, facts::armorEquipped);
            int held = rank(slot, facts::has);
            if (held >= 0 && (worn < 0 || held < worn)) {
                wear.add(slot[held]);
            }
        }
        return wear;
    }

    // the floor goes chest first: it is the most points per ingot
    private static final Item[] ARMOR_FLOOR = {Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_HELMET, Items.IRON_BOOTS};
    private static final int[] ARMOR_FLOOR_SLOT = {1, 2, 0, 3};

    // an End death sends us back with whatever is still on us, often nothing, and the stronghold walk is full of mobs. below the
    // bed armor line (the same test as NetherRegress.armorShort, only the full iron plan has a number) the next iron piece gets
    // made here, no phase regress. a slot already iron or better, or with a piece of it in the bag (armorToWear puts that one
    // on), asks for nothing. null = the floor is fine
    public static Item armorFloorPiece(GamerFacts facts, GamerConfig cfg) {
        if (cfg.overworld.armorPlan != OverworldConfig.ArmorPlan.FULL_IRON || facts.armorPoints() >= cfg.end.bedMinArmor) {
            return null;
        }
        for (int i = 0; i < ARMOR_FLOOR.length; i++) {
            Item[] slot = ARMOR_SLOTS[ARMOR_FLOOR_SLOT[i]];
            if (rank(slot, facts::armorEquipped) < 0 && rank(slot, facts::has) < 0) {
                return ARMOR_FLOOR[i];
            }
        }
        return null;
    }

    // ticks the armor floor step actually ran, then the visit goes in with what it has
    public static boolean armorFloorTimedOut(int ticksSpent, EndConfig cfg) {
        return ticksSpent >= cfg.armorFloorSeconds * 20;
    }

    // index of the best piece of the slot the test says yes to, -1 when none
    private static int rank(Item[] slot, Predicate<Item> test) {
        for (int i = 0; i < slot.length; i++) {
            if (test.test(slot[i])) {
                return i;
            }
        }
        return -1;
    }

    private static boolean anyEquipped(GamerFacts facts, Item[] slot) {
        return rank(slot, facts::armorEquipped) >= 0;
    }

    // what to walk to first while we are in the End and something of ours is lying around (dropped = the item entity is
    // tracked). order is the old BM2 one: beds, tools, bucket, armor. null = nothing worth a detour
    public static Item nextPickup(GamerFacts facts, EndConfig cfg, Predicate<Item> dropped) {
        if (facts.count(ItemHelper.BED) < cfg.beds) {
            Item bed = firstDropped(ItemHelper.BED, dropped);
            if (bed != null) {
                return bed;
            }
        }
        Item tool = missingTool(facts, WEAPONS, dropped);
        if (tool == null) {
            tool = missingTool(facts, PICKAXES, dropped);
        }
        if (tool == null && !facts.has(Items.WATER_BUCKET) && dropped.test(Items.WATER_BUCKET)) {
            tool = Items.WATER_BUCKET;
        }
        return tool != null ? tool : droppedArmor(facts, dropped);
    }

    private static Item missingTool(GamerFacts facts, Item[] tiers, Predicate<Item> dropped) {
        return facts.count(tiers) > 0 ? null : firstDropped(tiers, dropped);
    }

    private static Item droppedArmor(GamerFacts facts, Predicate<Item> dropped) {
        for (Item[] slot : ARMOR_SLOTS) {
            if (!anyEquipped(facts, slot) && facts.count(slot) == 0) {
                Item piece = firstDropped(slot, dropped);
                if (piece != null) {
                    return piece;
                }
            }
        }
        return null;
    }

    private static Item firstDropped(Item[] items, Predicate<Item> dropped) {
        for (Item item : items) {
            if (dropped.test(item)) {
                return item;
            }
        }
        return null;
    }

    // the walk in and the end of the bridge are where this helps, the pillar fight itself doesn't ask
    public static boolean armorLow(GamerFacts facts, EndConfig cfg) {
        return facts.armorPoints() < cfg.bedMinArmor;
    }

    // the spawn bed gets spawnBedBudgetSeconds from the step it starts at, not from the phase start (the bed errand ahead of it
    // can eat the whole budget). a step that never started has no clock, the bed errand giving up is what ends the wait for it
    public static boolean spawnClockExpired(double secondsInPhase, double spawnStartedAt, double budget) {
        return spawnStartedAt >= 0 && secondsInPhase - spawnStartedAt > budget;
    }

    // food short and the errand is far from the portal: eat first, the trip back is where the food goes
    public static boolean foodFirst(boolean foodShort, double errandBlocksFromPortal, double farBlocks) {
        return foodShort && errandBlocksFromPortal > farBlocks;
    }

    // the walk back to the portal and the spawn bed step are the way home: food never replaces them
    public static boolean foodFirst(boolean foodShort, double errandBlocksFromPortal, double farBlocks, boolean wayHome) {
        return !wayHome && foodFirst(foodShort, errandBlocksFromPortal, farBlocks);
    }

    // the prep's soft top-up: only with room to carry it, and not in front of an empty field (FoodDry ends it in about 90 s, so a
    // field with nothing on it costs that and not the whole cap)
    public static boolean wantsTopUp(boolean foodShort, boolean bagFull, boolean dry) {
        return foodShort && !bagFull && !dry;
    }

    // the bed search, from what the bag and the world offer: wool in the bag is crafted, a known bed is mined wherever it is, and with
    // neither we go find sheep. there is no fourth answer on purpose
    public enum BedPlan {
        CRAFT, MINE, WOOL
    }

    public static BedPlan bedPlan(int woolBeds, boolean bedKnown) {
        if (woolBeds > 0) {
            return BedPlan.CRAFT;
        }
        return bedKnown ? BedPlan.MINE : BedPlan.WOOL;
    }

    private static final long SHORT_LOG_TICKS = 5 * 60 * 20;

    // a search with no give up gets a line every 5 minutes, so a hopeless world is at least loud about it. last < 0 = never said
    public static boolean shortLogDue(long lastSaid, long now) {
        return lastSaid < 0 || now - lastSaid >= SHORT_LOG_TICKS;
    }

    public static String shortLine(int bedsShort, long minutes, int x, int y, int z) {
        return "endprep: short by " + bedsShort + " beds after " + minutes + " min, still looking, at " + x + " " + y + " " + z;
    }

    // the shared target: the fight's beds, plus one while the spawn bed still has to go down
    public static int bedTarget(EndConfig cfg, boolean wantSpawnBed) {
        return cfg.beds + (wantSpawnBed ? 1 : 0);
    }

    public static boolean wantSpawnBed(RunState state, EndConfig cfg) {
        // (a respawn bed the game confirmed near the portal, or one at the stronghold we just walked through, is spawn enough)
        return cfg.placeSpawnNearPortal && !Respawn.coversPortal(state) && !state.spawnBedGaveUp;
    }
}
