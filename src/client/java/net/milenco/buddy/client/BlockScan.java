package net.milenco.buddy.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
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
 * <p>Of each kind only the nearest {@link #KEEP} are kept, and that while reading: a section with nothing nearer than
 * what is kept already is not read at all. Water and lava sources count (a bucket is filled from them), and a lava sea
 * holds thousands: keeping them all and sorting them at the end of each round took half the client's tick in the nether.
 *
 * <p>An ore counts only where it touches air or a fluid: an anti-xray plugin (Paper's own, engine mode 2, as on Tweakcraft)
 * sends fake ores in every hidden block, and the real ones only once something opens them up. What the buddy digs open
 * is sent for real, and the next round sees it.
 */
final class BlockScan {
	private BlockScan() {}

	static final int CHUNKS = 3, UP = 48;
	static final int KEEP = 48;

	/** A wanted block: its id, and whether a place of it counts only open to the air (an ore) or only as a source (a fluid). */
	private record Kind(String id, boolean ore, boolean fluid) {}

	private static Map<Block, Kind> kinds;

	/** The last full round: per block id the nearest trusted places, nearest first; and when the round ended. */
	private static Map<String, List<BlockPos>> found = Map.of();
	private static long foundAt;
	private static String foundDim = "";
	/** The round under way; null before the first and after a change of world. */
	private static Round next;
	private static List<long[]> todo = new ArrayList<>();

	private static Map<Block, Kind> kinds() {
		if (kinds != null) return kinds;
		Map<Block, Kind> m = new HashMap<>();
		Set<String> ids = new HashSet<>(Nature.blocks().keySet());
		ids.addAll(List.of("crafting_table", "furnace", "blast_furnace", "smoker", "chest", "barrel", "end_portal_frame", "nether_portal",
			"obsidian", "lava", "water", "spawner", "nether_bricks", "end_gateway", "end_portal"));
		for (String id : ids) {
			BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.Identifier.withDefaultNamespace(id))
				.ifPresent(b -> m.put(b, new Kind(id, ore(id), id.equals("water") || id.equals("lava"))));
		}
		kinds = m;
		return m;
	}

	static boolean ore(String id) { return id.endsWith("_ore") || id.equals("ancient_debris"); }

	/** A few sections more of the round; a new round when the last is done, or when the buddy moved far or changed world. */
	static void tick(ClientLevel level, BlockPos at) {
		String dim = level.dimension().identifier().toString();
		if (!dim.equals(foundDim)) { found = Map.of(); foundDim = dim; todo.clear(); next = null; }
		if (todo.isEmpty()) {
			if (next != null) publish();
			begin(level, at);
		} else if (next.centre.distSqr(at) > 24 * 24) {
			begin(level, at);   // the half round of a place it left: the last full one stays until this one is done
		}
		Map<Block, Kind> want = kinds();
		for (int n = 0; n < 6 && !todo.isEmpty(); n++) {
			long[] t = todo.remove(0);
			int cx = (int) t[0], cz = (int) t[1], sy = (int) t[2];
			if (!level.hasChunk(cx, cz)) continue;
			LevelChunk ch = level.getChunk(cx, cz);
			int idx = ch.getSectionIndexFromSectionY(sy);
			if (idx < 0 || idx >= ch.getSections().length) continue;
			LevelChunkSection sec = ch.getSections()[idx];
			if (sec == null || sec.hasOnlyAir()) continue;
			// the wanted kinds the section's palette names (it may name a block no longer there); the global palette, for a
			// section of very many kinds of block, answers yes without naming any, and then every kind may be there
			Set<String> may = new HashSet<>();
			boolean unlisted = sec.maybeHas(st -> {
				Kind k = want.get(st.getBlock());
				if (k != null) may.add(k.id());
				return false;
			});
			if (unlisted) for (Kind k : want.values()) may.add(k.id());
			if (may.isEmpty()) continue;
			next.read(cx, sy, cz, may, new Cells(level, sec, want, cx << 4, sy << 4, cz << 4));
		}
	}

	/** A section of the game as the round reads it. */
	private static final class Cells implements Round.Cells {
		private final ClientLevel level;
		private final LevelChunkSection sec;
		private final Map<Block, Kind> want;
		private final int x0, y0, z0;
		private final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		/** The cell kind() read last, for counts(). */
		private BlockState st;
		private Kind k;

		Cells(ClientLevel level, LevelChunkSection sec, Map<Block, Kind> want, int x0, int y0, int z0) {
			this.level = level; this.sec = sec; this.want = want; this.x0 = x0; this.y0 = y0; this.z0 = z0;
		}

		@Override
		public String kind(int x, int y, int z) {
			st = sec.getBlockState(x, y, z);
			k = want.get(st.getBlock());
			return k == null ? null : k.id();
		}

		@Override
		public boolean counts(int x, int y, int z) {
			if (k.fluid()) return st.getFluidState().isSource();
			return !k.ore() || exposed(level, m.set(x0 + x, y0 + y, z0 + z));
		}
	}

	private static void publish() {
		found = next.result();
		foundAt = System.currentTimeMillis();
		foundCentre = next.centre;
	}

	/** Where the last full round was made from. */
	private static BlockPos foundCentre;

	/** A full round made from within this distance of here: what it knows is of this place. */
	static boolean covers(BlockPos at, int within) { return foundCentre != null && foundCentre.distSqr(at) <= (long) within * within; }

	private static void begin(ClientLevel level, BlockPos at) {
		todo.clear();
		next = new Round(at.immutable());
		int cx = at.getX() >> 4, cz = at.getZ() >> 4, cy = at.getY() >> 4;
		int lo = Math.max(level.getMinY(), at.getY() - UP) >> 4, hi = Math.min(level.getMaxY(), at.getY() + UP) >> 4;
		for (int dx = -CHUNKS; dx <= CHUNKS; dx++)
			for (int dz = -CHUNKS; dz <= CHUNKS; dz++)
				for (int sy = hi; sy >= lo; sy--) todo.add(new long[] { cx + dx, cz + dz, sy });
		// nearest first, so that half a round already knows what is close, and the kept places of the common kinds are
		// near ones early, which leaves the farther sections unread
		todo.sort(java.util.Comparator.comparingLong(t -> (t[0] - cx) * (t[0] - cx) + (t[1] - cz) * (t[1] - cz) + (t[2] - cy) * (t[2] - cy)));
	}

	/**
	 * One round's places: per kind the {@link #KEEP} nearest to the centre, kept while the sections are read. Apart from
	 * the game, so that a test can give it sections of its own.
	 */
	static final class Round {
		final BlockPos centre;
		private final Map<String, Nearest> kept = new HashMap<>();

		Round(BlockPos centre) { this.centre = centre; }

		/** A section to read: cells 0 to 15 each way. */
		interface Cells {
			/** The id of the wanted kind at a cell, null when the block there is not wanted. */
			String kind(int x, int y, int z);

			/** Whether the place kind() named last counts: an ore the server has shown, water or lava as a source. */
			boolean counts(int x, int y, int z);
		}

		/**
		 * The places of a section, chunk {@code cx}, {@code cz}, section {@code sy}, of the kinds it {@code may} hold, in the
		 * order the cells are read (y, then z, then x). Nothing is read that could not be kept: past the farthest kept place
		 * of every kind the section may hold, once each has {@link #KEEP}, the section, a row of it or a cell is passed over.
		 * The checks that cost (an ore's six neighbours, a fluid's state) come last, for a place that would be kept.
		 */
		void read(int cx, int sy, int cz, Collection<String> may, Cells cells) {
			int x0 = cx << 4, y0 = sy << 4, z0 = cz << 4;
			double reach = reach(may);
			// how near the section, and the rows of it, come to the centre on each axis
			long gx = gap(centre.getX(), x0), gy = gap(centre.getY(), y0), gz = gap(centre.getZ(), z0);
			if (gx * gx + gy * gy + gz * gz >= reach) return;
			for (int y = 0; y < 16; y++) {
				long dy = y0 + y - centre.getY(), dy2 = dy * dy;
				if (dy2 + gz * gz + gx * gx >= reach) continue;
				for (int z = 0; z < 16; z++) {
					long dz = z0 + z - centre.getZ(), dyz = dy2 + dz * dz;
					if (dyz + gx * gx >= reach) continue;
					for (int x = 0; x < 16; x++) {
						long dx = x0 + x - centre.getX(), d = dyz + dx * dx;
						if (d >= reach) continue;
						String id = cells.kind(x, y, z);
						if (id == null) continue;
						Nearest near = kept.get(id);
						if (near != null && !near.wants(d)) continue;
						if (!cells.counts(x, y, z)) continue;
						if (near == null) kept.put(id, near = new Nearest(KEEP));
						near.add(new BlockPos(x0 + x, y0 + y, z0 + z), d);
					}
				}
			}
		}

		/**
		 * The distance (squared) from which no place of these kinds is kept any more: the farthest of their farthest kept
		 * places; no limit while one of them has fewer than KEEP.
		 */
		private double reach(Collection<String> may) {
			long r = 0;
			for (String id : may) {
				Nearest n = kept.get(id);
				if (n == null || !n.full()) return Double.POSITIVE_INFINITY;
				r = Math.max(r, n.farthest());
			}
			return r;
		}

		/** Blocks between a coordinate and the span of sixteen from {@code lo}; 0 inside it. */
		private static long gap(int c, int lo) { return c < lo ? lo - c : c > lo + 15 ? c - (lo + 15) : 0; }

		/** Per block id its kept places, nearest first. */
		Map<String, List<BlockPos>> result() {
			Map<String, List<BlockPos>> out = new HashMap<>();
			for (Map.Entry<String, Nearest> e : kept.entrySet()) out.put(e.getKey(), e.getValue().nearestFirst());
			return out;
		}
	}

	/**
	 * The nearest places of one kind, at most a number: a heap with the farthest kept place on top, which a nearer one
	 * takes the room of. Of two places as far the one found first stays, so the places and their order are those of a
	 * stable sort of every place by distance, cut after that number.
	 */
	static final class Nearest {
		private record Place(BlockPos pos, long d, long seq) {}

		private static final Comparator<Place> NEAR_FIRST = Comparator.comparingLong(Place::d).thenComparingLong(Place::seq);

		private final int keep;
		private final PriorityQueue<Place> heap;
		private long seq;

		Nearest(int keep) {
			this.keep = keep;
			this.heap = new PriorityQueue<>(keep + 1, NEAR_FIRST.reversed());
		}

		boolean full() { return heap.size() >= keep; }

		/** The distance (squared) of the farthest kept place; -1 with none. */
		long farthest() { return heap.isEmpty() ? -1 : heap.peek().d(); }

		/** A place this far would be kept: while there is room, or nearer than the farthest kept. */
		boolean wants(long d) { return heap.size() < keep || d < heap.peek().d(); }

		void add(BlockPos pos, long d) {
			if (!wants(d)) return;
			if (full()) heap.poll();
			heap.add(new Place(pos, d, seq++));
		}

		List<BlockPos> nearestFirst() {
			List<Place> l = new ArrayList<>(heap);
			l.sort(NEAR_FIRST);
			List<BlockPos> out = new ArrayList<>(l.size());
			for (Place p : l) out.add(p.pos());
			return List.copyOf(out);
		}
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
