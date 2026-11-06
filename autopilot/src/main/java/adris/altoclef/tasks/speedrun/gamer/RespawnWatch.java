package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.Subscription;
import adris.altoclef.eventbus.events.SystemChatEvent;
import adris.altoclef.tasks.speedrun.gamer.tasks.NightSleepTask;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BedBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

// the world half of Respawn: listens for the server's "Respawn point set" / "no home bed" lines and writes the answer into
// the run state, and takes it back when the bed is seen gone
public class RespawnWatch {
    private static final int CHECK_EVERY_TICKS = 20;

    private final RunState state;
    private final Supplier<String> dimension;
    private final Runnable save;
    private Subscription<SystemChatEvent> chat;
    private int ticks;

    public RespawnWatch(RunState state, Supplier<String> dimension, Runnable save) {
        this.state = state;
        this.dimension = dimension;
        this.save = save;
    }

    public void start() {
        Respawn.bind(state);
        if (chat == null) {
            chat = EventBus.subscribe(SystemChatEvent.class, evt -> onLine(Respawn.read(evt.message)));
        }
    }

    public void stop() {
        Respawn.unbind();
        if (chat != null) {
            EventBus.unsubscribe(chat);
            chat = null;
        }
    }

    private void onLine(Respawn.Line line) {
        if (line == Respawn.Line.OTHER) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        ClientLevel world = Minecraft.getInstance().level;
        if (line == Respawn.Line.LOST) {
            Respawn.lose(state);
            save.run();
            return;
        }
        if (player == null || world == null) {
            return;
        }
        RunState.Pos foot = clicked(player, world);
        if (foot == null) {
            // not a bed (an anchor), and the spawn moved off the one we knew
            Respawn.lose(state);
        } else {
            Respawn.set(state, foot, dimension.get());
        }
        save.run();
    }

    // the bed we are in, else the closest one in click reach
    private static RunState.Pos clicked(LocalPlayer player, ClientLevel world) {
        BlockPos sleeping = player.getSleepingPos().orElse(null);
        if (sleeping != null) {
            return foot(world, sleeping);
        }
        BlockPos at = player.blockPosition();
        int reach = (int) Respawn.CLICK_REACH;
        List<RunState.Pos> beds = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-reach, -3, -reach), at.offset(reach, 4, reach))) {
            if (world.getBlockState(p).getBlock() instanceof BedBlock) {
                RunState.Pos foot = foot(world, p);
                if (foot != null && !beds.contains(foot)) {
                    beds.add(foot);
                }
            }
        }
        var eye = player.getEyePosition();
        return Respawn.nearest(beds, eye.x, eye.y, eye.z);
    }

    private static RunState.Pos foot(ClientLevel world, BlockPos any) {
        BlockPos foot = NightSleepTask.footOf(any, world.getBlockState(any));
        return foot == null ? null : new RunState.Pos(foot.getX(), foot.getY(), foot.getZ());
    }

    // once a second: our respawn bed is in a loaded chunk we can see and nothing stands there any more
    public void tick(ClientLevel world) {
        RunState.Pos at = state.respawnPos;
        if (at == null || ++ticks % CHECK_EVERY_TICKS != 0 || world == null) {
            return;
        }
        BlockPos pos = new BlockPos(at.x, at.y, at.z);
        boolean loaded = world.hasChunkAt(pos);
        if (Respawn.stale(state.respawnDim, dimension.get(), loaded, loaded && world.getBlockState(pos).getBlock() instanceof BedBlock)) {
            Respawn.lose(state);
            save.run();
        }
    }
}
