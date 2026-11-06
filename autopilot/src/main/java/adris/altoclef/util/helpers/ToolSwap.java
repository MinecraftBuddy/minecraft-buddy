package adris.altoclef.util.helpers;

// when DestroyBlockTask swaps a tool in, and when it gives up on swapping. pure, the caller does the clicking.
// born from a 26 s stall at a carrot: "found better tool, equipping" every tick and the swing never came, because the
// caller returned after asking whether or not the hand changed. we never pinned which tool it was (two tools tied for
// "best" trading places, or one the swap can't reach, both fit), so this covers both: only a strictly faster tool is
// worth a swap, and a few misses or swaps in a row means we swing with what we hold
public final class ToolSwap {
    // misses at one item before we stop trying and swing with what we hold
    public static final int GIVE_UP = 3;
    // swaps that "landed" for one block. something else re-selecting the slot behind our back would make every one of
    // them land, and the miss count would never run out
    public static final int MAX_SWAPS = 6;

    private Object wanted;
    private int misses;
    private int swaps;

    // swapping is only worth it for a tool that is strictly faster. anything we hold that can do the job as fast stays,
    // so equal speeds never trade places. a hand that can't do the job (no tool, wrong tool) swaps to any best
    public static boolean worthSwapping(boolean instant, Object held, boolean heldCorrect, double heldSpeed, Object best, double bestSpeed) {
        if (instant || best == null || best.equals(held)) {
            return false;
        }
        return !heldCorrect || bestSpeed > heldSpeed;
    }

    // the wear chain's reason to swap: both tools are right for the block, and the other one is the one that should die first.
    // slower is fine here, a stone pick for a stone block we're holding an iron pick at. different items only, so two stacks of
    // the same pick never trade places
    public static boolean worthSwappingDown(Object held, ToolPick.Option heldOption, Object best, ToolPick.Option bestOption) {
        if (best == null || best.equals(held)) {
            return false;
        }
        return heldOption.tool() && heldOption.correct() && !heldOption.sword()
                && bestOption.tool() && bestOption.correct() && !bestOption.sword()
                && ToolPick.chainOrder(bestOption, heldOption) < 0;
    }

    // false once this item has missed GIVE_UP times in a row. a different item is a fresh start
    public boolean mayTry(Object best) {
        if (!best.equals(wanted)) {
            wanted = best;
            misses = 0;
        }
        if (misses >= GIVE_UP || swaps >= MAX_SWAPS) {
            return false;
        }
        swaps++;
        return true;
    }

    // the hand holds it after the click
    public void landed() {
        wanted = null;
        misses = 0;
    }

    // the click went out and the hand still doesn't hold it
    public void missed(Object best) {
        if (best.equals(wanted)) {
            misses++;
        }
    }
}
