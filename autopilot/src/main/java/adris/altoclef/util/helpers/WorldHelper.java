package adris.altoclef.util.helpers;

import baritone.Baritone;
import adris.altoclef.AltoClef;
import baritone.utils.accessor.IClientConnection;
import baritone.api.utils.Dimension;
import baritone.api.BaritoneAPI;
import baritone.pathing.movement.MovementHelper;
import baritone.process.MineProcess;
import baritone.utils.BlockStateInterface;
import baritone.utils.FallingColumn;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.network.Connection;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CartographyTableBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.LoomBlock;
import net.minecraft.world.level.block.RedStoneOreBlock;
import net.minecraft.world.level.block.SpawnerBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import java.util.*;

/**
 * Super useful helper functions for getting information about the world.
 */
public interface WorldHelper {

    // God bless 1.18
    int WORLD_CEILING_Y = 255;
    int WORLD_FLOOR_Y = -64;

    /**
     * Get the number of in-game ticks the game/world has been active for.
     */
    static int getTicks() {
        Connection con = Objects.requireNonNull(Minecraft.getInstance().getConnection()).getConnection();
        return ((IClientConnection) con).getTicks();
    }

    static Vec3 toVec3d(BlockPos pos) {
        if (pos == null) return null;
        return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    static Vec3 toVec3d(Vec3i pos) {
        return new Vec3(pos.getX(), pos.getY(), pos.getZ());
    }

    static Vec3i toVec3i(Vec3 pos) {
        return new Vec3i(Mth.floor(pos.x()), Mth.floor(pos.y()), Mth.floor(pos.z()));
    }

    static BlockPos toBlockPos(Vec3 pos) {
        return new BlockPos(Mth.floor(pos.x()), Mth.floor(pos.y()), Mth.floor(pos.z()));
    }

    static boolean isSourceBlock(AltoClef mod, BlockPos pos, boolean onlyAcceptStill) {
        BlockState s = mod.getWorld().getBlockState(pos);
        if (s.getBlock() instanceof LiquidBlock) {
            // Only accept still fluids.
            if (!s.getFluidState().isSource() && onlyAcceptStill) return false;
            int level = s.getFluidState().getAmount();
            // Ignore if there's liquid above, we can't tell if it's a source block or not.
            BlockState above = mod.getWorld().getBlockState(pos.above());
            if (above.getBlock() instanceof LiquidBlock) return false;
            return level == 8;
        }
        return false;
    }

    static double distanceXZSquared(Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        return (delta.x * delta.x) + (delta.z * delta.z);
    }

    static double distanceXZ(Vec3 from, Vec3 to) {
        return Math.sqrt(distanceXZSquared(from, to));
    }

    static boolean inRangeXZ(Vec3 from, Vec3 to, double range) {
        return distanceXZSquared(from, to) < range * range;
    }

    static boolean inRangeXZ(BlockPos from, BlockPos to, double range) {
        return inRangeXZ(toVec3d(from), toVec3d(to), range);
    }

    static boolean inRangeXZ(Entity entity, Vec3 to, double range) {
        return inRangeXZ(entity.position(), to, range);
    }

    static boolean inRangeXZ(Entity entity, BlockPos to, double range) {
        return inRangeXZ(entity, toVec3d(to), range);
    }

    static boolean inRangeXZ(Entity entity, Entity to, double range) {
        return inRangeXZ(entity, to.position(), range);
    }

    static Dimension getCurrentDimension() {
        ClientLevel world = Minecraft.getInstance().level;
        if (world == null) return Dimension.OVERWORLD;
        return dimensionOf(world);
    }

    // for a level that is not the current one: a dead player keeps the level it died in after the respawn swapped ours
    static Dimension dimensionOf(Level world) {
        if (waterEvaporates(world)) return Dimension.NETHER;
        // no "natural" flag anymore, and the piglins_zombify attribute would call the end natural too, so ask the type
        if (overworldType(world)) return Dimension.OVERWORLD;
        return Dimension.END;
    }

    // natural and bedWorks were flags on the dimension type, only the two overworld types had them. the type, not the level
    // key, so a second overworld style world on a server still counts
    static boolean overworldType(Level world) {
        return world.dimensionTypeRegistration().is(BuiltinDimensionTypes.OVERWORLD)
                || world.dimensionTypeRegistration().is(BuiltinDimensionTypes.OVERWORLD_CAVES);
    }


    static boolean isSolid(AltoClef mod, BlockPos pos) {
        return mod.getWorld().getBlockState(pos).isRedstoneConductor(mod.getWorld(), pos);
    }

    /**
     * Get the "head" of a block with a bed, if the block is a bed.
     */
    static BlockPos getBedHead(AltoClef mod, BlockPos posWithBed) {
        BlockState state = mod.getWorld().getBlockState(posWithBed);
        if (state.getBlock() instanceof BedBlock) {
            Direction facing = state.getValue(BedBlock.FACING);
            if (mod.getWorld().getBlockState(posWithBed).getValue(BedBlock.PART).equals(BedPart.HEAD)) {
                return posWithBed;
            }
            return posWithBed.relative(facing);
        }
        return null;
    }

    /**
     * Get the "foot" of a block with a bed, if the block is a bed.
     */
    static BlockPos getBedFoot(AltoClef mod, BlockPos posWithBed) {
        BlockState state = mod.getWorld().getBlockState(posWithBed);
        if (state.getBlock() instanceof BedBlock) {
            Direction facing = state.getValue(BedBlock.FACING);
            if (mod.getWorld().getBlockState(posWithBed).getValue(BedBlock.PART).equals(BedPart.FOOT)) {
                return posWithBed;
            }
            return posWithBed.relative(facing.getOpposite());
        }
        return null;
    }

    // Get the left side of a chest, given a block pos.
    // Used to consistently identify whether a double chest is part of the same chest.
    static BlockPos getChestLeft(AltoClef mod, BlockPos posWithChest) {
        BlockState state = mod.getWorld().getBlockState(posWithChest);
        if (state.getBlock() instanceof ChestBlock) {
            ChestType type = state.getValue(ChestBlock.TYPE);
            if (type == ChestType.SINGLE || type == ChestType.LEFT) {
                return posWithChest;
            }
            Direction facing = state.getValue(ChestBlock.FACING);
            return posWithChest.relative(facing.getCounterClockWise());
        }
        return null;
    }

    static boolean isChestBig(AltoClef mod, BlockPos posWithChest) {
        BlockState state = mod.getWorld().getBlockState(posWithChest);
        if (state.getBlock() instanceof ChestBlock) {
            ChestType type = state.getValue(ChestBlock.TYPE);
            return (type == ChestType.RIGHT || type == ChestType.LEFT);
        }
        return false;
    }

    static int getGroundHeight(AltoClef mod, int x, int z) {
        // one mutable pos instead of a fresh BlockPos per y. and no scanning void below the end/nether floor
        BlockPos.MutableBlockPos check = new BlockPos.MutableBlockPos();
        int top = Math.min(WORLD_CEILING_Y, mod.getWorld().getMaxY());
        int bottom = Math.max(WORLD_FLOOR_Y, mod.getWorld().getMinY());
        for (int y = top; y >= bottom; --y) {
            if (isSolid(mod, check.set(x, y, z))) return y;
        }
        return -1;
    }

    static BlockPos getADesertTemple(AltoClef mod) {
        if (mod.getBlockTracker().isTracking(Blocks.STONE_PRESSURE_PLATE)) {
            Optional<BlockPos> stonePressurePlates = mod.getBlockTracker().getNearestTracking(Blocks.STONE_PRESSURE_PLATE);
            if (stonePressurePlates.isPresent() && mod.getWorld().getBlockState(stonePressurePlates.get()).getBlock() == Blocks.STONE_PRESSURE_PLATE && // Duct tape
                    mod.getWorld().getBlockState(stonePressurePlates.get().below()).getBlock() == Blocks.CUT_SANDSTONE &&
                    mod.getWorld().getBlockState(stonePressurePlates.get().below(2)).getBlock() == Blocks.TNT) {
                return stonePressurePlates.get();
            }
        }
        return null;
    }

    static boolean isUnopenedChest(AltoClef mod, BlockPos pos) {
        return mod.getItemStorage().getContainerAtPosition(pos).isEmpty();
    }

    static int getGroundHeight(AltoClef mod, int x, int z, Block... groundBlocks) {
        // callers pass one or two blocks, so a linear look beats building a HashSet on every call
        BlockPos.MutableBlockPos check = new BlockPos.MutableBlockPos();
        int top = Math.min(WORLD_CEILING_Y, mod.getWorld().getMaxY());
        int bottom = Math.max(WORLD_FLOOR_Y, mod.getWorld().getMinY());
        for (int y = top; y >= bottom; --y) {
            Block found = mod.getWorld().getBlockState(check.set(x, y, z)).getBlock();
            for (Block ground : groundBlocks) {
                if (ground == found) return y;
            }
        }
        return -1;
    }

    // the block would take over BreakRules.MAX_TICKS with the best thing in the bag (obsidian under an iron pick). not a
    // question for bedrock or liquids, those say what they always said
    static boolean tooSlowToBreak(AltoClef mod, BlockPos pos) {
        net.minecraft.world.level.block.state.BlockState state = mod.getWorld().getBlockState(pos);
        // most blocks asked about are quick, and those shouldn't cost a list
        return !BreakRules.alwaysFine(state) && BreakRules.tooSlow(bagOf(mod), state);
    }

    // what a break could be done with: the bag and the offhand (the tool gates count a pick over there too)
    static java.util.List<net.minecraft.world.item.ItemStack> bagOf(AltoClef mod) {
        net.minecraft.world.entity.player.Inventory inv = mod.getPlayer().getInventory();
        java.util.List<net.minecraft.world.item.ItemStack> bag = new java.util.ArrayList<>(inv.getNonEquipmentItems().size() + 1);
        bag.addAll(inv.getNonEquipmentItems());
        bag.add(mod.getPlayer().getItemBySlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND));
        return bag;
    }

    static boolean canBreak(AltoClef mod, BlockPos pos) {
        // cheap rejects go first. the context is cached per tick now but plausibleToBreak and the ocean check in canReach
        // still cost something, and most callers are asking about 25 blocks that mostly fail one of these
        if (mod.getWorld().getBlockState(pos).getDestroySpeed(mod.getWorld(), pos) < 0
                || mod.getExtraBaritoneSettings().shouldAvoidBreaking(pos)
                || mod.getBlockTracker().unreachable(pos)
                || tooSlowToBreak(mod, pos)) {
            return false;
        }
        // the old "JANK: check without paused interactions" toggle lives in the cache now, that's where it's read.
        // (not doing it creates bugs where we loop back and forth through the nether portal and stuff)
        return MineProcess.plausibleToBreak(BreakContextCache.get(mod), pos) && canReach(mod, pos);
    }

    // would the pather be let to dig this cell out to stand in it. only the rules (allowBreak and allowBreakAnyway, the
    // protected cells altoclef and the cast tasks hand it, unbreakables, avoidBreaking), not reach or speed: the pather prices those itself.
    // nothing to dig counts as yes, a liquid as no, standing in a dug out cell full of lava is not the plan
    static boolean pathMayDig(AltoClef mod, BlockPos pos) {
        net.minecraft.world.level.block.state.BlockState state = mod.getWorld().getBlockState(pos);
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        if (state.getCollisionShape(mod.getWorld(), pos).isEmpty()) {
            return true;
        }
        if (state.getDestroySpeed(mod.getWorld(), pos) < 0 || mod.getExtraBaritoneSettings().shouldAvoidBreaking(pos)) {
            return false;
        }
        baritone.pathing.movement.CalculationContext ctx = BreakContextCache.get(mod);
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        if (ctx.breakCostMultiplierAt(x, y, z, state) >= baritone.api.pathing.movement.ActionCosts.COST_INF) {
            return false;
        }
        // the same no the planner gives in getMiningDurationTicks0 (never break list, ice, infested, loose gravel or water
        // next door). picking a stand cell it won't dig just means walking at it forever. and the context flavour on
        // purpose: the per cell altoclef rule was asked up there already, and the never break bit is precomputed
        return MovementHelper.canWalkThrough(ctx, x, y, z, state) || !MovementHelper.avoidBreaking(ctx, x, y, z, state);
    }

    static boolean isInNetherPortal(AltoClef mod) {
        if (mod.getPlayer() == null)
            return false;
        PortalProcessor portalManager = mod.getPlayer().portalProcess;
        if (portalManager == null)
            return false;
        return portalManager.isInsidePortalThisTick();
    }

    // the flag only flips once the game has noticed us (a fresh player after a dimension change has none yet), the cells answer from the first tick
    static boolean isStandingInPortal(AltoClef mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null) {
            return false;
        }
        BlockPos feet = mod.getPlayer().blockPosition();
        return isInNetherPortal(mod) || mod.getWorld().getBlockState(feet).is(Blocks.NETHER_PORTAL)
                || mod.getWorld().getBlockState(feet.above()).is(Blocks.NETHER_PORTAL);
    }

    static boolean dangerousToBreakIfRightAbove(AltoClef mod, BlockPos toBreak) {
        // There might be mumbo jumbo next to it, we fall and we get killed by lava or something.
        if (MovementHelper.avoidBreaking(mod.getClientBaritone().bsi, toBreak.getX(), toBreak.getY(), toBreak.getZ(), mod.getWorld().getBlockState(toBreak))) {
            return true;
        }
        // Fall down
        for (int dy = 1; dy <= toBreak.getY() - WORLD_FLOOR_Y; ++dy) {
            BlockPos check = toBreak.below(dy);
            BlockState s = mod.getWorld().getBlockState(check);
            boolean tooFarToFall = dy > mod.getClientBaritoneSettings().maxFallHeightNoWater.value;
            // Don't fall in lava
            if (MovementHelper.isLava(s))
                return true;
            // Always fall in water
            // TODO: If there's a 1 meter thick layer of water and then a massive drop below, the bot will think it is safe.
            if (MovementHelper.isWater(s))
                return true;
            // We hit ground, depends
            if (WorldHelper.isSolid(mod, check)) {
                return tooFarToFall;
            }
        }
        // At this point we probably fall through the void, so not safe.
        return true;
    }

    // the drop's turn: dangerousToBreakIfRightAbove keeps us out of the lava, this keeps the obsidian out of it. see DropBurnRules
    static boolean dropWouldBurn(AltoClef mod, BlockPos pos) {
        // an unloaded chunk reads as plain air, which says "safe" about the very crust we walked away from. the tracker still
        // remembers that obsidian, so unknown counts as burning until we're close enough to look again
        if (!mod.getChunkTracker().isChunkLoaded(pos)) {
            return true;
        }
        Level level = mod.getWorld();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        DropBurnRules.Cells cells = new DropBurnRules.Cells() {
            @Override
            public boolean lava(int x, int y, int z) {
                return level.getFluidState(at.set(x, y, z)).is(FluidTags.LAVA);
            }

            @Override
            public boolean water(int x, int y, int z) {
                return level.getFluidState(at.set(x, y, z)).is(FluidTags.WATER);
            }

            @Override
            public boolean fallThrough(int x, int y, int z) {
                at.set(x, y, z);
                return level.getBlockState(at).getCollisionShape(level, at).isEmpty();
            }
        };
        return DropBurnRules.dropWouldBurn(cells, pos.getX(), pos.getY(), pos.getZ(), level.getMinY());
    }

    static boolean canPlace(AltoClef mod, BlockPos pos) {
        return !mod.getExtraBaritoneSettings().shouldAvoidPlacingAt(pos)
                && canReach(mod, pos);
    }

    // same question BlockItem asks before it places: does a mob (or we) stand where the block goes. the server says no and
    // the click does nothing otherwise. same call PlaceStationTask uses for its cells
    static boolean entityFreeFor(Level level, BlockPos pos, BlockState state, Player player) {
        return level.isUnobstructed(state, pos, CollisionContext.of(player));
    }

    // canPlace for a solid block. not folded into canPlace on purpose: that one also answers for water and lava (the
    // extinguish bucket goes in our own cell) and for "is this whole area fine" scans that include the cell we stand in,
    // and none of those care who is in the way
    static boolean canPlaceBlock(AltoClef mod, BlockPos pos, BlockState state) {
        return canPlace(mod, pos) && entityFreeFor(mod.getWorld(), pos, state, mod.getPlayer());
    }

    static boolean canReach(AltoClef mod, BlockPos pos) {
        if (Baritone.settings().altoAvoidOceanBlocks.value) {
            // are we up at the surface: a line at y 47 that only flips once we are clearly past it (OceanRule)
            if (OceanRule.PLAYER.atSurface(mod.getPlayer().getY()) && mod.getChunkTracker().isChunkLoaded(pos) && isOcean(mod.getWorld().getBiome(pos))) {
                // Block is in an ocean biome. If it's below sea level...
                if (pos.getY() < 64 && getGroundHeight(mod, pos.getX(), pos.getZ(), Blocks.WATER) > pos.getY()) {
                    return false;
                }
            }
        }
        return !mod.getBlockTracker().unreachable(pos);
    }

    static boolean isOcean(Holder<Biome> b) {
        return (b.is(Biomes.OCEAN)
                || b.is(Biomes.COLD_OCEAN)
                || b.is(Biomes.DEEP_COLD_OCEAN)
                || b.is(Biomes.DEEP_OCEAN)
                || b.is(Biomes.DEEP_FROZEN_OCEAN)
                || b.is(Biomes.DEEP_LUKEWARM_OCEAN)
                || b.is(Biomes.LUKEWARM_OCEAN)
                || b.is(Biomes.WARM_OCEAN)
                || b.is(Biomes.FROZEN_OCEAN));
    }

    static boolean isAir(AltoClef mod, BlockPos pos) {
        return mod.getBlockTracker().blockIsValid(pos, Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR);
        //return state.isAir() || isAir(state.getBlock());
    }

    static boolean isAir(Block block) {
        return block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR;
    }

    static boolean isInteractableBlock(AltoClef mod, BlockPos pos) {
        Block block = mod.getWorld().getBlockState(pos).getBlock();
        return (block instanceof ChestBlock
                || block instanceof EnderChestBlock
                || block instanceof CraftingTableBlock
                || block instanceof AbstractFurnaceBlock
                || block instanceof LoomBlock
                || block instanceof CartographyTableBlock
                || block instanceof EnchantingTableBlock
                || block instanceof RedStoneOreBlock
                || block instanceof BarrelBlock
        );
    }

    static boolean isInsidePlayer(AltoClef mod, BlockPos pos) {
        return pos.closerToCenterThan(mod.getPlayer().position(), 2);
    }

    static Iterable<BlockPos> getBlocksTouchingPlayer(AltoClef mod) {
        return getBlocksTouchingBox(mod, mod.getPlayer().getBoundingBox());
    }

    static Iterable<BlockPos> getBlocksTouchingBox(AltoClef mod, AABB box) {
        BlockPos min = new BlockPos(Mth.floor(box.minX), Mth.floor(box.minY), Mth.floor(box.minZ));
        BlockPos max = new BlockPos(Mth.floor(box.maxX), Mth.floor(box.maxY), Mth.floor(box.maxZ));
        return scanRegion(mod, min, max);
    }

    static Iterable<BlockPos> scanRegion(AltoClef mod, BlockPos start, BlockPos end) {
        return () -> new Iterator<>() {
            int x = start.getX(), y = start.getY(), z = start.getZ();

            @Override
            public boolean hasNext() {
                return y <= end.getY() && z <= end.getZ() && x <= end.getX();
            }

            @Override
            public BlockPos next() {
                BlockPos result = new BlockPos(x, y, z);
                ++x;
                if (x > end.getX()) {
                    x = start.getX();
                    ++z;
                    if (z > end.getZ()) {
                        z = start.getZ();
                        ++y;
                    }
                }
                return result;
            }
        };
    }

    static boolean fallingBlockSafeToBreak(BlockPos pos) {
        BlockStateInterface bsi = new BlockStateInterface(BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext());
        Level w = Minecraft.getInstance().level;
        assert w != null;
        while (isFallingBlock(pos)) {
            if (MovementHelper.avoidBreaking(bsi, pos.getX(), pos.getY(), pos.getZ(), w.getBlockState(pos)))
                return false;
            pos = pos.above();
        }
        return true;
    }

    static boolean isFallingBlock(BlockPos pos) {
        Level w = Minecraft.getInstance().level;
        assert w != null;
        return FallingColumn.isFalling(w.getBlockState(pos));
    }

    // a falling block entity this high up the column is still on its way into the cell. a stack lets go all at once, so
    // the top of a tall one is way up there while the bottom one has already landed
    double FALL_LOOKAHEAD = 6;

    static FallingColumn.Cells fallingCells(Level level) {
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        return FallingColumn.over((x, y, z) -> level.getBlockState(at.set(x, y, z)));
    }

    // sand, gravel and friends stacked right on the block, how many. breaking it brings every one of them down
    static int fallingAbove(Level level, BlockPos pos) {
        return FallingColumn.heightAbove(fallingCells(level), pos.getX(), pos.getY(), pos.getZ());
    }

    // breaking this lets something loose: a stack on top of it, or stalactites hanging off the bottom
    static boolean letsFallingLoose(Level level, BlockPos pos) {
        return fallingAbove(level, pos) > 0 || FallingColumn.hangingBelow(fallingCells(level), pos.getX(), pos.getY(), pos.getZ()) > 0;
    }

    // whatever breaking this lets loose ends up in the cells we are standing in. our own box, so a block that is partly
    // under us counts and one a step to the side doesn't
    static boolean breakingDropsOnUs(AltoClef mod, BlockPos target) {
        AABB b = mod.getPlayer().getBoundingBox();
        FallingColumn.Cells w = fallingCells(mod.getWorld());
        return FallingColumn.landsOnBox(w, target.getX(), target.getY(), target.getZ(), b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ)
                || FallingColumn.hangersFallOnBox(w, target.getX(), target.getY(), target.getZ(), b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
    }

    // a falling block entity somewhere over the target cell (its own cell up to FALL_LOOKAHEAD above it)
    static boolean fallingInFlightOver(Level level, BlockPos target) {
        return !level.getEntitiesOfClass(FallingBlockEntity.class, new AABB(target.getX(), target.getY(), target.getZ(), target.getX() + 1, target.getY() + 1 + FALL_LOOKAHEAD, target.getZ() + 1)).isEmpty();
    }

    // the cell is open and a falling block sits right on it with nothing under that one: it lets go two ticks from now,
    // and until it does there is no entity to see. that is the gap a swing or a step lands in
    static boolean fallingAboutToDrop(Level level, BlockPos target) {
        FallingColumn.Cells w = fallingCells(level);
        return !w.stops(target.getX(), target.getY(), target.getZ()) && w.falls(target.getX(), target.getY() + 1, target.getZ());
    }

    // sand or gravel that landed in the cell we stand in or the one our eyes are in. null when we are not buried. the eye
    // cell goes first, that's the one that suffocates
    static BlockPos buriedInFallenBlock(AltoClef mod) {
        Player me = mod.getPlayer();
        Level level = mod.getWorld();
        if (me == null || level == null) {
            return null;
        }
        for (BlockPos pos : new BlockPos[]{BlockPos.containing(me.getEyePosition()), me.blockPosition()}) {
            BlockState s = level.getBlockState(pos);
            if (FallingColumn.isGravity(s.getBlock()) && !s.getCollisionShape(level, pos).isEmpty()) {
                return pos;
            }
        }
        return null;
    }

    static Entity getSpawnerEntity(AltoClef mod, BlockPos pos) {
        BlockState state = mod.getWorld().getBlockState(pos);
        if (state.getBlock() instanceof SpawnerBlock) {
            BlockEntity be = mod.getWorld().getBlockEntity(pos);
            if (be instanceof SpawnerBlockEntity blockEntity) {
                return blockEntity.getSpawner().getOrCreateDisplayEntity(mod.getWorld(), pos);
            }
        }
        return null;
    }

    static Vec3 getOverworldPosition(Vec3 pos) {
        if (getCurrentDimension() == Dimension.NETHER) {
            pos = pos.multiply(8.0, 1, 8.0);
        }
        return pos;
    }

    static BlockPos getOverworldPosition(BlockPos pos) {
        if (getCurrentDimension() == Dimension.NETHER) {
            pos = new BlockPos(pos.getX() * 8, pos.getY(), pos.getZ() * 8);
        }
        return pos;
    }

    static boolean isChest(AltoClef mod, BlockPos block) {
        Block b = mod.getWorld().getBlockState(block).getBlock();
        return isChest(b);
    }

    static boolean isChest(Block b) {
        return b instanceof ChestBlock || b instanceof EnderChestBlock;
    }

    static boolean isBlock(AltoClef mod, BlockPos pos, Block block) {
        return mod.getWorld().getBlockState(pos).getBlock() == block;
    }

    static boolean canSleep() {
        ClientLevel world = Minecraft.getInstance().level;
        // the game's own rule (ServerPlayer.startSleepInBed refuses while Level.isBrightOutside()): dusk to dawn, 12542 to 23459 in clear
        // weather, and any time in a thunderstorm. the hand copied window this had ended at 23992, which is the end of a rainy
        // night. a nether or end bed explodes, so only a dimension where beds work counts
        // the dimension type, see overworldType
        return world != null && overworldType(world) && !world.isBrightOutside();
    }

    // ultraWarm used to be the nether-only flag, water_evaporates is the synced attribute that took its job. vanilla's bucket
    // asks it per position, but nothing in the vanilla data layers it by biome, so the dimension wide value is the same answer
    static boolean waterEvaporates(Level world) {
        return world.environmentAttributes().getDimensionValue(EnvironmentAttributes.WATER_EVAPORATES);
    }
}
