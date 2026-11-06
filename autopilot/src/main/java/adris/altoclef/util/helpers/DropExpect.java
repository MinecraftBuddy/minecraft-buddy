package adris.altoclef.util.helpers;

// "a drop is about to exist, stand still". the block is gone or the mob is dead, the item entity shows up a few ticks
// later, and in that gap the task above already picked the next block / the next cow (the beef was preempted by a new
// hunt in the same second). pure, the caller says what tick it is and whether it can see a drop yet.
//
// vanilla: a block drops its items the instant it breaks and the entity packet is a tick or two behind. a mob drops its
// loot in die() (the 20 tick death animation is only the body falling over), so a kill is the same wait with more
// slack for lag. the wait is only for the entity to EXIST: once it is in sight the pickup paths own it, and holding on
// for the 10 tick pickup delay would put half a second on every cobble of a tunnel
public final class DropExpect {
    public static final int BREAK_TICKS = 10;
    public static final int KILL_TICKS = 20;

    private boolean live;
    private long deadline;
    private boolean takeNow;

    public void expectBreak(long now) {
        arm(now + BREAK_TICKS);
    }

    public void expectKill(long now) {
        arm(now + KILL_TICKS);
    }

    private void arm(long deadline) {
        live = true;
        this.deadline = deadline;
        takeNow = false;
    }

    public boolean isLive() {
        return live;
    }

    // live and not past its deadline. for the ones asking from outside the task that ticks hold(), which is the only
    // thing that ever ends a wait, so a task that was swapped out mid wait must not look like it is still waiting
    public boolean isLive(long now) {
        return live && now < deadline;
    }

    // true when the last wait ended because the drop showed up (not because the clock ran out), so whoever is waiting
    // knows it is time to go and get it. cleared by the next expectation
    public boolean takeNow() {
        return takeNow;
    }

    // true: stand still this tick. false: carry on (nothing expected, it came, or the wait ran out)
    public boolean hold(long now, boolean dropSeen) {
        if (!live) {
            return false;
        }
        if (dropSeen) {
            live = false;
            takeNow = true;
            return false;
        }
        if (now >= deadline) {
            live = false;
            return false;
        }
        return true;
    }

    public void clear() {
        live = false;
        takeNow = false;
    }
}
