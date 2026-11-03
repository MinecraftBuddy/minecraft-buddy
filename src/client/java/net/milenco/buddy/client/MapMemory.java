package net.milenco.buddy.client;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * What he has seen, kept as a map, and brought up to date when it changes. Always, panel or not, a chunk a tick of the
 * chunks his game has loaded around him is drawn the way {@link MiniMap} draws the surface, and each chunk again after
 * {@link #AGAIN} ms, so that what changed is drawn anew. The drawing is kept in tiles of {@link #TILE} by
 * {@link #TILE} blocks, in the exchange folder as
 * {@code maptiles/<server>/<dimension>/<tx>_<tz>.bin} (the server's profile, {@link Profiles#key()}, with every
 * character but letters, digits, dot, dash and underscore made an underscore: one world per server, so that a test
 * server's world does not show in the map of another): gzip of the colours (three bytes a column, row by row from the
 * north-west)
 * and the heights (two bytes a column; -32768 is not seen). The panel's server reads those files and the page draws
 * them under the live map.
 * <p>
 * Underground (rock more than eight blocks above his head, as {@link MiniMap} decides it) and in a dimension with a
 * roof, the cave layer he is in is kept as well, the way the live map draws caves: per column the first floor below his
 * head, in {@code maptiles/<server>/<dimension>/cave<n>/}, sixteen blocks of height a layer ({@link MiniMap#layer}).
 * A cave layer shows everything of that layer, also zoomed out, so as not to confuse: the page shows the remembered
 * layer of the live map's cave view and no surface around it. A dimension with a roof (the nether) has no surface
 * tiles: its surface is the roof.
 */
final class MapMemory {
	private MapMemory() {}

	static final int TILE = 128;
	private static final int RADIUS = 10, AGAIN = 45_000, SAVE_EVERY = 10_000, FORGET_AFTER = 300_000;
	private static final short UNSEEN = Short.MIN_VALUE;

	private static final class Tile {
		final byte[] rgb = new byte[TILE * TILE * 3];
		final short[] h = new short[TILE * TILE];
		boolean dirty;
		long used;
		Tile() { Arrays.fill(h, UNSEEN); }
	}

	private static final Map<String, Tile> tiles = new HashMap<>();
	private static final Map<Long, Long> scanned = new HashMap<>();
	/** Per cave layer, when each chunk's layer was drawn: a jump back to a layer redraws nothing. */
	private static final Map<Integer, Map<Long, Long>> scannedCave = new HashMap<>();
	private static final int[][] SPIRAL = spiral();
	private static int next;
	private static String lastDim = "", lastServer = "";
	/**
	 * The cave layer he is in, with a margin: it changes only when his head is two blocks or more outside the layer,
	 * so a jump or a one-block dip at y 32 does not switch the map between two layers (21 Oct 2026, a review of this
	 * change). {@link MiniMap} reports the same layer to the page.
	 */
	private static int curLayer;
	private static boolean hasLayer;
	/** The block he last stood on, and when (his tickCount): during a jump the slice stays at the ground's height. */
	private static int groundY = Integer.MIN_VALUE, groundTick;

	/** His head height for the cave view: the ground he stood on a moment ago while he jumps, else where he is. */
	static int headY(LocalPlayer p) {
		int y = p.getBlockY();
		return groundY != Integer.MIN_VALUE && p.tickCount - groundTick < 15 && Math.abs(y - groundY) <= 2 ? groundY : y;
	}

	/** The cave layer MapMemory keeps for him now; before its first tick, the one of this height. */
	static int layer(int fallbackY) { return hasLayer ? curLayer : MiniMap.layer(fallbackY); }
	private static long nextSave;

	/** The chunk offsets within the radius, nearest first. */
	private static int[][] spiral() {
		List<int[]> out = new ArrayList<>();
		for (int dx = -RADIUS; dx <= RADIUS; dx++) for (int dz = -RADIUS; dz <= RADIUS; dz++) if (dx * dx + dz * dz <= RADIUS * RADIUS) out.add(new int[]{dx, dz});
		out.sort((a, b) -> Integer.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]));
		return out.toArray(new int[0][]);
	}

	/** Once a tick. */
	static void tick(Minecraft mc) {
		long now = System.currentTimeMillis();
		if (now >= nextSave) { nextSave = now + SAVE_EVERY; save(now); }
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) return;
		Level level = p.level();
		boolean roof = level.dimensionType().hasCeiling();
		String dim = level.dimension().identifier().toString(), server = folder(Profiles.key());
		if (!dim.equals(lastDim) || !server.equals(lastServer)) { lastDim = dim; lastServer = server; scanned.clear(); scannedCave.clear(); next = 0; hasLayer = false; groundY = Integer.MIN_VALUE; }
		if (p.onGround()) { groundY = p.getBlockY(); groundTick = p.tickCount; }
		int y0 = headY(p);
		if (!hasLayer || y0 < 16 * curLayer - 2 || y0 > 16 * curLayer + 17) {
			curLayer = MiniMap.layer(y0);
			hasLayer = true;
			// a layer not drawn yet this run: nearest chunks first
			if (!scannedCave.containsKey(curLayer)) next = 0;
		}
		int layer = curLayer;
		Map<Long, Long> caveScans = scannedCave.computeIfAbsent(layer, k -> new HashMap<>());
		// what the live map can show: caves underground and in a roof dimension, and wherever the page chose the cave view
		boolean under = MiniMap.underground(level, p) || MiniMap.caveForced();
		int pcx = p.getBlockX() >> 4, pcz = p.getBlockZ() >> 4;
		// at most one chunk drawn a tick (its surface, its cave layer or both), and at most a quarter of the circle looked
		// at for one
		for (int k = 0; k < SPIRAL.length / 4; k++) {
			int[] o = SPIRAL[next];
			next = (next + 1) % SPIRAL.length;
			int cx = pcx + o[0], cz = pcz + o[1];
			long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
			boolean surface = !roof && due(scanned, key, now), cave = under && due(caveScans, key, now);
			if (!surface && !cave) continue;
			if (!level.hasChunk(cx, cz)) continue;
			if (surface) { scanned.put(key, now); chunk(level, server, dim, cx, cz, now); }
			if (cave) {
				boolean first = !caveScans.containsKey(key);
				caveScans.put(key, now);
				caveChunk(level, server, dim, "cave" + layer, y0, cx, cz, now);
				// the first drawing of a chunk's layer reaches the page within seconds
				if (first) nextSave = Math.min(nextSave, now + 2000);
			}
			return;
		}
	}

	private static boolean due(Map<Long, Long> scans, long key, long now) {
		Long at = scans.get(key);
		return at == null || now - at >= AGAIN;
	}

	/**
	 * The cave layer of a chunk for a head at {@code y0}, as {@link MiniMap} draws it underground: the floors of the
	 * chunk and of the row north and the column west of it first (for the relief), then the colours. A column without a
	 * floor within reach of that head is left as it was.
	 */
	private static void caveChunk(Level level, String server, String dim, String layer, int y0, int cx, int cz, long now) {
		int bx = cx << 4, bz = cz << 4;
		// per column of the chunk, the row north and the column west of it: the ground as the live map draws it
		int[] ground = new int[17 * 17], depths = new int[17 * 17];
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (int dz = -1; dz < 16; dz++) for (int dx = -1; dx < 16; dx++) {
			int i = (dz + 1) * 17 + dx + 1;
			at.set(bx + dx, y0, bz + dz);
			int fy = MiniMap.caveFloor(level, at, y0);
			if (fy == Integer.MIN_VALUE) { ground[i] = Integer.MIN_VALUE; continue; }
			int[] gd = MiniMap.ground(level, at, fy);
			ground[i] = gd[0];
			depths[i] = gd[1];
		}
		for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
			int i = (dz + 1) * 17 + dx + 1, x = bx + dx, z = bz + dz, y = ground[i], depth = depths[i];
			if (y == Integer.MIN_VALUE) continue;
			at.set(x, y, z);
			BlockState st = level.getBlockState(at);
			int colour = MiniMap.colour(level, at, st);
			double f;
			if (depth > 0) f = Math.max(0.55, 1.08 - 0.07 * depth);
			else {
				int north = ground[dz * 17 + dx + 1], west = ground[(dz + 1) * 17 + dx];
				if (north == Integer.MIN_VALUE) north = y;
				if (west == Integer.MIN_VALUE) west = y;
				double dh = (y - north) + 0.6 * (y - west);
				f = 1 + Math.max(-0.4, Math.min(0.35, dh * 0.14));
				f *= 0.94 + 0.12 * (Math.floorMod(x * 73856093 ^ z * 19349663, 97) / 96.0);
			}
			f *= y > y0 - 6 ? 1 : 0.85;
			int rgb = ((clamp(((colour >> 16) & 255) * f)) << 16) | (clamp(((colour >> 8) & 255) * f) << 8) | clamp((colour & 255) * f);
			put(server, dim, layer, x, z, rgb, (short) y, now);
		}
	}

	private static void chunk(Level level, String server, String dim, int cx, int cz, long now) {
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
			int x = (cx << 4) + dx, z = (cz << 4) + dz;
			int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
			if (top < level.getMinY()) continue;
			int y = top;
			at.set(x, y, z);
			BlockState st = level.getBlockState(at);
			// grass, flowers, leaf litter, a torch: the ground under them, as Xaero draws it
			for (int k = 0; k < 3 && st.getFluidState().isEmpty() && st.getCollisionShape(level, at).isEmpty() && !(st.getBlock() instanceof LeavesBlock); k++) {
				at.setY(--y);
				st = level.getBlockState(at);
			}
			int depth = 0;
			if (!st.getFluidState().isEmpty()) {
				BlockPos.MutableBlockPos d = at.mutable();
				while (depth < 12 && !level.getBlockState(d).getFluidState().isEmpty()) { d.setY(d.getY() - 1); depth++; }
			}
			int colour = MiniMap.colour(level, at, st);
			double f;
			if (depth > 0) f = Math.max(0.55, 1.08 - 0.07 * depth);
			else {
				// relief against the north and west neighbours, by their surface (leaves count, as they do here)
				int north = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z - 1) - 1, west = level.getHeight(Heightmap.Types.WORLD_SURFACE, x - 1, z) - 1;
				if (north < level.getMinY()) north = top;
				if (west < level.getMinY()) west = top;
				double dh = (top - north) + 0.6 * (top - west);
				f = 1 + Math.max(-0.4, Math.min(0.35, dh * 0.14));
				f *= 0.94 + 0.12 * (Math.floorMod(x * 73856093 ^ z * 19349663, 97) / 96.0);
			}
			int rgb = ((clamp(((colour >> 16) & 255) * f)) << 16) | (clamp(((colour >> 8) & 255) * f) << 8) | clamp((colour & 255) * f);
			put(server, dim, "", x, z, rgb, (short) y, now);
		}
	}

	private static int clamp(double v) { return Math.max(0, Math.min(255, (int) v)); }

	private static void put(String server, String dim, String layer, int x, int z, int rgb, short y, long now) {
		int tx = Math.floorDiv(x, TILE), tz = Math.floorDiv(z, TILE);
		Tile t = tile(server, dim, layer, tx, tz);
		t.used = now;
		int i = Math.floorMod(z, TILE) * TILE + Math.floorMod(x, TILE);
		byte r = (byte) (rgb >> 16), g = (byte) (rgb >> 8), b = (byte) rgb;
		if (t.h[i] == y && t.rgb[i * 3] == r && t.rgb[i * 3 + 1] == g && t.rgb[i * 3 + 2] == b) return;
		t.h[i] = y;
		t.rgb[i * 3] = r; t.rgb[i * 3 + 1] = g; t.rgb[i * 3 + 2] = b;
		t.dirty = true;
	}

	/** The server's profile as a folder name. */
	static String folder(String key) { return key == null || key.isEmpty() ? "unknown" : key.replaceAll("[^A-Za-z0-9._-]", "_"); }

	/** A dimension as a folder name. The server names its dimensions, and an identifier may hold '/' and "..": as a path
	 *  that would reach out of maptiles, so every other character and a ".." become '_' (the panel reads the same name). */
	static String dimFolder(String dim) {
		String d = dim == null || dim.isEmpty() ? "unknown" : dim.replace(':', '_').replaceAll("[^A-Za-z0-9._-]", "_").replace("..", "__");
		return d.equals(".") ? "_" : d;
	}

	/** A tile's file: the surface's in the dimension's folder, a cave layer's in a folder of its own under it. */
	private static Path file(String server, String dim, String layer, int tx, int tz) {
		Path d = Paths.get(AfkConfig.get().exchangeDir, "maptiles", server, dimFolder(dim));
		return (layer.isEmpty() ? d : d.resolve(layer)).resolve(tx + "_" + tz + ".bin");
	}

	private static Tile tile(String server, String dim, String layer, int tx, int tz) {
		String key = server + "|" + dim + "|" + layer + "|" + tx + "|" + tz;
		Tile t = tiles.get(key);
		if (t != null) return t;
		t = new Tile();
		Path f = file(server, dim, layer, tx, tz);
		if (Files.exists(f)) {
			try (InputStream in = new GZIPInputStream(Files.newInputStream(f)); DataInputStream d = new DataInputStream(in)) {
				d.readFully(t.rgb);
				for (int i = 0; i < t.h.length; i++) t.h[i] = d.readShort();
			} catch (Exception e) {
				Arrays.fill(t.h, UNSEEN);
			}
		}
		tiles.put(key, t);
		return t;
	}

	/** The tiles that changed to disk, and out of memory what was not drawn into for a while. */
	private static void save(long now) {
		for (Iterator<Map.Entry<String, Tile>> it = tiles.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<String, Tile> e = it.next();
			Tile t = e.getValue();
			if (t.dirty) {
				String[] k = e.getKey().split("\\|", -1);
				try {
					Path f = file(k[0], k[1], k[2], Integer.parseInt(k[3]), Integer.parseInt(k[4])), tmp = f.resolveSibling(f.getFileName() + ".tmp");
					Files.createDirectories(f.getParent());
					ByteArrayOutputStream raw = new ByteArrayOutputStream();
					try (DataOutputStream d = new DataOutputStream(new GZIPOutputStream(raw))) {
						d.write(t.rgb);
						for (short v : t.h) d.writeShort(v);
					}
					Files.write(tmp, raw.toByteArray());
					Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
					t.dirty = false;
				} catch (Exception ex) {
					Exchange.emit("error", "msg", "maptile: " + ex.getMessage());
				}
			}
			if (!t.dirty && now - t.used > FORGET_AFTER) it.remove();
		}
	}
}
