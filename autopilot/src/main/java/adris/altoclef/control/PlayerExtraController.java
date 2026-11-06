package adris.altoclef.control;

import baritone.Baritone;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.BlockBreakingCancelEvent;
import adris.altoclef.eventbus.events.BlockBreakingEvent;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.ItemHelper;
import baritone.api.utils.input.Input;
import net.minecraft.client.player.LocalPlayer;
import adris.altoclef.util.helpers.BackOffRules;
import adris.altoclef.util.helpers.ReachRules;
import adris.altoclef.util.helpers.SwordSwing;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.Dimension;
import baritone.behavior.CombatAim;
import baritone.behavior.LegacyCombat;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class PlayerExtraController {

    private final AltoClef _mod;
    private BlockPos _blockBreakPos;
    private double _blockBreakProgress;

    public PlayerExtraController(AltoClef mod) {
        _mod = mod;

        EventBus.subscribe(BlockBreakingEvent.class, evt -> onBlockBreak(evt.blockPos, evt.progress));
        EventBus.subscribe(BlockBreakingCancelEvent.class, evt -> onBlockStopBreaking());
    }

    // continueDestroyBlock runs every tick we are mining, so a position nobody refreshed for this long is a block that
    // already broke. the cancel event only fires after three stopDestroyBlock calls and a finished break may never get them,
    // so the flag stayed up: a table placed where we had just mined a log failed its first interact check (it saw a
    // "break" making no progress) and got blacklisted and wandered off from
    private static final long BREAK_STALE_MS = 500;
    private volatile long _blockBreakStamp;

    private void onBlockBreak(BlockPos pos, double progress) {
        _blockBreakPos = pos;
        _blockBreakProgress = progress;
        _blockBreakStamp = System.currentTimeMillis();
    }

    private void onBlockStopBreaking() {
        _blockBreakPos = null;
        _blockBreakProgress = 0;
    }

    public BlockPos getBreakingBlockPos() {
        return _blockBreakPos;
    }

    public boolean isBreakingBlock() {
        return _blockBreakPos != null && System.currentTimeMillis() - _blockBreakStamp <= BREAK_STALE_MS;
    }

    public double getBreakingBlockProgress() {
        return _blockBreakProgress;
    }

    public boolean inRange(Entity entity) {
        return _mod.getPlayer().closerThan(entity, Baritone.settings().altoEntityReachRange.value);
    }

    // the last tick we were bitten, kept past hurtTime (10 ticks) so a mob that bites every 20 still reads as on us in between
    private long _lastHurtTick = Long.MIN_VALUE / 2;

    public long ticksSinceHurt() {
        long now = gameTime();
        if (_mod.getPlayer().hurtTime > 0) {
            _lastHurtTick = now;
        }
        return now - _lastHurtTick;
    }

    // swings that really went out, for the kill task's "stood in reach and nothing swung" cap
    private volatile long _swings;

    public long swings() {
        return _swings;
    }

    // the game tick of the last swing that went out. the kill task and the aura both swing in the same tick sometimes, and
    // two attack packets in one tick is a thing no hand does
    private long _lastSwingTick = Long.MIN_VALUE;

    public long lastSwingTick() {
        return _lastSwingTick;
    }

    public boolean attack(Entity entity) {
        return attack(entity, Baritone.settings().altoEntityReachRange.value);
    }

    // objectReach is how far a crystal or ghast ball can be. only CrystalStep passes more than the setting (CRYSTAL_REACH),
    // after its blast check said yes. the other crystal swings never asked, so they keep the short arm
    public boolean attack(Entity entity, double objectReach) {
        long now = gameTime();
        // the odd item is in hand to throw the charge away, nothing swings with it
        if (now == _lastSwingTick || chargeResetActive() || !canSwingAt(entity, objectReach)) {
            return false;
        }
        // pre 1.9 there is no cooldown to wait on, so everybody calls this every tick and the clicker is the pacing
        boolean legacy = LegacyCombat.active();
        if (legacy && !combat().clicker().due(now)) {
            return false;
        }
        _lastSwingTick = now;
        _swingAt = now;
        _swingScale = chargeScale();
        _swingTargetId = entity.getId();
        _swingBox = entity.getBoundingBox();
        // every deliberate swing comes through here: the one we hit is angry at us now, neutral or not
        _mod.getEntityTracker().noteProvoked(entity);
        logPiglinsBeside(entity, now);
        LegacyCombat.hit(_mod.getController(), _mod.getPlayer(), entity, InteractionHand.MAIN_HAND);
        if (legacy) {
            combat().clicker().clicked(now);
        }
        _swings++;
        adris.altoclef.util.snapshot.Snapshots.hit(_mod.getPlayer(), entity);
        return true;
    }

    // zombified piglins snap angry mid fight in the nether and we never knew which swing did it. one line per swing with one near, at most
    // every 10 ticks, so a sweep can be told from a stray hit
    private long _lastPiglinLog = -100;

    private void logPiglinsBeside(Entity target, long now) {
        if (now - _lastPiglinLog < 10 || WorldHelper.getCurrentDimension() != Dimension.NETHER) {
            return;
        }
        LocalPlayer player = _mod.getPlayer();
        StringBuilder near = new StringBuilder();
        for (ZombifiedPiglin z : _mod.getWorld().getEntitiesOfClass(ZombifiedPiglin.class, player.getBoundingBox().inflate(4))) {
            if (player.distanceTo(z) <= 4) {
                near.append(near.length() == 0 ? "" : ", ").append(String.format("%.1f", player.distanceTo(z))).append(z == target ? " (target)" : "")
                        .append(z.isAggressive() ? " angry" : " calm");
            }
        }
        if (near.length() > 0) {
            _lastPiglinLog = now;
            Debug.logInternal("swing: at " + BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).getPath() + ", charge "
                    + String.format("%.2f", chargeScale()) + ", zombified piglins within 4: " + near);
        }
    }

    // a mob only gets hit when the crosshair the server knows about is on it (CombatAim.onTarget). crystals and ghast balls
    // keep the old reach-only rule: nobody aims at those, the dragon fight and the fireball swat never did, and the server
    // doesn't care either way
    private boolean canSwingAt(Entity entity, double objectReach) {
        if (!(entity instanceof LivingEntity)) {
            return inRange(entity) || reachGap(_mod.getPlayer(), entity) <= objectReach;
        }
        return onTarget(entity);
    }

    public boolean onTarget(Entity entity) {
        return combat().onTarget(entity, swingReach());
    }

    // the same look test out to another reach: a thing that is still coming at us, judged on where it will be by the time
    // the swing lands (see MobDefenseChain.punchGhastBall)
    public boolean onTarget(Entity entity, double reach) {
        return combat().onTarget(entity, reach);
    }

    // ask for the head this tick (see CombatAim). every melee caller does this every tick it is fighting something, not only
    // on the tick it swings, or the aim only starts turning when the swing is already due
    public void aimAt(Entity entity, CombatAim.Priority priority) {
        handSettling(_mod.getPlayer());
        combat().want(entity, priority);
    }

    public void aimAt(Entity entity, CombatAim.Priority priority, Vec3 preferred) {
        handSettling(_mod.getPlayer());
        combat().want(entity, priority, preferred);
    }

    // the kill task's stall breaker, see CombatAim.wantOverPath
    public void aimAtOverPath(Entity entity) {
        handSettling(_mod.getPlayer());
        combat().wantOverPath(entity, CombatAim.Priority.KILL_TASK);
    }

    private CombatAim combat() {
        return _mod.getClientBaritone().getLookBehavior().combat();
    }

    // the reach a swing really has, see ReachRules.swingReach
    public double swingReach() {
        return ReachRules.swingReach(Baritone.settings().altoEntityReachRange.value, reachOf(_mod.getPlayer()));
    }

    // the stall breaker for a kill task that has stood in reach for too long: no hop, no ground check, no look at the keys,
    // just the hit when the cooldown allows it
    public boolean swingNow(Entity entity) {
        // same no-sweep rule as swordSwing: a calm neutral next to the target gets the early swing, a full one would sweep it
        // and the stall breaker is the swing that fires most in a fight that is biting us
        boolean ready = swordInHand() && entity instanceof LivingEntity target
                ? swordReady(target) : attackReady();
        if (!ready) {
            return false;
        }
        _crit.reset();
        _mod.getPlayer().setSprinting(false);
        return attack(entity);
    }

    // room to take a step back from a mob on us: a free neighbour cell (feet and head height) and the headroom the hop needs.
    // a shaft or a low tunnel has none, and a run-away path there only takes the keys from the swing
    public boolean roomToBackOff() {
        Level level = _mod.getWorld();
        BlockPos feet = _mod.getPlayer().blockPosition();
        boolean side = false;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos cell = feet.relative(dir);
            if (passable(level, cell) && passable(level, cell.above())) {
                side = true;
                break;
            }
        }
        return BackOffRules.roomToBackOff(side, passable(level, feet.above(2)));
    }

    // one machine for the whole bot. the kill task and the force field both swing at the same mob in the same tick, and
    // two machines would each want their own jump
    private final CritTiming _crit = new CritTiming();

    private long gameTime() {
        return _mod.getWorld().getGameTime();
    }

    // mid jump, with somebody still steering it. the kill tasks use this to not call a hop "off the ground, go approach"
    public boolean critInFlight() {
        return _crit.inFlight(gameTime());
    }

    // the server asks for the strength with 0.5 of a tick of partial, so that is the number that decides full damage and the
    // crit. asking with 0 here was a half tick behind the thing we are trying to line up with
    private static final float SERVER_PARTIAL = 0.5f;

    // a swing now would be at full strength, as the server will see it
    public boolean attackReady() {
        // pre 1.9 there is nothing to charge (ViaFabricPlus fakes a full scale there anyway). not clicker().due either: the
        // kill task's back-off reads this, and a due that flips every other tick starts and cancels a run-away path every tick
        if (LegacyCombat.active()) {
            return true;
        }
        return _mod.getPlayer().getAttackStrengthScale(SERVER_PARTIAL) >= 1;
    }

    // ticks of cooldown the held item has charged, capped at its delay. the weapon pick uses it to tell a fresh swing (a swap
    // is free) from a half charged one (a swap throws it away)
    public double chargedTicks() {
        // nothing to throw away pre 1.9, so every swap counts as right after a swing. the fake full scale would block them all
        if (LegacyCombat.active()) {
            return 0;
        }
        LocalPlayer player = _mod.getPlayer();
        return player.getAttackStrengthScale(SERVER_PARTIAL) * player.getCurrentItemAttackStrengthDelay();
    }

    // ticks until the swing is at full strength, 0 or less when it is. the scale already knows the weapon's delay
    private double ticksToFull() {
        LocalPlayer player = _mod.getPlayer();
        return (1 - player.getAttackStrengthScale(SERVER_PARTIAL)) * player.getCurrentItemAttackStrengthDelay();
    }

    // a melee caller normally sits out the ticks where the cooldown is not full. a crit has to start jumping before that
    // (see CritTiming.LEAD_TICKS) and has to be steered all the way down, so those ticks are worth a call. false whenever
    // baritone is walking, then nobody jumps and the old "wait for full" rules are the whole story
    public boolean wantsCritTick() {
        if (LegacyCombat.active() || _mod.getClientBaritone().getPathingBehavior().isPathing()
                || _mod.getMobDefenseChain().creeperStepActive()) {
            return false;
        }
        // a sword never hops. the ticks right before the charge fills are still worth a call, that is when sprint comes off.
        // from the early swing scale on too, the callers gate on this and the swing beside a calm neutral goes out down there
        if (swordInHand()) {
            return ticksToFull() <= SwordSwing.LOCK_LEAD_TICKS || chargeScale() >= SwordSwing.CALM_SWING_SCALE;
        }
        if (!Baritone.settings().altoJumpCrits.value) {
            return false;
        }
        // mid hop we still want the call (it is how the hop gets cancelled), a new one under pressure we don't: that is
        // the stretch where the kill task used to sit out the cooldown while a crowd hit it. same question CritTiming asks
        if (critInFlight()) {
            return true;
        }
        return !underPressure() && ticksToFull() <= CritTiming.LEAD_TICKS;
    }

    private boolean underPressure() {
        return CritTiming.underPressure(_mod.getMobDefenseChain().meleeNear(_mod), _mod.getPlayer().getHealth());
    }

    // every melee swing at a mob goes through here so the crit timing lives in one place. returns whether a swing or a
    // jump went out this tick. with altoJumpCrits off (or a target that can't crit) it is the plain rule: hit when the
    // cooldown is full and we are on the ground, falling, in water, or rising with no crit hop in flight
    public boolean melee(Entity entity) {
        if (chargeResetActive()) {
            return false;
        }
        LocalPlayer player = _mod.getPlayer();
        // a creeper step owns the keys: no hop, no sprint cancel, no walking up. a plain swing is all that is left of the module
        boolean stepping = _mod.getMobDefenseChain().creeperStepActive();
        // pre 1.9 the scale is faked full, so CritTiming would see a charged swing every tick and hop at everything. plain
        // clicking it is
        if (stepping || LegacyCombat.active() || !(entity instanceof LivingEntity target)) {
            _crit.reset();
            // the step owns sprint and the keys, the sweep rule is still ours
            if (stepping && !LegacyCombat.active() && swordInHand() && entity instanceof LivingEntity stepTarget) {
                return plainSwing(player, entity, swordReady(stepTarget));
            }
            return plainSwing(player, entity);
        }
        // a sword is paced by the charge and sweeps, no hop (see SwordSwing)
        if (swordInHand()) {
            _crit.reset();
            return swordSwing(player, target);
        }
        if (!Baritone.settings().altoJumpCrits.value) {
            _crit.reset();
            return plainSwing(player, entity);
        }
        double gap = reachGap(player, target);
        CritTiming.Sample sample = sample(player, target, gap);
        CritTiming.Step step = _crit.step(gameTime(), sample);
        logStep(step, sample);
        if (step == CritTiming.Step.JUMP || step == CritTiming.Step.APPROACH || critInFlight()) {
            // from the walk up to the swing nothing gets to turn sprint back on (toggle sprint, a held key). refreshed
            // every tick it's wanted, so it ends by itself the tick the hop does
            _mod.getClientBaritone().getInputOverrideHandler().lockSprintOff();
        }
        switch (step) {
            case JUMP:
                // vanilla throws the crit away for a sprinting hit, and the jump itself would boost us forward. the stop
                // packet goes out with this tick's movement, long before we are falling
                player.setSprinting(false);
                _mod.getInputControls().tryPress(Input.JUMP);
                walkUp(gap);
                return true;
            case APPROACH:
                player.setSprinting(false);
                walkUp(gap);
                return false;
            case SWING:
                if (!attackReady()) {
                    return false;
                }
                player.setSprinting(false);
                attack(entity);
                return true;
            case WAIT:
                // a held opener while the approach path is still alive: baritone has the keys and is about to be told
                // to stop, nothing of ours to press. (mid hop a path is a give up, so this and a settling weapon swap are
                // the only WAITs on the ground with one. in the air it is a crit waiting on sprint or the aim, and that
                // needs the lines below)
                if (heldOpener(sample)) {
                    return false;
                }
                // mid hop. jumping straight up while the mob stands 2 blocks off is how the fall ends with nothing to hit,
                // so keep closing (no sprint, that is the crit killer)
                player.setSprinting(false);
                walkUp(gap);
                return false;
            default:
                return plainSwing(player, entity);
        }
    }

    // the hand holds a sword (the sweep and the no-hop rules are the sword's)
    public boolean swordInHand() {
        return _mod.getPlayer().getMainHandItem().is(ItemTags.SWORDS);
    }

    // charge, then swing. shortly before the charge is full sprint is locked off so the swing can sweep. if we were still
    // sprinting when this call began, the stop packet has not gone out yet and the swing waits a tick for it
    private boolean swordSwing(LocalPlayer player, LivingEntity target) {
        boolean wasSprinting = player.isSprinting();
        boolean calm = calmBeside(target);
        boolean lock = SwordSwing.lockSprint(true, inKillReach(target), ticksToFull(), calm);
        if (lock) {
            _mod.getClientBaritone().getInputOverrideHandler().lockSprintOff();
            player.setSprinting(false);
        }
        // a neutral mob beside the target and the sweep would anger it: swing early, under the 0.9 a sweep needs (SwordSwing)
        boolean ready = swordReady(target);
        if (!SwordSwing.swing(ready, lock, !wasSprinting)) {
            return false;
        }
        return plainSwing(player, target, ready);
    }

    // the scale the server will judge the swing by, pre 1.9 always full
    private double chargeScale() {
        return LegacyCombat.active() ? 1 : _mod.getPlayer().getAttackStrengthScale(SERVER_PARTIAL);
    }

    // a neutral mob nobody has poked near the target (SwordSwing.calmNear): a full charge sword swing would sweep it and start a
    // fight we did not pick. we swing early then (SwordSwing.charged), and leave sprint alone: a sprint hit can't sweep either
    public boolean calmNeutralNear(Entity entity) {
        return entity instanceof LivingEntity target && calmBeside(target);
    }

    private boolean calmBeside(LivingEntity target) {
        AABB box = target.getBoundingBox();
        // the box around the target alone missed piglins right beside us while the target (a teleporting enderman) stood off a
        // couple of blocks: the client's view of who stands where lags the server's, and they got swept anyway. anything calm
        // within 3 of us counts too
        AABB a = box.inflate(2);
        AABB b = _mod.getPlayer().getBoundingBox().inflate(SwordSwing.calmReach());
        AABB around = new AABB(Math.min(a.minX, b.minX), Math.min(a.minY, b.minY), Math.min(a.minZ, b.minZ),
                Math.max(a.maxX, b.maxX), Math.max(a.maxY, b.maxY), Math.max(a.maxZ, b.maxZ));
        for (Mob mob : _mod.getWorld().getEntitiesOfClass(Mob.class, around)) {
            if (mob == target || !EntityHelper.isCalmNeutral(_mod, mob)) continue;
            double d = _mod.getPlayer().distanceToSqr(mob);
            if (SwordSwing.calmNear(box, mob.getBoundingBox(), d) || SwordSwing.calmByUs(d)) {
                return true;
            }
        }
        return false;
    }

    // the shared gate for every sword swing (melee, the stall breaker and the aura all end up here): charged enough, and never a
    // full charge swing with a calm neutral near. the early window is only four ticks, an aim that misses all of them lands past
    // the sweep line, and then the charge gets thrown away (item swap) instead of held: a held swing is a sweep waiting for the
    // mob to wander into the box, and there is no timeout that gives up and swings. no client movement exemptions either, the
    // server's idea of where everyone stands is a tick or two off ours
    private boolean swordReady(LivingEntity target) {
        boolean calm = calmBeside(target);
        double scale = chargeScale();
        if (SwordSwing.needsReset(scale, calm)) {
            if (resetCharge()) {
                _resetStuckSince = -1;
                return false;
            }
            // nothing to swap to or park on: wait a while for an item to show up, then swing (never on the first stuck tick)
            long now = gameTime();
            // (a clock that went backwards is a new world: start the wait over)
            if (_resetStuckSince < 0 || _resetStuckSince > now) {
                _resetStuckSince = now;
                return false;
            }
            if (SwordSwing.resetGaveUp(now, _resetStuckSince)) {
                // (and forget what the swap refused: the inventory may have moved since)
                _resetFailed.clear();
                _resetStuckSince = -1; // one swing per wait, the next full charge earns its own
                return true;
            }
            return false;
        }
        _resetStuckSince = -1;
        return SwordSwing.charged(scale, calm);
    }

    private long _resetAt = Long.MIN_VALUE / 2;
    private long _resetStuckSince = -1;
    // odd items the swap would not take (offhand and friends), skipped until a reset works again
    private final java.util.Set<Item> _resetFailed = new java.util.HashSet<>();

    // an odd item went into the hand to throw the charge away and has to stay a few ticks, see SwordSwing.RESET_HOLD_TICKS.
    // the weapon pick leaves the hand alone meanwhile
    public boolean chargeResetActive() {
        return SwordSwing.resetHeld(gameTime(), _resetAt);
    }

    // vanilla zeroes the charge when the held item changes type. an axe never sweeps so it is the better odd item, else any
    // block, else anything that is not a sword. true when the hand changed (or got parked), false when there was no way to
    private boolean resetCharge() {
        Item odd = resetItem();
        if (odd == null) {
            return emptyHandReset();
        }
        if (_mod.getSlotHandler().forceEquipItem(odd)) {
            // (the axe holds too: the callers without a target, the perch hit and the golem fight, swap straight back otherwise)
            _resetAt = gameTime();
            _resetFailed.clear();
            Debug.logInternal("swing: calm neutral near and a full charge, swapped to " + BuiltInRegistries.ITEM.getKey(odd).getPath()
                    + " to throw the charge away");
            return true;
        }
        // the swap can't reach it: remember, so the next pick tries something else
        _resetFailed.add(odd);
        return emptyHandReset();
    }

    // nothing but swords and armor on us: park the hand on an empty hotbar slot instead. the weapon pick walks back to slot 1
    private boolean emptyHandReset() {
        Inventory inventory = _mod.getPlayer().getInventory();
        for (int slot = 0; slot < 9; slot++) {
            if (slot != inventory.getSelectedSlot() && inventory.getItem(slot).isEmpty()) {
                inventory.setSelectedSlot(slot);
                _resetAt = gameTime();
                Debug.logInternal("swing: calm neutral near and a full charge, nothing to swap to, empty hand to throw the charge away");
                return true;
            }
        }
        Debug.logInternal("swing: calm neutral near and a full charge, nothing to swap to, not swinging");
        return false;
    }

    private Item resetItem() {
        Item block = null;
        Item other = null;
        for (ItemStack stack : _mod.getItemStorage().getItemStacksPlayerInventory(false)) {
            Item item = stack.getItem();
            if (stack.isEmpty() || ItemHelper.isSword(item) || ItemHelper.isEquippable(item) || _resetFailed.contains(item)) {
                continue;
            }
            if (ItemHelper.isAxe(item)) {
                return item;
            }
            if (block == null && item instanceof BlockItem) {
                block = item;
            } else if (other == null) {
                other = item;
            }
        }
        return block != null ? block : other;
    }

    // what the last swing that went out looked like, for the damage watch below
    private long _swingAt = Long.MIN_VALUE / 2;
    private double _swingScale;
    private int _swingTargetId = -1;
    private AABB _swingBox;

    // neutral mobs that took damage from us: who, how charged our last swing was, and whether the victim was the target or sat in
    // its sweep box. the client has the damage event's source (LivingEntity.handleDamageEvent sets lastDamageSource and hurtTime),
    // so this answers "which swing angered it". called every tick from the mod tick, once per mob per second
    private final java.util.Map<Integer, Long> _hurtLogged = new java.util.HashMap<>();

    public void tickHurtWatch() {
        LocalPlayer player = _mod.getPlayer();
        if (player == null || _mod.getWorld() == null) {
            return;
        }
        long now = gameTime();
        _hurtLogged.values().removeIf(at -> now - at > 200);
        for (Mob mob : _mod.getWorld().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(8))) {
            if (mob.hurtTime < 9) {
                continue;
            }
            Long was = _hurtLogged.get(mob.getId());
            if (was != null && now - was < 20) {
                continue;
            }
            DamageSource source = mob.getLastDamageSource();
            if (source == null || source.getEntity() != player) {
                continue;
            }
            _hurtLogged.put(mob.getId(), now);
            boolean target = mob.getId() == _swingTargetId;
            // a sweep victim never went through attack(), the anger model has to hear about it from here
            if (!target) {
                _mod.getEntityTracker().noteProvoked(mob);
            }
            if (!(mob instanceof ZombifiedPiglin || mob instanceof Piglin || mob instanceof Enderman)) {
                continue;
            }
            boolean inBox = !target && _swingBox != null && SwordSwing.inSweepBox(_swingBox, mob.getBoundingBox(), player.distanceToSqr(mob));
            Debug.logInternal("hurt by us: " + BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath() + " #" + mob.getId() + ", "
                    + String.format("%.1f", player.distanceTo(mob)) + " away, "
                    + (target ? "the target" : inBox ? "NOT the target, inside the sweep box now" : "NOT the target, outside the sweep box now")
                    + ", last swing " + (now - _swingAt) + " ticks ago at charge " + String.format("%.2f", _swingScale)
                    + ", sprinting " + player.isSprinting() + ", angry now " + mob.isAggressive());
        }
    }

    // what the machine said, once per change and not per tick, so the play log shows which veto is still keeping the bot
    // from crit hopping. WAIT is the boring middle of every hop and stays out of it, except the opener being held on the
    // ground, which is worth a line
    private String _lastCritLog = "";

    // a WAIT on the ground with a path alive. the machine says that for a held opener (and a settling swap, which is a
    // tick or two of the same thing)
    private static boolean heldOpener(CritTiming.Sample s) {
        return s.onGround && s.pathing;
    }

    private void logStep(CritTiming.Step step, CritTiming.Sample s) {
        if (step == CritTiming.Step.WAIT && !heldOpener(s)) {
            return;
        }
        String line = step.name();
        if (step == CritTiming.Step.WAIT) {
            line += " (holding the opener for the path to let go)";
        }
        if (step == CritTiming.Step.PLAIN || step == CritTiming.Step.APPROACH) {
            String veto = CritTiming.hopVeto(s);
            line += veto == null ? " (nothing vetoes: mob too far or leaving, or a plain hit is owed after a give up)" :" (" + veto + ")";
        }
        if (s.sprinting && step != CritTiming.Step.PLAIN) {
            line += " [sprinting]";
        }
        if (!line.equals(_lastCritLog)) {
            _lastCritLog = line;
            // hand, raw scale and the eye to box gap ride along: a cooldown that never fills is either the wrong item (a swap
            // resets it) or a mob that is not in the three block reach, and those two look the same without them
            LocalPlayer player = _mod.getPlayer();
            Debug.logInternal("crit: " + line + ", to full " + String.format("%.1f", s.ticksToFull) + ", near " + s.meleeNear
                    + ", hp " + s.health + (s.pathing ? ", pathing" : "") + (s.aimed ? ", aimed" : "") + (s.settling ? ", settling" : "")
                    + ", hand " + BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).getPath()
                    + ", scale " + String.format("%.2f", player.getAttackStrengthScale(SERVER_PARTIAL))
                    + ", gap " + String.format("%.2f", s.reachGap));
        }
    }

    // tapping forward is enough: no path is walking (the machine never walks up with one), so CombatAim has the head and
    // the movement yaw is the aim yaw, which is the mob or a few degrees from it while the turn finishes. tryPress lets go
    // by itself, so nothing is left held down if the task changes its mind. no point shoving into a mob we are touching
    private void walkUp(double gap) {
        if (gap > WALK_UP_GAP) {
            _mod.getInputControls().tryPress(Input.MOVE_FORWARD);
        }
    }

    private static final double WALK_UP_GAP = 1.2;

    // eye to the nearest point of the target's box, which is the distance vanilla compares to the interaction range
    private static double reachGap(LocalPlayer player, Entity target) {
        Vec3 eye = player.getEyePosition();
        AABB box = target.getBoundingBox();
        return ReachRules.gap(eye.x, eye.y, eye.z, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    // the kill tasks' idea of "close enough to hit": the eye to its box inside the swing's own reach, minus the bit the ray
    // to the middle of the mob is longer than the shortest gap (ReachRules.killReach)
    public boolean inKillReach(Entity entity) {
        return ReachRules.killReach(reachGap(_mod.getPlayer(), entity), Baritone.settings().altoEntityReachRange.value,
                reachOf(_mod.getPlayer()));
    }

    // how fast the target is going away from us along the flat line between us, blocks per tick (negative when it is
    // coming at us). from the position change, a remote entity's deltaMovement on the client is mostly a lie
    private static double retreatSpeed(LocalPlayer player, Entity target) {
        double dx = target.getX() - player.getX();
        double dz = target.getZ() - player.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-4) {
            return 0;
        }
        return ((target.getX() - target.xo) * dx + (target.getZ() - target.zo) * dz) / len;
    }

    // the interaction range attribute (3 for a plain player, creative and tools can change it)
    private static double reachOf(LocalPlayer player) {
        double reach = player.entityInteractionRange();
        return reach > 0 ? reach : ReachRules.MELEE_REACH;
    }

    // a hop is on, or planned for this cooldown, and the back-off in the kill tasks should keep its hands to itself. this
    // asks CritTiming.hopPlanned: the hop question with the path left out (the path in the way is usually the back-off
    // itself) and the lead window left out (a back-off that starts mid charge is still walking when the lead comes)
    public boolean hopWantsToStayClose(Entity entity) {
        if (LegacyCombat.active() || !Baritone.settings().altoJumpCrits.value || _mod.getMobDefenseChain().creeperStepActive()
                || swordInHand()) {
            return false;
        }
        if (critInFlight()) {
            return true;
        }
        if (!(entity instanceof LivingEntity target)) {
            return false;
        }
        LocalPlayer player = _mod.getPlayer();
        return CritTiming.hopPlanned(sample(player, target, reachGap(player, target)));
    }

    // on the ground, coming down or swimming is the old rule, and it is what keeps a swing from spending the rise of a crit hop.
    // with no hop in flight there is no crit to spare, so a bot climbing or pillaring with a mob on it gets to hit it too
    private boolean plainSwing(LocalPlayer player, Entity entity) {
        return plainSwing(player, entity, attackReady());
    }

    private boolean plainSwing(LocalPlayer player, Entity entity, boolean ready) {
        if (ready && (player.onGround() || player.getDeltaMovement().y() < 0 || player.isInWater() || !critInFlight())) {
            attack(entity);
            return true;
        }
        return false;
    }

    private CritTiming.Sample sample(LocalPlayer player, LivingEntity target, double gap) {
        CritTiming.Sample s = new CritTiming.Sample();
        // the same reach the swing is gated on, or the machine plans hops at mobs the swing then refuses
        s.inReach = inKillReach(target);
        s.reachGap = gap;
        s.reach = swingReach();
        s.aimed = onTarget(target);
        s.settling = handSettling(player);
        // hurtTime starts at 10 on a hit and counts down, which is about the "lately" we want. a creeper never hits us before
        // it goes off, so it counts as hitting us from the start: no opener hold on a lit fuse
        s.recentlyHurt = player.hurtTime > 0 || target instanceof Creeper;
        s.retreatSpeed = retreatSpeed(player, target);
        s.onGround = player.onGround();
        s.falling = player.getDeltaMovement().y() < 0 && player.fallDistance > 0;
        s.ticksToFull = ticksToFull();
        // armor and sharpness would make a normal hit weaker or stronger than this. wrong in the safe direction either
        // way: a hit that "kills" on paper but doesn't just gets the next swing, a crit we skip is half a heart
        s.normalHitKills = target.getHealth() + target.getAbsorptionAmount() <= ItemHelper.getAttackDamage(player.getMainHandItem().getItem());
        s.inFluid = player.isInWater() || player.isInLava() || player.isUnderWater() || player.isSwimming() || player.isFallFlying();
        s.onClimbable = player.onClimbable();
        s.blind = player.hasEffect(MobEffects.BLINDNESS);
        s.riding = player.isPassenger();
        s.usingItem = player.isUsingItem();
        // the key being down counts too: the shield goes up one player tick after the press
        s.shielding = player.isBlocking() || _mod.getInputControls().isHeldDown(Input.CLICK_RIGHT);
        s.pathing = _mod.getClientBaritone().getPathingBehavior().isPathing();
        s.sprinting = player.isSprinting();
        s.meleeNear = _mod.getMobDefenseChain().meleeNear(_mod);
        s.health = player.getHealth();
        // with a melee mob close the swing is owed again, no free openers while something is on us
        s.harmless = s.meleeNear == 0 && harmless(target);
        Level level = _mod.getWorld();
        BlockPos feet = player.blockPosition();
        // the jump is 1.25 up and we are 1.8 tall, so the cell two above our feet is the one a ceiling bonks us with
        s.lowHeadroom = !passable(level, feet.above(2));
        s.unsafeFloor = !floorAround(level, feet);
        return s;
    }

    // the item in hand the last time we looked, and the game time it last changed
    private Item _handItem;
    private long _handChangedAt = Long.MIN_VALUE;

    // the hand changed this tick or the last one. by item, not slot and not stack: forceEquipItem moves the slot before it
    // knows the swap worked, and the server swaps stack objects for equal copies all the time. a different item is also
    // exactly what vanilla resets the cooldown and the attack speed for, and it only does that in the player tick, after
    // we read them
    private boolean handSettling(LocalPlayer player) {
        Item now = player.getMainHandItem().getItem();
        long t = gameTime();
        if (_handItem == null) {
            _handItem = now;
        } else if (now != _handItem) {
            _handItem = now;
            _handChangedAt = t;
        }
        // (a clock that went backwards is a new world, nothing is settling there)
        return _handChangedAt != Long.MIN_VALUE && t >= _handChangedAt && t - _handChangedAt <= 1;
    }

    // food on legs: pig, cow, sheep, chicken, rabbit. a wolf, a bee or a polar bear bites back once poked (NeutralMob), a
    // hoglin is an Animal that never needed poking (Enemy), and everything that is not an Animal is a no by default
    private static boolean harmless(LivingEntity target) {
        return target instanceof Animal && !(target instanceof Enemy) && !(target instanceof NeutralMob);
    }

    private static boolean passable(Level level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    // a hit knocks us back, and a knockback next to a ledge is how a hop turns into a swim in lava. all four sides need
    // ground within two blocks down and no lava on the way to it
    private static boolean floorAround(Level level, BlockPos feet) {
        return floorUnder(level, feet.north()) && floorUnder(level, feet.south())
                && floorUnder(level, feet.east()) && floorUnder(level, feet.west());
    }

    private static boolean floorUnder(Level level, BlockPos column) {
        for (int down = 0; down <= 3; down++) {
            BlockPos pos = column.below(down);
            BlockState state = level.getBlockState(pos);
            if (state.getFluidState().is(FluidTags.LAVA)) {
                return false;
            }
            if (down > 0 && !state.getCollisionShape(level, pos).isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
