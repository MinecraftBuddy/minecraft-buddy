package adris.altoclef.util.helpers;

// stepping out of dragon breath from the survival chain, and when to quit fetching the water we dunked in. pure
public final class BreathDodge {

    public enum Move {
        NONE, RUN, DROP, DIG
    }

    // the End has no water to refill from, so a bucket pickup only happens while it is a free action
    public static final double END_PICKUP_REACH = 4.5;
    public static final double END_PICKUP_SECONDS = 3;
    public static final double PICKUP_SECONDS = 10;
    // the last water bucket in the End is the fall bucket for every fling, so that one is worth a short walk back
    public static final double END_LAST_PICKUP_REACH = 12;
    public static final double END_LAST_PICKUP_SECONDS = 10;

    public static final int STUCK_RUN_TICKS = 40;
    // one block out of the box is not out: the user task walks straight back in and the two take turns every tick
    public static final int CLEAR_BLOCKS = 3;
    public static final int MAX_CLEAR_TICKS = 100;
    // past this we're falling, not hopping, and that's MLG's. same line as isFallingOhNo
    public static final double FALL_SPEED = -0.7;
    // obsidian is 50, bedrock is -1
    public static final float HARD_BLOCK_HARDNESS = 50;

    private BreathDodge() {
    }

    // a drop off the side spends the bucket if the scoop after the clutch misses, and the dragon flings us later with
    // nothing to land on. so a drop only with a second one in the bag
    public static boolean spareWater(int waterBuckets) {
        return waterBuckets >= 2;
    }

    // in the air the clutch owns the fall. a pillar top has nowhere to run to (the goal is never reachable), so it is
    // either off the side on purpose with a spare bucket waiting, or down through our own feet
    public static Move choose(boolean inCloud, boolean grounded, boolean floorAround, boolean spareWater) {
        if (!inCloud || !grounded) {
            return Move.NONE;
        }
        if (floorAround) {
            return Move.RUN;
        }
        return spareWater ? Move.DROP : Move.DIG;
    }

    // keep going after the last cloud cell lets go of us, until it's really behind us. capped so a wall of clouds can't hold us.
    // the hops of a run count as the run: dropping it for one air tick just restarts it on landing
    public static Move follow(Move picked, Move last, boolean cloudClose, int ticksOut, boolean grounded, double ySpeed) {
        boolean keep = picked == Move.NONE && last == Move.RUN && cloudClose && ticksOut < MAX_CLEAR_TICKS && (grounded || ySpeed >= FALL_SPEED);
        return keep ? Move.RUN : picked;
    }

    // the same rule for a caller that only ever runs. false is the moment to drop the run
    public static boolean keepRunning(boolean inCloud, boolean running, boolean cloudClose, int ticksOut, boolean grounded, double ySpeed) {
        return follow(inCloud ? Move.RUN : Move.NONE, running ? Move.RUN : Move.NONE, cloudClose, ticksOut, grounded, ySpeed) == Move.RUN;
    }

    // a run that is still in the cloud and has not moved a block by now has nowhere to go (a wide pillar counts its own top
    // as floor). off the edge needs water, digging needs something we can dig in a few seconds. obsidian takes ~9 s, so on
    // a hard top even the last bucket goes over the side: standing in the cloud is the sure death, the clutch scoop usually
    // brings the water back. with no water at all the run stays
    public static Move escalate(Move move, boolean stuck, boolean spareWater, boolean anyWater, boolean hardBelow) {
        if (move == Move.DIG && hardBelow) {
            return anyWater ? Move.DROP : Move.RUN;
        }
        if (move != Move.RUN || !stuck) {
            return move;
        }
        if (spareWater || hardBelow && anyWater) {
            return Move.DROP;
        }
        return hardBelow ? Move.RUN : Move.DIG;
    }

    public static boolean pickupExpired(boolean inEnd, double seconds, double distance) {
        return pickupExpired(inEnd, false, seconds, distance);
    }

    // lastBucket: no water bucket left in the bag, this water is the only one we have in the End
    public static boolean pickupExpired(boolean inEnd, boolean lastBucket, double seconds, double distance) {
        if (inEnd && lastBucket) {
            return distance > END_LAST_PICKUP_REACH || seconds > END_LAST_PICKUP_SECONDS;
        }
        if (inEnd) {
            return distance > END_PICKUP_REACH || seconds > END_PICKUP_SECONDS;
        }
        return seconds > PICKUP_SECONDS;
    }
}
