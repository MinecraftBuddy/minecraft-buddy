package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.KitPlanner;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import baritone.api.utils.Dimension;

import java.util.Optional;

// "we are in the overworld in a nether phase and the kit is gone": a death in the Nether respawns us at home with an
// empty bag, and walking back into the Nether like that is how the next death happens. pure, shared by NETHER and EYES
public final class NetherRegress {
    static Optional<GamerPhase> fromOverworld(GamerFacts f, GamerConfig cfg) {
        if (f.dimension() != Dimension.OVERWORLD) {
            return Optional.empty();
        }
        if (KitPlanner.have(f, "stone_pickaxe") < 1) {
            return Optional.of(GamerPhase.GATHER);
        }
        if (!KitPlanner.essentialsMet(f, cfg.overworld) || armorShort(f, cfg)) {
            return Optional.of(GamerPhase.IRON);
        }
        return Optional.empty();
    }

    // the same test without the dimension: the nether death trip asks it from inside the nether, where the rebuild cannot
    // start until we are home again
    public static boolean kitShort(GamerFacts f, GamerConfig cfg) {
        return KitPlanner.have(f, "stone_pickaxe") < 1 || !KitPlanner.essentialsMet(f, cfg.overworld) || armorShort(f, cfg);
    }

    // the portal phase starts in the overworld on purpose, so it cannot ask for the whole kit back (an iron phase that
    // skipped itself on a timeout leaves armor short and the fire charge is used up by the very portal it lit). the
    // pickaxe is the one thing a naked respawn can be told apart by, and an iron phase never ends without it
    static Optional<GamerPhase> pickaxeLost(GamerFacts f) {
        if (f.dimension() != Dimension.OVERWORLD) {
            return Optional.empty();
        }
        // held() counts a worn out pick too: wearing one down during the prep is not a death
        if (KitPlanner.have(f, "stone_pickaxe") + KitPlanner.held(f, "stone_pickaxe") + KitPlanner.held(f, "iron_pickaxe") < 1) {
            return Optional.of(GamerPhase.GATHER);
        }
        if (KitPlanner.have(f, "iron_pickaxe") + KitPlanner.held(f, "iron_pickaxe") < 1) {
            return Optional.of(GamerPhase.IRON);
        }
        return Optional.empty();
    }

    // only the full iron plan has a number to hold it to, a lighter plan chose its own armor
    private static boolean armorShort(GamerFacts f, GamerConfig cfg) {
        return cfg.overworld.armorPlan == OverworldConfig.ArmorPlan.FULL_IRON && f.armorPoints() < cfg.end.bedMinArmor;
    }

    private NetherRegress() {
    }
}
