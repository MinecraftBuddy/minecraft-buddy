package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.ToolPick.Option;
import baritone.utils.ToolSet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Locale;

// is a block worth breaking at all with what we hold. obsidian by hand is 250 s, with an iron pick 42 s and drops nothing,
// and "keep swinging until the progress checker gets bored" is a very long way to find that out.
//
// the number is plain vanilla: tool speed / hardness / 30 (or 100 when the tool is the wrong one for the block), per tick.
// ToolSet.calculateSpeedVsBlock already does that with efficiency in it. haste, being in water or off the ground are
// left out on purpose: they move a time by a factor, not by the 20 s this is about, and none of them is free to ask for
public final class BreakRules {
    // 20 s. stone by hand is 7.5 s, deepslate by hand 15 s, obsidian with a diamond pick 9.4 s
    public static final int MAX_TICKS = 20 * 20;
    // even the bare hand on a block that needs the right tool is 1 / hardness / 100 per tick, so under this nothing is too slow
    private static final double ALWAYS_FINE_HARDNESS = MAX_TICKS / 100.0;

    private BreakRules() {
    }

    // ticks for a break that makes this much progress a tick. one tick is the least vanilla takes. nothing (0, or the -1
    // ToolSet gives for an unbreakable block) is never
    public static double ticks(double progressPerTick) {
        if (!(progressPerTick > 0)) {
            return Double.POSITIVE_INFINITY;
        }
        // (the float noise in the progress would turn 5000 ticks into 5001, hence the nudge)
        return Math.max(1, Math.ceil(1 / progressPerTick - 1e-3));
    }

    public static boolean tooSlow(double ticks) {
        return ticks > MAX_TICKS;
    }

    // the time with the best of these (see ToolPick for what best means), the bare hand when none beats it
    public static double bestTicks(List<Option> options, double handSpeed) {
        int pick = ToolPick.best(options, handSpeed);
        return ticks(pick < 0 ? handSpeed : options.get(pick).speed());
    }

    // true when no bag needs looking at: a fluid, no hardness, or hard enough to matter only past what the bare hand manages
    public static boolean alwaysFine(BlockState state) {
        if (state.getBlock() instanceof LiquidBlock) {
            return true;
        }
        float hardness = state.getDestroySpeed(null, null);
        return hardness < 0 || hardness <= ALWAYS_FINE_HARDNESS;
    }

    // the real thing. bedrock and friends (no hardness) are not ours to judge, that is what canBreak's own check is for,
    // and a fluid is never "broken" so it is never too slow either
    public static boolean tooSlow(List<ItemStack> bag, BlockState state) {
        return !alwaysFine(state) && tooSlow(bestTicks(ToolPick.optionsOf(bag, state), ToolSet.calculateSpeedVsBlock(ItemStack.EMPTY, state)));
    }

    // seconds and the tool for the log line: "not breaking obsidian at x y z, 41.7s with iron_pickaxe"
    public static String describe(List<ItemStack> bag, BlockState state) {
        double handSpeed = ToolSet.calculateSpeedVsBlock(ItemStack.EMPTY, state);
        List<Option> options = ToolPick.optionsOf(bag, state);
        int pick = ToolPick.best(options, handSpeed);
        double seconds = ticks(pick < 0 ? handSpeed : options.get(pick).speed()) / 20.0;
        return String.format(Locale.ROOT, "%.1fs with %s", seconds, pick < 0 ? "bare hand" : ToolPick.nameOf(bag.get(pick)));
    }
}
