package adris.altoclef.util.helpers;

import net.minecraft.core.BlockPos;

import java.util.function.Predicate;

// the block we are in the middle of breaking is not up for re-picking. the pick used to be redone every tick and a drop
// one or two blocks away won it as often as the block did, so the bot flipped between Destroy block and Pickup 12 times
// in 8 seconds, and every flip let go of the mouse button, which makes vanilla throw the crack away. the controller says
// which block is being broken, but only for 500 ms after the last tick of it (PlayerExtraController), and a pause for a
// tool swap or a step eats that, so this keeps its own memory of it for a while longer. pure, the caller feeds it ticks
public final class MineStick {
    // 1.5 s. longer than any pause we cause ourselves, shorter than anything worth staying for
    public static final int GRACE_TICKS = 30;

    // a drop this old (client ticks since we first saw it, so a lower bound) is a few seconds from despawning and is
    // worth walking off a block for. 6000 is the despawn age, 400 ticks is 20 s of walking
    public static final int DESPAWN_SOON_AGE = 6000 - 400;

    private BlockPos pos;
    private int seenTick;
    // the target we already got one free hold for, see begin
    private BlockPos begun;

    // the controller says this block is being broken right now (and it is one of ours: the caller checks that)
    public void breaking(int now, BlockPos at) {
        if (at != null) {
            pos = at;
            seenTick = now;
        }
    }

    // we picked this block and it is in reach (or the last step away), but the controller has not said a word yet. the
    // first ticks of a break were the unprotected ones: a drop out of the last block rolled out of the pickup box in that
    // window, won the pick, and the crack never got to start. one hold per target, it is not renewed (a block we can't
    // hit would otherwise be held forever), only a real break renews it
    public void begin(int now, BlockPos at) {
        if (wouldBegin(at)) {
            pos = at;
            begun = at;
            seenTick = now;
        }
    }

    // true when begin would start a hold (so the caller can say so once)
    public boolean wouldBegin(BlockPos at) {
        return at != null && !at.equals(begun);
    }

    // the block to stay on, or null. `stillGood` is the caller's idea of "still ours, still there, still in reach": a
    // block that broke fails it, which is the moment the drops get their turn
    public BlockPos holdOn(int now, Predicate<BlockPos> stillGood) {
        if (pos == null) {
            return null;
        }
        boolean good = stillGood.test(pos);
        if (now - seenTick > GRACE_TICKS || !good) {
            // a block that broke may come back as a new block, and that one is a fresh start
            if (!good && pos.equals(begun)) {
                begun = null;
            }
            pos = null;
            return null;
        }
        return pos;
    }

    // is this the block holdOn last handed out (the pursuit logic asks, to let it through the new-target rule)
    public boolean isHolding(BlockPos at) {
        return pos != null && pos.equals(at);
    }

    public void clear() {
        pos = null;
        begun = null;
    }

    // a drop takes over from a block nobody is holding, never from a held one (unless it is about to despawn), and never
    // from another drop: the one we are walking to is the one we get, the nearer one comes after
    public static boolean dropMayTakeOver(boolean currentIsDrop, boolean currentHeld, boolean dropDespawning) {
        if (currentIsDrop) {
            return false;
        }
        return !currentHeld || dropDespawning;
    }

    public static boolean aboutToDespawn(int dropAge) {
        return dropAge >= DESPAWN_SOON_AGE;
    }

    // a target we have never tried has to be at least twice as close (distance, so a quarter of it squared) to take over
    // from the one we are on. same idea as the divide by 4 in AbstractDoToClosestObjectTask for the ones it remembers
    public static boolean clearlyCloser(double newDistSq, double currentDistSq) {
        return newDistSq * 4 <= currentDistSq;
    }

    // don't swap the tool in hand while a crack is on the block: a different item resets the progress, and the slower
    // break with what we hold finishes sooner than a faster one started over
    public static boolean toolSwapWouldReset(boolean breakingNow, BlockPos breakingPos, double progress, BlockPos target) {
        return breakingNow && progress > 0 && breakingPos != null && breakingPos.equals(target);
    }

    // nothing in the bag beats the hand on this block (dirt, with the pick as the best tool): a pick in hand just wears for
    // no gain, so hold something that can't. an empty slot first, then any hotbar item that doesn't take damage, never
    // another tool. -1 when there is no such slot, the hand stays as it is
    public static int calmSlot(boolean[] empty, boolean[] damageable, int selected) {
        for (int i = 0; i < 9; i++) {
            if (i != selected && empty[i]) {
                return i;
            }
        }
        for (int i = 0; i < 9; i++) {
            if (i != selected && !damageable[i]) {
                return i;
            }
        }
        return -1;
    }
}
