package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.util.helpers.DangerMeter.Hazard;
import adris.altoclef.util.helpers.DangerMeter.Level;
import adris.altoclef.util.helpers.DangerMeter.Reading;
import baritone.api.utils.Dimension;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;

// the buddy's fork: the danger meter in shadow (phase 1). every EVERY ticks in the nether and the overworld it reads the meter
// from what the client sees and logs it: a line when its level changes, one at every hit taken, one at death, and one when a
// stretch in cover or flee ends. it steers nothing by default: everything it asks the game is a read (MobReachability.wayFor
// makes no verdict, LookHelper.seesPlayer is a plain ray), and only the forced drills' chain (DangerForceChain, tuning key
// danger_force, off by default) reads its level and threats. off with the tuning key danger_shadow=0
public final class DangerShadow {
    private static final int EVERY = 5;
    // mobs within this; a shooter within its range up to SHOOTER_RANGE
    private static final double MOB_RANGE = 16;
    private static final double SHOOTER_RANGE = 48;
    // the hazard scan: PUSH_RANGE round the feet, a floor below to -1, two above; an edge's drop looked for this far down,
    // and the scan done again at least this often (lava flows, fire spreads)
    private static final int SCAN = 3;
    private static final int DROP_DEPTH = 24;
    private static final int MIN_DROP = 5;
    private static final int RESCAN_TICKS = 40;
    // a mob that was after us and is gone within this distance counts as a kill
    private static final double KILL_GONE = 6;

    private static final DangerMeter.Track track = new DangerMeter.Track();
    private static final DangerMeter.Patience patience = new DangerMeter.Patience();
    private static Reading last = DangerMeter.none(20);
    // the mobs of the last reading by entity, with what each adds to T and push: only while danger_force is on (the forced
    // drills), empty otherwise
    private static List<DangerForce.Threat> threats = List.of();
    private static long tick;
    private static boolean wasDead;
    private static int errors;
    private static final int MAX_ERRORS = 50;

    // hits: the client gets a new damage source object with every hit; the damage is the hp lost round it
    private static DamageSource lastSource;
    private static float hp1;
    private static float hp2;
    private static String pendingKind;
    private static float pendingBefore;
    private static float pendingLow;
    private static long pendingAt;

    // progress: mobs that were after us at the last sample (id to distance), and the rods we held
    private static Map<Integer, Double> after = new HashMap<>();
    private static int lastRods = -1;

    // the hazard cache, per block position
    private record Spot(String kind, int x, int y, int z, double fall) {
    }

    private static List<Spot> spots = List.of();
    private static long spotsAt = Long.MIN_VALUE;
    private static long spotsTick = Long.MIN_VALUE / 4;

    private DangerShadow() {
    }

    // the meter's level now: calm while the meter is off (danger_shadow=0, or off after its errors)
    public static Level level() {
        return on() ? track.level() : Level.CALM;
    }

    // the last reading (the risk for a line)
    public static Reading last() {
        return last;
    }

    // the mobs of the last reading with their shares (danger_force on only)
    public static List<DangerForce.Threat> threats() {
        return on() ? threats : List.of();
    }

    // the forced drills' look at the last reading (danger_force on only, null otherwise): our damage a second with the weapon
    // the fight would use and the bow's, the foes counted with their want ("blaze 10.2 w1, ..."), and the "full" risk, every
    // hostile in sight or after us counted at want 1 (the review of (b2): the meter's own reading counts a mob that only sees
    // us at half, and only inside its horizon, so its first reading not calm says little of the crowd the buddy faces)
    public record Detail(double dps, double bowDps, int foes, String counted, double fullRisk, double fullT) {
    }

    private static Detail detail;

    public static Detail detail() {
        return on() ? detail : null;
    }

    // a full bow's damage a second as the forced fight shoots it: an arrow of 6 at full draw (9 on a crit) about every
    // 1.25 s, two in three landing on a mob that moves (a guess to size from the drills' hit lines)
    public static final double BOW_DPS = 4.0;

    private static boolean on() {
        return errors < MAX_ERRORS && Tuning.get("danger_shadow", 1) > 0;
    }

    public static void tick(AltoClef mod) {
        if (errors >= MAX_ERRORS) return;
        try {
            if (Tuning.get("danger_shadow", 1) <= 0) return;
            tickInner(mod);
        } catch (RuntimeException e) {
            // a shadow has no business taking the tick down: that sample is skipped (an entity list changed under us, say), the
            // first error is logged, and after MAX_ERRORS the meter stays off for this game
            errors++;
            if (errors == 1 || errors == MAX_ERRORS) {
                Debug.logInternal("danger meter error " + errors + (errors == MAX_ERRORS ? ", off from now" : "") + ": " + e);
            }
        }
    }

    private static void tickInner(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        ClientLevel level = mod.getWorld();
        if (player == null || level == null) {
            lastSource = null;
            pendingKind = null;
            return;
        }
        tick++;
        float hp = player.getHealth() + player.getAbsorptionAmount();
        Dimension dim = WorldHelper.getCurrentDimension();
        boolean watched = dim == Dimension.NETHER || dim == Dimension.OVERWORLD;
        boolean dead = player.isDeadOrDying();

        if (watched && !wasDead) {
            noteHit(player, hp);
        } else {
            lastSource = player.getLastDamageSource();
            pendingKind = null;
        }
        if (dead && !wasDead && watched) {
            // the killing blow's line first, then the meter as it stood
            flushHit(Math.min(hp, pendingLow));
            Debug.logInternal(DangerMeter.deathLine(track.level(), last));
            DangerMeter.Patience.Ended ended = patience.end(tick);
            if (ended != null) Debug.logInternal(DangerMeter.patienceLine(ended));
        }
        if (dead && !wasDead) {
            track.reset(tick);
            last = DangerMeter.none(0);
            threats = List.of();
            detail = null;
            after = new HashMap<>();
            lastRods = -1;
        }
        wasDead = dead;
        hp2 = dead ? 0 : hp1;
        hp1 = dead ? 0 : hp;
        if (dead) return;
        if (!watched) {
            last = DangerMeter.none(hp);
            threats = List.of();
            detail = null;
            return;
        }

        if (tick % EVERY != 0) return;
        Reading reading = read(mod, player, level, hp);
        last = reading;
        if (track.step(tick, reading)) Debug.logInternal(DangerMeter.levelLine(track.level(), reading));
        boolean progress = progress(level, player);
        DangerMeter.Patience.Ended ended = patience.step(tick, track.level(), reading.t(), hp, progress);
        if (ended != null) Debug.logInternal(DangerMeter.patienceLine(ended));
    }

    // ---- hits

    private static void noteHit(LocalPlayer player, float hp) {
        if (pendingKind != null) {
            pendingLow = Math.min(pendingLow, hp);
            // the health packet and the damage event can come a tick apart: the line goes a tick after the event
            if (tick > pendingAt) flushHit(pendingLow);
        }
        DamageSource source = player.getLastDamageSource();
        if (source != null && source != lastSource) {
            if (pendingKind != null) flushHit(pendingLow);
            pendingKind = kindOf(source);
            pendingBefore = Math.max(hp1, hp2);
            pendingLow = hp;
            pendingAt = tick;
        }
        lastSource = source;
    }

    private static void flushHit(float low) {
        if (pendingKind == null) return;
        Debug.logInternal(DangerMeter.hitLine(Math.max(0, pendingBefore - low), pendingKind, track.level(), last));
        pendingKind = null;
    }

    // the mob behind it (the shooter of an arrow), else the thing that touched us, else the kind of damage (lava, inFire, fall)
    private static String kindOf(DamageSource source) {
        Entity who = source.getEntity() != null ? source.getEntity() : source.getDirectEntity();
        if (who != null) return BuiltInRegistries.ENTITY_TYPE.getKey(who.getType()).getPath();
        return source.getMsgId();
    }

    // ---- the reading

    private static Reading read(AltoClef mod, LocalPlayer player, ClientLevel level, float hp) {
        boolean fireRes = player.hasEffect(MobEffects.FIRE_RESISTANCE);
        MobEffectInstance resistance = player.getEffect(MobEffects.RESISTANCE);
        double dps = player.getAttributeValue(Attributes.ATTACK_DAMAGE) * player.getAttributeValue(Attributes.ATTACK_SPEED);
        // (the forced drills: K with the weapon the fight would use, not what is in hand, and the bow for a flyer out of reach;
        // without the switch the meter reads as it always did)
        boolean forced = DangerForce.mode(Tuning.get("danger_force", 0)) != DangerForce.OFF;
        double bowDps = 0;
        if (forced) {
            net.minecraft.world.item.Item weapon = adris.altoclef.tasks.entity.AbstractKillEntityTask.bestWeapon(mod);
            if (weapon != null) dps = Math.max(dps, ItemHelper.getAttackDamage(weapon) * ItemHelper.getAttackSpeed(weapon));
            bowDps = adris.altoclef.tasks.entity.ForcedBowTask.canShoot(mod) ? BOW_DPS : 0;
        }
        DangerMeter.Us us = new DangerMeter.Us(hp, dps, player.getArmorValue(), player.getAttributeValue(Attributes.ARMOR_TOUGHNESS),
                level.getDifficulty().getId(), resistance == null ? 0 : resistance.getAmplifier() + 1, fireRes, bowDps);
        // (and the full count: every hostile in sight or after us at want 1)
        List<DangerMeter.Mob> full = forced ? new ArrayList<>() : null;

        long now = level.getGameTime();
        MobReachability reach = mod.getEntityTracker().getMobReachability();
        List<DangerMeter.Mob> mobs = new ArrayList<>();
        // (each mob's entity, for the forced drills' threats)
        List<Mob> bodies = new ArrayList<>();
        Map<Integer, Double> afterNow = new HashMap<>();
        for (Entity e : level.entitiesForRendering()) {
            if (!(e instanceof Mob mob) || !mob.isAlive()) continue;
            double distance = mob.distanceTo(player);
            if (distance > SHOOTER_RANGE) continue;
            String kind = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath();
            if (mob instanceof Piglin p && p.isHolding(s -> s.getItem() instanceof CrossbowItem)) kind = "piglin_crossbow";
            int size = mob instanceof AbstractCubeMob cube ? cube.getSize() : 1;
            MobThreat row = MobThreat.forMeter(kind, size);
            boolean shooter = row != null && row.shot() > 0;
            if (distance > MOB_RANGE && !(shooter && distance <= Math.min(row.range(), SHOOTER_RANGE))) continue;

            boolean hostileKind = mob instanceof Enemy;
            boolean afterUs = EntityHelper.isAngryAtPlayer(mod, mob);
            boolean calm = !afterUs && (mob instanceof Enderman || EntityHelper.isCalmNeutral(mod, mob));
            // only a pusher counts from a body that wants nothing: skip the rest of a calm one beyond the push range
            if (!afterUs && (calm || !hostileKind) && distance > DangerMeter.PUSH_RANGE) continue;
            double notice = row != null ? row.follow() : MOB_RANGE;
            boolean needSight = (shooter && distance <= row.range()) || (!afterUs && hostileKind && !calm && distance <= notice);
            boolean sees = needSight && LookHelper.seesPlayer(mob, player, Math.max(MOB_RANGE, Math.min(SHOOTER_RANGE, distance + 1)));
            double want = DangerMeter.wantsToHurt(afterUs, hostileKind, calm, sees, distance, notice);
            boolean way = row != null && row.flies() || reach.wayFor(mob, player, now);
            mobs.add(new DangerMeter.Mob(kind, row, mob.getX() - player.getX(), mob.getY() - player.getY(), mob.getZ() - player.getZ(),
                    want, way, sees, mob.getHealth()));
            bodies.add(mob);
            if (full != null && (afterUs || (hostileKind && !calm))) {
                boolean seen = afterUs || (needSight ? sees
                        : LookHelper.seesPlayer(mob, player, Math.max(MOB_RANGE, Math.min(SHOOTER_RANGE, distance + 1))));
                if (seen) {
                    full.add(new DangerMeter.Mob(kind, row, mob.getX() - player.getX(), mob.getY() - player.getY(), mob.getZ() - player.getZ(),
                            1, way, needSight ? sees : seen, mob.getHealth()));
                }
            }
            if (want > 0) afterNow.put(mob.getId(), distance);
        }
        pendingAfter = afterNow;
        List<Hazard> hazards = hazards(level, player);
        threats = forced ? threats(us, mobs, bodies, hazards) : List.of();
        Reading reading = DangerMeter.read(us, mobs, hazards);
        if (forced) {
            Reading all = DangerMeter.read(us, full, hazards);
            StringBuilder counted = new StringBuilder();
            int n = 0;
            for (DangerMeter.Mob m : mobs) {
                if (DangerMeter.damage3(m, us, m.distance()) <= 0) continue;
                n++;
                if (counted.length() > 0) counted.append(", ");
                counted.append(m.kind()).append(' ').append(DangerMeter.num(m.distance())).append(" w").append(m.want() == 1 ? "1" : DangerMeter.num(m.want()));
            }
            detail = new Detail(dps, bowDps, n, counted.toString(), all.risk(), all.t());
        } else {
            detail = null;
        }
        return reading;
    }

    // each mob's share as DangerMeter.read counts it (T, and push before read's cap at our hp), with its entity
    private static List<DangerForce.Threat> threats(DangerMeter.Us us, List<DangerMeter.Mob> mobs, List<Mob> bodies, List<Hazard> hazards) {
        List<DangerForce.Threat> out = new ArrayList<>();
        for (int i = 0; i < mobs.size(); i++) {
            DangerMeter.Mob m = mobs.get(i);
            double distance = m.distance();
            double t = DangerMeter.damage3(m, us, distance);
            double push = DangerMeter.pushRisk(m, us, distance, hazards);
            if (t <= 0 && push <= 0) continue;
            Mob body = bodies.get(i);
            out.add(new DangerForce.Threat(body.getId(), m.kind(), body.getX(), body.getY(), body.getZ(), t, push));
        }
        return out;
    }

    // ---- progress: a kill or a rod since the last sample (hp and T are the meter's own)

    private static Map<Integer, Double> pendingAfter = new HashMap<>();

    private static boolean progress(ClientLevel level, LocalPlayer player) {
        boolean kill = false;
        for (Map.Entry<Integer, Double> was : after.entrySet()) {
            if (pendingAfter.containsKey(was.getKey())) continue;
            Entity e = level.getEntity(was.getKey());
            if ((e instanceof LivingEntity living && living.isDeadOrDying()) || (e == null && was.getValue() <= KILL_GONE)) {
                kill = true;
                break;
            }
        }
        after = pendingAfter;
        int rods = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(Items.BLAZE_ROD)) rods += stack.getCount();
        }
        boolean rod = lastRods >= 0 && rods > lastRods;
        lastRods = rods;
        return kill || rod;
    }

    // ---- hazards: a block scan round the feet, cached per block position

    private static List<Hazard> hazards(ClientLevel level, LocalPlayer player) {
        BlockPos feet = player.blockPosition();
        if (feet.asLong() != spotsAt || tick - spotsTick >= RESCAN_TICKS) {
            spots = scan(level, feet);
            spotsAt = feet.asLong();
            spotsTick = tick;
        }
        List<Hazard> out = new ArrayList<>(spots.size());
        for (Spot s : spots) {
            out.add(new Hazard(s.kind(), s.x() + 0.5 - player.getX(), s.y() + 0.5 - player.getY(), s.z() + 0.5 - player.getZ(), s.fall()));
        }
        return out;
    }

    private static List<Spot> scan(ClientLevel level, BlockPos feet) {
        List<Spot> out = new ArrayList<>();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dx = -SCAN; dx <= SCAN; dx++) {
            for (int dz = -SCAN; dz <= SCAN; dz++) {
                if (dx * dx + dz * dz > (SCAN + 0.5) * (SCAN + 0.5)) continue;
                int x = feet.getX() + dx;
                int z = feet.getZ() + dz;
                for (int dy = -1; dy <= 2; dy++) {
                    String kind = hazardKind(level.getBlockState(at.set(x, feet.getY() + dy, z)));
                    // (a server's anti-xray sends magma for blocks hidden in the rock: one counts only with an open face)
                    if ("magma_block".equals(kind) && !openFace(level, x, feet.getY() + dy, z)) continue;
                    if (kind != null) out.add(new Spot(kind, x, feet.getY() + dy, z, 0));
                }
                if (dx == 0 && dz == 0) continue;
                double fall = drop(level, at, x, feet.getY(), z);
                if (fall != 0) out.add(new Spot("drop", x, feet.getY(), z, fall));
            }
        }
        return out;
    }

    // an open cell at our feet with nothing under it: the blocks down to the first floor (MIN_DROP and up), LAVA_DROP when lava
    // comes first, LETHAL_DROP when nothing comes within DROP_DEPTH; 0 for no edge (a floor near, or water to land in)
    private static double drop(ClientLevel level, BlockPos.MutableBlockPos at, int x, int y, int z) {
        if (!open(level, at.set(x, y, z)) || !open(level, at.set(x, y - 1, z))) return 0;
        for (int depth = 2; depth <= DROP_DEPTH; depth++) {
            BlockState state = level.getBlockState(at.set(x, y - depth, z));
            if (state.getFluidState().is(FluidTags.LAVA)) return DangerMeter.LAVA_DROP;
            if (state.getFluidState().is(FluidTags.WATER)) return 0;
            if (!state.getCollisionShape(level, at).isEmpty()) {
                int fallen = depth - 1;
                return fallen >= MIN_DROP ? fallen : 0;
            }
        }
        return DangerMeter.LETHAL_DROP;
    }

    // air or a fluid on one of the six faces of x y z
    private static boolean openFace(ClientLevel level, int x, int y, int z) {
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (int[] d : FACES) {
            BlockState state = level.getBlockState(n.set(x + d[0], y + d[1], z + d[2]));
            if (state.isAir() || !state.getFluidState().isEmpty()) return true;
        }
        return false;
    }

    private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private static boolean open(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && !state.getFluidState().is(FluidTags.LAVA);
    }

    private static String hazardKind(BlockState state) {
        if (state.getFluidState().is(FluidTags.LAVA)) return "lava";
        if (state.is(Blocks.MAGMA_BLOCK)) return "magma_block";
        if (state.is(Blocks.FIRE)) return "fire";
        if (state.is(Blocks.SOUL_FIRE)) return "soul_fire";
        if (state.is(Blocks.CAMPFIRE)) return state.getValue(CampfireBlock.LIT) ? "campfire" : null;
        if (state.is(Blocks.SOUL_CAMPFIRE)) return state.getValue(CampfireBlock.LIT) ? "soul_campfire" : null;
        if (state.is(Blocks.WITHER_ROSE)) return "wither_rose";
        if (state.is(Blocks.CACTUS)) return "cactus";
        if (state.is(Blocks.SWEET_BERRY_BUSH)) return "sweet_berry_bush";
        if (state.is(Blocks.POWDER_SNOW)) return "powder_snow";
        return null;
    }
}
