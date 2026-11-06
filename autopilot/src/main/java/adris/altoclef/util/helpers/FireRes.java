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
}
