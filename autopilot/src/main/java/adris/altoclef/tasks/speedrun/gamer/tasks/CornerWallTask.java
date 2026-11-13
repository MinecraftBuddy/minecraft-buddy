package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.AltoSettings;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.GetDownColumnTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.CombatCommit;
import adris.altoclef.util.helpers.CornerWall;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Fallable;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

// (the buddy's fork, Tuning unarmed_no_corner_fight, a fix of 13 Nov 2026) the wall of an unarmed run with no room
// (CombatCommit.wallInstead): a full block from the bag put between the buddy and the mob, at the head's height first, then at
// the feet's; or, with no cell between free (the mob stands in it), two down where the buddy stands and the cell over the head
// shut, as a player digs in. CornerWall has the rules, CombatCommit decides when and how long the run holds behind it; this
// clicks, digs and says how it stands. the click is MobDefenseChain.placeCell's
public final class CornerWallTask extends Task {
    private enum Stage {
        PICK, BLOCK, DIG, ROOF, BUILT, FAILED
    }

    // one cell's click gets this long (the block in hand, the look, the click, the block showing up) before the next is tried
    private static final long CELL_TICKS = 20;
    private static final long DIG_TICKS = 120;
    private static final long ROOF_TICKS = 40;
    // the block the dig just broke is on its way into the bag this long at most
    private static final long PICKUP_TICKS = 20;
    private static final double REACH = 4.5;
    // the top clicks the walls of the hole, then the cell over it; a cell beside the buddy its floor first (the top face one
    // block ahead is in plain view), then the sides, then the cell over it
    private static final Direction[] TOP_SUPPORT = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
    static final Direction[] BESIDE_SUPPORT = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};

    private final int foeId;
    // (Tuning skel_cover) the dig alone, no block between: the skeleton cover's way down when no cell between takes a block
    // (SkelCoverTask). say starts each line this task writes after "combat: "
    private final boolean digOnly;
    private final String say;
    // (Tuning creeper_no_heading_block) how deep the dig goes, and whether the top is shut after it: a creeper's wall digs one
    // down and leaves the top open (its fuse is a second and a half; two down and a roof take longer than that)
    private final int digDepth;
    private final boolean roofs;
    private Stage stage = Stage.PICK;
    private long stageSince = Long.MIN_VALUE;
    private String foeName = "mob";
    private final Set<BlockPos> cells = new LinkedHashSet<>();
    private final Set<BlockPos> skipped = new HashSet<>();
    private final Set<BlockPos> said = new HashSet<>();
    private BlockPos current;
    private long currentSince;
    private BlockPos column;
    private int floorY;
    private int depth;
    // the hole is shut over the head (a top went in, or it was shut already): SkelCoverTask's dig_first counts a hole without
    // one as no cover from a skeleton
    private boolean topShut;
    private final Object keys = new Object();

    public CornerWallTask(int foeId) {
        this(foeId, false, "cornered and unarmed: ", CornerWall.DEPTH, true);
    }

    private CornerWallTask(int foeId, boolean digOnly, String say, int digDepth, boolean roofs) {
        this.foeId = foeId;
        this.digOnly = digOnly;
        this.say = say;
        this.digDepth = digDepth;
        this.roofs = roofs;
    }

    // (the buddy's fork, Tuning creeper_no_heading_block, a fix of 13 Nov 2026) a creeper step with no safe heading: a block
    // from the bag in a cell beside the buddy toward the creeper (the head's height first, which takes its line and stops the
    // swell, then the feet's, which takes the blast's rays at its feet), else one down where the buddy stands (CreeperStepTask)
    public static CornerWallTask creeper(int creeperId) {
        return new CornerWallTask(creeperId, false, "creeper with no way out: ", CREEPER_DEPTH, false);
    }

    public static final int CREEPER_DEPTH = 1;

    // two down where the buddy stands and the top shut, nothing else (SkelCoverTask)
    static CornerWallTask digIn(String say) {
        return new CornerWallTask(-1, true, say, CornerWall.DEPTH, true);
    }

    // built with the cell over the head shut (false while building, for a wall of blocks and for a hole left open)
    public boolean topShut() {
        return topShut;
    }

    // what CombatCommit.wallFacts hears: building until it is up or given up
    public CombatCommit.WallState state() {
        return switch (stage) {
            case BUILT -> CombatCommit.WallState.BUILT;
            case FAILED -> CombatCommit.WallState.FAILED;
            default -> CombatCommit.WallState.BUILDING;
        };
    }

    // a wall is to be had here: a full block in the bag, or ground under the buddy to dig two down
    public static boolean offered(AltoClef mod) {
        return offered(mod, CornerWall.DEPTH);
    }

    // the same for a dig of this depth (the creeper's is one)
    public static boolean offered(AltoClef mod, int depth) {
        LocalPlayer player = mod.getPlayer();
        if (player == null) return false;
        return wallItems(mod).length > 0 || (player.onGround() && diggable(mod, mod.getWorld(), player.blockPosition(), depth));
    }

    @Override
    protected void onStart(AltoClef mod) {
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getInputControls().releaseOwnedBy(keys);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other == this;
    }

    @Override
    protected String toDebugString() {
        return (digOnly ? "Digging in (" : "Walling off a corner (") + stage + ")";
    }

    private void enter(Stage next, long now) {
        stage = next;
        stageSince = now;
        mod().getInputControls().releaseOwnedBy(keys);
    }

    private static AltoClef mod() {
        return AltoClef.getInstance();
    }

    private void fail(String why, long now) {
        Debug.logInternal("combat: " + say + (digOnly ? "no hole (" : "no wall (") + why + ")");
        enter(Stage.FAILED, now);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        Level level = mod.getWorld();
        long now = level.getGameTime();
        if (stageSince == Long.MIN_VALUE) stageSince = now;
        return switch (stage) {
            case PICK -> pick(mod, player, level, now);
            case BLOCK -> block(mod, player, level, now);
            case DIG -> dig(mod, player, level, now);
            case ROOF -> roof(mod, player, level, now);
            case BUILT, FAILED -> stand(mod);
        };
    }

    // behind the wall (or given up, till the machine lets go): still, the path paused
    private Task stand(AltoClef mod) {
        mod.getInputControls().releaseOwnedBy(keys);
        mod.getClientBaritone().getPathingBehavior().requestPause();
        return null;
    }

    private Task pick(AltoClef mod, LocalPlayer player, Level level, long now) {
        // (in the air after a knock: the machine's WALL_BUILD_TICKS bounds the wait)
        if (!player.onGround()) return stand(mod);
        BlockPos feet = player.blockPosition();
        if (digOnly) {
            if (shut(level, feet)) {
                Debug.logInternal("combat: " + say + "shut in already at " + feet.toShortString());
                topShut = true;
                enter(Stage.BUILT, now);
            } else if (diggable(mod, level, feet, digDepth)) {
                startDig(feet, now);
            } else {
                fail("no ground to dig at " + feet.toShortString(), now);
            }
            return null;
        }
        Entity foe = level.getEntity(foeId);
        if (!(foe instanceof LivingEntity alive) || !alive.isAlive()) foe = nearestMob(mod, player);
        if (foe != null) foeName = BuiltInRegistries.ENTITY_TYPE.getKey(foe.getType()).getPath();
        cells.clear();
        if (foe != null && wallItems(mod).length > 0) {
            for (int[] off : CornerWall.between(foe.getX() - player.getX(), foe.getZ() - player.getZ())) {
                BlockPos cell = feet.offset(off[0], off[1], off[2]);
                if (open(level, cell, player)) cells.add(cell);
            }
        }
        // (the head's cell over open ground has its face only once the feet's cell is placed: that one goes first then)
        boolean cellFree = false;
        for (BlockPos cell : cells) cellFree |= support(level, player, cell, BESIDE_SUPPORT) != null;
        boolean dig = diggable(mod, level, feet, digDepth);
        switch (CornerWall.pick(shut(level, feet), cellFree, dig)) {
            case BUILT -> {
                Debug.logInternal("combat: " + say + "shut in already at " + feet.toShortString());
                topShut = true;
                enter(Stage.BUILT, now);
            }
            case BLOCK -> enter(Stage.BLOCK, now);
            case DIG -> startDig(feet, now);
            case NONE -> fail("no free cell between us and the " + foeName + " and no ground to dig at " + feet.toShortString(), now);
        }
        return null;
    }

    private void startDig(BlockPos feet, long now) {
        column = feet;
        depth = digDepth;
        floorY = feet.getY() - depth;
        enter(Stage.DIG, now);
    }

    private Task block(AltoClef mod, LocalPlayer player, Level level, long now) {
        boolean any = false;
        BlockPos next = null;
        for (BlockPos cell : cells) {
            if (solid(level, cell)) {
                any = true;
                if (said.add(cell)) {
                    Debug.logInternal("combat: " + say + "a block put between us and the " + foeName + " at "
                            + cell.getX() + " " + cell.getY() + " " + cell.getZ());
                }
                continue;
            }
            if (next == null && !skipped.contains(cell) && open(level, cell, player) && support(level, player, cell, BESIDE_SUPPORT) != null) next = cell;
        }
        if (next == null) {
            if (any) {
                enter(Stage.BUILT, now);
            } else if (player.onGround() && diggable(mod, level, player.blockPosition(), digDepth)) {
                startDig(player.blockPosition(), now);
            } else {
                fail("no block went between us and the " + foeName, now);
            }
            return null;
        }
        if (!next.equals(current)) {
            current = next;
            currentSince = now;
        }
        if (now - currentSince > CELL_TICKS || !click(mod, player, level, next, BESIDE_SUPPORT)) {
            skipped.add(next);
            current = null;
        }
        return null;
    }

    private Task dig(AltoClef mod, LocalPlayer player, Level level, long now) {
        if (now - stageSince > DIG_TICKS) {
            fail("not " + depth + " down in " + DIG_TICKS / 20 + " s at " + column.toShortString(), now);
            return null;
        }
        if (player.onGround() && player.blockPosition().getY() <= floorY) {
            if (!roofs) {
                Debug.logInternal("combat: " + say + "dug down at " + column.getX() + " " + column.getY() + " " + column.getZ()
                        + " (" + depth + " deep, the top left open)");
                enter(Stage.BUILT, now);
                return null;
            }
            enter(Stage.ROOF, now);
            return null;
        }
        // straight down the column, never a side cell to dig from (ShelterTask's way)
        return new GetDownColumnTask(column.getX(), column.getZ(), floorY, false);
    }

    private Task roof(AltoClef mod, LocalPlayer player, Level level, long now) {
        // the cell over the top of the head, not feet + 2: standing on a slab the body reaches into that one
        BlockPos top = BlockPos.containing(player.getX(), player.getBoundingBox().maxY, player.getZ()).above();
        if (!level.getBlockState(top).canBeReplaced()) {
            built(level, top, now);
            return null;
        }
        // nothing for the top to go against (flat open ground): one more down, between the hole's walls, when the ground allows
        boolean against = false;
        for (Direction d : TOP_SUPPORT) {
            BlockPos n = top.relative(d);
            against |= level.getFluidState(n).isEmpty() && level.getBlockState(n).isFaceSturdy(level, n, d.getOpposite());
        }
        if (!against) {
            BlockPos feet = player.blockPosition();
            if (depth < CornerWall.MAX_DEPTH && digCell(level, feet.below(), havePick(mod)) && floorHolds(level, feet.below(2))) {
                depth++;
                floorY--;
                enter(Stage.DIG, now);
                return null;
            }
            built(level, null, now);
            return null;
        }
        // the dug block on its way into the bag, or a mob dropped into the cell: a moment, then up without a top (two down is
        // out of a zombie's reach all the same)
        boolean mobIn = !level.getEntitiesOfClass(LivingEntity.class, new AABB(top), e -> e != player).isEmpty();
        if (wallItems(mod).length == 0 || mobIn) {
            if (now - stageSince > PICKUP_TICKS) built(level, null, now);
            return null;
        }
        if (now - stageSince > ROOF_TICKS || !click(mod, player, level, top, TOP_SUPPORT)) built(level, null, now);
        return null;
    }

    private void built(Level level, BlockPos top, long now) {
        topShut = top != null;
        int deep = column == null ? 0 : column.getY() - mod().getPlayer().blockPosition().getY();
        String what = top == null ? "no top: nothing to shut it with or against"
                : "the top shut with " + BuiltInRegistries.BLOCK.getKey(level.getBlockState(top).getBlock()).getPath();
        Debug.logInternal("combat: " + say + (digOnly ? "dug in at " : "dug down at ") + column.getX() + " " + column.getY() + " " + column.getZ()
                + " (" + deep + " deep, " + what + ")");
        enter(Stage.BUILT, now);
    }

    // ---- the click (MobDefenseChain.placeCell's): sneak, the block in hand, the look on a face beside the cell, our own ray
    // along that look, then the use. false: nothing to place or no face in sight to click

    private boolean click(AltoClef mod, LocalPlayer player, Level level, BlockPos cell, Direction[] order) {
        return click(mod, player, level, cell, order, keys);
    }

    // (SkelCoverTask's clicks too) keys: who holds the sneak, let go of by the caller
    static boolean click(AltoClef mod, LocalPlayer player, Level level, BlockPos cell, Direction[] order, Object keys) {
        Item[] blocks = wallItems(mod);
        if (blocks.length == 0) return false;
        Direction[] face = new Direction[1];
        BlockPos support = support(level, player, cell, order, face);
        if (support == null) return false;
        // sneak first: a crafting table or a furnace in the wall would eat the click as a use
        mod.getInputControls().hold(Input.SNEAK, keys);
        if (!StorageHelper.isEquipped(mod, blocks)) {
            mod.getSlotHandler().forceEquipItem(blocks, true);
            return true;
        }
        Vec3 at = facePoint(support, face[0]);
        mod.getClientBaritone().getPathingBehavior().requestPause();
        LookHelper.lookAt(mod, at);
        // our own ray along the look we just set, the crosshair is a frame behind it
        HitResult hit = RayTraceUtils.rayTraceTowards(player, LookHelper.getLookRotation(player), REACH);
        if (!(hit instanceof BlockHitResult bhit) || !bhit.getBlockPos().equals(support) || bhit.getDirection() != face[0]) return true;
        InteractionResult result = Minecraft.getInstance().gameMode.useItemOn(player, InteractionHand.MAIN_HAND, bhit);
        if (result.consumesAction()) player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        return true;
    }

    private static Vec3 facePoint(BlockPos support, Direction face) {
        return Vec3.atCenterOf(support).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
    }

    static BlockPos support(Level level, LocalPlayer player, BlockPos cell, Direction[] order) {
        return support(level, player, cell, order, new Direction[1]);
    }

    // the first neighbour of the cell, in this order, with a sturdy dry face toward it in reach and in sight of the eye
    private static BlockPos support(Level level, LocalPlayer player, BlockPos cell, Direction[] order, Direction[] faceOut) {
        Vec3 eye = player.getEyePosition();
        for (Direction d : order) {
            BlockPos n = cell.relative(d);
            Direction face = d.getOpposite();
            if (!level.getFluidState(n).isEmpty() || !level.getBlockState(n).isFaceSturdy(level, n, face)) continue;
            Vec3 at = facePoint(n, face);
            if (eye.distanceTo(at) > REACH) continue;
            BlockHitResult hit = level.clip(new ClipContext(eye, at.add(Vec3.atLowerCornerOf(d.getUnitVec3i()).scale(0.02)),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(n) || hit.getDirection() != face) continue;
            faceOut[0] = face;
            return n;
        }
        return null;
    }

    // ---- the world's questions

    // a cell a block can still go in: replaceable, dry, and no body in it (ours included)
    static boolean open(Level level, BlockPos cell, LocalPlayer player) {
        return level.getBlockState(cell).canBeReplaced() && level.getFluidState(cell).isEmpty()
                && level.isUnobstructed(Blocks.STONE.defaultBlockState(), cell, CollisionContext.of(player));
    }

    static boolean solid(Level level, BlockPos c) {
        BlockState s = level.getBlockState(c);
        return s.isCollisionShapeFullBlock(level, c) && s.getFluidState().isEmpty();
    }

    // the buddy's two cells walled on every side and roofed
    static boolean shut(Level level, BlockPos feet) {
        if (!solid(level, feet.above(2))) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (!solid(level, feet.relative(d)) || !solid(level, feet.above().relative(d))) return false;
        }
        return true;
    }

    // two cells under the feet that come out with the tools at hand and stay put (no sand or gravel), no lava or water in or by
    // them or by the floor, and a floor under them that holds (air there is a cave or a drop)
    public static boolean diggable(AltoClef mod, Level level, BlockPos feet) {
        return diggable(mod, level, feet, CornerWall.DEPTH);
    }

    // the same for a dig of this depth: that many cells under the feet, the floor under the last
    public static boolean diggable(AltoClef mod, Level level, BlockPos feet, int depth) {
        boolean pick = havePick(mod);
        for (int i = 1; i <= depth; i++) {
            if (!digCell(level, feet.below(i), pick)) return false;
        }
        return floorHolds(level, feet.below(depth + 1));
    }

    private static boolean floorHolds(Level level, BlockPos floor) {
        for (BlockPos c : new BlockPos[]{floor.above(2), floor.above(), floor}) {
            if (!level.getFluidState(c).isEmpty()) return false;
            for (Direction d : Direction.values()) {
                if (!level.getFluidState(c.relative(d)).isEmpty()) return false;
            }
        }
        return level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP);
    }

    private static boolean digCell(Level level, BlockPos c, boolean pick) {
        BlockState s = level.getBlockState(c);
        if (s.getBlock() instanceof Fallable || level.getBlockEntity(c) != null || !s.isCollisionShapeFullBlock(level, c)) return false;
        return adris.altoclef.tasks.speedrun.gamer.NightShelter.diggable(s.is(BlockTags.MINEABLE_WITH_SHOVEL), s.is(BlockTags.BASE_STONE_OVERWORLD),
                s.getDestroySpeed(level, c), pick);
    }

    private static boolean havePick(AltoClef mod) {
        return mod.getItemStorage().hasItem(Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.GOLDEN_PICKAXE, Items.COPPER_PICKAXE,
                Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE);
    }

    // the closest living hostile within four blocks, for a foe id that left the world
    private static Entity nearestMob(AltoClef mod, LocalPlayer player) {
        Entity best = null;
        double bestDist = 4;
        try {
            for (Entity entity : mod.getEntityTracker().getHostiles()) {
                if (!(entity instanceof Mob mob) || !mob.isAlive()) continue;
                double d = mob.distanceTo(player);
                if (d < bestDist) {
                    bestDist = d;
                    best = mob;
                }
            }
        } catch (ConcurrentModificationException ignored) {
            // the tracker rebuilds its lists on another thread sometimes: no foe this tick, the wall goes the way the ground allows
        }
        return best;
    }

    // the blocks a wall is made of, in the order they go: the throwaway list's that stay put while the bag holds any, then any
    // other full block the route can spare (CornerWall.spare: logs, planks, stone kinds)
    static Item[] wallItems(AltoClef mod) {
        Set<Item> out = new LinkedHashSet<>();
        for (Item item : AltoSettings.getThrowawayItems(mod, true)) {
            if (item instanceof BlockItem block && !(block.getBlock() instanceof Fallable) && mod.getItemStorage().hasItem(item)) out.add(item);
        }
        if (!out.isEmpty()) return out.toArray(new Item[0]);
        LocalPlayer player = mod.getPlayer();
        if (player == null) return new Item[0];
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem item && spare(item.getBlock())) out.add(stack.getItem());
        }
        return out.toArray(new Item[0]);
    }

    private static boolean spare(Block block) {
        return CornerWall.spare(BuiltInRegistries.BLOCK.getKey(block).getPath(), block instanceof Fallable, block instanceof EntityBlock,
                block instanceof LeavesBlock, block.defaultBlockState().isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
    }
}
