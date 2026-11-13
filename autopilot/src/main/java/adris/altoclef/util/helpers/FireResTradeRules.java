package adris.altoclef.util.helpers;

import java.util.Locale;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

// the buddy's fork, Tuning fr_trade, on by default since the route (steering 118; steering 89): the first fire resistance bartered from piglins before the
// fortress (FireResTrade), as answers. no world in here: the trade reads the bag, the piglins, the blazes and the clocks and asks
public final class FireResTradeRules {

    // a barter in 26.3 gives a fire resistance potion 16 times in 469 per ingot (the drinkable one and the splash one, 8 each):
    // about one in 29
    public static final int POTION_WEIGHT = 16;
    public static final int BARTER_WEIGHT = 469;
    // the defaults of Tuning fr_trade_search (no piglin to trade with for this long) and fr_trade_max (the whole trade), seconds
    public static final double SEARCH_S = 120;
    public static final double MAX_S = 600;
    // out of gold, the last ingot is still admired (119 ticks) and its loot thrown and picked up: this long for that, game ticks
    public static final long LAST_THROW_TICKS = 200;
    // the trade is for the first rods' fight: begun with fewer rods than this
    public static final int ROD_LIMIT = 2;

    private FireResTradeRules() {
    }

    // a fire resistance potion and how it goes down: a drinkable one is drunk (the food chain), a splash or a lingering one is
    // thrown at the feet. a water bottle and every other potion are NONE
    public enum Form {
        NONE,
        DRINK,
        THROW
    }

    public static Form form(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Form.NONE;
        boolean drink = stack.is(Items.POTION);
        boolean thrown = stack.is(Items.SPLASH_POTION) || stack.is(Items.LINGERING_POTION);
        if (!drink && !thrown) return Form.NONE;
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null) return Form.NONE;
        for (MobEffectInstance effect : contents.getAllEffects()) {
            if (effect.is(MobEffects.FIRE_RESISTANCE)) return drink ? Form.DRINK : Form.THROW;
        }
        return Form.NONE;
    }

    // why the trade does not begin, null when it may: gold to give, no fire resistance potion yet, and the rods still to get
    public static String whyNot(int gold, boolean potion, int rods) {
        if (potion) return "a fire resistance potion already in the bag";
        if (rods >= ROD_LIMIT) return rods + " rods already";
        if (gold <= 0) return "no gold";
        return null;
    }

    public enum Step {
        // give gold, wait on a piglin admiring it, or pick up what it threw
        TRADE,
        // no piglin in sight: look for one
        SEARCH,
        // a blaze is near: the fight has this tick, the clocks run on
        YIELD,
        // Tuning route_trade_known: a piglin known (seen free a moment ago) has a hoglin by it or by the buddy: stand and wait,
        // the mob defense takes a hoglin that comes at the buddy
        WAIT,
        DONE,
        GIVE_UP
    }

    public record Verdict(Step step, String reason) {
        static Verdict of(Step step) {
            return new Verdict(step, null);
        }
    }

    // potion: a fire resistance potion in the bag, any form. gold: ingots left. pending: a piglin near holds gold (an ingot being
    // admired) or a fire resistance potion lies on the ground near. goldWearable: a piece of gold armour is worn or in the bag
    // (without one the piglins attack). sinceBegin, sinceGoldOut (MAX_VALUE while gold is left), sincePiglin (since a piglin was
    // last in sight to trade with, or since the begin): game ticks. piglinNear: one in sight to give gold to. atFortress: by the
    // spawner. blazeNear: the trade yields to a blaze (yields: the fight would take one)
    public static Verdict decide(boolean potion, int gold, boolean pending, boolean goldWearable, long sinceBegin, long sinceGoldOut,
                                 long sincePiglin, boolean piglinNear, boolean atFortress, boolean blazeNear, long searchTicks,
                                 long maxTicks) {
        if (potion) return Verdict.of(Step.DONE);
        if (sinceBegin >= maxTicks) return new Verdict(Step.GIVE_UP, "the cap of " + seconds(maxTicks) + " s passed");
        if (!goldWearable) return new Verdict(Step.GIVE_UP, "no gold armour to wear, the piglins would attack");
        if (gold <= 0 && (!pending || sinceGoldOut >= LAST_THROW_TICKS)) return new Verdict(Step.GIVE_UP, "out of gold");
        if (blazeNear) return Verdict.of(Step.YIELD);
        if (pending || (gold > 0 && piglinNear)) return Verdict.of(Step.TRADE);
        if (sincePiglin >= searchTicks) return new Verdict(Step.GIVE_UP, "no piglin found in " + seconds(searchTicks) + " s");
        if (atFortress) return new Verdict(Step.GIVE_UP, "at the fortress with no piglin in sight");
        return Verdict.of(Step.SEARCH);
    }

    // Tuning route_trade, on by default (steering 120): the route's trade ends with a potion, with all the gold handed, or at a
    // dead end, never on a clock of the search alone and never at the fortress: phase 3 does not begin with gold left and no
    // potion. the dead end (seconds; Tuning route_trade_dead_s): with Tuning route_trade_known (on by default, route jar 4)
    // this long with no piglin known at all since the last ingot a piglin took (a piglin is known while it was seen free to
    // take gold within KEEP_RANGE in the last KNOWN_TICKS and not seen dead), searching wider all the while; without it, this
    // long with no ingot taken. at about 6 s an ingot by one piglin the 64 gold take some 400 s; ten game minutes with no
    // piglin known, out to rings some 200 blocks round, is a nether with no piglin to be had within the run's 90 minutes
    public static final double DEAD_S = 600;
    // route_trade_known's backstop (seconds; Tuning route_trade_stall_s): piglins known all the while, yet none took an ingot
    // for this long (each one left alone after REFUSED_CLICKS clicks, an unreachable one after its approach cap, one held by a
    // hoglin known only KNOWN_TICKS past its last free look: a stream of such piglins could hold the trade for ever).
    // twenty minutes leaves the rest of a 90 minute run to the fortress and the blazes
    public static final double STALL_S = 1200;

    // (Tuning trade_wastes_grace, seconds, a fix of 13 Nov 2026, on at 120): wastes that come into view on the search (walked
    // to, or the feet in them after the walk) give the search at least this long more before the dead end's bound. a basalt
    // deltas start of the ab15 set found its wastes at 9.5 min in two route-61 runs and the bound fired 32 s later; the run of
    // that start that passed had its potion at 9.8 min. noneKnownAtWastes: the dead end's clock when they came into view (-1
    // none since the last ingot)
    public static long deadWithWastes(long deadTicks, long noneKnownAtWastes, long graceTicks) {
        if (noneKnownAtWastes < 0 || graceTicks <= 0) return deadTicks;
        return Math.max(deadTicks, noneKnownAtWastes + graceTicks);
    }
    // a piglin seen free to take gold is known this long after (game ticks): the hoglin's wait, the walk round a wall, a
    // piglin gone behind a hill a moment
    public static final long KNOWN_TICKS = 90 * 20;

    // a piglin last seen free sinceFree game ticks ago (MAX_VALUE never; a clock that went back counts as never) is known
    public static boolean known(long sinceFree) {
        return sinceFree >= 0 && sinceFree <= KNOWN_TICKS;
    }

    // as decide, for the route: potion, gold, pending, goldWearable, sinceGoldOut and blazeNear as there; sinceHanded: game
    // ticks since the last ingot a piglin took (or since the begin); noneKnown: game ticks with no piglin known since then
    // (route_trade_known; ignored without it); piglinNear: one in sight to give gold to; hoglinWait: a piglin known within
    // KEEP_RANGE that a hoglin keeps us off just now (hoglinBars); knownOn: Tuning route_trade_known; deadTicks, stallTicks the
    // bounds
    public static Verdict decideRoute(boolean potion, int gold, boolean pending, boolean goldWearable, long sinceGoldOut,
                                      long sinceHanded, long noneKnown, boolean piglinNear, boolean hoglinWait, boolean blazeNear,
                                      boolean knownOn, long deadTicks, long stallTicks) {
        if (potion) return Verdict.of(Step.DONE);
        if (!goldWearable) return new Verdict(Step.GIVE_UP, "no gold armour to wear, the piglins would attack");
        if (gold <= 0 && (!pending || sinceGoldOut >= LAST_THROW_TICKS)) return new Verdict(Step.GIVE_UP, OUT_OF_GOLD);
        if (blazeNear) return Verdict.of(Step.YIELD);
        if (pending) return Verdict.of(Step.TRADE);
        if (!knownOn && sinceHanded >= deadTicks) return new Verdict(Step.GIVE_UP, "a dead end, no piglin took gold in " + seconds(deadTicks) + " s");
        if (knownOn && noneKnown >= deadTicks) return new Verdict(Step.GIVE_UP, "the bound, no piglin known in " + seconds(deadTicks) + " s of searching");
        if (knownOn && sinceHanded >= stallTicks) return new Verdict(Step.GIVE_UP, "the bound, no piglin took gold in " + seconds(stallTicks) + " s");
        if (gold > 0 && piglinNear) return Verdict.of(Step.TRADE);
        if (knownOn && gold > 0 && hoglinWait) return Verdict.of(Step.WAIT);
        return Verdict.of(Step.SEARCH);
    }

    // the reason of the give-up with all the gold handed
    public static final String OUT_OF_GOLD = "out of gold";

    // Tuning route_trade_hoglin (route jar 4, 9 Nov 2026), blocks: a piglin is passed over only while a hoglin is within this
    // of it or of the buddy. the trade's rule from TradeWithPiglinsTask, the nearest hoglin within 64 of the piglin, barred
    // nearly every piglin of a crimson forest, where they share the ground, and the wastes' edge by one: smoke 3 run 1 handed
    // 16 ingots, then searched ten minutes with piglin 876529 in sight 30 blocks off ("a hoglin within 64 of it") and gave
    // up with 48 gold. a hoglin notices a player within 16 blocks and charges at about 4 blocks a second, its toss can land
    // the buddy in lava: within 12 of the piglin the buddy, by it to click, is in its charge within some 3 s and mid-click;
    // further off the mob defense (which takes hoglins) has the time to turn on one that comes. 0: the old rule
    public static final double HOGLIN_NEAR = 12;
    public static final double HOGLIN_OLD = 64;

    // whether a hoglin keeps us off a piglin: toPiglin the nearest hoglin's distance to the piglin, toBuddy the nearest
    // hoglin's to the buddy (infinite for none); range Tuning route_trade_hoglin (0 or below: the old rule)
    public static boolean hoglinBars(double toPiglin, double toBuddy, double range) {
        if (range <= 0) return toPiglin < HOGLIN_OLD;
        return toPiglin <= range || toBuddy <= range;
    }

    // the route's wider search once the wastes walk is over: rings round where it began, RING_STEP blocks further out each,
    // eight points to a ring taken in turn round it; past RINGS rings it starts again at the first. point k of the search,
    // from the centre cx, cz, the first point at the angle from (radians)
    public static final double RING_STEP = 40;
    public static final int RING_POINTS = 8;
    public static final int RINGS = 8;
    // a point not reached in this long is passed over for the next (game ticks)
    public static final long POINT_TICKS = 60 * 20;

    public static int ring(int k) {
        return 1 + Math.floorMod(k, RING_POINTS * RINGS) / RING_POINTS;
    }

    // a point's time by its distance from where its walk begins (Tuning trade_search_out, 12 Nov 2026): 1.5 s a block, at
    // least POINT_TICKS, at most POINT_MAX_TICKS
    public static final double POINT_TICKS_A_BLOCK = 30;
    public static final long POINT_MAX_TICKS = 240 * 20;

    public static long pointTicks(double distance) {
        return Math.max(POINT_TICKS, Math.min(POINT_MAX_TICKS, Math.round(distance * POINT_TICKS_A_BLOCK)));
    }

    // point k's bearing on ring r (1 the first): the same direction further out
    public static Spot searchPointOnRing(int k, int r, double cx, double cz, double from) {
        double a = from + Math.floorMod(k, RING_POINTS) * (2 * Math.PI / RING_POINTS);
        return new Spot((int) Math.round(cx + RING_STEP * r * Math.cos(a)), (int) Math.round(cz + RING_STEP * r * Math.sin(a)));
    }

    public static Spot searchPoint(int k, double cx, double cz, double from) {
        double r = RING_STEP * ring(k);
        double a = from + Math.floorMod(k, RING_POINTS) * (2 * Math.PI / RING_POINTS);
        return new Spot((int) Math.round(cx + r * Math.cos(a)), (int) Math.round(cz + r * Math.sin(a)));
    }

    // (look 28, 8 Nov 2026: a blaze at the 16-block line flipped the trade into a yield and back each second for six minutes,
    // and the yield handed the tick to the camp walk, not to a fight: 15 gold never handed, the cap with no potion.) the trade
    // yields to a blaze only while the fight would take one: within BLAZE_RANGE, or BLAZE_KEEP once yielding; and a yield lasts
    // YIELD_HOLD_TICKS past the last tick the fight would take one, so a blaze at the edge of reach does not restart the walk
    // each tick
    public static final double BLAZE_KEEP = 24;
    public static final long YIELD_HOLD_TICKS = 40;

    public static double yieldLine(boolean yielding) {
        return yielding ? BLAZE_KEEP : FireResRules.BLAZE_RANGE;
    }

    // fightTakes: the fight would take a blaze within yieldLine this tick. sinceTaken: game ticks since it last would
    // (MAX_VALUE when never)
    public static boolean yields(boolean fightTakes, long sinceTaken) {
        return fightTakes || sinceTaken < YIELD_HOLD_TICKS;
    }

    // a piglin in sight to trade with, with a margin: one is taken up within enter, and kept while within keep, so the trade
    // does not flick between the walk and the piglin at the edge
    public static final double ENTER_RANGE = 40;
    public static final double KEEP_RANGE = 56;

    public static boolean piglinNear(boolean wasNear, double distance) {
        return distance <= (wasNear ? KEEP_RANGE : ENTER_RANGE);
    }

    // Tuning fr_trade_stay, on by default since 8 Nov 2026, steering 98 and 101: one piglin found keeps the gold. a piglin admires an ingot for
    // about 6 s and takes no other meanwhile; without the switch the buddy walks to the nearest other free one within
    // KEEP_RANGE after every ingot. with it another free piglin is taken only within this many blocks of the buddy (who stands
    // by the current one); otherwise the buddy waits by the current one and hands it the next ingot once it is free
    public static final double STAY_OTHER_RANGE = 8;

    // whose turn the next ingot is: today's search (the nearest free one within KEEP_RANGE), the current piglin (wait by it
    // while it admires, give when it is free), or the other free one close by
    public enum Pick {
        SEARCH,
        STAY,
        OTHER
    }

    // stayOn: Tuning fr_trade_stay. present: the current piglin is alive and tracked by the client (dead or out of sight is
    // gone); leftAlone: it refused gold REFUSED_CLICKS times; hoglinNear: a hoglin keeps us off it (GiveGoldTask.tradeable);
    // currentDistance: from the buddy; currentFree: it takes gold now; nearestOtherFree: the nearest other free piglin from
    // the buddy, infinite for none. off, or with the current one gone, today's search
    public static Pick pick(boolean stayOn, boolean present, boolean leftAlone, boolean hoglinNear, double currentDistance,
                            boolean currentFree, double nearestOtherFree) {
        if (!stayOn || !present || leftAlone || hoglinNear || currentDistance > KEEP_RANGE) return Pick.SEARCH;
        if (currentFree) return Pick.STAY;
        return nearestOtherFree <= STAY_OTHER_RANGE ? Pick.OTHER : Pick.STAY;
    }

    // steering 99 and 102: the trade's pickup takes ender pearls a piglin threw as well as the potion. with Tuning
    // fr_trade_pearls (off by default) always; without it only while the goal goes through the End and asks more pearls
    // (EyeMath.pearlsNeeded, 0 for a rods order) than the bag holds
    public static boolean takesPearls(boolean pearlsOn, int pearlsNeeded, int pearlsInBag) {
        return pearlsOn || pearlsNeeded > pearlsInBag;
    }

    // by the spawner, blocks: the trade is for before the fortress, and the hold below ends here
    public static final double FORTRESS_NEAR = 32;

    // Tuning fr_trade_hold, off by default: the traded potion kept for the blazes. on the way it went down whenever the buddy
    // caught fire with no blaze in sight (13 of 31 runs that got one; those reached two rods safe in 7 of 13, against 13 of 14
    // with the effect coming on at the blazes): a few seconds of burning cost a few hp, and the potion's 180 s were spent by
    // the fight. the default of Tuning fr_trade_hold_hp: on fire at this health or below, the potion goes down after all
    public static final double HOLD_HP = 8;
    // the trade says every tick it runs that it ended with its potion; the food chain also runs while the rods task is
    // interrupted (a mob defense's run or fight, a meal), so the say counts this long (game ticks)
    public static final long HOLD_FRESH = 1200;
    // a hold this long after the last one is a new fire episode, with its own line (game ticks)
    public static final long HOLD_EPISODE_GAP = 40;

    // whether a fire resistance drink (or throw) that would go down now waits for the blazes. traded: the trade ended with its
    // potion. it waits only on the way: no blaze in drinking range, not at the fortress, and no task asked for it (the brew
    // box). there it goes down in lava (feet or eyes), or on fire with health at or below holdHp; merely on fire it waits
    public static boolean held(boolean traded, boolean blazeNear, boolean atFortress, boolean asked, boolean inLava, boolean onFire,
                               double health, double holdHp) {
        if (!traded || blazeNear || atFortress || asked || inLava) return false;
        return !onFire || health > holdHp;
    }

    // the trade's say still counts: said at saidAt, now is the game tick (a new world's clock below it does not count)
    public static boolean tradedFresh(long saidAt, long now) {
        return now >= saidAt && now - saidAt <= HOLD_FRESH;
    }

    // a hold at now begins a new fire episode, the last hold having been at lastHeld
    public static boolean newEpisode(long lastHeld, long now) {
        return now < lastHeld || now - lastHeld > HOLD_EPISODE_GAP;
    }

    // Tuning fr_trade_biome, on by default since 8 Nov 2026: where the search for a piglin starts. by the mob seen first, a trade begun in nether
    // wastes missed a piglin 13 times in 69, in a crimson forest 22 in 25, a soul sand valley 13 in 22, basalt deltas 7 in 13;
    // so out of the wastes the search first walks to the wastes the client has loaded. a spot is a column, x and z in blocks
    public record Spot(int x, int z) {
        public double distanceTo(double fromX, double fromZ) {
            return Math.hypot(x - fromX, z - fromZ);
        }
    }

    // a candidate this near a known bastion block is never walked to (bastions hold piglin brutes)
    public static final double BASTION_KEEP_OFF = 64;

    // the blocks a stop at via adds to the straight way from the start to the spawner, whole blocks
    public static long extraOnTheWay(double startX, double startZ, Spot via, Spot spawner) {
        double way = via.distanceTo(startX, startZ) + Math.hypot(spawner.x() - via.x(), spawner.z() - via.z());
        return Math.round(way - spawner.distanceTo(startX, startZ));
    }

    // the wastes the search walks to: candidates are the middles of the loaded chunks with nether wastes at the buddy's height.
    // one within BASTION_KEEP_OFF of a known bastion block is skipped, and one by the spawner too (the trade gives up there).
    // with the spawner known (else null) the one with the fewest extra blocks on the way to it, the nearer of equals (the
    // buddy goes there after the trade anyway); without it the nearest. null when none is left
    public static Spot wastesTarget(java.util.List<Spot> candidates, double startX, double startZ, Spot spawner, java.util.List<Spot> bastions) {
        Spot best = null;
        long bestExtra = Long.MAX_VALUE;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Spot c : candidates) {
            if (nearAny(c, bastions, BASTION_KEEP_OFF)) continue;
            if (spawner != null && c.distanceTo(spawner.x(), spawner.z()) <= FORTRESS_NEAR) continue;
            long extra = spawner == null ? 0 : extraOnTheWay(startX, startZ, c, spawner);
            double distance = c.distanceTo(startX, startZ);
            if (extra < bestExtra || (extra == bestExtra && distance < bestDistance)) {
                best = c;
                bestExtra = extra;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static boolean nearAny(Spot c, java.util.List<Spot> others, double range) {
        for (Spot o : others) {
            if (c.distanceTo(o.x(), o.z()) <= range) return true;
        }
        return false;
    }

    // the nearest blaze as the trade's lines write it
    public static String distance(double d) {
        return Double.isInfinite(d) ? "none in sight" : String.format(Locale.ROOT, "%.0f blocks off", d);
    }

    // the chance a potion has come by the n-th ingot (the kit's size: 64 gives 89%, 96 gives 96%, 128 gives 99%)
    public static double chanceWithin(int ingots) {
        if (ingots <= 0) return 0;
        return 1 - Math.pow(1 - (double) POTION_WEIGHT / BARTER_WEIGHT, ingots);
    }

    public static String seconds(long ticks) {
        return String.format(Locale.ROOT, "%.0f", ticks / 20.0);
    }
}
