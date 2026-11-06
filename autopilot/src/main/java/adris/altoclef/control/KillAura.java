package adris.altoclef.control;

import baritone.Baritone;
import adris.altoclef.AltoClef;
import adris.altoclef.tasks.entity.AbstractKillEntityTask;
import adris.altoclef.util.helpers.AuraPick;
import adris.altoclef.util.helpers.CombatRules;
import adris.altoclef.util.helpers.ReachRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import adris.altoclef.util.helpers.EntityHelper;
import baritone.behavior.CombatAim;
import baritone.behavior.LegacyCombat;
import adris.altoclef.util.helpers.StlHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.input.Input;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.skeleton.Stray;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownLingeringPotion;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Controls and applies killaura
 */
public class KillAura {
    // Smart aura data
    private final List<Entity> _targets = new ArrayList<>();
    private final TimerGame _hitDelay = new TimerGame(0.2);
    boolean _shielding = false;
    private double _forceFieldRange = Double.POSITIVE_INFINITY;
    private Entity _forceHit = null;
    // the combat policy's say, set every tick before tickEnd. defaults are the old behavior: shield whenever it fits
    private boolean _kiting = false;
    private boolean _shieldAllowed = true;
    private boolean _fighting = false;
    // the shield that is up came up holding us still, see startShielding
    private boolean _stoodStill = false;
    private int _committedId = -1;
    // a run on a lava rim with a knocker on us: shield only, no swings, see tickEnd
    private int _rimGuardId = -1;
    // whose name the shield keys go under. a task that brings its own aura passes itself, so the task's own key cleanup (a
    // finished root task is let go of without onStop) still lifts them
    private final Object _owner;

    public KillAura() {
        _owner = this;
    }

    public KillAura(Object owner) {
        _owner = owner;
    }

    public static void equipWeapon(AltoClef mod) {
        equipWeapon(mod, null);
    }

    // same pick as the kill tasks use. this loop used to equip a sword per inventory sword, every call
    public static void equipWeapon(AltoClef mod, Entity target) {
        AbstractKillEntityTask.equipWeapon(mod, false, target);
    }

    public void tickStart() {
        _targets.clear();
        _forceHit = null;
    }

    public void applyAura(Entity entity) {
        _targets.add(entity);
        // Always hit ghast balls.
        if (entity instanceof LargeFireball) _forceHit = entity;
    }

    public void setRange(double range) {
        _forceFieldRange = range;
    }

    // kiting is all feet and no hands: a swing turns our head and the path goes where the head points. the shield is
    // for standing our ground, which is the only time it earns its sneak speed and its paused pathing.
    // fighting: a committed fight. the flee line and "is the path safe to cancel" were the machine's to ask, not ours
    public void setPolicy(boolean kiting, boolean shieldAllowed, boolean fighting) {
        setPolicy(kiting, shieldAllowed, fighting, -1);
    }

    // committedId: the fight's target, -1 for none. every pick below prefers it while it is within arm's reach
    public void setPolicy(boolean kiting, boolean shieldAllowed, boolean fighting, int committedId) {
        _kiting = kiting;
        _shieldAllowed = shieldAllowed;
        _fighting = fighting;
        _committedId = committedId;
    }

    // the fight's own target when it is in reach, else the nearest, see AuraPick
    private Entity pick(AltoClef mod) {
        Vec3 eye = mod.getPlayer().getEyePosition();
        return AuraPick.pick(_targets, Entity::getId, _fighting ? _committedId : -1, entity -> {
            AABB box = entity.getBoundingBox();
            return ReachRules.gap(eye.x, eye.y, eye.z, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ) <= ReachRules.MELEE_REACH;
        }, entity -> entity.distanceToSqr(mod.getPlayer()));
    }

    // the id the brain's rim shield for a run is up for (CombatBrain.auraRimGuard), -1 for none. only read while kiting, set
    // every tick. the aim is that foe and nobody else: a creeper closer to us would not get the shield from tickEnd either
    public void setRimGuard(int foeId) {
        _rimGuardId = foeId;
    }

    // a blocked hit skips the knockback, and on a rim the knock is the lava. shield only: the swing stays off (a run is all feet and
    // no hands), the shield pauses the path for as long as the brain holds the guard, and the brain caps that (RimHold)
    private void rimGuard(AltoClef mod) {
        Optional<Entity> nearest = _targets.stream().filter(entity -> entity.getId() == _rimGuardId).findFirst();
        Item offhandItem = StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT).getItem();
        if (nearest.isEmpty() || !_shieldAllowed || mod.getFoodChain().needsToEat() || mod.getMLGBucketChain().isFallingOhNo(mod)
                || !mod.getMLGBucketChain().doneMLG() || mod.getMLGBucketChain().isChorusFruiting()
                || !(mod.getItemStorage().hasItem(Items.SHIELD) || mod.getItemStorage().hasItemInOffhand(Items.SHIELD))
                || mod.getPlayer().getCooldowns().isOnCooldown(new ItemStack(offhandItem))) {
            stopShielding(mod);
            return;
        }
        mod.getControllerExtras().aimAt(nearest.get(), CombatAim.Priority.AURA);
        if (StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT).getItem() != Items.SHIELD) {
            mod.getSlotHandler().forceEquipItemToOffhand(Items.SHIELD);
        } else {
            startShielding(mod);
        }
    }

    public void tickEnd(AltoClef mod) {
        if (_kiting && _rimGuardId >= 0) {
            rimGuard(mod);
            return;
        }
        if (_kiting) {
            stopShielding(mod);
            // ghast balls are still worth the turn
            if (_forceHit != null) {
                attack(mod, _forceHit, true);
            }
            return;
        }
        Optional<Entity> entities = _targets.stream().min(StlHelper.compareValues(entity -> entity.distanceToSqr(mod.getPlayer())));
        // (a cornered fight at hp 8 never bails, and going quiet under the flee line meant dropping the shield right when it
        // was the only thing between us and the respawn screen)
        if (entities.isPresent() && (CombatRules.aboveFleeLine(mod.getPlayer().getHealth()) || _fighting) &&
                !mod.getEntityTracker().entityFound(ThrownSplashPotion.class, ThrownLingeringPotion.class) && !mod.getFoodChain().needsToEat() &&
                (Double.isInfinite(_forceFieldRange) || entities.get().distanceToSqr(mod.getPlayer()) < _forceFieldRange * _forceFieldRange ||
                        entities.get().distanceToSqr(mod.getPlayer()) < 40) &&
                !mod.getMLGBucketChain().isFallingOhNo(mod) && mod.getMLGBucketChain().doneMLG() &&
                !mod.getMLGBucketChain().isChorusFruiting()) {
            PlayerSlot offhandSlot = PlayerSlot.OFFHAND_SLOT;
            Item offhandItem = StorageHelper.getItemStackInSlot(offhandSlot).getItem();
            // (the brain asks for the shield in every committed melee fight, see CombatRules.fightShield. a hoglin hit that is
            // blocked still throws us but costs nothing, and the throw comes either way, so they get it too)
            if (!_shieldAllowed) {
                stopShielding(mod);
            // (never for a ghast's ball: with the shield up the swing below is skipped, the ball came through to explode against
            // the shield, and its knock blew it off a platform three times in three in the ghast drill, 3 Nov 2026. punched back,
            // it explodes far off or kills the ghast)
            } else if (entities.get().getClass() != Creeper.class && entities.get().getClass() != Warden.class &&
                    entities.get().getClass() != WitherBoss.class && !(entities.get() instanceof LargeFireball)
                    && (mod.getItemStorage().hasItem(Items.SHIELD) || mod.getItemStorage().hasItemInOffhand(Items.SHIELD))
                    && !mod.getPlayer().getCooldowns().isOnCooldown(new ItemStack(offhandItem))
                    // mid movement or knocked back mid movement the path is never safe to cancel, and a fight waiting for it
                    // took its hits with the shield down. same deal as the arrow shield in MobDefenseChain
                    && (_fighting || mod.getClientBaritone().getPathingBehavior().isSafeToCancel())) {
                // (the creeper check above stays on the nearest one, the aim is the fight's own target)
                mod.getControllerExtras().aimAt(pick(mod), CombatAim.Priority.AURA);
                ItemStack shieldSlot = StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT);
                if (shieldSlot.getItem() != Items.SHIELD) {
                    mod.getSlotHandler().forceEquipItemToOffhand(Items.SHIELD);
                } else {
                    startShielding(mod);
                }
            }
            performDelayedAttack(mod);
        } else {
            stopShielding(mod);
        }
        // Run force field on map
        switch (Baritone.settings().altoForceFieldStrategy.value) {
            case FASTEST:
                performFastestAttack(mod);
                break;
            case SMART:
                // a committed fight is paced swings at one mob. the crowd branch below is 0.2 s pokes at the nearest (a sword waits for its charge in there)
                if (_fighting || _targets.size() <= 2 || _targets.stream().allMatch(entity -> entity instanceof Skeleton) ||
                        _targets.stream().allMatch(entity -> entity instanceof Witch) ||
                        _targets.stream().allMatch(entity -> entity instanceof Pillager) ||
                        _targets.stream().allMatch(entity -> entity instanceof Piglin) ||
                        _targets.stream().allMatch(entity -> entity instanceof Stray) ||
                        _targets.stream().allMatch(entity -> entity instanceof Blaze)) {
                    performDelayedAttack(mod);
                } else {
                    if (!mod.getFoodChain().needsToEat() && !mod.getMLGBucketChain().isFallingOhNo(mod) &&
                            mod.getMLGBucketChain().doneMLG() && !mod.getMLGBucketChain().isChorusFruiting()) {
                        // Attack force mobs ALWAYS.
                        if (_forceHit != null) {
                            attack(mod, _forceHit, true);
                        }
                        // pre 1.9 the clicker in attack() paces this, the fixed 0.2 s would be half a hand's cps
                        if (LegacyCombat.active() || mod.getControllerExtras().swordInHand() || _hitDelay.elapsed()) {
                            _hitDelay.reset();

                            Optional<Entity> toHit = Optional.ofNullable(pick(mod));

                            toHit.ifPresent(entity -> attack(mod, entity, true));
                        }
                    }
                }
                break;
            case DELAY:
                performDelayedAttack(mod);
                break;
            case OFF:
                break;
        }
    }

    private void performDelayedAttack(AltoClef mod) {
        if (!mod.getFoodChain().needsToEat() && !mod.getMLGBucketChain().isFallingOhNo(mod) &&
                mod.getMLGBucketChain().doneMLG() && !mod.getMLGBucketChain().isChorusFruiting()) {
            if (_forceHit != null) {
                attack(mod, _forceHit, true);
            }
            // wait for the attack delay
            if (_targets.isEmpty()) {
                return;
            }

            if (mod.getPlayer() == null) {
                return;
            }
            Optional<Entity> toHit = Optional.ofNullable(pick(mod));
            // the weapon goes in hand before the gate asks about the cooldown. a swap resets it, so judging the gate on one item
            // and swinging another was how a charge that was nearly full turned back into a fresh one
            toHit.ifPresent(entity -> readyTheWeapon(mod, entity));
            // a not yet full swing is a quiet tick, unless a crit hop is about to start or is in the air (never while
            // baritone is walking, so travelling past a mob still doesn't turn our head every tick)
            if (!mod.getControllerExtras().attackReady() && !mod.getControllerExtras().wantsCritTick()) {
                return;
            }

            toHit.ifPresent(entity -> attack(mod, entity, true, true));
        }
    }

    // the same guards attack() has before it equips, so a mob we would not swing at does not get a weapon swap (or our head)
    // either. the aim is asked for every tick here and not just on the swing tick, the turn takes a few ticks
    private void readyTheWeapon(AltoClef mod, Entity entity) {
        if (entity == null || entity instanceof LargeFireball) return;
        if (entity instanceof Mob mob && EntityHelper.isCalmNeutral(mod, mob)) return;
        if (inFieldRange(mod, entity)) {
            mod.getControllerExtras().aimAt(entity, CombatAim.Priority.AURA);
            equipWeapon(mod, entity);
        }
    }

    private boolean inFieldRange(AltoClef mod, Entity entity) {
        return Double.isInfinite(_forceFieldRange) || entity.distanceToSqr(mod.getPlayer()) < _forceFieldRange * _forceFieldRange ||
                entity.distanceToSqr(mod.getPlayer()) < 40;
    }

    private void performFastestAttack(AltoClef mod) {
        if (!mod.getFoodChain().needsToEat() && !mod.getMLGBucketChain().isFallingOhNo(mod) &&
                mod.getMLGBucketChain().doneMLG() && !mod.getMLGBucketChain().isChorusFruiting()) {
            // Just attack whenever you can. in a committed fight that is the one mob we picked, not the whole pile
            Entity picked = _fighting && !_targets.isEmpty() ? pick(mod) : null;
            if (picked != null) {
                attack(mod, picked);
                return;
            }
            for (Entity entity : _targets) {
                attack(mod, entity);
            }
        }
    }

    private void attack(AltoClef mod, Entity entity) {
        attack(mod, entity, false);
    }

    private void attack(AltoClef mod, Entity entity, boolean equipSword) {
        attack(mod, entity, equipSword, false);
    }

    // crit is only for the paced swings of the delayed aura. the crowd mode swings every 0.2 s whether the cooldown is back
    // or not (a sword is asked every tick and waits for the charge itself), and a machine that gets asked every fourth tick can't steer a jump
    private void attack(AltoClef mod, Entity entity, boolean equipSword, boolean crit) {
        if (entity == null) return;
        // second guard: a wolf nobody poked is not our enemy, whatever put it in the target list
        if (entity instanceof Mob mob && EntityHelper.isCalmNeutral(mod, mob)) return;
        if (!(entity instanceof LargeFireball)) {
            mod.getControllerExtras().aimAt(entity, CombatAim.Priority.AURA);
        }
        if (inFieldRange(mod, entity)) {
            if (entity instanceof LargeFireball) {
                // the buddy's fork: MobDefenseChain.punchGhastBall has it, aimed. this swing went out blind, and a ball sent off
                // along a look that is not on it can go into the ground at our feet
                return;
            }
            boolean canAttack;
            if (equipSword) {
                equipWeapon(mod, entity);
                canAttack = true;
            } else {
                // Equip non-tool
                canAttack = mod.getSlotHandler().forceDeequipHitTool();
            }
            if (canAttack) {
                // a sword always goes through melee: it waits for the charge and sets up the sweep (fireballs returned above)
                if ((crit || mod.getControllerExtras().swordInHand()) && !(entity instanceof LargeFireball)) {
                    mod.getControllerExtras().melee(entity);
                } else if (mod.getPlayer().onGround() || mod.getPlayer().getDeltaMovement().y() < 0 || mod.getPlayer().isInWater()
                        || !mod.getControllerExtras().critInFlight()) {
                    mod.getControllerExtras().attack(entity);
                }
            }
        }
    }

    public void startShielding(AltoClef mod) {
        // (the buddy's fork: no shield while a pillbox goes up, see BlazeShelter.building)
        if (adris.altoclef.util.helpers.BlazeShelter.building(mod)) return;
        _shielding = true;
        boolean standStill = CombatRules.auraShieldStandsStill(_fighting);
        // the rim guard handed over to a fight with its sneak still down. the magma pin has its own owner, so this only lifts ours
        if (_stoodStill && !standStill) {
            mod.getInputControls().releaseOwnedBy(_owner);
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
        }
        _stoodStill = standStill;
        // our own name on the keys: MobDefenseChain's shield letting go in the same tick must not lift ours, a shield only
        // blocks after 5 ticks of use and every lift starts that over. (passed by hand: the chain calls us from getPriority,
        // where Task.currentOwner() is nobody)
        if (standStill) {
            mod.getInputControls().hold(Input.SNEAK, _owner);
        }
        mod.getInputControls().hold(Input.CLICK_RIGHT, _owner);
        if (standStill) {
            mod.getClientBaritone().getPathingBehavior().requestPause();
            mod.getExtraBaritoneSettings().setInteractionPaused(true);
        }
        adris.altoclef.util.helpers.ShieldRaise.raise(mod);
        if (!mod.getPlayer().isBlocking()) {
            ItemStack handItem = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot());
            if (handItem.getItem().components().has(DataComponents.FOOD)) {
                // (the buddy's fork: a player picks another hotbar slot, the food stays where it is. the shift-click of the
                // whole stack into the bag came back by a swap at the next meal, once per raise: food went from the hotbar to the
                // bag and back for nothing, 4 Nov 2026)
                int free = adris.altoclef.control.SlotHandler.nonFoodSlot(mod.getPlayer().getInventory());
                if (free >= 0) {
                    mod.getPlayer().getInventory().setSelectedSlot(free);
                    return;
                }
                List<ItemStack> spaceSlots = mod.getItemStorage().getItemStacksPlayerInventory(false);
                if (!spaceSlots.isEmpty()) {
                    for (ItemStack spaceSlot : spaceSlots) {
                        if (spaceSlot.isEmpty()) {
                            mod.getSlotHandler().clickSlot(PlayerSlot.getEquipSlot(), 0, ContainerInput.QUICK_MOVE);
                            adris.altoclef.Debug.logInternal("slot: swapped " + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(handItem.getItem()).getPath() + " out of the hand for the shield");
                            return;
                        }
                    }
                }
                Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
                garbage.ifPresent(slot -> mod.getSlotHandler().forceEquipItem(StorageHelper.getItemStackInSlot(slot).getItem()));
            }
        }
    }

    public void stopShielding(AltoClef mod) {
        if (_shielding) {
            ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
            if (cursor.getItem().components().has(DataComponents.FOOD)) {
                Optional<Slot> toMoveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false).or(() -> StorageHelper.getGarbageSlot(mod));
                if (toMoveTo.isPresent()) {
                    Slot garbageSlot = toMoveTo.get();
                    mod.getSlotHandler().clickSlot(garbageSlot, 0, ContainerInput.PICKUP);
                }
            }
            // only our claims. a key somebody else still holds stays down, the food chain's plain hold for a bite included
            // jump isn't ours, the shield never holds it. letting it go anyway dropped whoever was swimming up for a tick
            mod.getInputControls().releaseOwnedBy(_owner);
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
            _shielding = false;
            _stoodStill = false;
        }
    }

    public enum Strategy {
        OFF,
        FASTEST,
        DELAY,
        SMART
    }
}
