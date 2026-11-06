package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.CraftInInventoryTask;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.speedrun.gamer.NetherPick;
import adris.altoclef.tasks.speedrun.gamer.NetherPick.Bag;
import adris.altoclef.tasks.speedrun.gamer.NetherPick.Kind;
import adris.altoclef.tasks.speedrun.gamer.NetherPick.Stage;
import adris.altoclef.tasks.speedrun.gamer.NetherPick.Tier;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

// one pickaxe (or sword) out of what the bag holds, stage by stage (planks, sticks, table, pick). every stage is the no-collect form of its
// craft, and the bag is asked again every tick: the moment an input is short the task is over (failed()) instead of letting the
// catalogue walk off to find it, because in the nether there is nothing to find
public class NetherPickTask extends Task {
    // a craft that is still going after this long is stuck, the phase tries again later
    private static final double GIVE_UP_SECONDS = 90;
    private static final double STAGE_SECONDS = 20;
    private static final double PICK_STAGE_SECONDS = 30;

    private final Tier tier;
    private final Kind kind;
    private final Supplier<Bag> bag;
    private final BooleanSupplier hasPick;

    private Stage stage;
    private double lastPickAt = Double.NEGATIVE_INFINITY;
    private double stageSince;
    private Task step;
    private boolean failed;

    public NetherPickTask(Tier tier, Supplier<Bag> bag, BooleanSupplier hasPick) {
        this(tier, Kind.PICKAXE, bag, hasPick);
    }

    public NetherPickTask(Tier tier, Kind kind, Supplier<Bag> bag, BooleanSupplier hasPick) {
        this.tier = tier;
        this.kind = kind;
        this.bag = bag;
        this.hasPick = hasPick;
    }

    public boolean failed() {
        return failed;
    }

    @Override
    protected void onStart(AltoClef mod) {
        stage = null;
        lastPickAt = Double.NEGATIVE_INFINITY;
        step = null;
        failed = false;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (hasPick.getAsBoolean()) {
            return null;
        }
        if (overTimeOnTask(GIVE_UP_SECONDS)) {
            failed = true;
            return null;
        }
        double now = timeOnTaskSeconds();
        Bag raw = bag.get();
        // (the grace runs from the last tick the book really had a table, so it cannot hold the craft up for ever)
        if (raw.table() && NetherPick.stage(raw, tier, kind) == Stage.PICK) {
            lastPickAt = now;
        }
        Bag b = NetherPick.graceTable(raw, stage == Stage.PICK, now - lastPickAt);
        Stage next = NetherPick.stage(b, tier, kind);
        if (next == Stage.ABORT) {
            failed = true;
            return null;
        }
        // a new task object only when the stage changes, or its pathing/crafting would restart every tick
        if (next != stage || step == null) {
            stage = next;
            stageSince = now;
            step = build(mod, next, b);
            if (step == null) {
                failed = true;
                return null;
            }
        }
        // a stage that should take seconds and is still going is stuck (the table step can fall into the container task's own
        // make-a-table branch, which collects, so this is what keeps that short), and a craft task that says it is done
        // with the pick still not in the bag will not get any further by being asked again
        if (now - stageSince > (next == Stage.PICK ? PICK_STAGE_SECONDS : STAGE_SECONDS)
                || (next == Stage.PICK && step.isFinished(mod))) {
            failed = true;
            return null;
        }
        setDebugState("Making a " + tier.name().toLowerCase() + " " + noun() + ": " + next.name().toLowerCase(), "Making a " + noun());
        return step;
    }

    // logs of the first kind we hold, cut into only the planks the rest of the craft still wants. the no-collect craft, because
    // the catalogue's planks task goes and chops a tree when it runs out of logs and a stem is a tree here too
    private Task cutLogs(AltoClef mod, Bag b) {
        return cutLogs(mod, Math.max(0, NetherPick.woodNeeded(b, tier, kind) - b.planks()));
    }

    // (NetherGoldTask cuts its table's planks the same way)
    static Task cutLogs(AltoClef mod, int missing) {
        for (Item log : ItemHelper.LOG) {
            int held = mod.getItemStorage().getItemCount(log);
            Item plank = held > 0 ? ItemHelper.logToPlanks(log) : null;
            if (plank == null) {
                continue;
            }
            int cut = Math.min(held, (missing + NetherPick.PLANKS_PER_LOG - 1) / NetherPick.PLANKS_PER_LOG);
            int target = mod.getItemStorage().getItemCount(plank) + NetherPick.PLANKS_PER_LOG * Math.max(1, cut);
            CraftingRecipe recipe = CraftingRecipe.newShapedRecipe("planks", new Item[][]{{log}, null, null, null}, NetherPick.PLANKS_PER_LOG);
            return new CraftInInventoryTask(new RecipeTarget(plank, target, recipe), false, true);
        }
        return null;
    }

    private Task build(AltoClef mod, Stage s, Bag b) {
        return switch (s) {
            case PLANKS -> cutLogs(mod, b);
            case STICKS -> new CraftInInventoryTask(new RecipeTarget(Items.STICK, NetherPick.sticksNeeded(kind), STICKS_RECIPE), false, true);
            case TABLE -> new CraftInInventoryTask(TaskCatalogue.recipeOf("crafting_table"), false, true);
            default -> new CraftInTableTask(TaskCatalogue.recipeOf(NetherPick.catalogueName(tier, kind)), false, true);
        };
    }

    // the same one CollectSticksTask uses: two planks make four sticks
    private static final CraftingRecipe STICKS_RECIPE = CraftingRecipe.newShapedRecipe("sticks",
            new ItemTarget[]{new ItemTarget("planks"), null, new ItemTarget("planks"), null}, 4);

    @Override
    public boolean isFinished(AltoClef mod) {
        return failed || hasPick.getAsBoolean();
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof NetherPickTask task && task.tier == tier && task.kind == kind;
    }

    @Override
    protected String toDebugString() {
        return "Making a " + tier.name().toLowerCase() + " " + noun() + " from the bag";
    }

    @Override
    protected String toHudString() {
        return "Making a " + noun();
    }

    private String noun() {
        return kind == Kind.SWORD ? "sword" : "pickaxe";
    }
}
