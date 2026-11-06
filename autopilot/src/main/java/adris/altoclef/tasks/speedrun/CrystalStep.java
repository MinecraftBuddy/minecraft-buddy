package adris.altoclef.tasks.speedrun;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.entity.DoToClosestEntityTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.resources.MineAndCollectTask;
import adris.altoclef.tasks.speedrun.gamer.end.CrystalBlast;
import adris.altoclef.tasks.speedrun.gamer.end.CrystalStand;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.ReachRules;
import adris.altoclef.util.helpers.StorageHelper;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// the sword fight's crystal run, pulled out so the bed fight can do it first: get a block to pillar with, pillar up to the
// covered spot just past the edge of a crystal's pillar and below it (CrystalStand), and smack it from there. which crystals
// is up to the caller, minus the ones we gave up on
public class CrystalStep {
    private static final int MINIMUM_BUILDING_BLOCKS = 1;
    private final Task _collectBuildMaterialsTask = new MineAndCollectTask(new ItemTarget(Items.END_STONE, 100), new Block[]{Blocks.END_STONE}, MiningRequirement.WOOD);
    // crystals with no safe spot to hit them from. left alone for good, the dragon is the better use of our time than a blast
    private final Set<Integer> _skipped = new HashSet<>();
    // one "not swinging" line per crystal, it would say the same thing every tick
    private final Set<Integer> _heldLogged = new HashSet<>();
    private int _targetId = -1;
    private int _targetTicks;
    // picked once per crystal: the side from our position flips as we walk around the pillar and the stand would dance with it
    private int[] _side;

    // null when no crystal we care about is left (or we can't get blocks for it), else the step
    public Task tick(AltoClef mod, Predicate<Entity> which, Consumer<String> debug) {
        Predicate<Entity> live = which.and(e -> !_skipped.contains(e.getId()));
        boolean any = mod.getEntityTracker().entityFound(live, EndCrystal.class);
        if (any && mod.getItemStorage().getItemCount(Items.DIRT, Items.COBBLESTONE, Items.NETHERRACK, Items.END_STONE) < MINIMUM_BUILDING_BLOCKS
                || (_collectBuildMaterialsTask.isActive() && !_collectBuildMaterialsTask.isFinished(mod))) {
            if (StorageHelper.miningRequirementMetInventory(mod, MiningRequirement.WOOD)) {
                mod.getBehaviour().addProtectedItems(Items.END_STONE);
                debug.accept("Collecting building blocks to pillar to crystals");
                return _collectBuildMaterialsTask;
            }
        } else {
            mod.getBehaviour().removeProtectedItems(Items.END_STONE);
        }
        if (!any) {
            return null;
        }
        debug.accept("Going for crystals from below their pillar tops");
        // the task outlives this call (same class, so the runner keeps the old one), and its pick only drops a crystal that
        // stops being valid. so skipped has to be part of valid, the filter alone would leave us parked on it
        return new DoToClosestEntityTask(crystal -> goFor(mod, crystal), live, EndCrystal.class) {
            @Override
            protected boolean isValid(AltoClef m, Entity obj) {
                return super.isValid(m, obj) && !_skipped.contains(obj.getId());
            }
        };
    }

    private Task goFor(AltoClef mod, Entity crystal) {
        LocalPlayer player = mod.getPlayer();
        boolean fresh = crystal.getId() != _targetId;
        if (fresh) {
            _targetId = crystal.getId();
            _targetTicks = 0;
            _side = CrystalStand.side(crystal.getX(), crystal.getZ(), player.getX(), player.getZ());
        }
        _targetTicks++;
        BlockPos at = crystal.blockPosition();
        int[] stand = CrystalStand.stand(at.getX(), at.getY(), at.getZ(), _side[0], _side[1], (x, y, z) -> pillar(mod, x, y, z));
        double gap = stand == null ? Double.MAX_VALUE : CrystalStand.standGap(stand, crystal.getX(), crystal.getY(), crystal.getZ());
        String skip = CrystalStand.skipReason(stand, gap, _targetTicks);
        if (skip != null) {
            _skipped.add(crystal.getId());
            Debug.logMessage("crystal: leaving the one at " + at.toShortString() + " alone, " + skip);
            return null;
        }
        BlockPos goal = new BlockPos(stand[0], stand[1], stand[2]);
        if (fresh) {
            Debug.logMessage(String.format("crystal: going for %s from %s, %.1f from its box", at.toShortString(), goal.toShortString(), gap));
        }
        trySwing(mod, player, crystal);
        return new GetToBlockTask(goal, false);
    }

    // swing only if the blast can't reach us. anywhere in reach counts, the stand is just where that is sure to be true
    private void trySwing(AltoClef mod, LocalPlayer player, Entity crystal) {
        Vec3 eye = player.getEyePosition();
        AABB box = crystal.getBoundingBox();
        // nothing to ask before it is in reach, and the seen check is 50ish raycasts
        if (ReachRules.gap(eye.x, eye.y, eye.z, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ) > ReachRules.CRYSTAL_REACH) {
            return;
        }
        double distance = player.position().distanceTo(crystal.position());
        double seen = ServerExplosion.getSeenPercent(crystal.position(), player);
        double damage = blastDamage(mod, player, crystal);
        if (CrystalBlast.safeToSwing(damage)) {
            mod.getControllerExtras().attack(crystal, ReachRules.CRYSTAL_REACH);
        } else if (_heldLogged.add(crystal.getId())) {
            Debug.logMessage(String.format("crystal: not swinging at %s, it would do %.1f to us (seen %.2f, %.1f out)",
                    crystal.blockPosition().toShortString(), damage, seen, distance));
        }
    }

    // what popping this crystal right now would do to us after armor. the bed fight's pillar swings ask this too
    public static double blastDamage(AltoClef mod, LocalPlayer player, Entity crystal) {
        double distance = player.position().distanceTo(crystal.position());
        // vanilla's own seen check works client side, it only needs the level's blocks
        double seen = ServerExplosion.getSeenPercent(crystal.position(), player);
        return CrystalBlast.predicted(distance, seen, mod.getWorld().getDifficulty().getId(),
                player.getArmorValue(), player.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
    }

    private static boolean pillar(AltoClef mod, int x, int y, int z) {
        BlockState state = mod.getWorld().getBlockState(new BlockPos(x, y, z));
        return state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK);
    }
}
