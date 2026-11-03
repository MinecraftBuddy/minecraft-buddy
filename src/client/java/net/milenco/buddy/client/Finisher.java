package net.milenco.buddy.client;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.milenco.buddy.version.Game;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * What a block still needs after it is placed, and what the builder may leave alone. Placing gives the facing, the
 * half, the count; the rest is done the way a player does it:
 * <ul>
 *   <li>a click to change a setting: repeater delay, comparator mode, note, open or shut, a daylight detector
 *       inverted, a lever on (that one last, it gives power);</li>
 *   <li>an item used on the block: glow ink, dye and wax on a sign, a plant in a pot, a bucket
 *       into a cauldron, compost, an eye in a portal frame, glowstone in an anchor, a candle on a cake, flint and
 *       steel or a shovel for a candle or campfire, a hoe for farmland, a shovel for a path, a book on a lectern
 *       or in a chiseled bookshelf, a disc in a jukebox;</li>
 *   <li>item frames (and their item and turn), armor stands and paintings from the schematic.</li>
 * </ul>
 * Blocks without an item of their own first get their base block (cauldron, flower pot, cake, dirt). Things that
 * change by themselves (grass spreading, copper weathering, ice melting, a piston head) are not "repaired".
 * The {@code what} of a fix goes into events.jsonl.
 */
final class Finisher {
	private Finisher() {}

	/** Where on the block a click has to land for this fix. */
	interface Rule { boolean ok(Level level, BlockPos pos, BlockHitResult hit, Vec3 eye); }

	static final Rule ANY = (l, p, h, e) -> true;
	/** Hoe and shovel: not the underside, and air above. */
	static final Rule TOP_OPEN = (l, p, h, e) -> h.getDirection() != Direction.DOWN && l.getBlockState(p.above()).isAir();
	static Rule face(Direction d) { return (l, p, h, e) -> h.getDirection() == d; }

	/**
	 * One thing still to do on a block. {@code item} is what to hold (also what to fetch), {@code fits} what else
	 * will do; {@code emptyHand} when only a bare hand works; {@code last} for what gives power.
	 */
	record Fix(String what, Item item, Predicate<ItemStack> fits, boolean emptyHand, boolean last, Rule rule) {
		static Fix click(String what, boolean last) { return new Fix(what, null, s -> true, false, last, ANY); }
		static Fix use(String what, Item item, Rule rule) { return new Fix(what, item, s -> s.is(item), false, false, rule); }
	}

	// ---------- blocks that come in two steps ----------

	/** The block to place first for a block that has no item of its own, or null. */
	static BlockState base(BlockState want) {
		Block b = want.getBlock();
		if (b instanceof LayeredCauldronBlock || b == Blocks.LAVA_CAULDRON) return Blocks.CAULDRON.defaultBlockState();
		if (b instanceof FlowerPotBlock && b != Blocks.FLOWER_POT) return Blocks.FLOWER_POT.defaultBlockState();
		if (b instanceof CandleCakeBlock) return Blocks.CAKE.defaultBlockState();
		if (b == Blocks.FARMLAND || b == Blocks.DIRT_PATH) return Blocks.DIRT.defaultBlockState();
		return null;
	}

	/** Hoe turns these into farmland (coarse and rooted dirt into dirt first, then a second time). */
	static boolean tillable(Block b) {
		return b == Blocks.DIRT || b == Blocks.GRASS_BLOCK || b == Blocks.DIRT_PATH || b == Blocks.COARSE_DIRT || b == Blocks.ROOTED_DIRT;
	}

	/** A shovel turns these into a path. */
	static boolean pathable(Block b) {
		return b == Blocks.GRASS_BLOCK || b == Blocks.DIRT || b == Blocks.PODZOL || b == Blocks.COARSE_DIRT || b == Blocks.MYCELIUM || b == Blocks.ROOTED_DIRT;
	}

	/**
	 * Does what stands here only need its second step? The base of a two-step block, or for farmland and a path
	 * any ground the hoe or shovel works on: grass was first broken and replaced by dirt, for nothing.
	 */
	static boolean readyFor(BlockState want, BlockState have) {
		Block w = want.getBlock(), h = have.getBlock();
		BlockState base = base(want);
		if (base == null) return false;
		if (h == base.getBlock()) return true;
		return (w == Blocks.FARMLAND && tillable(h)) || (w == Blocks.DIRT_PATH && pathable(h));
	}

	/** Seeds and stems only go on farmland: they wait for it rather than count as "no spot to place". */
	static boolean needsFarmland(BlockState want) {
		Block b = want.getBlock();
		return b instanceof CropBlock || b instanceof StemBlock || b instanceof PitcherCropBlock;
	}

	// ---------- what may be left as it is ----------

	private static boolean soil(Block b) { return b == Blocks.GRASS_BLOCK || b == Blocks.DIRT || b == Blocks.PODZOL || b == Blocks.MYCELIUM; }

	/**
	 * Close enough, because the world changes it by itself: grass and dirt spread into each other, copper weathers,
	 * ice melts. Rebuilding those again and again would never end.
	 */
	static boolean sameEnough(BlockState want, BlockState have) {
		Block w = want.getBlock(), h = have.getBlock();
		if (soil(w) && soil(h)) return true;
		if (w instanceof WeatheringCopper && h instanceof WeatheringCopper)
			return WeatheringCopper.getFirst(want).getBlock() == WeatheringCopper.getFirst(have).getBlock();
		return w == Blocks.ICE && h == Blocks.WATER;
	}

	/** Left alone where the schematic wants air: a piston's head or a moving block belongs to the piston. */
	static boolean harmlessInAir(BlockState have) {
		return have.is(Blocks.PISTON_HEAD) || have.is(Blocks.MOVING_PISTON);
	}

	// ---------- the fixes ----------

	/** What this block still needs (the right block, or its base, already stands), or null when it is done. */
	static Fix fix(Level world, Level schem, BlockPos pos, BlockState want, BlockState have) {
		Block w = want.getBlock(), h = have.getBlock();
		// two-step blocks: the base (or ground the tool works on) stands
		if (readyFor(want, have)) {
			if (w instanceof LayeredCauldronBlock && w == Blocks.WATER_CAULDRON)
				return want.getValue(LayeredCauldronBlock.LEVEL) == 3 ? Fix.use("cauldron water", Items.WATER_BUCKET, ANY) : null;
			if (w == Blocks.POWDER_SNOW_CAULDRON)
				return want.getValue(LayeredCauldronBlock.LEVEL) == 3 ? Fix.use("cauldron powder snow", Items.POWDER_SNOW_BUCKET, ANY) : null;
			if (w == Blocks.LAVA_CAULDRON) return Fix.use("cauldron lava", Items.LAVA_BUCKET, ANY);
			if (w instanceof FlowerPotBlock pot) return Fix.use("plant in pot", pot.getPotted().asItem(), ANY);
			if (w instanceof CandleCakeBlock) {
				Item candle = Lumberjack.itemOf(Lumberjack.blockId(w).replace("_cake", ""));
				return candle == null || candle == Items.AIR ? null : Fix.use("candle on cake", candle, ANY);
			}
			if (w == Blocks.FARMLAND) return new Fix("till", Items.IRON_HOE, s -> s.is(ItemTags.HOES), false, false, TOP_OPEN);
			if (w == Blocks.DIRT_PATH) return new Fix("pad", Items.IRON_SHOVEL, s -> s.is(ItemTags.SHOVELS), false, false, TOP_OPEN);
			return null;
		}
		if (w != h) return null;

		// a click to change a setting
		if (w instanceof RepeaterBlock && !want.getValue(RepeaterBlock.DELAY).equals(have.getValue(RepeaterBlock.DELAY))) return Fix.click("delay", false);
		if (w instanceof ComparatorBlock && want.getValue(ComparatorBlock.MODE) != have.getValue(ComparatorBlock.MODE)) return Fix.click("mode", false);
		if (w instanceof NoteBlock && !want.getValue(NoteBlock.NOTE).equals(have.getValue(NoteBlock.NOTE))) return Fix.click("toon", false);
		if (w instanceof DaylightDetectorBlock && want.getValue(DaylightDetectorBlock.INVERTED) != have.getValue(DaylightDetectorBlock.INVERTED)) return Fix.click("inverted", false);
		if (w instanceof LeverBlock && want.getValue(LeverBlock.POWERED) != have.getValue(LeverBlock.POWERED)) return Fix.click("lever", true);
		if (opensByHand(w) && want.hasProperty(BlockStateProperties.OPEN) && want.getValue(BlockStateProperties.OPEN) != have.getValue(BlockStateProperties.OPEN)
			&& !(have.hasProperty(BlockStateProperties.POWERED) && have.getValue(BlockStateProperties.POWERED)))   // redstone holds it: leave it
			return Fix.click("open or shut", false);

		// an item on the block
		if (w instanceof SignBlock) return signFix(world, schem, pos, want);
		if (w instanceof EndPortalFrameBlock && want.getValue(EndPortalFrameBlock.HAS_EYE) && !have.getValue(EndPortalFrameBlock.HAS_EYE))
			return Fix.use("eye", Items.ENDER_EYE, ANY);
		if (w instanceof RespawnAnchorBlock && have.getValue(RespawnAnchorBlock.CHARGE) < want.getValue(RespawnAnchorBlock.CHARGE))
			return Fix.use("glowstone", Items.GLOWSTONE, ANY);
		if (w instanceof ComposterBlock && have.getValue(ComposterBlock.LEVEL) < want.getValue(ComposterBlock.LEVEL) && have.getValue(ComposterBlock.LEVEL) < 7)
			return new Fix("compost", Items.WHEAT_SEEDS, Game::compostable, false, false, ANY);
		// How far a crop has grown is left alone: it grows by itself. Bone meal on every plant to match the stage a
		// schematic happened to be saved in would take thousands for one field.
		if (want.hasProperty(BlockStateProperties.LIT) && (w instanceof CampfireBlock || w instanceof AbstractCandleBlock)
			&& want.getValue(BlockStateProperties.LIT) != have.getValue(BlockStateProperties.LIT)) {
			if (want.getValue(BlockStateProperties.LIT)) {
				boolean can = w instanceof CampfireBlock ? CampfireBlock.canLight(have) : w instanceof CandleCakeBlock ? CandleCakeBlock.canLight(have) : CandleBlock.canLight(have);
				return can ? Fix.use("light", Items.FLINT_AND_STEEL, ANY) : null;
			}
			if (w instanceof CampfireBlock) return new Fix("douse", Items.IRON_SHOVEL, s -> s.is(ItemTags.SHOVELS), false, false, TOP_OPEN);
			return new Fix("blow out", null, s -> s.isEmpty(), true, false, ANY);
		}
		if (w instanceof LecternBlock && want.getValue(LecternBlock.HAS_BOOK) && !have.getValue(LecternBlock.HAS_BOOK))
			return new Fix("book on lectern", Items.WRITABLE_BOOK, s -> s.is(Items.WRITABLE_BOOK) || s.is(Items.WRITTEN_BOOK), false, false, ANY);
		if (w instanceof JukeboxBlock && want.getValue(JukeboxBlock.HAS_RECORD) && !have.getValue(JukeboxBlock.HAS_RECORD)) {
			Item disc = schem.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.JukeboxBlockEntity jb ? jb.getTheItem().getItem() : Items.AIR;
			return disc == Items.AIR ? null : Fix.use("disc", disc, ANY);
		}
		if (w instanceof ChiseledBookShelfBlock) {
			for (int i = 0; i < 6; i++) {
				var slot = ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(i);
				if (want.getValue(slot) && !have.getValue(slot)) {
					int n = i;
					Direction front = have.getValue(ChiseledBookShelfBlock.FACING);
					return new Fix("book in shelf", Items.BOOK, s -> s.is(Items.BOOK) || s.is(Items.ENCHANTED_BOOK) || s.is(Items.WRITABLE_BOOK) || s.is(Items.WRITTEN_BOOK),
						false, false, (l, p, hit, e) -> hit.getDirection() == front && bookSlot(p, hit, front) == n);
				}
			}
		}
		return null;
	}

	/** Doors, trapdoors and gates you open by hand (not the iron ones). */
	private static boolean opensByHand(Block b) {
		if (b == Blocks.IRON_DOOR || b == Blocks.IRON_TRAPDOOR) return false;
		return b instanceof DoorBlock || b instanceof TrapDoorBlock || b instanceof FenceGateBlock;
	}

	/** Which of the six books a click on the front of a chiseled bookshelf takes, as vanilla works it out. */
	private static int bookSlot(BlockPos pos, BlockHitResult hit, Direction front) {
		Vec3 v = hit.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ());
		double x = switch (front) {
			case NORTH -> 1.0 - v.x;
			case SOUTH -> v.x;
			case WEST -> v.z;
			case EAST -> 1.0 - v.z;
			default -> -1;
		};
		if (x < 0) return -1;
		int row = v.y >= 0.5 ? 0 : 1;
		int col = x < 0.375 ? 0 : x < 0.6875 ? 1 : 2;
		return col + row * 3;
	}

	/** Glow ink, dye and wax, each on the side of the sign it belongs to; wax last, it locks the sign. */
	private static Fix signFix(Level world, Level schem, BlockPos pos, BlockState want) {
		if (!(schem.getBlockEntity(pos) instanceof SignBlockEntity ws) || !(world.getBlockEntity(pos) instanceof SignBlockEntity hs)) return null;
		if (hs.isWaxed()) return null;
		for (boolean front : new boolean[] { true, false }) {
			var wt = Game.signText(ws, front);
			var ht = Game.signText(hs, front);
			Rule side = signSide(front);
			if (wt.getColor() != ht.getColor()) return Fix.use(front ? "dye front" : "dye back", Lumberjack.itemOf("minecraft:" + wt.getColor().getSerializedName() + "_dye"), side);
			if (wt.hasGlowingText() && !ht.hasGlowingText()) return Fix.use(front ? "glow front" : "glow back", Items.GLOW_INK_SAC, side);
			if (!wt.hasGlowingText() && ht.hasGlowingText()) return Fix.use(front ? "dim front" : "dim back", Items.INK_SAC, side);
		}
		if (ws.isWaxed()) return Fix.use("was", Items.HONEYCOMB, ANY);
		return null;
	}

	/** The side of the sign you stand on decides which side the ink goes on, as vanilla's isFacingFrontText does. */
	private static Rule signSide(boolean front) {
		return (l, p, hit, eye) -> {
			BlockState st = l.getBlockState(p);
			if (!(st.getBlock() instanceof SignBlock sign)) return false;
			Vec3 c = sign.getSignHitboxCenterPosition(st);
			double dx = eye.x - (p.getX() + c.x), dz = eye.z - (p.getZ() + c.z);
			float facing = sign.getYRotationDegrees(st);
			float toMe = (float) (Mth.atan2(dz, dx) * 180.0F / Math.PI) - 90.0F;
			return (Mth.degreesDifferenceAbs(facing, toMe) <= 90.0F) == front;
		};
	}

	// ---------- entities ----------

	/** One thing to do about an entity of the schematic: put it there, fill a frame, or turn it. */
	record EntityJob(String what, Entity want, Entity have) {}

	/** Item frames, armor stands and paintings of the schematic that are not (yet) right in the world. */
	static List<EntityJob> entityJobs(Level world, Level schem, AABB box) {
		List<EntityJob> out = new ArrayList<>();
		for (Entity e : schem.getEntitiesOfClass(Entity.class, box, x -> x instanceof ItemFrame || x instanceof ArmorStand || x instanceof Painting)) {
			Entity have = twin(world, e);
			if (have == null) { out.add(new EntityJob("place", e, null)); continue; }
			if (e instanceof ItemFrame wf && have instanceof ItemFrame hf) {
				ItemStack wi = wf.getItem(), hi = hf.getItem();
				if (!wi.isEmpty() && hi.isEmpty()) out.add(new EntityJob("fill frame", e, have));
				else if (!wi.isEmpty() && ItemStack.isSameItem(wi, hi) && wf.getRotation() != hf.getRotation()) out.add(new EntityJob("turn frame", e, have));
			}
		}
		return out;
	}

	/** The entity of the same kind at the same spot in the world, if any. */
	static Entity twin(Level world, Entity want) {
		AABB near = new AABB(want.blockPosition()).inflate(0.2);
		for (Entity e : world.getEntitiesOfClass(Entity.class, near, x -> x.getType() == want.getType())) {
			if (!e.blockPosition().equals(want.blockPosition())) continue;
			if (want instanceof net.minecraft.world.entity.decoration.HangingEntity wh && e instanceof net.minecraft.world.entity.decoration.HangingEntity eh
				&& wh.getDirection() != eh.getDirection()) continue;
			return e;
		}
		return null;
	}

	/** The item that puts this entity there. */
	static Item placeItem(Entity want) {
		if (want instanceof GlowItemFrame) return Items.GLOW_ITEM_FRAME;
		if (want instanceof ItemFrame) return Items.ITEM_FRAME;
		if (want instanceof Painting) return Items.PAINTING;
		return Items.ARMOR_STAND;
	}

	/** The yaw vanilla gives a new armor stand for this player yaw. */
	static float standYaw(float playerYaw) {
		return (float) Mth.floor((Mth.wrapDegrees(playerYaw - 180.0F) + 22.5F) / 45.0F) * 45.0F;
	}

	// ---------- order and safety ----------

	/** Blocks that give power: placed last, so nothing starts moving while the rest is still being built. */
	static boolean powerSource(BlockState st) {
		Block b = st.getBlock();
		return b instanceof RedstoneTorchBlock || b == Blocks.REDSTONE_BLOCK || b instanceof ObserverBlock || b instanceof DaylightDetectorBlock
			|| b instanceof BasePressurePlateBlock || b instanceof TripWireHookBlock || b instanceof TargetBlock || b instanceof SculkSensorBlock;
	}

	/** TNT only where nothing can power it: no signal now and no power source or redstone right beside it. */
	static boolean safeForTnt(Level level, BlockPos pos) {
		if (level.hasNeighborSignal(pos)) return false;
		for (Direction d : Direction.values()) {
			BlockState n = level.getBlockState(pos.relative(d));
			if (powerSource(n) || n.isSignalSource() || n.is(Blocks.REDSTONE_WIRE) || n.getBlock() instanceof FireBlock
				|| n.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) return false;
		}
		return true;
	}

	/** Does the schematic want a block that holds water without a waterlogged setting (kelp, seagrass)? */
	static boolean growsInWater(BlockState want) {
		return want.getBlock() != Blocks.WATER && !want.hasProperty(BlockStateProperties.WATERLOGGED)
			&& want.getFluidState().is(net.minecraft.tags.FluidTags.WATER) && want.getFluidState().isSource();
	}
}
