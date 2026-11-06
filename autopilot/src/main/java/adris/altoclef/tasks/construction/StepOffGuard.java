package adris.altoclef.tasks.construction;

// counts how many separate times we stepped off a block, so "step off, walk back on, step off" can
// be noticed instead of run until the user gets bored. a run of consecutive ticks is one step off
public class StepOffGuard {

    private final int allowed;
    private int count;
    private boolean steppingOff;

    public StepOffGuard(int allowed) {
        this.allowed = allowed;
    }

    // true on the step off that goes past the allowed number, and the count starts over
    public boolean tick(boolean stepOffThisTick) {
        if (!stepOffThisTick) {
            steppingOff = false;
            return false;
        }
        if (steppingOff) {
            return false;
        }
        steppingOff = true;
        if (++count > allowed) {
            count = 0;
            return true;
        }
        return false;
    }

    // we actually got to mine something, so whatever happened before wasn't a loop
    public void reset() {
        count = 0;
        steppingOff = false;
    }
}
