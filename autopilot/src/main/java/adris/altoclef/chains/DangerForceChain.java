package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.entity.ForcedBowTask;
import adris.altoclef.tasks.entity.KillEntityTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
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
import adris.altoclef.util.helpers.FireStep;
import adris.altoclef.util.helpers.FleeWay;
import adris.altoclef.util.helpers.Tuning;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
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
// the wheel), and at the lock one line of the way out on foot from the threats (FleeWay), for the rig's record.
// the fight keeps its place but plays it as a player would (9 Nov 2026, the ladder's first block: 13 of 14 fights died
// standing in the blazes' fire with the bow drawn and never loosed): it steps out of fire to the nearest cell that is not
// burning within three blocks and fights on from there (FireStep), and below MEAL_HP with the food bar short of full it
// lets go of the bow for a meal from the hotbar and goes back to the fight; the rest of the time the hand is the fight's
// and the food chain stays off it (holdsTheHand), so no bite fights the bow for the hotbar slot. with danger_hold on and the
// switch on, the wheel is held standing before the lock too (after a death, before the next summon), where the mob
// defense used to hold it at nothing
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
    // the fight keeps its weapon (the bow or the sword) at least this long before it changes to the other, and keeps the bow
    // this long through a lost line (a knock back, the blaze behind an edge) before it goes for the sword
    private static final long WEAPON_HOLD = 20;
    // before the lock, with danger_hold and the switch on: the wheel held standing, over the mob defense's idle 0 and under
    // every chain that acts
    static final float HOLD_STAND = 1;
    // a step out of the fire is given up after this long, and the next one waits this long after the last
    private static final long STEP_TICKS = 60;
    private static final long STEP_AGAIN = 20;
    // a meal below this health with the food bar short of full; a meal that has not begun (or gone on) this long is given
    // up, and the next one waits this long
    static final float MEAL_HP = 10;
    private static final long MEAL_WAIT = 60;
    private static final long MEAL_AGAIN = 100;

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
    // the bow's line lost since (Long.MIN_VALUE: not lost)
    private long _bowLostAt = Long.MIN_VALUE;
    private GetToBlockTask _step;
    private long _stepSince;
    private long _stepUntil;
    private long _stepNextAt = Long.MIN_VALUE;
    private boolean _meal;
    private long _mealSince;
    private long _mealChewAt = Long.MIN_VALUE;
    private long _mealNextAt = Long.MIN_VALUE;
    private boolean _holdLogged;
    // the forced fight has the hand this tick (FoodChain asks, a tick late: it is asked before this chain)
    private static boolean _hand;

    public DangerForceChain(TaskRunner runner) {
        super(runner);
        _mod = runner.getMod();
    }

    // the forced fight holds the hand: no meal starts and none goes on (FoodChain.eatingBlocked); off outside the drills
    public static boolean holdsTheHand() {
        return _hand;
    }

    @Override
    public float getPriority(AltoClef mod) {
        _hand = false;
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
        if (choice.act() == Act.NONE && UserTaskChain.held()) {
            // the switch on, not locked yet (after a death, before the summon), and the order held: nothing else would act,
            // and the mob defense held the wheel at nothing (its warning, three times in three minutes)
            if (!_holdLogged) {
                _holdLogged = true;
                Debug.logInternal("danger: force " + (force == DangerForce.FIGHT ? "fight" : "flee") + " stands, waiting for the meter");
            }
            setTask(_stand);
            out = HOLD_STAND;
        } else {
            _holdLogged = false;
        }
        // (_secondAt starts at Long.MIN_VALUE, and now - Long.MIN_VALUE overflows to a negative number: the first test
        // first, or no second line is ever written)
        if (_secondAt == Long.MIN_VALUE || now - _secondAt >= SECOND) {
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
                    endStep(now, "nobody to fight");
                    _meal = false;
                    return stand(now);
                }
                boolean wasFight = _act == Act.FIGHT;
                begin(Act.FIGHT, now);
                // the hand is the fight's, a step out of the fire's included; only the fight's own meal lets go of it
                _hand = true;
                // out of the fire first, then a meal, then the fight
                if (stepOutOfFire(mod, player, world, now) || meal(mod, player, now)) {
                    return PRIORITY;
                }
                boolean wants = ForcedBowTask.wants(mod, mob);
                boolean bow = wants;
                if (wants) {
                    _bowLostAt = Long.MIN_VALUE;
                } else if (_bow && _fight instanceof ForcedBowTask && !_fight.stopped() && ForcedBowTask.flyer(mob) && ForcedBowTask.canShoot(mod)) {
                    if (_bowLostAt == Long.MIN_VALUE) _bowLostAt = now;
                    if (now - _bowLostAt < WEAPON_HOLD) bow = true;
                }
                boolean newTarget = _fightId != mob.getId();
                if (!newTarget && bow != _bow && now - _weaponSince < WEAPON_HOLD) bow = _bow;
                if (!bow) _bowLostAt = Long.MIN_VALUE;
                if (bow && _bow && _fight instanceof ForcedBowTask shooting && !shooting.stopped()) {
                    // the same bow on another mob: the draw under way goes on, the aim goes over (a new task let go of it)
                    if (newTarget) {
                        shooting.retarget(mob);
                        _weaponSince = now;
                        logFight(choice, mob, player, true);
                        _fightId = mob.getId();
                    }
                } else if (_fight == null || newTarget || bow != _bow || _fight.stopped()) {
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
                    if (!wasFight || newTarget || bow != _bow) logFight(choice, mob, player, bow);
                    _fightId = mob.getId();
                    _bow = bow;
                }
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

    // "danger: force fight blaze #id, 7.0 off, bow, risk 0.3"
    private void logFight(Choice choice, Mob mob, LocalPlayer player, boolean bow) {
        Debug.logInternal("danger: force fight " + choice.target().kind() + " #" + mob.getId() + ", "
                + DangerMeter.num(mob.distanceTo(player)) + " off, " + (bow ? "bow" : "melee") + ", risk "
                + DangerMeter.num(DangerShadow.last().risk()));
    }

    // fire in a cell the body is in (a burning block under the feet is fire in the feet's cell): a step to the nearest cell
    // out of it within FireStep.RADIUS, the fight on from there. true while the step holds the wheel
    private boolean stepOutOfFire(AltoClef mod, LocalPlayer player, ClientLevel world, long now) {
        if (_step != null) {
            boolean done = _step.stopped() || _step.isFinished(mod);
            if (!done && now < _stepUntil) {
                setTask(_step);
                return true;
            }
            endStep(now, done ? (inFire(world, player) ? "there, still in fire" : "out of the fire") : "the step taking too long");
        }
        if (now < _stepNextAt || !inFire(world, player)) return false;
        FireStep.Spot spot = FireStep.find((x, y, z) -> fireCell(world, x, y, z), player.getBlockX(), player.getBlockY(), player.getBlockZ());
        if (spot == null) {
            _stepNextAt = now + STEP_AGAIN;
            Debug.logInternal("danger: force fight in fire, no cell out of it within " + FireStep.RADIUS + " blocks");
            return false;
        }
        _step = new GetToBlockTask(new BlockPos(spot.x(), spot.y(), spot.z()));
        _stepSince = now;
        _stepUntil = now + STEP_TICKS;
        Debug.logInternal("danger: force fight steps out of the fire to " + spot.x() + " " + spot.y() + " " + spot.z() + ", "
                + DangerMeter.num(spot.blocks()) + " blocks" + (spot.byFire() ? ", beside fire" : ""));
        setTask(_step);
        return true;
    }

    private void endStep(long now, String why) {
        if (_step == null) return;
        Debug.logInternal("danger: force fight step over after " + DangerMeter.num((now - _stepSince) / 20.0) + " s (" + why + ")");
        _step = null;
        _stepNextAt = now + STEP_AGAIN;
    }

    // below MEAL_HP with the food bar short of full and the food chain's pick in the hotbar (a player eats from the hotbar
    // in a fight; the bag only with E open): the bow goes down, the food chain eats, and the fight goes on once the bar is
    // full or the health is back. a meal that does not begin (the shield up for a fireball, the food chain's own rules) is
    // given up after MEAL_WAIT. true while the meal holds the wheel
    private boolean meal(AltoClef mod, LocalPlayer player, long now) {
        float hp = player.getHealth();
        int food = player.getFoodData().getFoodLevel();
        boolean want = hp < MEAL_HP && food < 20 && hotbarFood(mod, player);
        boolean chewing = player.isUsingItem() && player.getUseItem().has(DataComponents.CONSUMABLE);
        if (_meal) {
            if (chewing) {
                _mealChewAt = now;
            } else if (!want) {
                Debug.logInternal("danger: force fight back to the fight after the meal, hp " + DangerMeter.num(hp) + ", food " + food);
                _meal = false;
                return false;
            } else if (now - Math.max(_mealSince, _mealChewAt) > MEAL_WAIT) {
                Debug.logInternal("danger: force fight meal did not go on for " + DangerMeter.num(MEAL_WAIT / 20.0) + " s, back to the fight, hp "
                        + DangerMeter.num(hp) + ", food " + food);
                _meal = false;
                _mealNextAt = now + MEAL_AGAIN;
                return false;
            }
            _hand = false;
            setTask(_stand);
            return true;
        }
        if (!want || now < _mealNextAt) return false;
        _meal = true;
        _mealSince = now;
        _mealChewAt = Long.MIN_VALUE;
        Debug.logInternal("danger: force fight stops for a meal, hp " + DangerMeter.num(hp) + ", food " + food);
        _hand = false;
        setTask(_stand);
        return true;
    }

    // the food chain's pick is in the hotbar
    private static boolean hotbarFood(AltoClef mod, LocalPlayer player) {
        java.util.Optional<Item> pick = mod.getFoodChain().pickedFood();
        if (pick.isEmpty()) return false;
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getItem(i).is(pick.get())) return true;
        }
        return false;
    }

    // fire in a cell the player's body is in
    private static boolean inFire(ClientLevel world, LocalPlayer player) {
        net.minecraft.world.phys.AABB box = player.getBoundingBox();
        for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX); x++) {
            for (int y = (int) Math.floor(box.minY); y <= (int) Math.floor(box.maxY); y++) {
                for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ); z++) {
                    if (world.getBlockState(new BlockPos(x, y, z)).getBlock() instanceof BaseFireBlock) return true;
                }
            }
        }
        return false;
    }

    private static FireStep.Cell fireCell(ClientLevel world, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        if (!world.hasChunkAt(pos)) return FireStep.Cell.UNLOADED;
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof BaseFireBlock || state.getBlock() instanceof CampfireBlock || state.is(Blocks.MAGMA_BLOCK)
                || state.getFluidState().is(FluidTags.LAVA)) {
            return FireStep.Cell.HOT;
        }
        return state.getCollisionShape(world, pos).isEmpty() ? FireStep.Cell.OPEN : FireStep.Cell.SOLID;
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
        _bowLostAt = Long.MIN_VALUE;
        _step = null;
        _stepNextAt = Long.MIN_VALUE;
        _meal = false;
        _mealNextAt = Long.MIN_VALUE;
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
