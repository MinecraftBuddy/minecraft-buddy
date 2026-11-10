package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.entity.KillEntityTask;
import adris.altoclef.tasks.movement.CommittedRunTask;
import adris.altoclef.tasks.movement.HealUpTask;
import adris.altoclef.tasks.movement.IdleTask;
import adris.altoclef.tasks.speedrun.gamer.EndermanNook;
import adris.altoclef.tasks.speedrun.gamer.tasks.PerchTask;
import adris.altoclef.util.helpers.PearlHuntHook;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Enderman;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.EntityTracker;
import adris.altoclef.tasks.resources.CollectBlazeRodsTask;
import adris.altoclef.util.helpers.BlazeFightRules;
import adris.altoclef.util.helpers.BoxRules;
import adris.altoclef.util.helpers.CombatCommit;
import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatLog;
import adris.altoclef.util.helpers.CombatRules;
import adris.altoclef.util.helpers.RimHold;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.FightNames;
import adris.altoclef.util.helpers.FoeRules;
import adris.altoclef.util.helpers.LavaShore;
import adris.altoclef.util.helpers.MobFooting;
import adris.altoclef.util.helpers.PerchRules;
import baritone.pathing.movement.CliffAvoidance;
import adris.altoclef.util.helpers.MobReachability;
import adris.altoclef.util.helpers.Provocations;
import adris.altoclef.util.helpers.ReachRules;
import adris.altoclef.util.helpers.RecoverHold;
import adris.altoclef.util.helpers.RunZones;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WeaponPick;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Dimension;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// the world half of CombatCommit, for every dimension. MobDefenseChain calls tick() once per game tick (that is the only
// place the machine moves) and then asks what to hold the wheel with. nothing in here hands the wheel back: that is the
// chain's call, and it only does it when the machine says NONE. who counts as a foe in which dimension is FoeRules
final class CombatBrain {

    // the steady priorities of a commitment. above the user task (50) and the food chain (55), same numbers the old
    // kill (65) and flee (80) branches used, so nothing that was ordered against them moved
    static final float FIGHT_PRIORITY = 65;
    static final float RUN_PRIORITY = 80;
    // the heal-up hold: over the user task (50) so it cannot walk us back in, under food collect (55) so a bot with no food
    // can still go and get some
    static final float RECOVER_PRIORITY = 53;
    // the tracker's hostile list does not reach past 16 anyway (it keeps mobs closer than 16), and the warden's boom
    // counts out to 15 (CombatCommit.BOOM_RANGE), so this stays 16 even though a run now calls 12 clear
    private static final double FOE_RANGE = CombatCommit.FOE_RANGE;

    private final CombatCommit _commit = new CombatCommit();
    // this tick's angry hostiles as entities, nearest first (the run goal steers around them)
    private List<Mob> _foeMobs = List.of();
    // every blaze the tracker has, calm or not (crowd() keeps the live ones in range). the hostile list only lists the charged ones, so a run steered back into the ones
    // that were just waiting for it to turn around
    private List<Mob> _crowdBlazes = List.of();
    // who the aura may swing at: the fight target and anything in contact that has hit us
    private final Set<Integer> _swingAt = new HashSet<>();
    private CombatRules.Stance _stance = CombatRules.Stance.CALM;
    private int _meleeNear;
    private int _meleeAround;
    private boolean _shotAt;
    private long _shotUntil = Long.MIN_VALUE / 2;
    // a melee fight with the target close, the shield is up for its swing (CombatRules.fightShield)
    private boolean _fightShield;
    // a ledge over a lethal drop with a melee foe close: the shield stops the knock that would send us over
    private boolean _rim;
    // a run on a rim with a knocker on us: the shield goes up and nothing swings (KillAura.rimGuard), capped by _rimHold
    private int _rimGuardId = -1;
    private final RimHold _rimHold = new RimHold();
    private static final long STANCE_LOG_GAP = 20;
    private CombatRules.Stance _loggedStance = CombatRules.Stance.CALM;
    private long _stanceLogAt = Long.MIN_VALUE / 2;
    private float _lastHealth = -1;
    private DamageSource _lastSource;
    private long _lastHurtAt = Long.MIN_VALUE / 2;
    // the blaze or skeleton whose projectile last hit us, and when: the hold runs from it (RecoverHold.rerunSource)
    private DamageSource _shotSeen;
    private Mob _shooter;
    private long _shooterAt = Long.MIN_VALUE / 2;
    // projectile hits taken, counted every tick (rangedSource only runs in the hold) for the perch's "a shooter found us up here"
    private DamageSource _shotCounted;
    private int _shots;
    private long _shotTick = PerchRules.Facts.NEVER;
    // the perch task is the same object for the whole perch, a new one each tick would pillar from scratch every time
    private PerchTask _perch;
    private long _perchStart = Long.MIN_VALUE;
    private CommittedRunTask _run;
    private KillEntityTask _fight;
    private final HealUpTask _healUp = new HealUpTask();
    // the run into a blaze fight's corner (BlazeShelter): round by a standing pillbox's door, and the committed run once it
    // had BoxWay.RUN_TICKS
    private final adris.altoclef.util.helpers.BoxWay _shelterWay = new adris.altoclef.util.helpers.BoxWay();
    private net.minecraft.core.BlockPos _shelterRunTo;
    private final adris.altoclef.util.helpers.FireOnTheWay _shelterFire = new adris.altoclef.util.helpers.FireOnTheWay();
    // the run's cell from the model of where to stand (tuning stand_model), the nether only
    private final adris.altoclef.util.helpers.StandPick _stand = new adris.altoclef.util.helpers.StandPick("combat run");
    private final IdleTask _splitHold = new IdleTask();
    private int _fightId = -1;
    private String _fightName = FightNames.UNKNOWN;
    private final FightNames _names = new FightNames();
    // the enderman fight target and where it last stood alive, for the one "gone" line: a kill and a blink out of the client
    // world both end the fight as FIGHT_DEAD, and only one of them drops a pearl
    private int _enderId = -1;
    private Vec3 _enderLastPos;
    private boolean _enderGoneSaid;
    private LocalPlayer _player;
    private Dimension _dimension;
    // when the last run ended, for the rods task: it waits to heal before it walks back to whatever we ran from
    private long _lastRunEnd = Long.MIN_VALUE / 2;
    // a run happened and nothing has settled since: the end is stamped when the machine is back to nothing, so a run that
    // turned into a fight counts from when the fight is over
    private boolean _ranSince;

    // one line on the first tick an enderman target stops being a target. the client keeps a dead one around through its
    // death animation with hp 0, one that teleported out of tracking range is just not there anymore
    private void noteEnderGone(Dimension dimension, Entity entity, boolean alive, LocalPlayer player) {
        int id = _commit.targetId();
        if (alive) {
            // (anything else as the target forgets the old enderman, its id could come back as somebody else)
            _enderId = entity instanceof Enderman ? id : -1;
            _enderLastPos = entity.position();
            _enderGoneSaid = false;
            return;
        }
        if (id != _enderId || _enderGoneSaid || _enderLastPos == null) return;
        _enderGoneSaid = true;
        Vec3 at = entity != null ? entity.position() : _enderLastPos;
        String why = entity == null ? "left the client world"
                : entity instanceof Enderman man && man.getHealth() <= 0 ? "died" : "removed with hp left";
        Debug.logInternal("combat: [" + dimension.name().toLowerCase(java.util.Locale.ROOT) + "] enderman #" + id + " gone: " + why
                + ", last at " + Math.round(at.x) + " " + Math.round(at.y) + " " + Math.round(at.z) + ", " + Math.round(at.distanceTo(player.position()))
                + " blocks off, dy " + Math.round(at.y - player.getY()));
    }

    long lastRunEnd() {
        return _lastRunEnd;
    }

    Mode mode() {
        return _commit.mode();
    }

    CombatCommit commit() {
        return _commit;
    }

    // "zombie", for the card's red strip. "it" until a fight has named its target
    String fightName() {
        return _fightName;
    }

    CombatRules.Stance stance() {
        return _stance;
    }

    boolean shotAt() {
        return _shotAt;
    }

    int meleeNear() {
        return _meleeNear;
    }

    int meleeAround() {
        return _meleeAround;
    }

    boolean swingsAt(int id) {
        return _swingAt.contains(id);
    }

    // the aura is all feet and no hands while running. the shield is for a committed melee fight (fightShield) or a
    // boxed in one
    boolean auraKiting() {
        return _commit.mode() == Mode.RUN;
    }

    // in a committed fight the flee line was already the machine's call (a cornered one never bails), so the aura keeps its
    // hands and its shield under it
    boolean auraFighting() {
        return _commit.mode() == Mode.FIGHT && !_commit.perching();
    }

    // the id the aura should swing at while it is in reach, -1 when nothing is committed
    int fightTarget() {
        return _commit.mode() == Mode.FIGHT && !_commit.perching() ? _commit.targetId() : -1;
    }

    // the perch task swings for itself and nothing shields: the top of the pillar reads as a ledge but nothing can knock us off it
    boolean perching() {
        return _commit.perching();
    }

    // the fight has a path going and the target is not in contact yet, see CombatRules.fightShieldDown
    private boolean _fightWalking;
    private boolean _walkNoArrowShield;

    // same reason, for the chain's arrow shield: a volley is three shots, and raising it for each one is the walk gone. a skeleton
    // keeps it, the walk up to one under arrows is the point of that shield. a patrol does not: a raise is 5 ticks, a bolt from
    // this close lands in 2 to 4, and with five of them shooting the shield would just slow the walk and block nothing
    boolean walkNoArrowShield() {
        return _walkNoArrowShield;
    }

    boolean auraShield() {
        if (_commit.perching() || _fightWalking) return false;
        return _commit.mode() == Mode.FIGHT && (_commit.cornered() || _fightShield) || _rim || blazePocket();
    }

    // the buddy's fork: the rods task fights blazes come round into a pocket, and wants the shield up between swings
    private static boolean blazePocket() {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        return level != null && level.getGameTime() <= adris.altoclef.util.helpers.BlazeFightRules.pocketShieldUntil;
    }

    int auraRimGuard() {
        return _rimGuardId;
    }

    float holdPriority() {
        return switch (_commit.mode()) {
            case FIGHT -> FIGHT_PRIORITY;
            case RUN -> RUN_PRIORITY;
            default -> _commit.recovering() ? RECOVER_PRIORITY : 0;
        };
    }

    // the heal-up hold is on (a re-run from it counts too, it comes back to the hold)
    boolean recovering() {
        return _commit.recovering();
    }

    void dropHold() {
        _commit.recoverHold().reset();
    }

    void reset() {
        _commit.reset();
        _lastRunEnd = Long.MIN_VALUE / 2;
        _ranSince = false;
        _foeMobs = List.of();
        _crowdBlazes = List.of();
        _swingAt.clear();
        _stance = CombatRules.Stance.CALM;
        _meleeNear = 0;
        _meleeAround = 0;
        _shotAt = false;
        _shotUntil = Long.MIN_VALUE / 2;
        _fightShield = false;
        _rim = false;
        _rimGuardId = -1;
        _rimHold.reset();
        _loggedStance = CombatRules.Stance.CALM;
        _stanceLogAt = Long.MIN_VALUE / 2;
        _lastHealth = -1;
        _lastSource = null;
        _lastHurtAt = Long.MIN_VALUE / 2;
        _shotSeen = null;
        _shooter = null;
        _shooterAt = Long.MIN_VALUE / 2;
        _shotCounted = null;
        _shots = 0;
        _shotTick = PerchRules.Facts.NEVER;
        _perch = null;
        _perchStart = Long.MIN_VALUE;
        _run = null;
        _fight = null;
        _fightId = -1;
        _fightName = FightNames.UNKNOWN;
        _names.clear();
        _enderId = -1;
        _enderLastPos = null;
        _enderGoneSaid = false;
    }

    // what to hold the wheel with right now, null for nothing (the chain lets go of whatever it has)
    Task wheelTask(AltoClef mod) {
        switch (_commit.mode()) {
            case FIGHT: {
                // a perch has no target, its task stands where we are and swings at whatever is in reach. KillEntityTask would
                // path to a target and walk us off the pillar
                if (_commit.perching()) {
                    if (_perch == null || _perchStart != _commit.startTick()) {
                        _perch = new PerchTask(_commit.perchHeight());
                        _perchStart = _commit.startTick();
                    } else if (_perch.stopped()) {
                        // a chain cut in and gave the wheel back: same column, it only starts over. a new one would stack h more from here
                        _perch.reset();
                    }
                    return _perch;
                }
                Entity target = mod.getWorld().getEntity(_commit.targetId());
                // the fight outlives a dead splitter for a moment (CombatCommit.SPLIT_WAIT_TICKS): stand, the children are coming
                if (target == null || !target.isAlive()) return _splitHold;
                // the same task object for the whole fight, a new one each tick would restart its stuck checks
                if (_fight == null || _fightId != target.getId() || _fight.stopped()) {
                    _fight = new KillEntityTask(target);
                    // no sword or axe is still a fight, and a pickaxe beats a fist
                    _fight.useToolsWhenUnarmed();
                    _fightId = target.getId();
                }
                return _fight;
            }
            case RUN: {
                // (the buddy's fork: in a blaze fight with a corner near, the run is into the corner and a stand there, see
                // BlazeShelter)
                net.minecraft.core.BlockPos shelter = adris.altoclef.util.helpers.BlazeShelter.fresh(mod.getWorld().getGameTime());
                boolean nearShelter = shelter != null && mod.getPlayer().position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(shelter))
                        <= adris.altoclef.util.helpers.BlazeShelter.RANGE;
                // (Tuning run_shelter_wither, on by default, route jar 3: not a corner with a wither skeleton within
                // SHELTER_WITHER of it. route jar 2 run 2 ran "into" the corner it already stood in, a half built box in the
                // open with the skeleton at its side, and ate there under fireballs till it died)
                if (nearShelter && adris.altoclef.util.helpers.Tuning.get("run_shelter_wither", 0) > 0 && witherNear(shelter, SHELTER_WITHER)) {
                    if (!shelter.equals(_shelterSkipped)) {
                        _shelterSkipped = shelter;
                        adris.altoclef.Debug.logInternal("combat: the run passes the blaze fight's corner at " + shelter.toShortString()
                                + ", a wither skeleton within " + (int) SHELTER_WITHER + " of it");
                    }
                    nearShelter = false;
                    shelter = null;
                }
                // (the buddy's fork, stand_model on, in the nether: the run's goal is the model's cell, the corner one cell among
                // the rest; with no cell from it the corner and the committed run as before)
                boolean model = adris.altoclef.util.helpers.StandPick.on() && WorldHelper.getCurrentDimension() == Dimension.NETHER;
                net.minecraft.core.BlockPos stand = model ? _stand.cell(mod, adris.altoclef.util.helpers.StandModel.Query.of().hitBack(0)
                        .maxRate(adris.altoclef.util.helpers.StandModel.RUN_RATE), nearShelter ? List.of(shelter) : List.of(), null) : null;
                if (stand != null ? stand.equals(shelter) : nearShelter) {
                    if (adris.altoclef.util.helpers.BlazeShelter.in(mod.getPlayer().position(), shelter)) {
                        _shelterWay.reset();
                        return _healUp;
                    }
                    // (null: the corner not reached in BoxWay.RUN_TICKS, the committed run below)
                    adris.altoclef.tasksystem.Task way = _shelterWay.step(mod, shelter);
                    if (way != null) {
                        adris.altoclef.tasksystem.Task fire = _shelterFire.step(mod, shelter);
                        if (fire != null) return fire;
                        if (!shelter.equals(_shelterRunTo)) {
                            _shelterRunTo = shelter;
                            adris.altoclef.Debug.logInternal("combat: the run is into the blaze fight's corner at " + shelter.toShortString());
                        }
                        return way;
                    }
                    stand = null;
                }
                _shelterRunTo = null;
                if (stand != null) {
                    // (the model's cell: the walk there, standing in it the heal wait)
                    adris.altoclef.tasksystem.Task walk = _stand.walk(mod);
                    return walk != null ? walk : _healUp;
                }
                // (a run that goes on from the last one has the old origin and a longer way: the old task's goal is met already)
                if (_run == null || _run.stopped() || !_run.sameRun(_commit.originX(), _commit.originZ(), _commit.runDistance())) {
                    _run = new CommittedRunTask(_commit.originX(), _commit.originZ(), _commit.runDistance(), this::crowd);
                }
                return _run;
            }
            default:
                // nothing committed, but hurt from the last one: stand here and heal instead of walking back in
                return _commit.recovering() ? _healUp : null;
        }
    }

    // a wither skeleton this close to the blaze fight's corner makes it no place to run to: its reach of about 2 and the
    // second (3.3 blocks) it takes to close on a buddy who stopped in the corner
    static final double SHELTER_WITHER = 6;
    private net.minecraft.core.BlockPos _shelterSkipped;

    private boolean witherNear(net.minecraft.core.BlockPos at, double range) {
        net.minecraft.world.phys.Vec3 c = net.minecraft.world.phys.Vec3.atBottomCenterOf(at);
        for (Mob mob : _crowdBlazes) {
            if (mob instanceof WitherSkeleton && mob.isAlive() && mob.position().distanceTo(c) <= range) return true;
        }
        return false;
    }

    // in contact: a bit past arm's reach, a cube's middle is further off than its edge
    private static final double LAVA_FOE_RANGE = 3.5;
    // blazes this close stay in a run's crowd (see _crowdBlazes)
    static final double BLAZE_CROWD_RANGE = 20;

    // the mob the lava guard leans toward: the fight's own target, else whatever hostile is in contact
    Entity lavaFoe(AltoClef mod) {
        // (the top of a perch is an edge, but nothing is going to knock us off it)
        if (_commit.perching()) return null;
        if (_commit.mode() == Mode.FIGHT) {
            Entity target = mod.getWorld().getEntity(_commit.targetId());
            if (target instanceof Mob mob && mob.isAlive() && mob.distanceTo(mod.getPlayer()) <= LAVA_FOE_RANGE) return mob;
        }
        LocalPlayer player = mod.getPlayer();
        for (Mob mob : _foeMobs) {
            if (mob.isAlive() && mob.distanceTo(player) <= LAVA_FOE_RANGE) return mob;
        }
        return null;
    }

    // the goal asks this from the main thread once a tick
    private List<Entity> crowd() {
        LocalPlayer player = _player;
        if (player == null) return new ArrayList<>(_foeMobs);
        return new ArrayList<>(FoeRules.<Mob>crowdWithBlazes(_foeMobs, _crowdBlazes, m -> m.distanceTo(player), Mob::isAlive, BLAZE_CROWD_RANGE));
    }

    private static List<Mob> trackedBlazes(AltoClef mod) {
        List<Mob> out = new ArrayList<>();
        try {
            out.addAll(mod.getEntityTracker().getTrackedEntities(Blaze.class));
            // wither skeletons that have not noticed us are not foes, but a run must not steer into them either
            for (WitherSkeleton skeleton : mod.getEntityTracker().getTrackedEntities(WitherSkeleton.class)) {
                if (!out.contains(skeleton)) out.add(skeleton);
            }
        } catch (ConcurrentModificationException ignored) {
            // one tick of stale blazes is fine
        }
        return out;
    }

    // the closest tracked wither skeleton, out of sight or not (trackedBlazes already has every one of them, fresh this tick)
    private double nearestWither(LocalPlayer player) {
        double best = Double.POSITIVE_INFINITY;
        for (Mob mob : _crowdBlazes) {
            if (mob instanceof WitherSkeleton && mob.isAlive()) best = Math.min(best, mob.distanceTo(player));
        }
        return best;
    }

    // one game tick. creeperClose: a lit creeper close enough that we do not sit down to a meal next to it (we do not
    // run from it either, the task walks on). enabled: false is the off switch (altoCommitCombat), the foes are still
    // worked out for the stance and the crit counts but nothing is ever committed to. creeperRepeat: the creeper step just
    // started on one that came back (CreeperStep.escalate), which idle turns into a run
    void tick(AltoClef mod, long now, boolean creeperClose, boolean enabled, boolean creeperRepeat) {
        LocalPlayer player = mod.getPlayer();
        Dimension dimension = WorldHelper.getCurrentDimension();
        // a new player is a respawn or a new world, and a new dimension is a new world too: whatever we were committed to
        // (a run's origin, a target id) died with the old one
        if (player != _player || dimension != _dimension) {
            reset();
            _player = player;
            _dimension = dimension;
        }
        // dead is not a fight or a run or a hold: the entities go away around a corpse and that is not a "fight over"
        if (player.isDeadOrDying() || player.getHealth() <= 0) {
            _commit.reset();
            _swingAt.clear();
            _run = null;
            _fight = null;
            _perch = null;
            _stance = CombatRules.Stance.CALM;
            return;
        }
        countShots(player, now);
        EntityTracker tracker = mod.getEntityTracker();
        Provocations book = tracker.getProvocations();
        MobReachability reach = tracker.getMobReachability();

        List<Mob> mobs = new ArrayList<>();
        List<Foe> foes = new ArrayList<>();
        boolean inFight = _commit.mode() == Mode.FIGHT && !_commit.perching();
        boolean shotByExcluded = gatherFoes(mod, dimension, book, now, player, mobs, foes, inFight);
        if (inFight) addBlazesThatHitUs(mod, dimension, book, now, player, mobs, foes);
        // a swelling creeper stops being angry at us (the fuse goal pushes the chase aside), so the hostile list drops it right when
        // the run away from it needs to see it
        if (_commit.mode() == Mode.RUN && _commit.why() == CombatCommit.Why.REPEAT_CREEPER) addNearCreepers(mod, player, mobs, foes);
        sortNearestFirst(mobs, foes);
        _foeMobs = mobs;
        // the hold's re-run source: who threw the last projectile at us, when it is a blaze or a skeleton. the run steers around it
        // like around any foe, the hostile list alone would leave it blind to a thrower it never carried
        Mob shooter = rangedSource(player, now);
        if (!recovering()) shooter = null;
        boolean freshShot = shooter != null && now - _shooterAt <= RecoverHold.SHOT_SOURCE_TICKS;
        if (shooter != null && !mobs.contains(shooter)) {
            List<Mob> withShooter = new ArrayList<>(mobs);
            withShooter.add(shooter);
            _foeMobs = withShooter;
        }
        _crowdBlazes = trackedBlazes(mod);
        // the pearl hunt owns the feet while endermen are the only thing around: a run from a teleporter goes nowhere and a
        // heal hold would sit in the open. the nook it is in (or digging) is the whole answer, so nothing is committed to.
        // the foes stay, the stance (the gapple at 8 hp) and the aura's contact swings still hear about them
        boolean standDown = EndermanNook.brainStandsDown(PearlHuntHook.running(now), mobs.size(),
                (int) mobs.stream().filter(m -> m instanceof Enderman).count());
        enabled &= !standDown;

        // the edge scan once a tick for everybody: the way fight's engage, the fight's shield and the run's shield
        boolean blazeContact = blazeInContact(mod, player);
        boolean rim = onRim(mod, player, foes, blazeContact);
        Foe target = null;
        if (_commit.mode() == Mode.FIGHT) {
            Entity entity = mod.getWorld().getEntity(_commit.targetId());
            if (entity instanceof Mob mob && mob.isAlive()) {
                target = tridentRun(foe(dimension, mob, mob.distanceTo(player), book, now, player).withPatrol(patrol(mod, mob, player)), mob, player);
            }
            noteEnderGone(dimension, entity, entity instanceof Mob m && m.isAlive(), player);
        }
        boolean lavaClose = lavaClose(mod, player, foes, target);

        // the name is written down while the target is alive, a dead mob is already gone from the world by the time we say so.
        // by id: "the last name we saw" said zombie for a skeleton whose fight ended before anyone looked at it
        if (target != null && _commit.mode() == Mode.FIGHT) _names.note(_commit.targetId(), nameOf(mod, _commit.targetId()));
        String fighting = _names.of(_commit.targetId());
        if (enabled) {
            // how far the run got, asked before the step: an end chained into a new run has moved the origin by the time we say so
            int blocks = (int) Math.round(Math.hypot(player.getX() - _commit.originX(), player.getZ() - _commit.originZ()));
            _commit.perchFacts(perchFacts(mod, player, mobs, foes, now));
            // (Tuning box_stamp_fight, on: a fix, 10 Nov 2026. the standing pillbox's stamp, by which a walker by its wall that never
            // hits is no progress, came from the rods task's tick alone, which a fight takes: 40 ticks into every fight from the box
            // the rule was off, and a wither skeleton outside the wall held the buddy 18 game minutes in its cell, unhurt, the
            // order stalled (realfort-route-17 run 19). the buddy's feet in the standing box keep the stamp fresh)
            if (adris.altoclef.util.helpers.Tuning.get("box_stamp_fight", 0) > 0
                    && adris.altoclef.util.helpers.BlazeShelter.inStandingBox(player.blockPosition())) {
                adris.altoclef.util.helpers.BlazeShelter.boxStands(now);
            }
            Event event = _commit.step(new CombatCommit.Tick(now, player.getHealth(), player.getX(), player.getZ(), foes, target,
                    way(mod, mobs, foes).withRim(rim).withLavaClose(lavaClose), armed(mod, foes), tryingToMove(mod, player), creeperRepeat, body(mod, dimension),
                    boxedIn(mod, player, mobs, foes), 0, _commit.recovering() && (freshShot || blazeWatching(mod, player)), lingering(player)));
            String note = _commit.takeNote();
            if (note != null) Debug.logInternal("combat: [" + dimension.name().toLowerCase(java.util.Locale.ROOT) + "] " + note);
            String perchNote = _commit.takePerchNote();
            if (perchNote != null) Debug.logInternal("combat: [" + dimension.name().toLowerCase(java.util.Locale.ROOT) + "] " + perchNote);
            // the heal wait starts when the run (and the fight it may have turned into) is all over
            if (_commit.mode() == Mode.RUN) {
                _ranSince = true;
            } else if (_commit.mode() == Mode.NONE && _ranSince) {
                _ranSince = false;
                _lastRunEnd = now;
            }
            // a fight that just started has its target in the world right now, which is the only chance to learn its name
            if (_commit.mode() == Mode.FIGHT && !_commit.perching()) {
                _names.note(_commit.targetId(), nameOf(mod, _commit.targetId()));
                _fightName = _names.of(_commit.targetId());
            }
            // same chance for the enderman gone line: one that the step just picked and that blinks off before the next tick
            // would never have been written down
            if (_commit.mode() == Mode.FIGHT && mod.getWorld().getEntity(_commit.targetId()) instanceof Enderman man && man.isAlive()) {
                noteEnderGone(dimension, man, true, player);
            }
            if (event != Event.NONE) {
                // the old one ended and the next one started on the same step, both get their line, in that order
                if (_commit.endedFirst() != Event.NONE) log(mod, dimension, _commit.endedFirst(), fighting, mobs, blocks);
                log(mod, dimension, event, fighting, mobs, blocks);
                // a new run is a new origin, a new fight is a new target, whatever the old ones were
                _run = null;
                // the nether sweep keeps off the spot we were chased out of by a heavy hitter (nether coordinates only, the
                // zones mean nothing in another dimension)
                if (dimension == Dimension.NETHER && _commit.mode() == Mode.RUN && _commit.why() == CombatCommit.Why.HEAVY
                        && (event == Event.RUN_START || event == Event.FIGHT_TO_RUN)) {
                    RunZones.INSTANCE.add(_commit.originX(), _commit.originZ(), now, RunZones.RADIUS);
                }
                // a run from a crowd or low hp next to a wither pack: the spawner walk keeps off the pack's closet too
                if (dimension == Dimension.NETHER && _commit.mode() == Mode.RUN
                        && (_commit.why() == CombatCommit.Why.CROWD || _commit.why() == CombatCommit.Why.LOW_HP)
                        && (event == Event.RUN_START || event == Event.FIGHT_TO_RUN)) {
                    double[] pack = CollectBlazeRodsTask.witherPackCenter(mod, new Vec3(_commit.originX(), player.getY(), _commit.originZ()), BlazeFightRules.PACK_SPAWNER_RADIUS);
                    if (pack != null) {
                        RunZones.INSTANCE.add(pack[0], pack[1], now, RunZones.PACK_RADIUS);
                        Debug.logInternal("zone: wither pack at " + Math.round(pack[0]) + "," + Math.round(pack[1]) + " r" + (int) RunZones.PACK_RADIUS);
                    }
                }
                // a fight that took over from a run: the run's path is still alive, and CritTiming holds the opener while one is
                // (the perch stands where we are, the run's path would walk us off before the pillar is up)
                if (event == Event.RUN_TO_FIGHT || event == Event.RUN_CHASED_FIGHT || event == Event.PERCH_START) {
                    mod.getClientBaritone().getPathingBehavior().cancelEverything();
                }
            }
        } else if (_commit.mode() != Mode.NONE) {
            // switched off in the middle of one: let go of it, no line (it is not a transition the machine made)
            _commit.reset();
            _ranSince = false;
        } else {
            // only the heal-up hold: the grudges and the cooldown of an idle machine stay where they were (the night pillar
            // switches us off every tick it stands down)
            dropHold();
        }
        if (_commit.mode() != Mode.RUN) {
            _run = null;
            _stand.reset();
        }
        if (_commit.mode() != Mode.FIGHT) _fight = null;
        if (!_commit.perching()) _perch = null;

        // running: a zombie that keeps pace with us is the one we are running from, not one that is stuck
        reach.setFleeing(_commit.mode() == Mode.RUN);

        _swingAt.clear();
        int meleeNear = 0;
        int meleeAround = 0;
        double nearest = Double.POSITIVE_INFINITY;
        boolean shotAt = shotByExcluded;
        double nearestFast = Double.POSITIVE_INFINITY;
        double nearestMelee = Double.POSITIVE_INFINITY;
        for (int i = 0; i < foes.size(); i++) {
            Foe foe = foes.get(i);
            nearest = Math.min(nearest, foe.distance());
            // (the nearest fast one gets to say when a bite may start, see CombatRules.RUN_BITE_START_FAST. shooters keep
            // their distance, so they are not chasers)
            if (foe.melee() && foe.distance() < nearestFast && foe.fast()) {
                nearestFast = foe.distance();
            }
            // a shooter close enough to hit us with a line to us. the los ray is only cast for the ones that are in range
            // (the ray is only cast when the cheap answers did not already say yes)
            if (!shotAt && foe.ranged() && !foe.creeper()
                    && CombatCommit.shotAt(foe, foe.distance() <= CombatRules.SHOOTER_RANGE && !foe.sight() && mobs.get(i).hasLineOfSight(player))) {
                shotAt = true;
            }
            if (foe.hitUs() && foe.distance() <= CombatCommit.CONTACT) _swingAt.add(foe.id());
            // (a creeper is not a melee foe to the rules but it is one to a bite, it walks up and goes off)
            // (the fight target counts whatever it is: a skeleton at point blank is no time to chew, and a melee target knocked back to
            // five blocks is not a walk-up, the swap to food would eat its swing cooldown. only a shooter we are closing on is)
            if (foe.id() == _commit.targetId() && _commit.mode() == Mode.FIGHT && foe.melee()) nearestMelee = 0;
            if (!foe.ranged() || (foe.id() == _commit.targetId() && _commit.mode() == Mode.FIGHT)) nearestMelee = Math.min(nearestMelee, foe.distance());
            if (!foe.melee()) continue;
            if (foe.distance() <= CombatCommit.CONTACT) meleeNear++;
            if (foe.distance() <= CombatRules.SWARM_RANGE) meleeAround++;
        }
        if (_commit.mode() == Mode.FIGHT && !_commit.perching()) _swingAt.add(_commit.targetId());
        // (the target can be missing from a hostile pass, and losing the shield for that restarts its 5 ticks)
        Foe fightFoe = target;
        List<Foe> shieldFoes = fightFoe != null && foes.stream().noneMatch(f -> f.id() == fightFoe.id()) ? List.of(fightFoe) : foes;
        _fightShield = _commit.mode() == Mode.FIGHT && !_commit.perching() && CombatRules.fightShield(_fightShield, _commit.targetId(), shieldFoes);
        _fightWalking = _commit.mode() == Mode.FIGHT && !_commit.perching() && CombatRules.fightShieldDown(
                mod.getClientBaritone().getPathingBehavior().getCurrent() != null, target != null ? target.distance() : Double.POSITIVE_INFINITY);
        _walkNoArrowShield = _fightWalking && target != null && (target.kind() == Kind.FLYER || target.patrol());
        // (outside a fight the shield pauses pathing, so it waits until the walk is done instead of pinning us on the edge)
        _rim = rim && !_commit.perching() && CombatRules.rimShield(true, blazeContact, foes, lavaClose) && mod.getClientBaritone().getPathingBehavior().getInProgress().isEmpty();
        // a run on a rim with a cube on us has no walk to wait for: the next hit is the knock into the lava, so the shield is up
        // for it, for a leash's worth of ticks (RimHold)
        Foe rimRun = _commit.mode() == Mode.RUN ? CombatRules.rimRunFoe(rim, foes) : null;
        _rimGuardId = _rimHold.step(rimRun == null ? -1 : rimRun.id()) ? rimRun.id() : -1;
        if (_rimGuardId >= 0) {
            _commit.pauseCorner();
            _rim = true;
            _swingAt.add(rimRun.id());
        }
        // the perch task swings for itself, the aura would be a second hand on the same sword
        if (_commit.perching()) {
            _swingAt.clear();
            _rimGuardId = -1;
        }
        _meleeNear = meleeNear;
        _meleeAround = meleeAround;
        // a shooter that was on us a moment ago still is: the ray flickers behind a tree and the skeleton steps in and out of the
        // range line, and a bite started in that blink was an arrow in the face
        // (only the bite hears it, shotAt() stays the raw answer for the meals that ask the chain)
        boolean biteShot = shotAt;
        if (shotAt) {
            _shotUntil = Math.max(_shotUntil, now + CombatCommit.SHOT_LATCH);
        } else if (now <= _shotUntil) {
            biteShot = true;
        }
        _shotAt = shotAt;
        // (in lava a bite is the wrong thing to be doing, the escape has the wheel)
        boolean burning = mod.getPlayer().isOnFire() && !mod.getPlayer().isInLava();
        CombatCommit.Bite bite = new CombatCommit.Bite(biteShot, nearestFast, mod.getFoodChain().isTryingToEat(),
                nearestMelee, CombatRules.fightHungry(mod.getPlayer().getFoodData().getFoodLevel(), burning), nearestWither(player));
        _stance = stance(mod, nearest, creeperClose, bite);
        logStance(mod, dimension, now, nearest, creeperClose, bite);
        logHurt(mod, dimension);
    }

    // the cheap foe check first, the cell scan only when one is close enough to matter (rimScan). same edge the kill task steps back from.
    // a blaze that its own task keeps out of the foes still counts when it is in contact
    private static boolean onRim(AltoClef mod, LocalPlayer player, List<Foe> foes, boolean blazeContact) {
        if (mod.getClientBaritone().bsi == null) return false;
        if (!CombatRules.rimScan(blazeContact, foes)) return false;
        return LavaShore.edgeNear(CliffAvoidance.of(mod.getClientBaritone().bsi), player.getX(), player.getY(), player.getZ());
    }

    // same lava the walk off a shore looks for (LavaShore.groundCell), a knock carries about that far. only a cube asks, so 49
    // columns of cell lookups only happen with one about. the target counts too, it can be missing from a hostile pass
    private static boolean lavaClose(AltoClef mod, LocalPlayer player, List<Foe> foes, Foe target) {
        if (mod.getClientBaritone().bsi == null || !CombatRules.cubeAbout(foes, target)) return false;
        return LavaShore.lavaDeepNear(CliffAvoidance.of(mod.getClientBaritone().bsi), player.getX(), player.getY(), player.getZ(), LavaShore.GROUND_CLEAR);
    }

    private static boolean blazeInContact(AltoClef mod, LocalPlayer player) {
        try {
            for (Entity entity : mod.getEntityTracker().getHostiles()) {
                if (entity instanceof Blaze && entity.isAlive() && entity.distanceTo(player) <= CombatRules.CONTACT_RANGE) {
                    return true;
                }
            }
        } catch (ConcurrentModificationException ignored) {
            // same story as gatherFoes
        }
        return false;
    }

    // fills mobs and foes (in step) from the tracker's hostiles. hands back whether a mob some task kept for itself is
    // shooting at us: a task's own blaze is not ours to fight, but it still counts for "do not eat right now"
    private static boolean gatherFoes(AltoClef mod, Dimension dimension, Provocations book, long now, LocalPlayer player,
                                      List<Mob> mobs, List<Foe> foes, boolean fighting) {
        boolean shotByExcluded = false;
        try {
            for (Entity entity : mod.getEntityTracker().getHostiles()) {
                if (!(entity instanceof Mob mob) || !mob.isAlive()) continue;
                double distance = mob.distanceTo(player);
                if (distance > FOE_RANGE) continue;
                FoeRules.Candidate candidate = candidate(mod, dimension, mob, distance, book, now, player, fighting);
                Foe foe = FoeRules.accept(dimension, candidate);
                if (foe != null) {
                    mobs.add(mob);
                    foes.add(tridentRun(withSplit(foe.withFast(fast(mob)).withWet(wet(mob, player)).withPatrol(patrol(mod, mob, player)), mob, player), mob, player));
                } else if (!shotByExcluded) {
                    shotByExcluded = FoeRules.excludedShooterOnUs(dimension, candidate);
                }
            }
        } catch (ConcurrentModificationException ignored) {
            // the tracker rebuilds its lists on another thread sometimes, one tick of stale combat state is fine
        }
        return shotByExcluded;
    }

    // the hostile list only holds a blaze while it is charged, and it stops being charged right after the volley that hit us. then
    // it is gone for five seconds, and it did not stop being the problem. same lookup as the fight target, no angry check to fail
    private static void addBlazesThatHitUs(AltoClef mod, Dimension dimension, Provocations book, long now, LocalPlayer player,
                                           List<Mob> mobs, List<Foe> foes) {
        try {
            for (Blaze blaze : mod.getEntityTracker().getTrackedEntities(Blaze.class)) {
                if (!blaze.isAlive() || mobs.contains(blaze)) continue;
                double distance = blaze.distanceTo(player);
                if (distance > FOE_RANGE || !FoeRules.liftsExclusion(typeOf(blaze), true, book.sinceHit(blaze.getId(), now))) continue;
                mobs.add(blaze);
                foes.add(foe(dimension, blaze, distance, book, now, player));
            }
        } catch (ConcurrentModificationException ignored) {
            // same as gatherFoes, one tick of stale state is fine
        }
    }

    private static void addNearCreepers(AltoClef mod, LocalPlayer player, List<Mob> mobs, List<Foe> foes) {
        try {
            for (Creeper creeper : mod.getEntityTracker().getTrackedEntities(Creeper.class)) {
                if (!creeper.isAlive() || mobs.contains(creeper)) continue;
                double distance = creeper.distanceTo(player);
                if (distance > CombatCommit.LOW_HP_RANGE) continue;
                mobs.add(creeper);
                foes.add(new Foe(creeper.getId(), distance, false, true, CombatCommit.NEVER));
            }
        } catch (ConcurrentModificationException ignored) {
            // same as gatherFoes, one tick of stale state is fine
        }
    }

    // a stance change is a line, but a follower hovering right on the bite line would flip it every tick, so a line is at most
    // one per STANCE_LOG_GAP ticks and carries from the last stance we said to the one we are in now. the reason string is only
    // built when the line goes out
    private void logStance(AltoClef mod, Dimension dimension, long now, double nearest, boolean creeperClose, CombatCommit.Bite bite) {
        if (_stance == _loggedStance || (now >= _stanceLogAt && now - _stanceLogAt < STANCE_LOG_GAP)) return;
        LocalPlayer player = mod.getPlayer();
        String why = CombatCommit.stanceWhy(_commit.mode(), player.getHealth(), nearest, creeperClose, bite, _commit.recovering());
        Debug.logInternal(CombatLog.stance(dimension, _loggedStance, _stance, why, Math.round(player.getHealth())));
        _loggedStance = _stance;
        _stanceLogAt = now;
    }

    // hp went down: one line per new damage source object (the client makes a fresh one per hit), naming who if it knows
    // wither, poison or burning (fire resistance covers the fire) keep hurting after the hit that started them
    private static boolean lingering(LocalPlayer player) {
        return player.hasEffect(MobEffects.WITHER) || player.hasEffect(MobEffects.POISON)
                || (player.isOnFire() && !player.isInLava() && !adris.altoclef.util.helpers.FireRes.has(player));
    }

    private void logHurt(AltoClef mod, Dimension dimension) {
        LocalPlayer player = mod.getPlayer();
        float hp = player.getHealth();
        float before = _lastHealth;
        _lastHealth = hp;
        if (before < 0 || hp >= before) return;
        DamageSource source = player.getLastDamageSource();
        // wither and fire hand back the same cached source every tick, so "new object" hid all of their damage. a source with
        // no attacker gets a line at most every STANCE_LOG_GAP ticks instead
        long now = mod.getWorld().getGameTime();
        if (source != null && source == _lastSource && (source.getEntity() != null || now - _lastHurtAt < STANCE_LOG_GAP)) return;
        _lastSource = source;
        _lastHurtAt = now;
        String who = null;
        String how = null;
        if (source != null) {
            how = source.getMsgId();
            Entity attacker = source.getEntity();
            if (attacker != null) {
                who = attacker instanceof Mob mob ? MobReachability.shortName(mob)
                        : BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType()).getPath();
            }
        }
        String line = CombatLog.hurt(dimension, before - hp, who, how);
        if (source != null && source.is(DamageTypes.HOT_FLOOR)) {
            line += CombatLog.hotFloor(player.input.keyPresses.shift(), player.onGround(), player.getY(), player.input.getClass().getSimpleName());
        }
        Debug.logInternal(line);
    }

    private final CombatCommit.Drive _drive = new CombatCommit.Drive();

    // the run is really trying to get somewhere: a path executing, or the search came back with none, and we are not using an
    // item (a run bite crawls by design), in the air or in water. the no room rule only counts these ticks
    private boolean tryingToMove(AltoClef mod, LocalPlayer player) {
        var pathing = mod.getClientBaritone().getPathingBehavior();
        return _drive.trying(_commit.mode() == Mode.RUN, pathing.isPathing(), pathing.getInProgress().isPresent(), pathing.calcFailures(),
                player.isUsingItem(), player.onGround(), player.isInWater());
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    // every side of us is a wall (feet or head cell baritone would not walk through) or has a mob standing in it: a run has
    // nowhere to go. only asked while something is close enough for a run to be on the table
    private static boolean boxedIn(AltoClef mod, LocalPlayer player, List<Mob> mobs, List<Foe> foes) {
        if (foes.isEmpty() || foes.get(0).distance() > CombatCommit.LOW_HP_RANGE) return false;
        BlockStateInterface bsi = mod.getClientBaritone().bsi;
        if (bsi == null) return false;
        BlockPos pos = player.blockPosition();
        // a jump out of a one deep pit needs the cell over our head clear
        boolean headroom = walkable(bsi, pos.getX(), pos.getY() + 2, pos.getZ());
        boolean[] open = new boolean[SIDES.length];
        boolean[] filled = new boolean[SIDES.length];
        for (int i = 0; i < SIDES.length; i++) {
            int x = pos.getX() + SIDES[i][0];
            int z = pos.getZ() + SIDES[i][1];
            // (a side we could step up onto is a way out too, a one deep pit is not a box)
            open[i] = walkable(bsi, x, pos.getY(), z) && walkable(bsi, x, pos.getY() + 1, z)
                    || headroom && walkable(bsi, x, pos.getY() + 1, z) && walkable(bsi, x, pos.getY() + 2, z);
            if (!open[i]) continue;
            AABB cell = new AABB(x, pos.getY(), z, x + 1, pos.getY() + 2, z + 1);
            for (int m = 0; m < mobs.size() && !filled[i]; m++) {
                if (foes.get(m).distance() <= CombatCommit.CONTACT + 1) filled[i] = cell.intersects(mobs.get(m).getBoundingBox());
            }
        }
        return BoxRules.boxedIn(open, filled);
    }

    private static boolean walkable(BlockStateInterface bsi, int x, int y, int z) {
        return MovementHelper.canWalkThrough(bsi, x, y, z) && !MovementHelper.avoidWalkingInto(bsi.get0(x, y, z));
    }

    // a sword, an axe, or a tool that hits harder than a fist (the fight task swings those, see useToolsWhenUnarmed). only the
    // machine's idle and run ask, so the bag is only walked when something is on us or we are already running
    private boolean armed(AltoClef mod, List<Foe> foes) {
        boolean asked = _commit.mode() == Mode.RUN;
        for (Foe foe : foes) {
            // (a sniper that is shooting us is a fight we would start, so it wants to know too)
            if (foe.distance() <= CombatCommit.CONTACT || (foe.sniper() && foe.shooting()) || witherClose(foe)) asked = true;
        }
        if (!asked) return true;
        List<WeaponPick.Candidate> candidates = bagCandidates(mod);
        return WeaponPick.best(candidates) != null || WeaponPick.bestTool(candidates) != null;
    }

    // the bag is only walked when something is in contact: that is the only time an in the way fight can start
    // (or a sniper shooting us, the walk up to it wants a sword too. same ask as armed)
    private static boolean weaponIfNear(AltoClef mod, List<Foe> foes) {
        for (Foe foe : foes) {
            if (foe.distance() <= CombatCommit.CONTACT || (foe.sniper() && foe.shooting()) || witherClose(foe)) return hasWeapon(mod);
        }
        return true;
    }

    // the wither skeleton start rule picks its fight from out here, so the bag gets asked before it does
    private static boolean witherClose(Foe foe) {
        return foe.wither() && foe.sight() && foe.distance() <= CombatCommit.WITHER_START_RANGE;
    }

    // a sword or an axe only, for the fights we pick (a pickaxe is fine for the one that is already hitting us)
    private static boolean patrol(AltoClef mod, Mob mob, LocalPlayer player) {
        return FoeRules.patrol(typeOf(mob), player.getArmorValue(), () -> hasWeapon(mod));
    }

    private static boolean hasWeapon(AltoClef mod) {
        return WeaponPick.best(bagCandidates(mod)) != null;
    }

    private static List<WeaponPick.Candidate> bagCandidates(AltoClef mod) {
        List<WeaponPick.Candidate> candidates = new ArrayList<>();
        candidates.add(WeaponPick.Candidate.of(StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot())));
        for (ItemStack stack : mod.getItemStorage().getItemStacksPlayerInventory(true)) {
            candidates.add(WeaponPick.Candidate.of(stack));
        }
        return candidates;
    }

    // how many path nodes ahead count as "in the way". three is the next couple of steps, further out the mob has time to move
    private static final int WAY_NODES = 3;

    // the path's side of the tick: which of the mobs in contact sit in the next few nodes (feet or head) or in the block the
    // current movement would place under its dest (a bridge or a step up into a zombie). baritone does wait for a mob to
    // leave the place cell, but only 20 ticks, then it replans into the same cell
    private static CombatCommit.Way way(AltoClef mod, List<Mob> mobs, List<Foe> foes) {
        IPathingBehavior pathing = mod.getClientBaritone().getPathingBehavior();
        boolean moving = pathing.isPathing() || pathing.getInProgress().isPresent();
        double y = mod.getPlayer().getY();
        IPathExecutor current = pathing.getCurrent();
        if (current == null) return new CombatCommit.Way(Set.of(), moving, y, weaponIfNear(mod, foes));
        List<AABB> cells = new ArrayList<>();
        try {
            IPath path = current.getPath();
            List<BetterBlockPos> positions = path.positions();
            int from = Math.max(0, current.getPosition());
            // (from + 1: the node we are standing on is ours, a zombie hugging us is not "in the path")
            for (int i = from + 1; i < positions.size() && i <= from + WAY_NODES; i++) {
                BetterBlockPos p = positions.get(i);
                cells.add(new AABB(p.x, p.y, p.z, p.x + 1, p.y + 2, p.z + 1));
            }
            List<IMovement> movements = path.movements();
            if (from < movements.size()) {
                BetterBlockPos dest = movements.get(from).getDest();
                cells.add(new AABB(dest.x, dest.y - 1, dest.z, dest.x + 1, dest.y, dest.z + 1));
            }
        } catch (IndexOutOfBoundsException e) {
            // the executor moved on between two reads, next tick has it
        }
        Set<Integer> inWay = new HashSet<>();
        for (int i = 0; i < foes.size(); i++) {
            if (foes.get(i).distance() > CombatCommit.CONTACT) continue;
            // a hair bigger, a zombie leaning into the cell is in it
            AABB box = mobs.get(i).getBoundingBox().inflate(0.05);
            for (AABB cell : cells) {
                if (cell.intersects(box)) {
                    inWay.add(foes.get(i).id());
                    break;
                }
            }
        }
        return new CombatCommit.Way(inWay, moving, y, weaponIfNear(mod, foes));
    }

    private CombatRules.Stance stance(AltoClef mod, double nearest, boolean creeperClose, CombatCommit.Bite bite) {
        // gapples are never picked as food (FoodSelector keeps them for exactly this), so check the bag ourselves
        boolean gapple = mod.getItemStorage().hasItem(Items.GOLDEN_APPLE) || mod.getItemStorage().hasItem(Items.ENCHANTED_GOLDEN_APPLE);
        // a perch is a fight that may eat: "no meals mid fight" is for fights we cannot step out of, up here nothing reaches us. calm
        // even at the flee line (that one is for runs and fights on the ground), a lit creeper is still a reason not to sit down
        // (only once the pillar is up: a meal while stacking eats the placement clicks and the pillar gives up)
        if (_commit.perching() && !creeperClose && _perch != null && _perch.onTop()) return CombatRules.Stance.CALM;
        return CombatCommit.stance(_commit.mode(), mod.getPlayer().getHealth(), nearest, creeperClose, gapple, bite, _commit.recovering(),
                mod.getFoodChain().hasFood());
    }

    // projectile hits, every tick. the game hands a fresh source object per hit and keeps it for a couple of seconds
    private void countShots(LocalPlayer player, long now) {
        DamageSource source = player.getLastDamageSource();
        if (source == null || source == _shotCounted) return;
        _shotCounted = source;
        boolean ranged = source.getEntity() instanceof Mob mob && MobReachability.isRanged(mob);
        if (source.getDirectEntity() instanceof Projectile) {
            _shots++;
            _shotTick = now;
        }
        // a shooter past FOE_RANGE is no foe, so shotAt never sees it and the 60 tick latch never starts: a trident from 18
        // blocks hit us mid meal after the run ended. the hit itself keeps the bite closed for a full SHOT_MEMORY
        if (ranged || source.getDirectEntity() instanceof Projectile) _shotUntil = CombatCommit.hitLatch(_shotUntil, now);
    }

    // the world's side of the perch for this tick, null when nothing is close enough to matter (and we are not on one already)
    private PerchRules.Facts perchFacts(AltoClef mod, LocalPlayer player, List<Mob> mobs, List<Foe> foes, long now) {
        boolean near = _commit.perching();
        for (int i = 0; i < foes.size() && !near; i++) {
            near = foes.get(i).distance() <= PerchRules.RANGE;
        }
        if (!near) return null;
        Map<Integer, Double> heights = new HashMap<>();
        double tallest = 0;
        for (int i = 0; i < mobs.size(); i++) {
            Mob mob = mobs.get(i);
            if (foes.get(i).distance() > PerchRules.RANGE || !PerchRules.species(typeOf(mob))) continue;
            double height = mob.getBbHeight();
            heights.put(mob.getId(), height);
            tallest = Math.max(tallest, height);
        }
        // the angry perch species near the pillar and any creeper, off the world: the foe list drops what cannot reach us
        int near6 = 0;
        int near4 = 0;
        boolean creeper8 = false;
        boolean creeper4 = false;
        try {
            for (Entity entity : mod.getEntityTracker().getHostiles()) {
                if (!(entity instanceof Mob mob) || !mob.isAlive() || !PerchRules.species(typeOf(mob))) continue;
                double d = mob.distanceTo(player);
                if (d > PerchRules.LEAVE_RANGE || !EntityHelper.isAngryAtPlayer(mod, mob)) continue;
                near6++;
                if (d <= PerchRules.CAP_RANGE) near4++;
            }
            for (Creeper creeper : mod.getEntityTracker().getTrackedEntities(Creeper.class)) {
                if (!creeper.isAlive()) continue;
                double d = creeper.distanceTo(player);
                creeper8 |= d <= PerchRules.RANGE;
                creeper4 |= d <= PerchRules.LEAVE_RANGE; // (named for the old 4, the perch leaves at LEAVE_RANGE now)
            }
        } catch (ConcurrentModificationException ignored) {
            // one tick of stale mobs is fine
        }
        var level = mod.getWorld();
        BlockPos here = player.blockPosition();
        double feetY = player.getY();
        // the same step-beside-us math the golem fight uses, for the tallest chaser we have to clear
        int cells = Math.max(2, (int) Math.ceil(tallest));
        double rise = Math.max(0, MobFooting.groundBeside(level, here, feetY, cells) - feetY);
        // free cells straight up from our feet cell, a few is plenty (h + 2 is the most anyone asks)
        int air = 0;
        while (air < 6) {
            BlockPos cell = here.above(air);
            var state = level.getBlockState(cell);
            if (!state.getCollisionShape(level, cell).isEmpty() || !state.getFluidState().isEmpty()) break;
            air++;
        }
        return new PerchRules.Facts(player.onGround(), feetY, rise, StorageHelper.getBuildingMaterialCount(mod), air,
                mod.getFoodChain().hasFood(), _shots, _shotTick, _perch != null && _perch.failed(), heights, near6, near4, creeper8, creeper4);
    }

    // what the heal-up hold wants to know. barred: a task that fights on its own terms (golem, dragon, blaze rods) has mob
    // defense excluded, or this is the end, where standing still next to the dragon is not healing
    private static RecoverHold.Body body(AltoClef mod, Dimension dimension) {
        LocalPlayer player = mod.getPlayer();
        boolean barred = dimension == Dimension.END || mod.getBehaviour().hasMobDefenseExclusion();
        FoodChain food = mod.getFoodChain();
        return new RecoverHold.Body(player.getFoodData().getFoodLevel(), food != null && food.hasHealFood(), barred,
                food != null && food.isTryingToEat(), lingering(player));
    }

    // the blaze or skeleton whose projectile hit us lately, or null. the damage source is only kept by the game for a moment, so it
    // is read every tick and the thrower remembered here. the window only gates starting the re-run (see tick): once it is running
    // the thrower stays in the run's crowd until the run is over, or the run would turn back toward it after two seconds
    private Mob rangedSource(LocalPlayer player, long now) {
        DamageSource source = player.getLastDamageSource();
        if (source != null && source != _shotSeen) {
            _shotSeen = source;
            if (source.getDirectEntity() instanceof Projectile && source.getEntity() instanceof Mob mob
                    && RecoverHold.rerunSource(typeOf(mob))) {
                _shooter = mob;
                _shooterAt = now;
            }
        }
        boolean stale = now - _shooterAt > RecoverHold.SHOT_SOURCE_TICKS && _commit.mode() != Mode.RUN;
        if (_shooter != null && (stale || !_shooter.isAlive())) _shooter = null;
        return _shooter;
    }

    // a blaze with a line on us that the hostile list does not carry (it only lists the charged ones and the ones in melee). asked
    // only while the heal-up hold is on: the hold re-runs on it, so the meal is not eaten in front of a blaze that has not hit us yet
    private static boolean blazeWatching(AltoClef mod, LocalPlayer player) {
        try {
            for (Blaze blaze : mod.getEntityTracker().getTrackedEntities(Blaze.class)) {
                double distance = player.distanceTo(blaze);
                // the ray only for the ones close enough to matter
                if (blaze.isAlive() && distance <= BlazeFightRules.SIGHT_RANGE && BlazeFightRules.watching(distance, blaze.hasLineOfSight(player))) return true;
            }
        } catch (ConcurrentModificationException ignored) {
            // one tick of stale blazes is fine
        }
        return false;
    }

    // the fight target looked up on its own: it can have stopped being angry (or being a candidate at all) and still be the
    // one we are fighting
    private static Foe foe(Dimension dimension, Mob mob, double distance, Provocations book, long now, LocalPlayer player) {
        Kind kind = FoeRules.kindOf(dimension, typeOf(mob));
        long sinceHit = book.sinceHit(mob.getId(), now);
        return withSplit(new Foe(mob.getId(), distance, FoeRules.shoots(typeOf(mob), MobReachability.isRanged(mob), kind), mob instanceof Creeper, sinceHit, kind,
                reachGap(player, mob), sees(dimension, mob, sinceHit, player), book.sinceHitBefore(mob.getId(), now), fast(mob)).withBrute(dimension == Dimension.NETHER && typeOf(mob).equals("piglin_brute"))
                .withWither(dimension == Dimension.NETHER && typeOf(mob).equals("wither_skeleton")).withWet(wet(mob, player)), mob, player);
    }

    // the buddy's fork: a drowned with a trident throws for eight on hard from ten blocks, three throws through no armour. with
    // less on than a set of copper it is a run at any hp, as the warden is: a bare start's food search along a beach went back
    // to the water after the first hit and died of the next two (the practice runner, 2 Nov 2026, attempt 83)
    static final int TRIDENT_ARMOUR = 10;

    private static Foe tridentRun(Foe foe, Mob mob, LocalPlayer player) {
        if (foe.kind() != Kind.NORMAL || !(mob instanceof net.minecraft.world.entity.monster.zombie.Drowned)
                || !mob.isHolding(Items.TRIDENT) || player.getArmorValue() >= TRIDENT_ARMOUR) {
            return foe;
        }
        return new Foe(foe.id(), foe.distance(), foe.ranged(), foe.creeper(), foe.sinceHit(), Kind.UNTOUCHABLE, foe.reachGap(), foe.sight(),
                foe.sinceHitBefore(), foe.fast(), foe.splitType(), foe.splits(), foe.x(), foe.y(), foe.z(), foe.knocker(), foe.brute(), foe.wet(),
                foe.wither());
    }

    // it is in the water, or we are afloat with no floor (wading with our feet on the bottom is still a fight we can take)
    static boolean wet(Mob mob, LocalPlayer player) {
        return mob.isInWater() || (player.isInWater() && !player.onGround());
    }

    // the slimes and magma cubes carry where they stand, CombatCommit wants it to find the splits of one that died
    // (a wither skeleton carries its height over us in y instead, the pack rule only wants to know about the ones on our floor.
    // a blaze too, CombatCommit.blazeToSwap only walks to one that is not hovering)
    private static Foe withSplit(Foe foe, Mob mob, LocalPlayer player) {
        String type = typeOf(mob);
        boolean cube = type.equals("slime") || type.equals("magma_cube");
        // a size 1 cube has 1 max health (size squared) and leaves nothing behind
        Foe out = cube ? foe.withSplit(type, mob.getMaxHealth() > 1, mob.getX(), mob.getY(), mob.getZ()) : foe;
        if (foe.wither() || foe.kind() == Kind.FLYER) out = out.withSplit(null, false, 0, mob.getY() - player.getY(), 0);
        // the ones whose hit shoves us, the rim rules ask for them (an angry piglin is only ever a foe once it is angry)
        return cube || type.equals("zombified_piglin") ? out.withKnocker(true) : out;
    }

    // slimes and magma cubes set their speed from their size (0.2 + 0.1 per size, so 0.3 at the smallest), spiders are 0.3, so all
    // of them count: the run bite lines and the "it comes along on the run" fight both read this
    private static boolean fast(Mob mob) {
        return mob.getAttributeValue(Attributes.MOVEMENT_SPEED) >= CombatRules.FAST_MOB_SPEED;
    }

    // a line to us, asked of every shooter in the tracker (FOE_RANGE): the in-the-way pick wants to know about one that has not hit
    // us yet, a skeleton in the open is the case. the ray is the costly bit, but there are never many shooters in 16 blocks.
    // "shooter" is the same word the Foe gets (a blaze is one, though it is not a ranged attack mob by class)
    private static boolean sees(Dimension dimension, Mob mob, long sinceHit, LocalPlayer player) {
        // a brute is the odd one: melee, but the run wants to know it sees us before it has hit anything
        if (dimension == Dimension.NETHER && typeOf(mob).equals("piglin_brute")) {
            return mob.distanceTo(player) <= CombatCommit.BRUTE_RUN_RANGE && mob.hasLineOfSight(player);
        }
        // and the wither skeleton: not a shooter, but the start rule (witherComing) wants its line when it is close
        if (dimension == Dimension.NETHER && typeOf(mob).equals("wither_skeleton")) {
            return mob.distanceTo(player) <= CombatCommit.WITHER_START_RANGE && mob.hasLineOfSight(player);
        }
        if (mob instanceof Creeper) return false;
        Kind kind = FoeRules.kindOf(dimension, typeOf(mob));
        return FoeRules.shoots(typeOf(mob), MobReachability.isRanged(mob), kind) && mob.hasLineOfSight(player);
    }

    // eye to its box, the number the reach rules use
    private static double reachGap(LocalPlayer player, Mob mob) {
        Vec3 eye = player.getEyePosition();
        AABB box = mob.getBoundingBox();
        return ReachRules.gap(eye.x, eye.y, eye.z, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    private static void sortNearestFirst(List<Mob> mobs, List<Foe> foes) {
        // tiny lists, insertion sort keeps the two in step without a pairing record
        for (int i = 1; i < foes.size(); i++) {
            for (int j = i; j > 0 && foes.get(j).distance() < foes.get(j - 1).distance(); j--) {
                java.util.Collections.swap(foes, j, j - 1);
                java.util.Collections.swap(mobs, j, j - 1);
            }
        }
    }

    // one line per transition, never per tick (the text is CombatLog's)
    private void log(AltoClef mod, Dimension dimension, Event event, String fighting, List<Mob> mobs, int blocks) {
        LocalPlayer player = mod.getPlayer();
        String line = CombatLog.line(dimension, event, _commit.why(), _commit.cornered(), _commit.noRoom(), _commit.cornerKind(), nameOf(mod, _commit.targetId()), fighting,
                describe(mobs), Math.round(player.getHealth()), blocks);
        if (line != null) Debug.logInternal(line);
    }

    private static String nameOf(AltoClef mod, int id) {
        return mod.getWorld().getEntity(id) instanceof Mob mob ? MobReachability.shortName(mob) : "it";
    }

    private static String typeOf(Mob mob) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath();
    }

    // the costly questions are asked lazily, FoeRules only asks them when the cheap ones left the mob in the running
    private static FoeRules.Candidate candidate(AltoClef mod, Dimension dimension, Mob mob, double distance, Provocations book, long now,
                                                LocalPlayer player, boolean fighting) {
        long sinceHit = book.sinceHit(mob.getId(), now);
        return new FoeRules.Candidate(typeOf(mob), mob.getId(), distance,
                () -> !FoeRules.liftsExclusion(typeOf(mob), fighting, sinceHit) && mod.getBehaviour().shouldExcludeFromMobDefense(mob),
                () -> EntityHelper.isAngryAtPlayer(mod, mob),
                () -> EntityHelper.canMobHarmPlayer(mod, mob),
                sinceHit, mob instanceof Slime slime ? slime.getSize() : 0, MobReachability.isRanged(mob), mob instanceof Creeper,
                reachGap(player, mob), sees(dimension, mob, sinceHit, player), book.sinceHitBefore(mob.getId(), now));
    }

    // how many of each kind, in the order they showed up
    private static String describe(List<Mob> mobs) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Mob mob : mobs) counts.merge(MobReachability.shortName(mob), 1, Integer::sum);
        if (counts.isEmpty()) return "nothing in sight";
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (out.length() > 0) out.append(", ");
            out.append(e.getValue()).append(' ').append(e.getKey()).append(e.getValue() == 1 ? "" : "s");
        }
        return out.toString();
    }
}
