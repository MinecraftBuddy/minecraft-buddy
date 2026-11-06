package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.CraftInInventoryTask;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.resources.MineAndCollectTask;
import adris.altoclef.tasks.speedrun.gamer.NetherGold;
import adris.altoclef.tasks.speedrun.gamer.NetherGold.Bag;
import adris.altoclef.tasks.speedrun.gamer.NetherGold.Stage;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.RecipeTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.function.Supplier;

// one gold piece out of nether gold ore, stage by stage (nuggets, a table, ingots, the piece). the same shape as NetherPickTask:
// every craft is the no-collect form, the bag is asked again every tick, and the moment the bag can not finish the job it is over
// (failed()) instead of the catalogue walking off to find something. the phase decides when it may run at all (no piglin in the
// box a block break would anger, see NetherGold), and wears the piece once it is held
public class NetherGoldTask extends Task {
    private final String pieceName;
    private final Item piece;
    private final int need;
    private final Supplier<Bag> bag;
    // where the try began: the ore must be within NetherGold.ORE_BLOCKS of it, so the dig never turns into a trek
    private final BlockPos anchor;

    // nothing in reach to dig for this long and the dig is over: the tracker still lists ore the mining task gave up on
    private static final double WANDER_GIVE_UP_SECONDS = 4;

    private Stage stage;
    private Task step;
    private boolean failed;
    private double wanderSince;

    public NetherGoldTask(String pieceName, Item piece, int need, Supplier<Bag> bag, BlockPos anchor) {
        this.pieceName = pieceName;
        this.piece = piece;
        this.need = need;
        this.bag = bag;
        this.anchor = anchor;
    }

    public boolean failed() {
        return failed;
    }

    // the piece is in the bag (or on us, then the bag is not asked again)
    public boolean made(AltoClef mod) {
        return mod.getItemStorage().getItemCount(piece) > 0;
    }

    @Override
    protected void onStart(AltoClef mod) {
        stage = null;
        step = null;
        failed = false;
        wanderSince = -1;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (made(mod)) {
            return null;
        }
        Bag b = bag.get();
        Stage next = NetherGold.stage(b, need);
        if (next == Stage.ABORT) {
            failed = true;
            return null;
        }
        // a new task object only when the stage changes, or its pathing/crafting would restart every tick
        if (next != stage || step == null) {
            stage = next;
            step = build(mod, next, b);
            if (step == null) {
                failed = true;
                return null;
            }
        }
        if (step instanceof MineAndCollectTask mine && mine.wandering()) {
            double now = timeOnTaskSeconds();
            wanderSince = wanderSince < 0 ? now : wanderSince;
            if (now - wanderSince > WANDER_GIVE_UP_SECONDS) {
                failed = true;
                return null;
            }
        } else {
            wanderSince = -1;
        }
        setDebugState("Gold piece for piglins: " + next.name().toLowerCase(), "Digging gold for piglins");
        return step;
    }

    private Task build(AltoClef mod, Stage s, Bag b) {
        return switch (s) {
            case PLANKS -> NetherPickTask.cutLogs(mod, Math.max(0, NetherGold.PLANKS_PER_TABLE - b.planks()));
            case TABLE -> new CraftInInventoryTask(TaskCatalogue.recipeOf("crafting_table"), false, true);
            case INGOTS -> {
                ItemTarget n = new ItemTarget(Items.GOLD_NUGGET);
                CraftingRecipe recipe = CraftingRecipe.newShapedRecipe("gold_ingot", new ItemTarget[]{n, n, n, n, n, n, n, n, n}, 1);
                yield new CraftInTableTask(new RecipeTarget(Items.GOLD_INGOT, Math.min(need, NetherGold.ingotsAfterCraft(b)), recipe), false, true);
            }
            case PIECE -> new CraftInTableTask(TaskCatalogue.recipeOf(pieceName), false, true);
            case MINE -> new MineAndCollectTask(new ItemTarget(Items.GOLD_NUGGET, b.nuggets() + NetherGold.nuggetsToMine(b, need)),
                    new Block[]{Blocks.NETHER_GOLD_ORE}, MiningRequirement.WOOD)
                    .onlyWhere(pos -> NetherGold.oreInReach(pos.getX(), pos.getY(), pos.getZ(), anchor.getX(), anchor.getY(), anchor.getZ()));
            default -> null;
        };
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return failed || made(mod);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof NetherGoldTask task && task.pieceName.equals(pieceName) && task.need == need;
    }

    @Override
    protected String toDebugString() {
        return "Making " + pieceName + " from nether gold";
    }

    @Override
    protected String toHudString() {
        return "Digging gold for piglins";
    }
}
