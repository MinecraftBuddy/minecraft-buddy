package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Why;
import baritone.api.utils.Dimension;
import java.util.Locale;

// the one `combat:` line a transition gets, as text, so every event can be checked to say what happened and where. the
// dimension goes in square brackets right after the prefix, everything after it reads the same in all three
public final class CombatLog {

    private CombatLog() {
    }

    // the run and chase lines end in [WHY] (the enum name), because "hp 12" alone doesn't say which rule fired
    // null for no transition. target is who the fight is about (the new one for a chain), fighting the name remembered from the
    // fight that just ended (the mob is already gone from the world by then), crowd the "2 zombies, 1 skeleton" list
    public static String line(Dimension dimension, Event event, Why why, boolean cornered, String target, String fighting,
                              String crowd, int hp, int blocks) {
        return line(dimension, event, why, cornered, false, target, fighting, crowd, hp, blocks);
    }

    // noRoom: the corner fight came from a run that went nowhere with the foe in reach, not from the three second window
    public static String line(Dimension dimension, Event event, Why why, boolean cornered, boolean noRoom, String target, String fighting,
                              String crowd, int hp, int blocks) {
        return line(dimension, event, why, cornered, noRoom, null, target, fighting, crowd, hp, blocks);
    }

    // corner: which corner it is (CombatCommit.cornerKind), on the end in [] like the run lines' why. null says nothing
    public static String line(Dimension dimension, Event event, Why why, boolean cornered, boolean noRoom, String corner, String target, String fighting,
                              String crowd, int hp, int blocks) {
        String kind = corner == null ? "" : " [" + corner + "]";
        String tag = "combat: [" + dimension.name().toLowerCase(Locale.ROOT) + "] ";
        return switch (event) {
            case NONE -> null;
            case FIGHT_START -> tag + "FIGHT " + target + " (" + fightWhy(why) + ", hp " + hp + ")";
            case FIGHT_NEXT -> cornered ? tag + (why == Why.CHASED ? "still chased, next " : "cornered, next ") + target + kind
                    : tag + "target dead, next " + target + " (hit us, hp " + hp + ")";
            case RUN_START -> why == Why.LOBBER ? tag + "RUN from witch (can't reach it) [" + why + "]"
                    : tag + "RUN from " + crowd + " (" + runWhy(why, hp) + ") [" + why + "]";
            case FIGHT_TO_RUN -> tag + "FIGHT -> RUN from " + crowd + " (" + runWhy(why, hp) + ") [" + why + "]";
            case RUN_TO_FIGHT -> tag + (noRoom ? "cornered, no room, fighting " : "cornered, fighting ") + target + kind;
            case FIGHT_DEAD -> tag + "fight over, " + fighting + " dead";
            case FIGHT_LOST -> tag + "fight over, lost track of " + fighting;
            case FIGHT_CLEARED -> tag + "fight over, " + fighting + " is out of the way";
            case FIGHT_STALLED -> tag + "fight over, can't get to " + fighting + ", ignoring it until it hits us again";
            case RUN_CLEAR -> tag + "run over, " + blocks + " blocks, clear";
            case RUN_CAP -> tag + "run over, " + CombatCommit.RUN_CAP / 20 + " s cap, " + blocks + " blocks";
            case RUN_STUCK -> tag + "run over, stuck " + blocks + " blocks from where it started";
            case RUN_CHASED_FIGHT -> tag + "chased too long, turning to fight " + target + " [" + why + "]";
            // (the why, the height and the chasers are in the perch note that goes out right after)
            case PERCH_START -> tag + "PERCH, stacking up out of reach (hp " + hp + ")";
            case PERCH_END -> tag + "perch over";
        };
    }

    // the lit creeper preempt (CreeperStep), not a transition of the machine, so it gets its own line
    public static String creeperStep(Dimension dimension, double distance) {
        return "combat: [" + dimension.name().toLowerCase(Locale.ROOT) + "] lit creeper at " + Math.round(distance)
                + " blocks, stepping out of the blast";
    }

    // what the step does before baritone has a path: sprints (degrees off straight away, 0 = straight) or has nowhere safe to
    public static String creeperSprint(Dimension dimension, int degrees) {
        return "combat: [" + dimension.name().toLowerCase(Locale.ROOT) + "] creeper step: sprinting "
                + (degrees == 0 ? "straight away" : Math.abs(degrees) + " degrees off straight away");
    }

    public static String creeperNoHeading(Dimension dimension) {
        return "combat: [" + dimension.name().toLowerCase(Locale.ROOT) + "] creeper step: no safe heading, waiting on the path";
    }

    // the goal going to baritone, and it being dropped before we got there (so it gets asked again)
    public static String creeperPath(Dimension dimension, boolean failed) {
        return "combat: [" + dimension.name().toLowerCase(Locale.ROOT) + "] creeper step: "
                + (failed ? "no path running, asking again" : "path asked for");
    }

    // the food chain's stance moved. reason is CombatCommit.stanceWhy, so a hurt bot that is not eating says why not
    public static String stance(Dimension dimension, CombatRules.Stance from, CombatRules.Stance to, String reason, int hp) {
        return "combat: [" + dimension.name().toLowerCase(Locale.ROOT) + "] stance " + from + " -> " + to + " (" + reason + ", hp " + hp + ")";
    }

    // hp went down. amount in hearts-and-halves, who is the attacker's name or null, how is the damage type ("arrow",
    // "mob", "fall"), both null when the client does not know
    public static String hurt(Dimension dimension, float amount, String who, String how) {
        String tag = "combat: [" + dimension.name().toLowerCase(Locale.ROOT) + "] hurt " + String.format(Locale.ROOT, "%.1f", amount);
        if (who == null && how == null) return tag + " (source unknown)";
        if (who == null) return tag + " (" + how + ")";
        return tag + " by " + who + (how == null ? "" : " (" + how + ")");
    }

    // what a magma hit gets on the end of its hurt line. magma only spares a held shift, so this says if it was, and whose input
    // object said so
    public static String hotFloor(boolean shift, boolean onGround, double y, String input) {
        return String.format(Locale.ROOT, " shift=%b onGround=%b y=%.2f input=%s", shift, onGround, y, input);
    }

    private static String fightWhy(Why why) {
        return switch (why) {
            case IN_WAY -> "in our way";
            case PINNED -> "shooting us";
            case RIM -> "on a rim";
            default -> "hit us";
        };
    }

    public static String runWhy(Why why, int hp) {
        return switch (why) {
            case SHOT -> "hp " + hp + ", it is shooting";
            case HEAVY -> "something nasty close, hp " + hp;
            case CROWD -> "a crowd on us, hp " + hp;
            case UNARMED -> "it hit us and we have nothing to hit back with, hp " + hp;
            case REPEAT_CREEPER -> "a creeper that keeps coming back, hp " + hp;
            default -> "hp " + hp;
        };
    }
}
