package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.time.TimerGame;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

// "come here" for a whispered come: walks up to a player and is done, unlike FollowPlayerTask which keeps shadowing them
// forever. same legs as follow (GetToEntityTask while they are loaded, their last known spot when they are not)
public class ComeToPlayerTask extends Task {

    // close enough to hand them things. GetToEntityTask is asked for 2, so this is reachable
    static final double ARRIVED_DISTANCE = 3;
    // somebody who keeps running away, or is on an island, is not worth walking after all day
    private static final double GIVE_UP_SECONDS = 120;

    private final String _playerName;
    private final TimerGame _giveUp = new TimerGame(GIVE_UP_SECONDS);
    private boolean _gaveUp;

    public ComeToPlayerTask(String playerName) {
        _playerName = playerName;
        // a timer starts at zero, which is "elapsed" for anyone connected longer than the interval. the clock runs from
        // the command, and onStart comes again after every eat or fight that took the wheel, so it can't live there
        _giveUp.reset();
    }

    @Override
    protected void onStart(AltoClef mod) {
        // the clock runs from when the command was given, onStart comes again after every eat or fight that took the wheel
        _gaveUp = false;
    }

    // straight from the world: the entity tracker only refreshes while a task is running, and this is asked on the very
    // first tick of one
    private Optional<Player> find(AltoClef mod) {
        for (Player p : mod.getWorld().players()) {
            if (p.getName().getString().equals(_playerName)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_giveUp.elapsed()) {
            mod.logWarning("Couldn't get to \"" + _playerName + "\", giving up.");
            _gaveUp = true;
            return null;
        }
        Optional<Player> player = find(mod);
        if (player.isPresent()) {
            return new GetToEntityTask(player.get(), 2);
        }
        Optional<Vec3> last = mod.getEntityTracker().getPlayerMostRecentPosition(_playerName);
        if (last.isEmpty()) {
            mod.logWarning("Can't see \"" + _playerName + "\" anymore.");
            _gaveUp = true;
            return null;
        }
        Vec3 spot = last.get();
        if (spot.closerThan(mod.getPlayer().position(), 2)) {
            // we are where we last saw them and they are not in sight, there is nothing left to walk to
            mod.logWarning("Lost sight of \"" + _playerName + "\".");
            _gaveUp = true;
            return null;
        }
        return new GetToBlockTask(new BlockPos((int) Math.floor(spot.x), (int) Math.floor(spot.y), (int) Math.floor(spot.z)), false);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        if (_gaveUp) {
            return true;
        }
        Optional<Player> player = find(mod);
        return player.isPresent() && mod.getPlayer().closerThan(player.get(), ARRIVED_DISTANCE);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof ComeToPlayerTask task && task._playerName.equals(_playerName);
    }

    @Override
    protected String toHudString() {
        return "Coming to " + _playerName;
    }

    @Override
    protected String toDebugString() {
        return "Coming to player " + _playerName;
    }
}
