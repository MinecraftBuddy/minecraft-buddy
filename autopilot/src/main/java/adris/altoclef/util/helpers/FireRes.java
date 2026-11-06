package adris.altoclef.util.helpers;

import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;

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
}
