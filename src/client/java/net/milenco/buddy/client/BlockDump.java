package net.milenco.buddy.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * {@code {"cmd":"blocks","x":100,"y":64,"z":-200,"r":6,"dy":3}}: the blocks in a box round a spot as the buddy's game
 * knows them, written to {@code blocks.txt} in the exchange folder, one layer a block from the top down, a character a
 * block with a legend (21 Oct 2026: to see why a place is "unreachable" without hundreds of questions to the server's
 * console). {@code @} is where the buddy's feet are. Without x, y, z: round the buddy. At most 12 across and 8 up or down.
 */
final class BlockDump {
	private BlockDump() {}

	private static final String CHARS = "#=+%&$ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!?*~^<>/|";

	static void command(JsonObject o, Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null) { Exchange.emit("error", "msg", "blocks: not in a world"); return; }
		Level level = p.level();
		int cx = o.has("x") ? o.get("x").getAsInt() : p.getBlockX();
		int cy = o.has("y") ? o.get("y").getAsInt() : p.getBlockY();
		int cz = o.has("z") ? o.get("z").getAsInt() : p.getBlockZ();
		int r = Math.min(12, o.has("r") ? o.get("r").getAsInt() : 5), dy = Math.min(8, o.has("dy") ? o.get("dy").getAsInt() : 3);
		Map<String, Character> legend = new LinkedHashMap<>();
		StringBuilder out = new StringBuilder();
		BlockPos feet = p.blockPosition();
		out.append("blocks round ").append(cx).append(' ').append(cy).append(' ').append(cz).append(" r ").append(r).append(" dy ").append(dy)
			.append(", x from ").append(cx - r).append(" (left) to ").append(cx + r).append(", z from ").append(cz - r).append(" (top) to ").append(cz + r)
			.append("; ").append(p.getGameProfile().name()).append(" stands at ").append(feet.toShortString()).append('\n');
		for (int y = cy + dy; y >= cy - dy; y--) {
			out.append("\ny ").append(y).append('\n');
			for (int z = cz - r; z <= cz + r; z++) {
				StringBuilder row = new StringBuilder();
				for (int x = cx - r; x <= cx + r; x++) {
					BlockPos q = new BlockPos(x, y, z);
					if (q.equals(feet)) { row.append('@'); continue; }
					if (!level.isLoaded(q)) { row.append(' '); continue; }
					BlockState st = level.getBlockState(q);
					if (st.isAir()) { row.append('.'); continue; }
					String name = describe(st);
					Character c = legend.get(name);
					if (c == null) { c = legend.size() < CHARS.length() ? CHARS.charAt(legend.size()) : '?'; legend.put(name, c); }
					row.append(c);
				}
				out.append(String.format("%6d ", z)).append(row).append('\n');
			}
		}
		out.append("\nlegend:\n");
		legend.forEach((name, c) -> out.append(c).append(' ').append(name).append('\n'));
		try {
			Path f = Paths.get(AfkConfig.get().exchangeDir, "blocks.txt");
			Files.writeString(f, out.toString());
			Exchange.emit("blocks", "file", f.toString(), "kinds", legend.size());
		} catch (Exception e) {
			Exchange.emit("error", "msg", "block view not saved: " + e.getMessage());
		}
	}

	/** A block with the properties that matter for walking and opening. */
	private static String describe(BlockState st) {
		String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
		String props = st.getProperties().stream()
			.filter(pr -> java.util.Set.of("facing", "half", "open", "type", "part", "shape", "hinge", "waterlogged", "axis", "occupied", "attached", "moisture", "age").contains(pr.getName()))
			.map(pr -> pr.getName() + "=" + value(st, pr))
			.filter(s -> !s.equals("waterlogged=false") && !s.equals("occupied=false"))
			.collect(Collectors.joining(","));
		return props.isEmpty() ? id : id + "[" + props + "]";
	}

	private static <T extends Comparable<T>> String value(BlockState st, Property<T> pr) { return pr.getName(st.getValue(pr)); }
}
