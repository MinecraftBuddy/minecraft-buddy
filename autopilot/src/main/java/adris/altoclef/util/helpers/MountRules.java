package adris.altoclef.util.helpers;

// (the buddy's fork, Tuning mounted_no_run, a fix of 14 Nov 2026, on) a melee mob riding a mount, the rules with no minecraft in
// them: CombatBrain says which foes ride a living mount (a zombie on a zombie horse, a chicken jockey), CombatCommit turns the runs
// from one into a fight or the dug-in cover, SkelCover.Hold keeps the cover while one is about. test heel, box 1, a night start
// on frozen-river snow with an empty hand: a spider's hit began a run, a zombie on a zombie horse speared the buddy from 3 blocks
// off four times on the 93 blocks uphill (20 to 12, 13.3 to 9.5, 12 to 7) and twice more after it, dead; a mount outruns the buddy,
// so a run on foot only gives it free hits ('speared by Zombie' 15 times in the records since 13 Nov)
public final class MountRules {
    private MountRules() {
    }

    // a mounted melee mob this close counts whether it hit us or not: a horse covers it in a second and a half
    public static final double NEAR = CombatRules.LOW_HP_RANGE;
    // one that hit us lately counts out to this (the foe list's range)
    public static final double RANGE = CombatCommit.FOE_RANGE;
    // a sword or an axe and this much hp or more: the fight on it (a spear's charge took 8, a jab 4 to 5)
    public static final float FIGHT_HP = 14;

    public static boolean on() {
        return Tuning.get("mounted_no_run", 0) > 0;
    }

    // a mounted melee mob this far off, that hit us lately or not, is one the runs are about
    public static boolean about(double distance, boolean hitUs) {
        return distance <= NEAR || (hitUs && distance <= RANGE);
    }

    // the fight on it, not the cover: a sword or an axe at hand and the hp for its hits
    public static boolean fight(boolean blade, float hp) {
        return blade && hp >= FIGHT_HP;
    }
}
