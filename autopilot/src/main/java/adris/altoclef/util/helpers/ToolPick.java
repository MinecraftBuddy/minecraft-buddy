package adris.altoclef.util.helpers;

import baritone.Baritone;
import baritone.utils.ToolSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

// which stack to break a block with. the choosing is plain numbers (the tests feed it those, item tags are not bound
// outside a world so a real pickaxe reads as "not correct for anything" there), StorageHelper does the looking at slots
// and the caller does the equipping
public final class ToolPick {
    private ToolPick() {
    }

    // one stack as it matters for one block. speed is what ToolSet.calculateSpeedVsBlock says (progress per tick, bigger is faster).
    // soft is wood or gold (all the durability of a wet noodle, kept for last), maxDamage is how tough the material is and left is
    // what this one has left, those three are the wear chain
    public record Option(boolean tool, boolean correct, boolean shears, double speed, boolean sword, boolean soft, int maxDamage, int left) {
        public Option(boolean tool, boolean correct, boolean shears, double speed) {
            this(tool, correct, shears, speed, false, false, 0, 0);
        }
    }

    public static Option optionOf(ItemStack stack, BlockState state) {
        // itemSaver's nearly dead tools are not options, same rule as ToolSet so the bag and the hotbar agree
        boolean saved = Baritone.settings().itemSaver.value && stack.getMaxDamage() > 1
                && stack.getDamageValue() + Baritone.settings().itemSaverThreshold.value >= stack.getMaxDamage();
        boolean tool = !saved && ItemHelper.isTool(stack.getItem());
        boolean shears = stack.getItem() == Items.SHEARS && ItemHelper.areShearsEffective(state.getBlock());
        // only a tool that could matter gets the speed worked out, most of a bag is cobble and food
        boolean worthAsking = (tool || shears) && !stack.isEmpty();
        boolean sword = tool && ItemHelper.isSword(stack.getItem());
        boolean correct = tool && stack.isCorrectToolForDrops(state);
        boolean soft = false;
        if (tool && correct) {
            String name = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            soft = name.startsWith("wooden_") || name.startsWith("golden_");
        }
        return new Option(tool, correct, shears, worthAsking ? ToolSet.calculateSpeedVsBlock(stack, state) : 0,
                sword, soft, stack.getMaxDamage(), stack.getMaxDamage() - stack.getDamageValue());
    }

    public static List<Option> optionsOf(List<ItemStack> stacks, BlockState state) {
        List<Option> out = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            out.add(optionOf(stack, state));
        }
        return out;
    }

    // "iron_pickaxe", or "bare hand" for nothing. for log lines
    public static String nameOf(ItemStack stack) {
        return stack.isEmpty() ? "bare hand" : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    // negative when a should be used before b: not wood or gold, then the tougher the material the later, then the fewer
    // uses left the sooner. a spare stone pick goes before the iron one and a half dead one before a fresh one
    public static int chainOrder(Option a, Option b) {
        if (a.soft() != b.soft()) return a.soft() ? 1 : -1;
        if (a.maxDamage() != b.maxDamage()) return Integer.compare(a.maxDamage(), b.maxDamage());
        return Integer.compare(a.left(), b.left());
    }

    // a tool that is no faster than the bare hand just eats durability (dirt, sand, glass), ToolSet skips those
    public static boolean noBetterThanHand(boolean damageable, double speed, double handSpeed) {
        return damageable && speed <= handSpeed;
    }

    // index of the stack to swing, -1 when nothing in there beats the bare hand (handSpeed), settings as they are right now
    public static int best(List<Option> options, double handSpeed) {
        return best(options, handSpeed, Baritone.settings().toolWearChain.value, Baritone.settings().useSwordToMine.value);
    }

    // the right tool for the block wins when we have one (shears jump the queue on leaves and friends). when nobody has the
    // right tool the fastest wrong one still beats a bucket: iron on obsidian takes 42 s and a bucket takes 250, and
    // an empty answer here used to mean "keep swinging whatever is in the hand"
    public static int best(List<Option> options, double handSpeed, boolean chain, boolean swords) {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).shears()) {
                // shears take priority over leaf blocks
                return i;
            }
        }
        int right = chain ? chainBest(options) : -1;
        if (right < 0) {
            right = fastestRightTool(options, swords);
        }
        return right >= 0 ? right : fastestWrongTool(options, handSpeed, swords);
    }

    // swords only mine when the setting says so. they cost twice the durability per block
    private static boolean usable(Option o, boolean swords) {
        return o.tool() && (swords || !o.sword());
    }

    // the chain: of the right tools (never swords, those are the fallbacks below) the one that is next to die
    private static int chainBest(List<Option> options) {
        int best = -1;
        for (int i = 0; i < options.size(); i++) {
            Option o = options.get(i);
            if (!o.tool() || o.sword() || !o.correct()) continue;
            if (best < 0 || chainOrder(o, options.get(best)) < 0) {
                best = i;
            }
        }
        return best;
    }

    private static int fastestRightTool(List<Option> options, boolean swords) {
        int best = -1;
        double highest = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < options.size(); i++) {
            Option o = options.get(i);
            if (usable(o, swords) && o.correct() && o.speed() > highest) {
                highest = o.speed();
                best = i;
            }
        }
        return best;
    }

    // strictly faster than the bare hand or it isn't worth the swap. same idea as baritone's ToolSet.getBestSlot, which
    // never asks whether the tool is "correct" at all
    private static int fastestWrongTool(List<Option> options, double handSpeed, boolean swords) {
        double highest = handSpeed;
        int best = -1;
        for (int i = 0; i < options.size(); i++) {
            Option o = options.get(i);
            if (usable(o, swords) && o.speed() > highest) {
                highest = o.speed();
                best = i;
            }
        }
        return best;
    }

    // the real thing: these stacks against this block
    public static int best(List<ItemStack> stacks, BlockState state) {
        return best(optionsOf(stacks, state), ToolSet.calculateSpeedVsBlock(ItemStack.EMPTY, state));
    }

    // the one tool the chain would use for this block, -1 if no tool of the right kind and tier. ToolSet.getBestSlot and the
    // hotbar bring-down both ask it
    public static int chainIndex(List<ItemStack> stacks, BlockState state) {
        List<Option> options = optionsOf(stacks, state);
        // shears jump the queue in best(), the chain stays out of their way
        for (Option o : options) {
            if (o.shears()) return -1;
        }
        return chainBest(options);
    }
}
