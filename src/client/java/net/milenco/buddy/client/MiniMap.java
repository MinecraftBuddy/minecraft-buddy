package net.milenco.buddy.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.Gson;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.scores.PlayerTeam;

/**
 * A map of what lies around the buddy, for the web panel, updated live: the buddy's place and its surroundings (with a cave mode,
 * as Xaero's minimap has), so that a place to go to can be picked on it.
 * <p>
 * While the panel asks for the buddy's state, every two seconds {@code map.json} in the exchange folder: {@link #SIZE} by
 * {@link #SIZE} blocks around the buddy, north up, each column in the colour a vanilla map gives its top block (shaded by the
 * height of the columns north and west of it, in the biome's own colours for grass, leaves and water, as Xaero's map
 * does, where a river is easier to see) and with that block's height, so that a click on the page is a real spot to
 * walk to. Under the sky it shows the surface; underground, in the nether, or when the page asks for it, the
 * layer the buddy is on: per column the first floor below the buddy's head with air above it (Xaero's cave mode). With it the
 * players and monsters within the map, and the places known here: the base's bed, the chests and the fields. The work is
 * spread over the ticks, a few rows a tick.
 */
final class MiniMap {
	private MiniMap() {}

	static final int SIZE = 128;
	private static final Gson GSON = new Gson();

	/** "auto", "surface" or "cave", from the page. */
	private static String mode = "auto";
	private static long nextStart;
	private static int row = -1, x0, z0, y0;
	private static boolean cave;
	private static final byte[] rgb = new byte[SIZE * SIZE * 3];
	private static final short[] height = new short[SIZE * SIZE];

	static void command(com.google.gson.JsonObject o) {
		if (o.has("mode")) {
			String m = o.get("mode").getAsString();
			if (m.equals("auto") || m.equals("surface") || m.equals("cave")) mode = m;
		}
		nextStart = 0;
	}

	/** Once a tick while the panel wants the buddy's state. */
	static void tick(Minecraft mc, boolean wanted) {
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null || !wanted) { row = -1; return; }
		long now = System.currentTimeMillis();
		Level level = p.level();
		if (row < 0) {
			if (now < nextStart) return;
			nextStart = now + 2000;
			x0 = p.getBlockX() - SIZE / 2;
			z0 = p.getBlockZ() - SIZE / 2;
			// the head as MapMemory keeps it: the ground stood on a moment ago during a jump, so a hop does not move the slice
			y0 = MapMemory.headY(p);
			cave = caveForced() || (mode.equals("auto") && underground(level, p));
			row = 0;
		}
		// a few rows a tick: the whole map in eight ticks
		for (int k = 0; k < SIZE / 8 && row < SIZE; k++, row++) for (int col = 0; col < SIZE; col++) column(level, row, col);
		if (row >= SIZE) { row = -1; write(mc, p); }
	}

	private static void column(Level level, int r, int c) {
		int x = x0 + c, z = z0 + r, i = r * SIZE + c;
		if (!level.hasChunk(x >> 4, z >> 4)) { set(i, 0, Short.MIN_VALUE); return; }
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos(x, 0, z);
		int y;
		if (!cave) {
			y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
		} else {
			y = caveFloor(level, at, y0);
			if (y == Integer.MIN_VALUE) { set(i, 0x101010, Short.MIN_VALUE); return; }
		}
		int[] gd = ground(level, at, y);
		y = gd[0];
		int depth = gd[1];
		at.setY(y);
		BlockState st = level.getBlockState(at);
		int colour = colour(level, at, st);
		// water darker the deeper it is; land shaded by how it rises against its north and west neighbours, as relief
		double f;
		if (depth > 0) f = Math.max(0.55, 1.08 - 0.07 * depth);
		else {
			int north = r > 0 ? height[i - SIZE] : y, west = c > 0 ? height[i - 1] : y;
			if (north == Short.MIN_VALUE) north = y;
			if (west == Short.MIN_VALUE) west = y;
			double dh = (y - north) + 0.6 * (y - west);
			f = 1 + Math.max(-0.4, Math.min(0.35, dh * 0.14));
			// a little grain per block, as the textures give Xaero's map
			f *= 0.94 + 0.12 * (Math.floorMod(x * 73856093 ^ z * 19349663, 97) / 96.0);
		}
		if (cave) f *= y > y0 - 6 ? 1 : 0.85;
		int rr = clamp(((colour >> 16) & 255) * f), gg = clamp(((colour >> 8) & 255) * f), bb = clamp((colour & 255) * f);
		// the height of the top to stand or swim at (it was y + depth, above a fluid: Walk there aimed in the air)
		set(i, (rr << 16) | (gg << 8) | bb, (short) y);
	}

	private static int clamp(double v) { return Math.max(0, Math.min(255, (int) v)); }

	/**
	 * The cave layer's floor in the column of {@code at} (its x and z) for a head at {@code y0}: from the head down,
	 * through a solid block first (the buddy may stand beside a wall), then to the first floor with air above it, at most 24
	 * below; {@link Integer#MIN_VALUE} when there is none. Shared with {@link MapMemory}, so that what it remembers of a
	 * cave layer looks like the live map.
	 */
	static int caveFloor(Level level, BlockPos.MutableBlockPos at, int y0) {
		int y = y0 + 1, lowest = y0 - 24;
		at.setY(y);
		int guard = 0;
		while (y > lowest && !level.getBlockState(at).isAir() && guard++ < 8) at.setY(--y);
		while (y > lowest && level.getBlockState(at).isAir()) at.setY(--y);
		return y <= lowest ? Integer.MIN_VALUE : y;
	}

	/** The cave layer a head at this height is in: sixteen blocks a layer, as {@link MapMemory} keeps them. */
	static int layer(int y0) { return Math.floorDiv(y0, 16); }

	/** Underground: rock well above the head (the roof of a house or a tree overhead is still the surface), or a roof dimension. */
	static boolean underground(Level level, LocalPlayer p) {
		return level.dimensionType().hasCeiling()
			|| level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getBlockX(), p.getBlockZ()) > p.getBlockY() + 8;
	}

	/** The page chose the cave view: MapMemory then keeps the cave layer too, wherever the buddy is. */
	static boolean caveForced() { return mode.equals("cave"); }

	/**
	 * From a floor found at {@code y} (with {@code at}'s x and z set): grass, flowers, a torch or a rail skipped to the
	 * ground under them, as Xaero draws it, and the depth of the fluid there. Returns {the ground's y, the depth}.
	 * Shared with {@link MapMemory}, so that the live square and the remembered ring are drawn alike.
	 */
	static int[] ground(Level level, BlockPos.MutableBlockPos at, int y) {
		at.setY(y);
		BlockState st = level.getBlockState(at);
		for (int k = 0; k < 3 && st.getFluidState().isEmpty() && st.getCollisionShape(level, at).isEmpty() && !(st.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock); k++) {
			at.setY(--y);
			st = level.getBlockState(at);
		}
		int depth = 0;
		if (!st.getFluidState().isEmpty()) {
			BlockPos.MutableBlockPos d = at.mutable();
			while (depth < 12 && !level.getBlockState(d).getFluidState().isEmpty()) { d.setY(d.getY() - 1); depth++; }
		}
		return new int[] { y, depth };
	}

	/**
	 * A block's colour on the map: grass, leaves and water in the biome's own tint (darkened to what their textures
	 * average), the rest in the vanilla map colour a little darker, so that a forest reads dark and a river blue.
	 */
	static int colour(Level level, BlockPos at, BlockState st) {
		if (!(level instanceof net.minecraft.client.renderer.block.BlockAndTintGetter tint)) return scale(st.getMapColor(level, at).col, 0.86);
		if (!st.getFluidState().isEmpty() && st.getFluidState().is(net.minecraft.tags.FluidTags.WATER))
			return scale(net.minecraft.client.renderer.BiomeColors.getAverageWaterColor(tint, at), 0.82);
		MapColor mc = st.getMapColor(level, at);
		if (mc == MapColor.NONE) return 0x202020;
		// the grass and foliage colormaps are textures, which a client without a window (the buddy's) reads as all black:
		// then a fixed grass green and leaf green, as a map of the plains draws them
		if (mc == MapColor.GRASS) return tinted(net.minecraft.client.renderer.BiomeColors.getAverageGrassColor(tint, at), 0.6, 0x4E702E);
		if (mc == MapColor.PLANT && st.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock)
			return tinted(net.minecraft.client.renderer.BiomeColors.getAverageFoliageColor(tint, at), 0.56, 0x26481A);
		return scale(mc.col, 0.86);
	}

	private static int tinted(int rgb, double k, int fallback) {
		return (rgb & 0xFFFFFF) == 0 ? fallback : scale(rgb, k);
	}

	private static int scale(int rgb, double k) {
		return (clamp(((rgb >> 16) & 255) * k) << 16) | (clamp(((rgb >> 8) & 255) * k) << 8) | clamp((rgb & 255) * k);
	}

	private static void set(int i, int colour, short y) {
		rgb[i * 3] = (byte) (colour >> 16);
		rgb[i * 3 + 1] = (byte) (colour >> 8);
		rgb[i * 3 + 2] = (byte) colour;
		height[i] = y;
	}

	/** A figure as its name tag reads: the team's prefix and suffix around its profile name, with legacy colour codes
	 *  taken as codes, and the colour of its first letter that shows. */
	private static Map<String, Object> figure(Player o) {
		Component tag = PlayerTeam.formatNameForTeam(o.level().getScoreboard().getPlayersTeam(o.getScoreboardName()),
			Component.literal(o.getGameProfile().name()));
		StringBuilder text = new StringBuilder();
		int[] colour = {-1};
		tag.visit((style, s) -> {
			Integer cur = style.getColor() == null ? null : style.getColor().getValue();
			for (int i = 0; i < s.length(); i++) {
				char ch = s.charAt(i);
				if (ch == '\u00a7' && i + 1 < s.length()) {
					ChatFormatting f = ChatFormatting.getByCode(s.charAt(++i));
					TextColor c = f == null ? null : TextColor.fromLegacyFormat(f);   // null for bold, italic and the like
					if (c != null) cur = c.getValue();
					else if (f == ChatFormatting.RESET) cur = style.getColor() == null ? null : style.getColor().getValue();
					continue;
				}
				text.append(ch);
				if (colour[0] < 0 && !Character.isWhitespace(ch) && cur != null) colour[0] = cur;
			}
			return Optional.empty();
		}, Style.EMPTY);
		Map<String, Object> n = new LinkedHashMap<>();
		n.put("name", text.toString().replaceAll("\\s+", " ").trim());
		if (colour[0] >= 0) n.put("colour", String.format("#%06x", colour[0] & 0xffffff));
		n.put("x", Math.round(o.getX() * 10) / 10.0); n.put("z", Math.round(o.getZ() * 10) / 10.0); n.put("y", o.getBlockY());
		return n;
	}

	private static void write(Minecraft mc, LocalPlayer p) {
		Level level = p.level();
		String dim = level.dimension().identifier().toString();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("t", System.currentTimeMillis());
		m.put("x0", x0); m.put("z0", z0); m.put("size", SIZE);
		m.put("mode", cave ? "cave" : "surface");
		// the cave layer the page asks MapMemory's tiles for, when it shows caves: the one MapMemory holds (with its margin)
		m.put("y0", y0);
		m.put("layer", cave ? "cave" + MapMemory.layer(y0) : "");
		m.put("chosen", mode);
		m.put("dim", dim);
		m.put("rgb", Base64.getEncoder().encodeToString(rgb));
		byte[] h = new byte[SIZE * SIZE * 2];
		for (int i = 0; i < height.length; i++) { h[i * 2] = (byte) (height[i] >> 8); h[i * 2 + 1] = (byte) height[i]; }
		m.put("heights", Base64.getEncoder().encodeToString(h));
		AABB box = new AABB(x0, level.getMinY(), z0, x0 + SIZE, level.getMaxY(), z0 + SIZE);
		List<Object> players = new ArrayList<>(), npcs = new ArrayList<>(), mobs = new ArrayList<>();
		for (Player o : level.players()) {
			if (o == p || !box.contains(o.position())) continue;
			// a player the tab list does not show is a figure the server put there: its profile name is often colour codes
			// alone (FancyNpcs), which shows as nothing in the game, so the map shows what its name tag reads
			if (!AfkHelperClient.listed(o)) { npcs.add(figure(o)); continue; }
			players.add(Map.of("name", o.getGameProfile().name(), "x", Math.round(o.getX() * 10) / 10.0, "z", Math.round(o.getZ() * 10) / 10.0, "y", o.getBlockY()));
		}
		for (Entity e : level.getEntities(p, box, e -> e instanceof Enemy && e.isAlive()))
			mobs.add(Map.of("x", Math.round(e.getX()), "z", Math.round(e.getZ()), "kind", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath()));
		m.put("players", players);
		m.put("npcs", npcs);
		m.put("mobs", mobs);
		List<Object> places = new ArrayList<>();
		// within 512 blocks, not only on the live map: the page also draws what is remembered (MapMemory) and can be moved
		var base = Tap.baseAt(Work.dimOf(level), p.position(), 512);
		if (base != null) places.add(Map.of("kind", "bed", "x", base.bed().getX(), "y", base.bed().getY(), "z", base.bed().getZ()));
		for (BlockPos c : Stash.known(Work.dimOf(level))) if (near(c, p)) places.add(Map.of("kind", "chest", "x", c.getX(), "z", c.getZ()));
		for (BlockPos c : Work.knownFields(Work.dimOf(level))) if (near(c, p)) places.add(Map.of("kind", "field", "x", c.getX(), "z", c.getZ()));
		m.put("places", places);
		try {
			Path f = Paths.get(AfkConfig.get().exchangeDir).resolve("map.json"), tmp = f.resolveSibling("map.json.tmp");
			Files.writeString(tmp, GSON.toJson(m), StandardCharsets.UTF_8);
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			Exchange.emit("error", "msg", "map for the panel not saved: " + e.getMessage());
		}
	}

	private static boolean near(BlockPos c, LocalPlayer p) { return Math.abs(c.getX() - p.getBlockX()) <= 512 && Math.abs(c.getZ() - p.getBlockZ()) <= 512; }
}
