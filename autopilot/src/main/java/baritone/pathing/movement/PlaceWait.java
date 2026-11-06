package baritone.pathing.movement;

// the pure half of "a mob is standing where the block goes". the server refuses a block that overlaps an entity, so the
// click just does nothing and the movement waits for a placement that cannot come. wait a little (mobs wander off), then
// say it cannot be done and let the planner pick something else
public final class PlaceWait {
    // a movement holds still this many ticks for the cell to clear, then it is UNREACHABLE
    public static final int MOVEMENT_PATIENCE = 20;
    // the "place it anywhere" task is cheaper to redirect, it has other spots
    public static final int TASK_PATIENCE = 10;

    public enum Verdict {
        // nothing in the cell, place away
        CLEAR,
        // something in it, keep waiting
        WAIT,
        // it has been in there too long
        GIVE_UP
    }

    private PlaceWait() {
    }

    // consecutive ticks blocked: any clear tick starts over
    public static int next(boolean blocked, int ticksBlocked) {
        return blocked ? ticksBlocked + 1 : 0;
    }

    public static Verdict judge(int ticksBlocked, int patience) {
        if (ticksBlocked <= 0) return Verdict.CLEAR;
        return ticksBlocked > patience ? Verdict.GIVE_UP : Verdict.WAIT;
    }
}
