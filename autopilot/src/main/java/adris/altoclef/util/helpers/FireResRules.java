package adris.altoclef.util.helpers;

// when to drink a potion of fire resistance, as answers. no world in here: the food chain reads the bag, the effect and the
// blazes and asks. the effect takes every fire damage there is (LivingEntity.hurtServer refuses IS_FIRE with it on): the
// burning, a blaze's or a ghast's fireball, lava. fire was a third of all the damage taken in the nether in thirty attempts,
// a blaze's fireballs another sixth
public final class FireResRules {

    // a blaze this close counts as a fight coming: the drink takes 32 ticks, and the first fireball sets us burning for five
    // seconds, so it goes down before the fight rather than in it
    public static final double BLAZE_RANGE = 16;
    // a running effect with this little left is drunk again while the fire is still about
    public static final int REDRINK_TICKS = 200;

    private FireResRules() {
    }

    // effectTicks: what is left of a fire resistance effect on us, 0 for none. blazeDistance: the nearest blaze, infinite for none
    public static boolean wantsToDrink(boolean hasPotion, int effectTicks, boolean onFire, double blazeDistance) {
        if (!hasPotion || effectTicks > REDRINK_TICKS) return false;
        return onFire || blazeDistance <= BLAZE_RANGE;
    }

    // a stop asked of the food chain (the lava escape asks one for as long as it runs) holds every bite, but not a wanted fire
    // resistance drink in lava: that drink is a way out too. the MLG and falling checks are not this rule's and still hold it
    public static boolean stopHolds(boolean stopAsked, boolean inLava, boolean wantsDrink) {
        return stopAsked && !(inLava && wantsDrink);
    }
}
