package adris.altoclef.util.helpers;

import adris.altoclef.Debug;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

// the buddy's fork: whether the decisions take fire resistance into account. with ignore_fire_resistance they act as without
// it (the douse, the run when burning, the lava escape, the gapple), so a drill with the effect given by the rig measures the
// same tactic with fire's damage taken away (steering 37: with the effect nothing doused the fires in the pillbox, Baritone
// walks into no fire, and the buddy stood locked out of its box for twelve game minutes, realfort-a-fireproof run 2)
public final class FireRes {
    private FireRes() {
    }

    public static boolean has(LivingEntity e) {
        return e.hasEffect(MobEffects.FIRE_RESISTANCE) && Tuning.get("ignore_fire_resistance", 0) <= 0;
    }

    // in lava under the potion the lava escape waits till this many ticks are left of it: deep in a shaft dug down past the lava
    // sea's level (the walk to the spawner, realfort-ab-fpreach-1 run 8 and realfort-ab-fpcamp-1 run 2) the escape never started
    // while the potion held, and with it gone the lava killed the buddy in two seconds
    public static final int LAVA_MARGIN_TICKS = 600;

    public static boolean endingSoon(LivingEntity e) {
        var fx = e.getEffect(MobEffects.FIRE_RESISTANCE);
        return fx != null && !fx.isInfiniteDuration() && fx.getDuration() <= LAVA_MARGIN_TICKS;
    }

    // a drink asked for by a task, the last game tick it was (the brew box drinks its potion inside before it opens, with no
    // blaze near or fire on the buddy, BrewBoxTask): the food chain drinks as if on fire while it is fresh
    static final long ASK_FRESH = 5;
    private static volatile long askedAt = Long.MIN_VALUE / 2;

    public static void ask(long now) {
        askedAt = now;
    }

    public static boolean asked(long now) {
        return now >= askedAt && now - askedAt <= ASK_FRESH;
    }

    // Tuning fr_trade_hold, off by default: the traded potion kept for the blazes (FireResTradeRules.held). FireResTrade says
    // every tick it runs that it ended with its potion, and by which spawner; the splash throw and the food chain's drink ask
    private static volatile long tradedAt = Long.MIN_VALUE / 2;
    private static volatile BlockPos tradedSpawner;
    private static volatile long heldAt = Long.MIN_VALUE / 2;

    public static void traded(long now, BlockPos spawner) {
        tradedAt = now;
        tradedSpawner = spawner;
    }

    // whether a fire resistance potion that would go down now (FireResRules.wantsToDrink said yes) waits for the blazes, with
    // a line once per fire episode. always false with the switch off. blaze: the nearest one, infinite for none
    public static boolean holdsTraded(LivingEntity e, double blaze, boolean asked, long now) {
        if (Tuning.get("fr_trade_hold", 0) <= 0) return false;
        if (!FireResTradeRules.tradedFresh(tradedAt, now)) return false;
        BlockPos spawner = tradedSpawner;
        boolean atFortress = spawner != null && e.position().distanceTo(Vec3.atCenterOf(spawner)) <= FireResTradeRules.FORTRESS_NEAR;
        boolean inLava = e.isInLava() || e.isEyeInFluid(FluidTags.LAVA);
        double hp = Tuning.get("fr_trade_hold_hp", FireResTradeRules.HOLD_HP);
        if (!FireResTradeRules.held(true, blaze <= FireResRules.BLAZE_RANGE, atFortress, asked, inLava, e.isOnFire(), e.getHealth(), hp)) {
            return false;
        }
        if (FireResTradeRules.newEpisode(heldAt, now)) {
            Debug.logInternal(String.format(Locale.ROOT, "strategy trade: the potion held for the blazes (on fire, %.1f hp, nearest blaze %s)",
                    e.getHealth(), FireResTradeRules.distance(blaze)));
        }
        heldAt = now;
        return true;
    }
}
