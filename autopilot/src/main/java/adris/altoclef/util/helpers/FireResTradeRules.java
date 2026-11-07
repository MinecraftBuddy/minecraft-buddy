package adris.altoclef.util.helpers;

import java.util.Locale;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

// the buddy's fork, Tuning fr_trade, off by default, steering 89: the first fire resistance bartered from piglins before the
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
    // spawner. blazeNear: a blaze in drinking range
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

    // a piglin in sight to trade with, with a margin: one is taken up within enter, and kept while within keep, so the trade
    // does not flick between the walk and the piglin at the edge
    public static final double ENTER_RANGE = 40;
    public static final double KEEP_RANGE = 56;

    public static boolean piglinNear(boolean wasNear, double distance) {
        return distance <= (wasNear ? KEEP_RANGE : ENTER_RANGE);
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
