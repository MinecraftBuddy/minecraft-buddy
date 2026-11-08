package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.entity.ForcedBowTask;
import adris.altoclef.tasks.entity.KillEntityTask;
import adris.altoclef.tasks.movement.IdleTask;
import adris.altoclef.tasks.movement.RunAwayFromHostilesTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.helpers.DangerForce;
import adris.altoclef.util.helpers.DangerForce.Act;
import adris.altoclef.util.helpers.DangerForce.Choice;
import adris.altoclef.util.helpers.DangerForce.Threat;
import adris.altoclef.util.helpers.DangerMeter;
import adris.altoclef.util.helpers.DangerMeter.Reading;
import adris.altoclef.util.helpers.DangerShadow;
import adris.altoclef.util.helpers.FleeWay;
import adris.altoclef.util.helpers.Tuning;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.BlockState;

// the buddy's fork: the forced drills (DangerForce, tuning key danger_force; off by default, and then this chain never asks for
// the wheel). the first time the shadow meter is not calm after the switch went on, the arm locks and keeps the wheel until
// the switch goes off, calm or not (the count ladder, (b2)): 1 fights the meter's top threat with the fight's own task
// (KillEntityTask, the swing through the controller's one gate: one a tick, in reach and in sight, at the start of the tick
// like every swing), or with the kit's bow a flyer out of the sword's reach with a line to it (ForcedBowTask); 2 runs from
// the remembered crowd with the run-away task, and once everyone is FLEE_DISTANCE off stands and looks again. with nobody
// left the arm stands. the mob defense still reads the world every tick (its shield and its aura's swing at what is in
// contact go on, as in any fight), but its fight, run, cover and perch, and the user task, do not get the wheel while this
// holds it. it acts with or without an order (the ladder holds the order's task, UserTaskChain and danger_hold). while the
// switch is on it logs a line a second (the meter, the foes it counts, the full risk, which arm acted and which chain held
// the wheel), and at the lock one line of the way out on foot from the threats (FleeWay), for the rig's record
public class DangerForceChain extends SingleTaskChain {
    // over the mob defense's run (80) and creeper step (70), the ghast shelter (55) and the user task with the blaze fight in it
    // (50); under the dragon breath's dodge (85), lava, fire and falls (WorldSurvivalChain, 90 and 100) and the bucket clutch
    // (MLGBucketFallChain, 100): a forced arm is about the mobs, not about the floor
    public static final float PRIORITY = 82;
    // a flee runs to this far from every mob in its crowd: past the meter's shooter range (48), so the meter goes calm before
    // the run is done; a run that is done with the meter not calm starts again from where the crowd is then
    static final double FLEE_DISTANCE = FleeWay.MAX;
    // with everyone that far already, the buddy stands and looks again this often
    private static final long PROBE_TICKS = 20;
    // the line a second
    private static final long SECOND = 20;
    // the fight keeps its weapon (the bow or the sword) at least this long before it changes to the other
    private static final long WEAPON_HOLD = 20;

    private final AltoClef _mod;
    private final DangerForce.Rule _rule = new DangerForce.Rule();
    private Act _act = Act.NONE;
    private long _actSince;
    private Task _fight;
    private int _fightId = -1;
    private boolean _bow;
    private long _weaponSince;
    private RunAwayFromHostilesTask _run;
    private boolean _runDone;
    private long _probeAt = Long.MIN_VALUE;
    private final IdleTask _stand = new IdleTask();
    private List<Threat> _crowd = List.of();
    private boolean _wayLogged;
    private long _secondAt = Long.MIN_VALUE;

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
            _wayLogged = false;
            return Float.NEGATIVE_INFINITY;
        }
        LocalPlayer player = mod.getPlayer();
        ClientLevel world = mod.getWorld();
        if (!AltoClef.inGame() || player == null || world == null || player.isDeadOrDying()) {
            if (_act != Act.NONE) letGo(mod, "no buddy");
            _rule.reset();
            _wayLogged = false;
            return Float.NEGATIVE_INFINITY;
        }
        long now = world.getGameTime();
        boolean wasLocked = _rule.locked();
        List<Threat> threats = DangerShadow.threats();
        Choice choice = _rule.step(force, DangerShadow.level(), now, threats, id -> {
            Entity e = world.getEntity(id);
            return e != null && e.isAlive();
        });
        if (_rule.locked() && !wasLocked && !_wayLogged) {
            _wayLogged = true;
            logWay(world, player, threats.isEmpty() ? choice.crowd() : threats);
        }
        float out = act(mod, player, world, choice, now);
        if (now - _secondAt >= SECOND) {
            _secondAt = now;
            Debug.logInternal(secondLine(force, choice.act()));
        }
        return out;
    }

    private float act(AltoClef mod, LocalPlayer player, ClientLevel world, Choice choice, long now) {
        switch (choice.act()) {
            case FIGHT: {
                Entity target = world.getEntity(choice.target().id());
                if (!(target instanceof Mob mob) || !mob.isAlive()) {
                    return stand(now);
                }
                boolean bow = ForcedBowTask.wants(mod, mob);
                boolean newTarget = _fightId != mob.getId();
                if (!newTarget && bow != _bow && now - _weaponSince < WEAPON_HOLD) bow = _bow;
                if (_fight == null || newTarget || bow != _bow || _fight.stopped()) {
                    // (the same task object for the whole fight, as the mob defense keeps its own: a new one each tick would
                    // restart its stuck checks)
                    if (bow) {
                        _fight = new ForcedBowTask(mob);
                    } else {
                        KillEntityTask kill = new KillEntityTask(mob);
                        kill.useToolsWhenUnarmed();
                        _fight = kill;
                    }
                    if (bow != _bow || newTarget) _weaponSince = now;
                    if (_act != Act.FIGHT || newTarget || bow != _bow) {
                        Debug.logInternal("danger: force fight " + choice.target().kind() + " #" + mob.getId() + ", "
                                + DangerMeter.num(mob.distanceTo(player)) + " off, " + (bow ? "bow" : "melee") + ", risk "
                                + DangerMeter.num(DangerShadow.last().risk()));
                    }
                    _fightId = mob.getId();
                    _bow = bow;
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
            case STAND:
                return stand(now);
            default:
                return Float.NEGATIVE_INFINITY;
        }
    }

    // locked with nobody to act on: the wheel held standing
    private float stand(long now) {
        if (_act != Act.STAND) {
            Debug.logInternal("danger: force " + _act.name().toLowerCase(Locale.ROOT) + " stands, nobody left to act on");
        }
        begin(Act.STAND, now);
        _fight = null;
        _fightId = -1;
        _run = null;
        setTask(_stand);
        return PRIORITY;
    }

    private void begin(Act act, long now) {
        if (_act != act) {
            _act = act;
            _actSince = now;
        }
    }

    // the wheel back: one line, and the next arm starts from fresh tasks
    private void letGo(AltoClef mod, String why) {
        long now = mod.getWorld() != null ? mod.getWorld().getGameTime() : _actSince;
        Debug.logInternal("danger: force " + _act.name().toLowerCase(Locale.ROOT) + " off after "
                + DangerMeter.num((now - _actSince) / 20.0) + " s (" + why + ")");
        _act = Act.NONE;
        _fight = null;
        _fightId = -1;
        _bow = false;
        _run = null;
        _runDone = false;
        _probeAt = Long.MIN_VALUE;
        _crowd = List.of();
    }

    // danger: second, arm <fight|flee>, act <fight|flee|stand|none>, wheel <chain>, level <l>, risk <r>, T <t>, push <p>,
    // K <k>, D <d>, hp <hp>, dps <dps>, bow <bow dps>, foes <n> (<kind dist wWANT>, ...), full <risk>
    private String secondLine(int force, Act act) {
        Reading r = DangerShadow.last();
        DangerShadow.Detail d = DangerShadow.detail();
        TaskChain wheel = _mod.getTaskRunner() == null || !_mod.getTaskRunner().isActive() ? null : _mod.getTaskRunner().getCurrentTaskChain();
        return "danger: second, arm " + (force == DangerForce.FIGHT ? "fight" : "flee") + ", act " + act.name().toLowerCase(Locale.ROOT)
                + ", wheel " + (wheel == null ? "none" : wheel.getClass().getSimpleName()) + ", level " + DangerShadow.level().word()
                + ", risk " + DangerMeter.num(r.risk()) + ", T " + DangerMeter.num(r.t()) + ", push " + DangerMeter.num(r.push())
                + ", K " + DangerMeter.num(r.k()) + ", D " + DangerMeter.num(r.d()) + ", hp " + DangerMeter.num(r.hp())
                + ", dps " + (d == null ? "-" : DangerMeter.num(d.dps())) + ", bow " + (d == null ? "-" : DangerMeter.num(d.bowDps()))
                + ", foes " + (d == null ? 0 : d.foes()) + " (" + (d == null ? "" : d.counted()) + "), full " + (d == null ? "-" : DangerMeter.num(d.fullRisk()));
    }

    // danger: way from the threats' centre <dist> off, best <blocks> <end> (<turn>), <turn> <blocks> <end>, ...
    private void logWay(ClientLevel world, LocalPlayer player, List<Threat> threats) {
        if (threats.isEmpty()) return;
        double[] c = DangerForce.centre(threats);
        FleeWay.Way way = FleeWay.find((x, y, z) -> cell(world, x, y, z), player.getX(), player.getBlockY(), player.getZ(), c[0], c[2]);
        Debug.logInternal("danger: way from the threats' centre " + DangerMeter.num(Math.sqrt(sq(c[0] - player.getX()) + sq(c[2] - player.getZ())))
                + " off, " + way.line());
    }

    private static FleeWay.Cell cell(ClientLevel world, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        if (!world.hasChunkAt(pos)) return FleeWay.Cell.UNLOADED;
        BlockState state = world.getBlockState(pos);
        if (state.getFluidState().is(FluidTags.LAVA)) return FleeWay.Cell.LAVA;
        if (!state.getCollisionShape(world, pos).isEmpty()) return FleeWay.Cell.SOLID;
        if (state.getFluidState().is(FluidTags.WATER)) return FleeWay.Cell.WATER;
        return FleeWay.Cell.OPEN;
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
        // a run that reached its distance: getPriority starts another (or stands and probes) while the arm holds; a fight's
        // target dead is the rule's to see
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
