package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.speedrun.gamer.EyeMath;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.PhaseHandler;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasksystem.Task;
import net.minecraft.world.item.Items;

import java.util.Optional;

// rods -> powder -> eyes in the 2x2 grid, any dimension. asks for exactly what the bag can make (never more, the catalogue
// would go and hunt for the missing pearl), powder first, then the eyes. the engine's protected items keep rods, pearls and
// powder out of the throwaway policy
public class EyesPhase implements PhaseHandler {
    private Task craft;
    private String craftKey = "";
    private String hudState;

    @Override
    public GamerPhase phase() {
        return GamerPhase.EYES;
    }

    @Override
    public String hud() {
        return GamerPhase.EYES.hud();
    }

    @Override
    public String hudState() {
        return hudState;
    }

    @Override
    public boolean isDone(GamerFacts facts, RunState state, GamerConfig cfg) {
        return EyeMath.eyesDone(facts, cfg, state);
    }

    // not even the floor in the bag (a death, a lost chest): back to the nether once, the stronghold phases have the same rule
    @Override
    public Optional<GamerPhase> regressTo(GamerFacts facts, RunState state, GamerConfig cfg) {
        // at home without the kit (a death): plan the kit first, the nether trip comes after it
        Optional<GamerPhase> gear = NetherRegress.fromOverworld(facts, cfg);
        if (gear.isPresent()) {
            return gear;
        }
        if (state.netherRevisits < 1 && EyeMath.potentialEyes(facts) < EyeMath.floorGoal(cfg, state.framesFilled)) {
            return Optional.of(GamerPhase.NETHER);
        }
        return Optional.empty();
    }

    @Override
    public void onEnter(AltoClef mod, GamerContext ctx) {
        craft = null;
        craftKey = "";
        hudState = null;
    }

    @Override
    public Task tick(AltoClef mod, GamerContext ctx) {
        GamerFacts f = ctx.facts();
        int eyes = EyeMath.eyes(f);
        int want = Math.min(EyeMath.targetGoal(ctx.cfg(), ctx.state()), eyes + EyeMath.craftableNow(f));
        int powderNeeded = want - eyes;
        if (powderNeeded <= 0) {
            hudState = null;
            return null;
        }
        if (f.count(Items.BLAZE_POWDER) < powderNeeded) {
            hudState = "Grinding Blaze Rods into powder";
            return cached("powder" + powderNeeded, Items.BLAZE_POWDER, powderNeeded);
        }
        hudState = "Crafting Eyes of Ender";
        return cached("eye" + want, Items.ENDER_EYE, want);
    }

    // the same task object for the same ask, so it keeps its state while the inventory changes under it
    private Task cached(String key, net.minecraft.world.item.Item item, int count) {
        if (craft == null || !key.equals(craftKey)) {
            craft = TaskCatalogue.getItemTask(item, count);
            craftKey = key;
        }
        return craft;
    }
}
