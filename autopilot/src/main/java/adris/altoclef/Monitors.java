package adris.altoclef;

import adris.altoclef.tasks.speedrun.gamer.GamerTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.trace.CursorWatch;
import adris.altoclef.trace.DebugCollapse;
import adris.altoclef.trace.EntryLatch;
import adris.altoclef.trace.FallTrace;
import adris.altoclef.trace.Lines;
import adris.altoclef.trace.MonitorErrors;
import adris.altoclef.trace.StandWatch;
import adris.altoclef.trace.Trace;
import adris.altoclef.trace.Vitals;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.Baritone;
import baritone.api.utils.DebugTap;
import baritone.api.pathing.goals.Goal;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.movements.MovementTraverse;
import net.minecraft.world.entity.Entity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;

// the things the log keeps an eye on that no task owns: a line of vitals every few seconds while we drive, an item that
// has been on the cursor for too long, and the feet standing still on a live path. all of them only ever flag, nothing
// here touches the bot. every tick is a few compares, the reading and formatting happen when a line is due
public final class Monitors {

    // 5 s of ticks
    private static final int VITALS_EVERY = 100;
    // 1 s of ticks
    private static final int CURSOR_EVERY = 20;

    private int vitalsTicks;
    private int cursorTicks;
    private final CursorWatch cursorWatch = new CursorWatch();
    private final StandWatch standWatch = new StandWatch();
    // 40 ticks out of the lava before the next dip gets its own line
    private final EntryLatch lavaLatch = new EntryLatch(40);
    // the last 60 ticks of motion, dumped when a nether fall gets long or we land in lava
    private final FallTrace fallTrace = new FallTrace();
    private final BlockPos.MutableBlockPos underFeet = new BlockPos.MutableBlockPos();
    private int lastHurtTime;
    private boolean lavaEntered;

    // baritone's logDebug goes nowhere unless chatDebug is on, which is most of what it says about why it stopped. the
    // collapse is static because the tap is, and so is the tick it stamps lines with (the path threads log too)
    private static final DebugCollapse BARITONE = new DebugCollapse();
    private static volatile long clientTick;

    // once, at startup
    public static void tapBaritone() {
        DebugTap.setSink(text -> BARITONE.offer(text.replace('\n', ' ').replace('\r', ' '), System.currentTimeMillis(), clientTick,
                Monitors::sayBaritone));
    }

    private static void sayBaritone(String text) {
        Debug.logInternal("baritone: " + text);
    }

    // while the task runner is active, after it has ticked so the chain's task list is this tick's
    public void onActiveTick(AltoClef mod) {
        if (++vitalsTicks < VITALS_EVERY) {
            return;
        }
        vitalsTicks = 0;
        try {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                Debug.logInternal(vitals(mod, player));
            }
        } catch (RuntimeException e) {
            // a log line is never worth a crash in the tick, but a monitor that always throws should not look like a quiet one
            broke("vitals", e);
        }
    }

    private static final MonitorErrors ERRORS = new MonitorErrors();

    // the first throw from each monitor is said once, after that it stays swallowed. the saying can't throw either
    public static void broke(String monitor, RuntimeException error) {
        try {
            String line = ERRORS.first(monitor, error);
            if (line != null) {
                Debug.logInternal(line);
            }
        } catch (RuntimeException ignored) {
            // nothing left to tell
        }
    }

    // every tick, runner or not: the cursor can get stuck while we are idle too
    public void onTick(AltoClef mod) {
        clientTick++;
        try {
            BARITONE.flush(System.currentTimeMillis(), Monitors::sayBaritone);
        } catch (RuntimeException e) {
            broke("baritone tap", e);
        }
        try {
            watchLava(mod);
        } catch (RuntimeException e) {
            broke("lava", e);
        }
        try {
            watchStand(mod);
        } catch (RuntimeException e) {
            broke("stand", e);
        }
        if (++cursorTicks >= CURSOR_EVERY) {
            cursorTicks = 0;
            try {
                watchCursor();
            } catch (RuntimeException e) {
                broke("cursor", e);
            }
        }
        // last of the watchers: a TRACE DUMP from the stand or cursor watch should not find its lead up pushed out by our lines
        try {
            watchFall(mod);
        } catch (RuntimeException e) {
            broke("falltrace", e);
        }
    }

    // a live path is baritone holding an executor (the path on screen). paused, or stuck inside a movement, or starved of
    // input all still count, naming which is the whole job of the line. this runs ahead of baritone's own tick, so
    // paused/keys/state are as of the tick before
    private void watchStand(AltoClef mod) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        Baritone baritone = mod.getClientBaritone();
        StandWatch.Event event = standWatch.tick(StandReport.live(baritone), player.getX(), player.getY(), player.getZ());
        if (event == StandWatch.Event.OVER) {
            Debug.logInternal(Lines.standOver(standWatch.lastStand(), standWatch.overBecauseMoved()));
        } else if (event == StandWatch.Event.FIRE) {
            Debug.logInternal(StandReport.line(mod, baritone, player, standWatch.ticks(), BARITONE.latest(), clientTick - BARITONE.latestTick()));
            if (standWatch.wantsDump()) {
                // after the line, so the dump has it. the cooldown keeps this to one a minute with the other monitors
                Trace.dump("monitor:stand");
            }
        }
    }

    // one line per dip: standing in it, or the client saying the last hit was lava (a hop out still burns)
    private void watchLava(AltoClef mod) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        DamageSource source = player.hurtTime > 0 ? player.getLastDamageSource() : null;
        // only standing in it keeps the latch held: a lava hit that lingers after we climbed out must not eat the re-arm count
        boolean lava = player.isInLava() || (lavaLatch.armed() && source != null && source.is(DamageTypes.LAVA));
        lavaEntered = lavaLatch.tick(lava);
        if (lavaEntered) {
            Debug.logInternal(StandReport.lava(mod, mod.getClientBaritone(), player, WorldHelper.getCurrentDimension().name().toLowerCase(Locale.ROOT)));
        }
    }

    // every tick is a few array writes. the lines only get built when a nether fall passes 4 blocks or the lava line just fired
    private void watchFall(AltoClef mod) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        // whatever the executor ran since our last look, not what the path points at now: by now a failed movement is
        // cancelled and a finished one has handed over, and their status + place is the part worth reading
        Movement movement = mod.getClientBaritone().getPathingBehavior().takeTicked();
        Object place = null;
        if (movement instanceof MovementTraverse traverse) {
            place = traverse.lastPlace();
        }
        // hurtTime stays up for ten ticks, only the tick it starts is a hit
        boolean hit = player.hurtTime > lastHurtTime;
        lastHurtTime = player.hurtTime;
        Entity attacker = null;
        if (hit && player.getLastDamageSource() != null) {
            attacker = player.getLastDamageSource().getEntity();
        }
        var move = player.getDeltaMovement();
        if (attacker != null) {
            // the knock itself shows in the ring over the next ticks, this is the who and where
            Debug.logInternal(String.format(Locale.ROOT, "[falltrace] hit by %s at %.2f %.2f %.2f, we are at %.2f %.2f %.2f vel %.3f %.3f %.3f",
                    BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType()).getPath(), attacker.getX(), attacker.getY(), attacker.getZ(), player.getX(), player.getY(), player.getZ(),
                    move.x, move.y, move.z));
        }
        var input = player.input.keyPresses;
        byte keys = (byte) ((input.forward() ? FallTrace.FORWARD : 0) | (input.backward() ? FallTrace.BACK : 0)
                | (input.left() ? FallTrace.LEFT : 0) | (input.right() ? FallTrace.RIGHT : 0)
                | (input.sprint() ? FallTrace.SPRINT : 0) | (input.jump() ? FallTrace.JUMP : 0) | (input.shift() ? FallTrace.SNEAK : 0));
        Goal goal = mod.getClientBaritone().getPathingBehavior().getGoal();
        fallTrace.push(player.getX(), player.getY(), player.getZ(), move.x, move.y, move.z, player.onGround(), (float) player.fallDistance, keys,
                player.getYRot(), player.getXRot(), player.verticalCollision, player.verticalCollisionBelow, player.level().getBlockState(underFeet.set(player.getBlockX(), player.getBlockY() - 1, player.getBlockZ())).getBlock(), movement, movement == null ? null : movement.status(), place, goal, player.input.getClass(),
                player.isBlocking(), hit, attacker == null ? -1 : attacker.getId(), attacker == null ? 0 : attacker.getX(), attacker == null ? 0 : attacker.getY(),
                attacker == null ? 0 : attacker.getZ());
        // ahead of the dump, the tick that dumps is already in it
        String carried = fallTrace.carriedLine(Monitors::fallMoveName);
        if (carried != null) {
            Debug.logInternal(carried);
        }
        boolean nether = WorldHelper.getCurrentDimension() == baritone.api.utils.Dimension.NETHER;
        if (fallTrace.falling(nether, (float) player.fallDistance) | lavaEntered) {
            dumpFall();
        }
        if (lavaEntered) {
            fallTrace.carryOn();
        }
    }

    private void dumpFall() {
        for (int i = 0; i < fallTrace.size(); i++) {
            Debug.logInternal(fallTrace.line(i, Monitors::fallMoveName));
        }
    }

    private static String fallMoveName(Object m) {
        return m instanceof Movement mv ? mv.getClass().getSimpleName() + " " + mv.getSrc().toShortString() + "->" + mv.getDest().toShortString() : "none";
    }

    private void watchCursor() {
        ItemStack carried = StorageHelper.getItemStackInCursorSlot();
        boolean screen = Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?>;
        long now = System.currentTimeMillis();
        if (cursorWatch.tick(!carried.isEmpty(), screen, now)) {
            Debug.logInternal(Lines.cursorStuck(name(carried.getItem()), carried.getCount(), cursorWatch.stuckMs(now) / 1000));
            Trace.dump("monitor:cursor");
        }
    }

    private String vitals(AltoClef mod, LocalPlayer player) {
        ItemStack carried = StorageHelper.getItemStackInCursorSlot();
        ItemStack held = player.getMainHandItem();
        BlockPos at = player.blockPosition();
        TaskChain chain = mod.getTaskRunner().getCurrentTaskChain();
        String task = "";
        if (chain != null) {
            List<Task> tasks = chain.getTasks();
            if (!tasks.isEmpty()) {
                task = String.valueOf(tasks.get(0));
            }
        }
        Vitals.Gamer gamer = mod.getUserTaskChain().getCurrentTask() instanceof GamerTask g ? g.vitals() : null;
        return Vitals.format(player.getHealth(), player.getAbsorptionAmount(), player.getFoodData().getFoodLevel(),
                player.getFoodData().getSaturationLevel(), player.getArmorValue(), held.isEmpty() ? null : name(held.getItem()),
                carried.isEmpty() ? null : name(carried.getItem()), carried.getCount(), at.getX(), at.getY(), at.getZ(),
                WorldHelper.getCurrentDimension().name().toLowerCase(Locale.ROOT), chain == null ? null : chain.getClass().getSimpleName(), task, gamer,
                StandReport.pathWord(mod.getClientBaritone()), standWatch.stillTicks(), player.isInWater());
    }

    private static String name(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}
