package net.milenco.buddy.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * The blocks a buddy knows around it, of the kinds an order can need: what {@link Nature} names, and the stations
 * (crafting table, furnace). The chunks the client has are read a few sections a tick, so one round over three chunks
 * around it, 48 blocks up and down, takes a couple of seconds and costs nothing noticeable.
 *
 * <p>An ore counts only where it touches air or a fluid: an anti-xray plugin (Paper's own, engine mode 2, as on Tweakcraft)
 * sends fake ores in every hidden block, and the real ones only once something opens them up. What the buddy digs open
 * is sent for real, and the next round sees it.
 */
final class BlockScan {
	private BlockScan() {}

	static final int CHUNKS = 3, UP = 48;
	private static final int KEEP = 48;
	private static Set<Block> wanted;

	/** The last full round: per block id the nearest trusted places, nearest first; and when the round ended. */
	private static Map<String, List<BlockPos>> found = Map.of();
	private static long foundAt;
	private static String foundDim = "";
	/** The round under way. */
	private static Map<String, List<BlockPos>> next = new HashMap<>();
	private static List<long[]> todo = new ArrayList<>();
	private static BlockPos centre;

	private static Set<Block> wanted() {
		if (wanted != null) return wanted;
		Set<Block> s = new HashSet<>();
		Set<String> ids = new HashSet<>(Nature.blocks().keySet());
		ids.addAll(List.of("crafting_table", "furnace", "blast_furnace", "smoker", "chest", "barrel", "end_portal_frame", "nether_portal",
			"obsidian", "lava", "water", "spawner", "nether_bricks", "end_gateway", "end_portal"));
		for (String id : ids) {
			BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.Identifier.withDefaultNamespace(id)).ifPresent(s::add);
		}
		wanted = s;
		return s;
	}

	static boolean ore(String id) { return id.endsWith("_ore") || id.equals("ancient_debris"); }

	/** A few sections more of the round; a new round when the last is done, or when the buddy moved far or changed world. */
	static void tick(ClientLevel level, BlockPos at) {
		String dim = level.dimension().identifier().toString();
		if (!dim.equals(foundDim)) { found = Map.of(); foundDim = dim; todo.clear(); centre = null; }
		if (todo.isEmpty()) {
			if (centre != null) publish();
			begin(level, at);
		} else if (centre.distSqr(at) > 24 * 24) {
			begin(level, at);   // the half round of a place it left: the last full one stays until this one is done
		}
		Set<Block> want = wanted();
		for (int n = 0; n < 6 && !todo.isEmpty(); n++) {
			long[] t = todo.remove(0);
			int cx = (int) t[0], cz = (int) t[1], sy = (int) t[2];
			if (!level.hasChunk(cx, cz)) continue;
			LevelChunk ch = level.getChunk(cx, cz);
			int idx = ch.getSectionIndexFromSectionY(sy);
			if (idx < 0 || idx >= ch.getSections().length) continue;
			LevelChunkSection sec = ch.getSections()[idx];
			if (sec == null || sec.hasOnlyAir() || !sec.maybeHas(st -> want.contains(st.getBlock()))) continue;
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
			for (int y = 0; y < 16; y++)
				for (int z = 0; z < 16; z++)
					for (int x = 0; x < 16; x++) {
						BlockState st = sec.getBlockState(x, y, z);
						if (!want.contains(st.getBlock())) continue;
						m.set((cx << 4) + x, (sy << 4) + y, (cz << 4) + z);
						String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
						if (ore(id) && !exposed(level, m)) continue;
						if ((id.equals("water") || id.equals("lava")) && !st.getFluidState().isSource()) continue;
						next.computeIfAbsent(id, k -> new ArrayList<>()).add(m.immutable());
					}
		}
	}

	private static void publish() {
		Map<String, List<BlockPos>> done = new HashMap<>();
		BlockPos c = centre;
		for (Map.Entry<String, List<BlockPos>> e : next.entrySet()) {
			List<BlockPos> l = e.getValue();
			l.sort(java.util.Comparator.comparingDouble(b -> b.distSqr(c)));
			done.put(e.getKey(), List.copyOf(l.subList(0, Math.min(KEEP, l.size()))));
		}
		found = done;
		foundAt = System.currentTimeMillis();
		foundCentre = c;
	}

	/** Where the last full round was made from. */
	private static BlockPos foundCentre;

	/** A full round made from within this distance of here: what it knows is of this place. */
	static boolean covers(BlockPos at, int within) { return foundCentre != null && foundCentre.distSqr(at) <= (long) within * within; }

	private static void begin(ClientLevel level, BlockPos at) {
		next = new HashMap<>();
		todo.clear();
		centre = at.immutable();
		int cx = at.getX() >> 4, cz = at.getZ() >> 4, cy = at.getY() >> 4;
		int lo = Math.max(level.getMinY(), at.getY() - UP) >> 4, hi = Math.min(level.getMaxY(), at.getY() + UP) >> 4;
		for (int dx = -CHUNKS; dx <= CHUNKS; dx++)
			for (int dz = -CHUNKS; dz <= CHUNKS; dz++)
				for (int sy = hi; sy >= lo; sy--) todo.add(new long[] { cx + dx, cz + dz, sy });
		// nearest first, so that half a round already knows what is close
		todo.sort(java.util.Comparator.comparingLong(t -> (t[0] - cx) * (t[0] - cx) + (t[1] - cz) * (t[1] - cz) + (t[2] - cy) * (t[2] - cy)));
	}

	/** Air, a fluid or a block that lets light through beside it: the server has shown the real block. */
	static boolean exposed(ClientLevel level, BlockPos pos) {
		for (Direction d : Direction.values()) {
			BlockState n = level.getBlockState(pos.relative(d));
			if (n.isAir() || !n.getFluidState().isEmpty() || !n.isSolidRender()) return true;
		}
		return false;
	}

	/** The nearest known places of a block, as of the last round; checked again against the world by the caller. */
	static List<BlockPos> places(String id) { return found.getOrDefault(id, List.of()); }

	static long age() { return foundAt == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - foundAt; }

	/** Blocks away from here to the nearest known one, or -1. */
	static double near(String id, BlockPos from) {
		List<BlockPos> l = places(id);
		double best = -1;
		for (BlockPos b : l) {
			double d = Math.sqrt(b.distSqr(from));
			if (best < 0 || d < best) best = d;
		}
		return best;
	}

	/** Forget a place (broken, or found not to be what it was). */
	static void gone(String id, BlockPos pos) {
		List<BlockPos> l = found.get(id);
		if (l == null || !l.contains(pos)) return;
		Map<String, List<BlockPos>> copy = new HashMap<>(found);
		List<BlockPos> nl = new ArrayList<>(l);
		nl.remove(pos);
		copy.put(id, List.copyOf(nl));
		found = copy;
	}
}
