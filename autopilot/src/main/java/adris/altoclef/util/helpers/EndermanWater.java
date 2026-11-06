package adris.altoclef.util.helpers;

// an angry enderman is not run from (it teleports and chases, running is just free hits for it), it is fought to the end. the
// one thing that beats it at the bottom of the health bar is water: endermen are hurt by it and flee from it, in the end and
// the overworld alike. pure, the chain hands in what it saw
public final class EndermanWater {
    // at or under this we stop trading blows and put water at our feet, if there is a bucket of it
    public static final float FLOOR_HP = 6;
    // close enough to be the one hitting us next
    public static final double NEAR = 8;

    private EndermanWater() {
    }

    // nearestAngry: distance to the closest enderman that is angry at us, infinity for none. the nether burns the water off
    public static boolean dunkNow(float health, double nearestAngry, boolean hasWaterBucket, boolean waterEvaporates, boolean inWater) {
        return health <= FLOOR_HP && nearestAngry <= NEAR && hasWaterBucket && !waterEvaporates && !inWater;
    }

    // the dunk from the fire code leaves the water for the bucket to pick up the moment we stop burning. with an angry enderman
    // still about that would pull our own wall down
    public static boolean keepWater(double nearestAngry) {
        return nearestAngry <= NEAR;
    }
}
