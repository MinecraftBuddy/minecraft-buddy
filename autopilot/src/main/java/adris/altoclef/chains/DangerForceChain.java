package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.entity.KillEntityTask;
import adris.altoclef.tasks.movement.IdleTask;
import adris.altoclef.tasks.movement.RunAwayFromHostilesTask;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.helpers.DangerForce;
import adris.altoclef.util.helpers.DangerForce.Act;
import adris.altoclef.util.helpers.DangerForce.Choice;
import adris.altoclef.util.helpers.DangerForce.Threat;
import adris.altoclef.util.helpers.DangerMeter;
import adris.altoclef.util.helpers.DangerShadow;
import adris.altoclef.util.helpers.Tuning;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

// the buddy's fork: the forced drills (DangerForce, tuning key danger_force; off by default, and then this chain never asks for
// the wheel). while the shadow meter is not calm: 1 fights the meter's top threat with the fight's own task (KillEntityTask,
// the swing through the controller's one gate: one a tick, in reach and in sight, at the start of the tick like every
// swing), 2 runs from the meter's crowd with the run-away task until the meter has been calm 2 s. either way the order
// waits. the mob defense still reads the world every tick (its shield and its aura's swing at what is in contact go on, as
// in any fight), but its fight, run, cover and perch, and the blaze fight's camp, hide and box (the user task) do not get
// the wheel while this holds it
public class DangerForceChain extends SingleTaskChain {
    // over the mob defense's run (80) and creeper step (70), the ghast shelter (55) and the user task with the blaze fight in it
    // (50); under the dragon breath's dodge (85), lava, fire and falls (WorldSurvivalChain, 90 and 100) and the bucket clutch
    // (MLGBucketFallChain, 100): a forced arm is about the mobs, not about the floor
    public static final float PRIORITY = 82;
    // a flee runs to this far from every mob in its crowd: past the meter's shooter range (48), so the meter goes calm before
    // the run is done; a run that is done with the meter not calm starts again from where the crowd is then
    static final double FLEE_DISTANCE = 50;
    // with everyone that far already and the meter still not calm, the buddy stands and looks again this often
    private static final long PROBE_TICKS = 20;

    private final AltoClef _mod;
    private final DangerForce.Rule _rule = new DangerForce.Rule();
    private Act _act = Act.NONE;
    private long _actSince;
    private KillEntityTask _fight;
    private int _fightId = -1;
    private RunAwayFromHostilesTask _run;
    private boolean _runDone;
    private long _probeAt = Long.MIN_VALUE;
    private final IdleTask _stand = new IdleTask();
    private List<Threat> _crowd = List.of();

    public DangerForceChain(TaskRunner runner) {
        super(runner);
        _mod = runner.getMod();
    }

    @Override
    public float getPriority(AltoClef mod) {
        int force = DangerForce.mode(Tuning.get("danger_force", 0));
        if (force == DangerForce.OFF) {
            if (_act != Act.NONE) letGo(mod, "the switch off");
            _rule.reset();
            return Float.NEGATIVE_INFINITY;
        }
        LocalPlayer player = mod.getPlayer();
        if (!AltoClef.inGame() || player == null || mod.getWorld() == null || player.isDeadOrDying()
                || mod.getUserTaskChain() == null || !mod.getUserTaskChain().isActive()) {
            if (_act != Act.NONE) letGo(mod, "no order or no buddy");
            _rule.reset();
            return Float.NEGATIVE_INFINITY;
        }
        long now = mod.getWorld().getGameTime();
        Choice choice = _rule.step(force, DangerShadow.level(), now, DangerShadow.threats());
        switch (choice.act()) {
            case FIGHT: {
                Entity target = mod.getWorld().getEntity(choice.target().id());
                if (!(target instanceof Mob mob) || !mob.isAlive()) {
                    if (_act != Act.NONE) letGo(mod, "the target gone");
                    return Float.NEGATIVE_INFINITY;
                }
                if (_fight == null || _fightId != mob.getId() || _fight.stopped()) {
                    // (the same task object for the whole fight, as the mob defense keeps its own: a new one each tick would
                    // restart its stuck checks)
                    _fight = new KillEntityTask(mob);
                    _fight.useToolsWhenUnarmed();
                    if (_act != Act.FIGHT || _fightId != mob.getId()) {
                        Debug.logInternal("danger: force fight " + choice.target().kind() + " #" + mob.getId() + ", "
                                + DangerMeter.num(mob.distanceTo(player)) + " off, risk " + DangerMeter.num(DangerShadow.last().risk()));
                    }
                    _fightId = mob.getId();
                }
                begin(Act.FIGHT, now);
                setTask(_fight);
                return PRIORITY;
            }
            case FLEE: {
                if (_act != Act.FLEE) {
                    Debug.logInternal("danger: force flee from " + kinds(choice.crowd()) + ", their centre "
                            + DangerMeter.num(Math.sqrt(sq(choice.cx() - player.getX()) + sq(choice.cy() - player.getY()) + sq(choice.cz() - player.getZ())))
                            + " off, risk " + DangerMeter.num(DangerShadow.last().risk()));
                }
                begin(Act.FLEE, now);
                _crowd = choice.crowd();
                if (_run == null || _run.stopped() || _runDone) {
                    _runDone = false;
                    _run = null;
                    if (now >= _probeAt) {
                        RunAwayFromHostilesTask run = new RunAwayFromHostilesTask(FLEE_DISTANCE, true, this::crowd);
                        if (run.alreadySafe(mod)) {
                            _probeAt = now + PROBE_TICKS;
                        } else {
                            _run = run;
                        }
                    }
                }
                setTask(_run != null ? _run : _stand);
                return PRIORITY;
            }
            default:
                if (_act != Act.NONE) letGo(mod, _act == Act.FLEE ? "the meter calm 2 s" : "the meter calm");
                return Float.NEGATIVE_INFINITY;
        }
    }

    private void begin(Act act, long now) {
        if (_act != act) {
            _act = act;
            _actSince = now;
        }
    }

    // the wheel back to the order: one line, and the next arm starts from fresh tasks
    private void letGo(AltoClef mod, String why) {
        long now = mod.getWorld() != null ? mod.getWorld().getGameTime() : _actSince;
        Debug.logInternal("danger: force " + _act.name().toLowerCase(Locale.ROOT) + " off after "
                + DangerMeter.num((now - _actSince) / 20.0) + " s (" + why + "), back to the order");
        _act = Act.NONE;
        _fight = null;
        _fightId = -1;
        _run = null;
        _runDone = false;
        _probeAt = Long.MIN_VALUE;
        _crowd = List.of();
    }

    // the crowd as live entities, for the run's goal (asked on the main thread once a tick)
    private List<Entity> crowd() {
        List<Entity> out = new ArrayList<>();
        if (_mod.getWorld() == null) return out;
        for (Threat th : _crowd) {
            Entity e = _mod.getWorld().getEntity(th.id());
            if (e != null && e.isAlive()) out.add(e);
        }
        return out;
    }

    // "hoglin, wither_skeleton x2"
    private static String kinds(List<Threat> crowd) {
        java.util.Map<String, Integer> n = new java.util.TreeMap<>();
        for (Threat th : crowd) n.merge(th.kind(), 1, Integer::sum);
        StringBuilder sb = new StringBuilder();
        n.forEach((k, v) -> sb.append(sb.length() > 0 ? ", " : "").append(k).append(v > 1 ? " x" + v : ""));
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private static double sq(double v) {
        return v * v;
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        // a run that reached its distance: getPriority starts another while the meter is not calm (a fight's target dead is
        // the meter's to see)
        if (_act == Act.FLEE) _runDone = true;
    }

    @Override
    public boolean isActive() {
        // asked every tick, so a calm meter turning is seen; the answer is no wheel while the switch is off
        return true;
    }

    @Override
    public String getName() {
        return "Danger Force";
    }
}
