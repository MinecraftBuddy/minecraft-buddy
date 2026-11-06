package adris.altoclef.tasks.speedrun.gamer.end;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.MinecraftFacts;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.phases.DragonPhase;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.apache.commons.lang3.ArrayUtils;

// the dragon for a #get order (the dragon's egg, an elytra): #gamer's fight in the End, run on its own. DragonPhase does the
// work as it does in a run (the void crossed from the platform, our drops and armor, end stone, the breath dodged, beds while
// there are beds and the sword after), under a small context of its own instead of the run's engine. done once the exit
// portal stands, which is when the dragon has died (EnderDragonFight.setDragonKilled). never the walk into the exit portal
// that ends a run: the order goes on in the End, to the egg on the pillar or to a gateway
public class SlayDragonTask extends Task implements GamerContext {
    // what the run tracks and keeps for the fight: the exit portal says the dragon is dead, the beds are the weapon
    private static final Block[] TRACKED = ArrayUtils.addAll(new Block[]{Blocks.END_PORTAL}, ItemHelper.itemsToBlocks(ItemHelper.BED));
    private static final Item[] PROTECTED = ArrayUtils.addAll(ItemHelper.BED, Items.ENDER_PEARL, Items.TORCH, Items.ELYTRA,
            Items.DRAGON_EGG, Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.BOW, Items.ARROW);

    private final DragonPhase _phase = new DragonPhase();
    private final GamerConfig _cfg = new GamerConfig();
    private final RunState _state = new RunState();
    private AltoClef _mod;
    private MinecraftFacts _facts;
    private boolean _pushed;
    private double _startedAt = -1;
    private String _failed;

    public SlayDragonTask() {
        _state.phase = GamerPhase.DRAGON;
    }

    // the step a tick of the phase gives, as this task hands it on: nothing once the dragon is dead (the phase's own next step
    // would be the exit portal)
    static Task afterTick(Task phaseStep, boolean dead) {
        return dead ? null : phaseStep;
    }

    public boolean dragonDead() {
        return _state.dragonDead;
    }

    // why the fight cannot go on, from the phase (the dragon killed us too often); null while it can
    public String failed() {
        return _failed;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _mod = mod;
        if (_facts == null) {
            _facts = new MinecraftFacts(mod);
        }
        if (!_pushed) {
            mod.getBehaviour().push();
            mod.getBehaviour().addProtectedItems(PROTECTED);
            mod.getBlockTracker().trackBlock(TRACKED);
            _pushed = true;
        }
        mod.getExtraBaritoneSettings().canWalkOnEndPortal(false);
        _phase.onEnter(mod, this);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (!_facts.refresh()) {
            return null;
        }
        if (_startedAt < 0) {
            _startedAt = _facts.gameTime() / 20.0;
        }
        Task step = _phase.tick(mod, this);
        Task out = afterTick(step, _state.dragonDead);
        if (out == null) {
            walkOnEndPortal(false);
        }
        return out;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _state.dragonDead || _failed != null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        _phase.onExit(mod, this);
        mod.getExtraBaritoneSettings().canWalkOnEndPortal(false);
        // a pause (another chain has the wheel) keeps the tracking and the protection, only a real stop lets go
        if (interruptTask == null && _pushed) {
            _pushed = false;
            try {
                mod.getBlockTracker().stopTracking(TRACKED);
            } catch (RuntimeException e) {
                Debug.logInternal("slay dragon: stopTracking " + e);
            }
            mod.getBehaviour().pop();
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof SlayDragonTask;
    }

    @Override
    protected String toDebugString() {
        String hud = _phase.hudState();
        return "Slay the dragon" + (hud == null ? "" : ": " + hud);
    }

    // ---- the context DragonPhase works in

    @Override
    public GamerConfig cfg() {
        return _cfg;
    }

    @Override
    public RunState state() {
        return _state;
    }

    @Override
    public GamerFacts facts() {
        return _facts;
    }

    @Override
    public int attempt() {
        return 1;
    }

    @Override
    public double secondsInPhase() {
        return _startedAt < 0 || _facts == null ? 0 : _facts.gameTime() / 20.0 - _startedAt;
    }

    // nothing to save: an order's fight starts fresh, and the exit portal tells a dead dragon on sight
    @Override
    public void save() {
    }

    @Override
    public void progress(String what) {
        Debug.logInternal("slay dragon: " + what);
    }

    @Override
    public void fail(String reason) {
        _failed = reason;
        Debug.logMessage("Slay the dragon: " + reason);
    }

    @Override
    public void log(String line) {
        Debug.logMessage(line);
    }

    @Override
    public void walkOnEndPortal(boolean on) {
        if (_mod != null) {
            _mod.getExtraBaritoneSettings().canWalkOnEndPortal(on);
        }
    }
}
