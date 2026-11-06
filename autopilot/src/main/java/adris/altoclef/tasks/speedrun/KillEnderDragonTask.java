package adris.altoclef.tasks.speedrun;

import adris.altoclef.AltoClef;
import adris.altoclef.BotBehaviour;
import adris.altoclef.Debug;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.entity.AbstractKillEntityTask;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.slot.MoveItemToSlotFromInventoryTask;
import adris.altoclef.tasks.speedrun.gamer.end.DragonWaitRules;
import adris.altoclef.util.slots.PlayerSlot;
import net.minecraft.world.entity.EquipmentSlot;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.CombatRules;
import adris.altoclef.util.helpers.FoeRules;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.altoclef.SettingsOverrides;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.DragonPhaseInstance;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Here we go
 * the final stretch
 * <p>
 * Until something inevitably fucks up and I gotta go back here to fix it
 * in which case this'll be pretty ironic.
 */
public class KillEnderDragonTask extends Task {

    private static final String[] DIAMOND_ARMORS = new String[]{"diamond_chestplate", "diamond_leggings", "diamond_helmet", "diamond_boots"};
    // Don't accidentally anger endermen lol
    private final TimerGame _lookDownTimer = new TimerGame(0.5);
    private final CrystalStep _crystals = new CrystalStep();
    private final PunkEnderDragonTask _punkTask = new PunkEnderDragonTask();
    // the ring the bed fight waits on, breath and fireball dodge included
    private final WaitForDragonAndPearlTask _keepClear = new WaitForDragonAndPearlTask(true);
    // not cleared in onStart: a meal interrupts us and comes back through there
    private boolean _stayingAway;
    // false for the outer islands trip: the gateways only show up once the dragon is dead, but we are not done after that
    private final boolean _enterExitPortal;
    // the level we pushed, for the hand over on a pause
    private BotBehaviour.State _level;
    private Task _pumpkinTask;
    private BlockPos _exitPortalTop;

    public KillEnderDragonTask() {
        this(true);
    }

    public KillEnderDragonTask(boolean enterExitPortal) {
        _enterExitPortal = enterExitPortal;
    }

    // a carved pumpkin on the head: endermen do not care that we look at them
    public static boolean pumpkinWorn(AltoClef mod) {
        return mod.getPlayer().getItemBySlot(EquipmentSlot.HEAD).is(Items.CARVED_PUMPKIN);
    }

    private static Task getPickupTaskIfAny(AltoClef mod, Item... itemsToPickup) {
        for (Item check : itemsToPickup) {
            if (mod.getEntityTracker().itemDropped(check)) {
                return new PickupDroppedItemTask(new ItemTarget(check), true);
            }
        }
        return null;
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBehaviour().releasePaused(this);
        _level = mod.getBehaviour().push();
        mod.getBlockTracker().trackBlock(Blocks.END_PORTAL);
        // don't forcefield calm endermen (an angry one is the aura's again).
        mod.getBehaviour().addForceFieldExclusion(KillEnderDragonTask::calmDragonFightEntity);
        // and mob defense keeps out too: an enderman mid-crystal is not worth a fight or a run
        mod.getBehaviour().addMobDefenseExclusion(KillEnderDragonTask::ownedByTheDragonFight);
        mod.getBehaviour().setPreferredStairs(true);
    }

    // the mobs the dragon tasks deal with on their own terms while they are live (shared by the sword and the bed task)
    public static boolean ownedByTheDragonFight(Entity entity) {
        return FoeRules.dragonFightOwns(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath(), !angryEnderman(entity));
    }

    // an enderman that has made us its target (creepy is the synced flag, aggressive the melee goal's)
    public static boolean angryEnderman(Entity entity) {
        return entity instanceof Enderman man && (man.isCreepy() || man.isAggressive());
    }

    // the aura's list: the dragon, its parts and calm endermen. an angry one is fair game for the aura too
    public static boolean calmDragonFightEntity(Entity entity) {
        return (entity instanceof Enderman && !angryEnderman(entity)) || entity instanceof EnderDragon || entity instanceof EnderDragonPart;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_exitPortalTop == null) {
            _exitPortalTop = locateExitPortalTop(mod);
        }

        // Collect the following if dropped:
        // - Diamond Sword
        // - Diamond Armor
        // - Food (List)

        List<Item> toPickUp = new ArrayList<>(Arrays.asList(Items.DIAMOND_SWORD, Items.DIAMOND_BOOTS, Items.DIAMOND_LEGGINGS, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_HELMET));
        if (StorageHelper.calculateInventoryFoodScore(mod) < 10) {
            toPickUp.addAll(Arrays.asList(
                    Items.BREAD, Items.COOKED_BEEF, Items.COOKED_CHICKEN, Items.COOKED_MUTTON, Items.COOKED_RABBIT, Items.COOKED_PORKCHOP
            ));
        }

        Task pickupDrops = getPickupTaskIfAny(mod, toPickUp.toArray(Item[]::new));
        if (pickupDrops != null) {
            setDebugState("Picking up drops in end.");
            return pickupDrops;
        }

        if (mod.getItemStorage().hasItem(Items.CARVED_PUMPKIN) && !pumpkinWorn(mod)) {
            setDebugState("Putting on a pumpkin", "Putting on a pumpkin");
            if (_pumpkinTask == null) {
                _pumpkinTask = new MoveItemToSlotFromInventoryTask(new ItemTarget(Items.CARVED_PUMPKIN, 1), PlayerSlot.getEquipSlot(EquipmentSlot.HEAD));
            }
            return _pumpkinTask;
        }

        // If not equipped diamond armor and we have any, equip it.
        for (Item armor : ItemHelper.DIAMOND_ARMORS) {
            // the pumpkin sits where the helmet goes, do not swap them back and forth
            if (armor == Items.DIAMOND_HELMET && pumpkinWorn(mod)) {
                continue;
            }
            // worn netherite is better than the diamond piece in the bag (EndGear.armorToWear's rule), swapping would only make the
            // two of them trade places every time the dragon phase hands us back
            Item netherite = ItemHelper.NETHERITE_ARMORS[java.util.Arrays.asList(ItemHelper.DIAMOND_ARMORS).indexOf(armor)];
            if (StorageHelper.isArmorEquipped(mod, netherite)) {
                continue;
            }
            try {
                if (mod.getItemStorage().hasItem(armor) && !StorageHelper.isArmorEquipped(mod, armor)) {
                    setDebugState("Equipping " + armor);
                    return new EquipArmorTask(armor);
                }
            } catch (NullPointerException e) {
                // Should never happen.
                Debug.logError("NullpointerException that Should never happen.");
                e.printStackTrace();
            }
        }

        if (!isRailingOnDragon() && _lookDownTimer.elapsed() && !mod.getControllerExtras().isBreakingBlock()) {
            if (mod.getPlayer().onGround()) {
                _lookDownTimer.reset();
                mod.getClientBaritone().getLookBehavior().updateTarget(new Rotation(0f, 90f), true);
            }
        }

        // If there is a portal, enter it.
        if (_enterExitPortal && mod.getBlockTracker().anyFound(Blocks.END_PORTAL)) {
            setDebugState("Entering portal to beat the game.");
            return new DoToClosestBlockTask(
                    blockPos -> new GetToBlockTask(blockPos.above(), false),
                    Blocks.END_PORTAL
            );
        }

        if (stayAway(mod)) {
            setDebugState("Too hurt for this, keeping clear", "Healing up");
            _keepClear.setExitPortalTop(_exitPortalTop);
            return _keepClear;
        }

        // If we have no building materials (stone + cobble + end stone), get end stone
        // If there are crystals, suicide blow em up.
        // If there are no crystals, punk the dragon if it's close.
        Task crystals = _crystals.tick(mod, entity -> true, this::setDebugState);
        if (crystals != null) {
            return crystals;
        }

        // Punk dragon
        if (mod.getEntityTracker().entityFound(EnderDragon.class)) {
            setDebugState("Punking dragon");
            return _punkTask;
        }
        setDebugState("Couldn't find ender dragon... This can be very good or bad news.");
        return null;
        //return new KillEntitiesTask(EnderDragonEntity.class);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // a chain cutting in mid fight must not turn the endermen and the dragon back into foes (same as ResourceTask)
        if (isInterrupting()) {
            mod.getBehaviour().holdWhilePaused(this, chain(), _level);
        } else {
            mod.getBehaviour().releasePaused(this);
        }
        _level = null;
        mod.getBehaviour().pop();
        mod.getBlockTracker().stopTracking(Blocks.END_PORTAL);
    }

    @Override
    protected void onStopWhilePaused(AltoClef mod) {
        mod.getBehaviour().releasePaused(this);
    }

    @Override
    protected boolean isEqual(Task other) {
        // the outer islands trip must not be mistaken for the plain fight, it never walks into the exit portal
        return other instanceof KillEnderDragonTask task && task._enterExitPortal == _enterExitPortal;
    }

    @Override
    protected String toHudString() {
        return "Fighting the Ender Dragon";
    }

    @Override
    protected String toDebugString() {
        return "Killing Ender Dragon";
    }

    // the meal is the food chain's, it already eats under the same 14. what the crystals and the punk can't have is the hp
    private boolean stayAway(AltoClef mod) {
        float hp = mod.getPlayer().getHealth();
        boolean canHeal = mod.getPlayer().getFoodData().getFoodLevel() >= CombatRules.REGEN_HUNGER || mod.getFoodChain().hasFood();
        boolean next = _exitPortalTop != null && mod.getEntityTracker().entityFound(EnderDragon.class) && DragonWaitRules.stayAway(_stayingAway, hp, canHeal);
        if (next != _stayingAway) {
            Debug.logMessage(String.format("dragon: %s at hp %.1f", next ? "keeping clear" : "back to the fight", hp));
            _stayingAway = next;
        }
        return next;
    }

    private boolean isRailingOnDragon() {
        return _punkTask.getMode() == Mode.RAILING;
    }

    private BlockPos locateExitPortalTop(AltoClef mod) {
        if (!mod.getChunkTracker().isChunkLoaded(new BlockPos(0, 64, 0))) return null;
        int height = WorldHelper.getGroundHeight(mod, 0, 0, Blocks.BEDROCK);
        if (height != -1) return new BlockPos(0, height, 0);
        return null;
    }

    private enum Mode {
        WAITING_FOR_PERCH,
        RAILING
    }

    private class PunkEnderDragonTask extends Task {

        private final HashMap<BlockPos, Double> _breathCostMap = new HashMap<>();
        private final TimerGame _hitHoldTimer = new TimerGame(0.1);
        private final TimerGame _hitResetTimer = new TimerGame(0.4);
        private final TimerGame _randomWanderChangeTimeout = new TimerGame(20);
        private Mode _mode = Mode.WAITING_FOR_PERCH;

        private static final int WANDER_TRIES = 30;
        private static final int ISLAND_SCAN_TOP = 90;
        private static final int ISLAND_SCAN_BOTTOM = 30;
        private BlockPos _randomWanderPos;
        private boolean _wasHitting;
        private boolean _wasReleased;
        // what the user had for auto jump, null when we did not touch it
        private Boolean _userAutoJump;
        private static final String AUTO_JUMP = "options.autoJump";

        private PunkEnderDragonTask() {
        }

        public Mode getMode() {
            return _mode;
        }

        private void hit(AltoClef mod) {
            mod.getExtraBaritoneSettings().setInteractionPaused(true);
            if (!_wasHitting) {
                _wasHitting = true;
                _wasReleased = false;
                _hitHoldTimer.reset();
                _hitResetTimer.reset();
                Debug.logInternal("HIT");
                mod.getInputControls().tryPress(Input.CLICK_LEFT);
                //mod.getPlayer().swingHand(Hand.MAIN_HAND);
            }
            if (_hitHoldTimer.elapsed()) {
                if (!_wasReleased) {
                    Debug.logInternal("    up");
                    //mod.getControllerExtras().mouseClickOverride(0, false);
                    _wasReleased = true;
                }
            }
            if (_wasHitting && _hitResetTimer.elapsed() && mod.getPlayer().getAttackStrengthScale(0) > 0.99) {
                _wasHitting = false;
                // Code duplication maybe?
                //mod.getControllerExtras().mouseClickOverride(0, false);
                mod.getExtraBaritoneSettings().setInteractionPaused(false);
                _hitResetTimer.reset();
            }
        }

        private void stopHitting(AltoClef mod) {
            if (_wasHitting) {
                //MinecraftClient.getInstance().options.keyAttack.setPressed(false);
                if (!_wasReleased) {
                    //mod.getControllerExtras().mouseClickOverride(0, false);
                    mod.getExtraBaritoneSettings().setInteractionPaused(false);
                    _wasReleased = true;
                }
                _wasHitting = false;
            }
        }


        @Override
        protected void onStart(AltoClef mod) {
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        }

        @Override
        protected Task onTick(AltoClef mod) {
            if (!mod.getEntityTracker().entityFound(EnderDragon.class)) {
                setDebugState("No dragon found.");
                return null;
            }
            Optional<Entity> dragon = mod.getEntityTracker().getClosestEntity(EnderDragon.class);
            if (dragon.isPresent()) {
                EnderDragon dragonEntity = (EnderDragon) dragon.get();
                DragonPhaseInstance dragonPhase = dragonEntity.getPhaseManager().getCurrentPhase();
                //Debug.logInternal("PHASE: " + dragonPhase);
                boolean perchingOrGettingReady = dragonPhase.getPhase() == EnderDragonPhase.LANDING || dragonPhase.isSitting();
                switch (_mode) {
                    case RAILING -> {
                        if (!perchingOrGettingReady) {
                            Debug.logMessage("Dragon no longer perching.");
                            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
                            _mode = Mode.WAITING_FOR_PERCH;
                            break;
                        }
                        //DamageSource.DRAGON_BREATH
                        Entity head = dragonEntity.head;
                        // Go for the head
                        if (head.closerThan(mod.getPlayer(), 7.5) && dragonEntity.dragonDeathTime <= 1) {
                            // Equip weapon
                            AbstractKillEntityTask.equipWeapon(mod);
                            // Look torwards da dragon
                            Vec3 targetLookPos = head.position().add(0, 3, 0);
                            Rotation targetRotation = RotationUtils.calcRotationFromVec3d(mod.getClientBaritone().getPlayerContext().playerHead(), targetLookPos, mod.getClientBaritone().getPlayerContext().playerRotations());
                            mod.getClientBaritone().getLookBehavior().updateTarget(targetRotation, true);
                            // Also look towards da dragon
                            forceAutoJumpOff();
                            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                            hit(mod);
                        } else {
                            stopHitting(mod);
                        }
                        if (!mod.getClientBaritone().getCustomGoalProcess().isActive()) {
                            // Set goal to closest block within the pillar that's by the head.
                            if (_exitPortalTop != null) {
                                int bottomYDelta = -3;
                                BlockPos closest = null;
                                double closestDist = Double.POSITIVE_INFINITY;
                                for (int dx = -2; dx <= 2; ++dx) {
                                    for (int dz = -2; dz <= 2; ++dz) {
                                        // We have sort of a rounded circle here.
                                        if (Math.abs(dx) == 2 && Math.abs(dz) == 2) continue;
                                        BlockPos toCheck = _exitPortalTop.offset(dx, bottomYDelta, dz);
                                        double distSq = toCheck.distToCenterSqr(head.position());
                                        if (distSq < closestDist) {
                                            closest = toCheck;
                                            closestDist = distSq;
                                        }
                                    }
                                }
                                if (closest != null) {
                                    mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(
                                            new GoalGetToBlock(closest)
                                    );
                                }
                            }
                        }
                        setDebugState("Railing on dragon");
                    }
                    case WAITING_FOR_PERCH -> {
                        stopHitting(mod);
                        if (perchingOrGettingReady) {
                            // We're perching!!
                            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
                            Debug.logMessage("Dragon perching detected. Dabar duosiu į snuki.");
                            _mode = Mode.RAILING;
                            break;
                        }
                        // Run around aimlessly, dodging dragon fire
                        if (_randomWanderPos != null && WorldHelper.inRangeXZ(mod.getPlayer(), _randomWanderPos, 2)) {
                            _randomWanderPos = null;
                        }
                        if (_randomWanderPos != null && _randomWanderChangeTimeout.elapsed()) {
                            _randomWanderPos = null;
                            Debug.logMessage("Reset wander pos after timeout, oof");
                        }
                        if (_randomWanderPos == null) {
                            _randomWanderPos = getRandomWanderPos(mod);
                            _randomWanderChangeTimeout.reset();
                            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
                        }
                        if (_randomWanderPos == null) {
                            // no end stone in a few probes (void under us?), try again next tick
                            setDebugState("Looking for somewhere to wait", "Waiting for the dragon to perch");
                            return null;
                        }
                        if (!mod.getClientBaritone().getCustomGoalProcess().isActive()) {
                            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(
                                    new GoalGetToBlock(_randomWanderPos)
                            );
                        }
                        setDebugState("Waiting for perch", "Waiting for the dragon to perch");
                    }
                }
            }
            return null;
        }

        // through SettingsOverrides so options.txt never sees the false and the user's value comes back
        private void forceAutoJumpOff() {
            var autoJump = Minecraft.getInstance().options.autoJump();
            // a hold the user changed behind our back is not ours any more, what is in there is theirs
            if (_userAutoJump == null || !SettingsOverrides.isHeld(AUTO_JUMP)) {
                _userAutoJump = autoJump.get();
            }
            SettingsOverrides.put(AUTO_JUMP, autoJump::get, autoJump::set, false);
        }

        // only while the hold is still ours, a change the user made in the meantime is theirs
        private void releaseAutoJump() {
            var autoJump = Minecraft.getInstance().options.autoJump();
            if (_userAutoJump != null && SettingsOverrides.isHeld(AUTO_JUMP) && !autoJump.get()) {
                SettingsOverrides.put(AUTO_JUMP, autoJump::get, autoJump::set, _userAutoJump);
            }
            _userAutoJump = null;
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
            releaseAutoJump();
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
            //mod.getControllerExtras().mouseClickOverride(0, false);
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof PunkEnderDragonTask;
        }

        @Override
        protected String toHudString() {
            return "Fighting the Ender Dragon";
        }

        @Override
        protected String toDebugString() {
            return "Punking the dragon";
        }

        private BlockPos getRandomWanderPos(AltoClef mod) {
            double RADIUS_RANGE = 45;
            double MIN_RADIUS = 7;
            // this used to try 5000 times per call, each a scan from the world ceiling, on the main thread. the island
            // is a disk right under us so a few probes find end stone, and a miss just means we ask again next tick
            for (int tries = 0; tries < WANDER_TRIES; tries++) {
                double radius = MIN_RADIUS + (RADIUS_RANGE - MIN_RADIUS) * Math.random();
                double angle = Math.PI * 2 * Math.random();
                int x = (int) (radius * Math.cos(angle)),
                        z = (int) (radius * Math.sin(angle));
                int y = islandSurface(mod, x, z);
                if (y == -1) continue;
                BlockPos check = new BlockPos(x, y, z);
                if (mod.getWorld().getBlockState(check).getBlock() == Blocks.END_STONE) {
                    // We found a spot!
                    return check.above();
                }
            }
            return null;
        }

        // the island sits well below the pillar tops, no need to start the scan at the ceiling
        private int islandSurface(AltoClef mod, int x, int z) {
            for (int y = ISLAND_SCAN_TOP; y >= ISLAND_SCAN_BOTTOM; --y) {
                if (WorldHelper.isSolid(mod, new BlockPos(x, y, z))) return y;
            }
            return -1;
        }
    }
}
