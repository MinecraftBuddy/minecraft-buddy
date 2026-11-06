package adris.altoclef;

import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.trace.Lines;
import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.utils.input.Input;
import baritone.behavior.PathingBehavior;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementState;
import baritone.pathing.path.PathExecutor;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

// reads the game for the stand line, only ever when one is due (a few times a minute at worst). nothing here changes
// anything. a few of the executor's numbers (how long it has been on this movement, which segment owns the tick) are
// private and have no getter, so they are read with reflection and come out as "?" if a field ever goes missing
final class StandReport {

    private static final Field ON_CURRENT = field(PathExecutor.class, "ticksOnCurrent");
    private static final Field ESTIMATE = field(PathExecutor.class, "currentMovementOriginalCostEstimate");
    private static final Field BOAT = field(PathExecutor.class, "boat");
    private static final Field SHORTCUT = field(PathExecutor.class, "groundShortcut");
    private static final Field FLIGHT = field(PathExecutor.class, "flight");
    private static final Field MOVE_STATE = field(Movement.class, "currentState");

    private StandReport() {
    }

    // true when baritone has an executor, which is what the path on screen is. a pause keeps it, a finished or failed
    // path drops it, a search that has not landed yet is not one
    static boolean live(Baritone baritone) {
        return baritone.getPathingBehavior().getCurrent() != null;
    }

    // "none" / "live" / "calc" for the vitals line
    static String pathWord(Baritone baritone) {
        PathingBehavior pb = baritone.getPathingBehavior();
        if (pb.getCurrent() != null) {
            return "live";
        }
        return pb.getInProgress().isPresent() ? "calc" : "none";
    }

    static String line(AltoClef mod, Baritone baritone, LocalPlayer player, int ticks, String lastDebug, long lastDebugAgo) {
        PathingBehavior pb = baritone.getPathingBehavior();
        PathExecutor exec = pb.getCurrent();
        IPath path = exec == null ? null : exec.getPath();
        int index = exec == null ? 0 : exec.getPosition();
        Minecraft mc = Minecraft.getInstance();
        TaskChain taskChain = mod.getTaskRunner().getCurrentTaskChain();
        Optional<PathingCommand> command = baritone.getPathingControlManager().mostRecentCommand();
        String by = baritone.getPathingControlManager().mostRecentInControl().map(IBaritoneProcess::displayName).orElse(null);
        return Lines.standing(new Lines.Stand(ticks, player.getX(), player.getY(), player.getZ(), move(exec, path, index),
                index, path == null ? 0 : path.length(), String.valueOf(pb.getGoal()), exec != null && !pb.isPathing(),
                command.map(c -> c.commandType.name()).orElse(null), by, mod.getExtraBaritoneSettings().isInteractionPaused(),
                keys(baritone, player), held(mc.options), player.isCrouching(), mc.gui.screen() == null ? null : mc.gui.screen().getClass().getSimpleName(), using(player),
                player.onGround(), player.isInWater(), calc(pb), chain(taskChain), taskChain == null ? null : leaf(taskChain.getTasks()),
                Lines.lastDebug(lastDebug, lastDebugAgo), exec != null && !pb.isPathing() ? pb.pausedBy() : null, preparedHold(path, index)));
    }

    private static String preparedHold(IPath path, int index) {
        if (path == null || index < 0 || index >= path.movements().size()) return null;
        return path.movements().get(index) instanceof Movement movement ? movement.preparedHold() : null;
    }

    // the lava entry line: what was driving when we hit the lava, so a death in it can be pinned on a goal or a task
    static String lava(AltoClef mod, Baritone baritone, LocalPlayer player, String dimension) {
        PathingBehavior pb = baritone.getPathingBehavior();
        PathExecutor exec = pb.getCurrent();
        IPath path = exec == null ? null : exec.getPath();
        TaskChain taskChain = mod.getTaskRunner().getCurrentTaskChain();
        return "lava: entered at " + at(player.getBlockX(), player.getBlockY(), player.getBlockZ()) + " [" + dimension + "]"
                + " task=" + (taskChain == null ? null : leaf(taskChain.getTasks()))
                + " goal=" + pb.getGoal()
                + " move=" + move(exec, path, exec == null ? 0 : exec.getPosition())
                + " swimLava=" + AltoClefSettings.getInstance().canSwimThroughLava()
                + " onFire=" + player.isOnFire()
                + " fireRes=" + player.hasEffect(MobEffects.FIRE_RESISTANCE)
                + " hp=" + Math.round(player.getHealth());
    }

    private static String move(PathExecutor exec, IPath path, int index) {
        if (exec == null || path == null || index < 0 || index >= path.movements().size()) {
            return null;
        }
        IMovement movement = path.movements().get(index);
        return Lines.move(movement.getClass().getSimpleName(), at(movement.getSrc().getX(), movement.getSrc().getY(), movement.getSrc().getZ()),
                at(movement.getDest().getX(), movement.getDest().getY(), movement.getDest().getZ()), status(movement), onCurrent(exec), owner(exec));
    }

    private static String at(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    private static String status(IMovement movement) {
        Object state = read(MOVE_STATE, movement);
        if (state instanceof MovementState s && s.getStatus() != null) {
            return s.getStatus().name();
        }
        return "?";
    }

    // the executor's own clock for this movement against the point it gives up, which is the cost it expected when the
    // movement started plus movementTimeoutTicks
    private static String onCurrent(PathExecutor exec) {
        Object ticks = read(ON_CURRENT, exec);
        Object estimate = read(ESTIMATE, exec);
        String left = ticks instanceof Integer i ? Integer.toString(i) : "?";
        String right = estimate instanceof Double d
                ? Long.toString(Math.round(d + Baritone.settings().movementTimeoutTicks.value)) : "?";
        return left + "/" + right;
    }

    // a boat trip, a ground shortcut or a sprint jump flight takes the tick away from the movement
    private static String owner(PathExecutor exec) {
        // a missing field would read as "nobody has it", which is a claim we can't back up
        if (BOAT == null || SHORTCUT == null || FLIGHT == null) {
            return "?";
        }
        if (read(BOAT, exec) != null) {
            return "boat";
        }
        if (read(SHORTCUT, exec) != null) {
            return "shortcut";
        }
        if (read(FLIGHT, exec) != null) {
            return "flight";
        }
        return "movement";
    }

    private static String keys(Baritone baritone, LocalPlayer player) {
        StringBuilder sb = new StringBuilder();
        for (Input input : Input.values()) {
            if (baritone.getInputOverrideHandler().isInputForcedDown(input)) {
                sb.append(sb.length() == 0 ? "" : ",").append(input.name());
            }
        }
        // sprint is not an input baritone forces, it is the player's own flag
        if (player.isSprinting()) {
            sb.append(sb.length() == 0 ? "" : ",").append("SPRINT");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // the vanilla key mappings that are actually down: altoclef's own holds and the real keyboard. baritone's forced inputs
    // never touch these (they feed the movement input directly), so a crouch of baritone's reads keys=SNEAK held=none
    // sneak=true, and a crouch with nobody in keys= is one of ours
    private static String held(Options options) {
        StringBuilder sb = new StringBuilder();
        held(sb, "SNEAK", options.keyShift);
        held(sb, "BACK", options.keyDown);
        held(sb, "FORWARD", options.keyUp);
        held(sb, "JUMP", options.keyJump);
        held(sb, "USE", options.keyUse);
        held(sb, "ATTACK", options.keyAttack);
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void held(StringBuilder sb, String name, KeyMapping key) {
        if (key.isDown()) {
            sb.append(sb.length() == 0 ? "" : ",").append(name);
        }
    }

    private static String using(LocalPlayer player) {
        if (!player.isUsingItem()) {
            return null;
        }
        ItemStack stack = player.getUseItem();
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath() + " " + player.getUseItemRemainingTicks() + "t left";
    }

    // the same rule findPathInNewThread uses: a search with no current path is the blocking kind, one with a path is
    // planning the next segment
    private static String calc(PathingBehavior pb) {
        if (pb.getInProgress().isEmpty()) {
            return "none";
        }
        return pb.getCurrent() == null ? "primary" : "planAhead";
    }

    // the tasks list is parent first, so the last one is the leaf that is actually doing something
    static String leaf(List<Task> tasks) {
        return tasks.isEmpty() ? null : String.valueOf(tasks.get(tasks.size() - 1));
    }

    static String chain(TaskChain chain) {
        return chain == null ? null : chain.getClass().getSimpleName();
    }

    private static Object read(Field f, Object from) {
        if (f == null || from == null) {
            return null;
        }
        try {
            return f.get(from);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
