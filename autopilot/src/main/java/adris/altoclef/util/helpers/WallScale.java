package adris.altoclef.util.helpers;

// Tuning wall_scale (1 by default, 9 Nov 2026): the path search's budgets are wall time (Baritone's primary and failure
// timeouts, plan-ahead too, and a trip's what-if search), so on a core three times slower the search sees a third of the
// nodes in the same budget. the rig's netcup boxes run 2.5-3x slower single-threaded than cascade, and the same jar passed
// 27-31% there against about 42% on cascade; the boxes' queue lines carry a scale so the buddy gets the time its core needs.
// a real buddy keeps 1
public final class WallScale {
    private WallScale() {
    }

    public static long ms(long budget) {
        return scaled(budget, Tuning.get("wall_scale", 1));
    }

    // pure: the budget times the scale, capped at ten; a scale of a tenth or less, or not a number, leaves the budget
    static long scaled(long budget, double scale) {
        if (!(scale > 0.1) || Double.isInfinite(scale)) return budget;
        return Math.round(budget * Math.min(scale, 10.0));
    }
}
