package net.milenco.buddy.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.TreeMap;

/**
 * Counts the spots inside a sphere where a mob can spawn, for spawn-proofing around a farm. On request
 * through commands.jsonl ({@code spawnscan} with x, y, z, r); the spots go to {@code spawnscan.txt} in
 * the exchange folder, one per line as "x y z floor headroom", so the counting by layer or block
 * happens outside the game.
 *
 * Light is deliberately left out: in the nether zombified piglins and magma cubes spawn at any light
 * and fill the same mob cap, so a lit floor is not spawn-proof there.
 */
public final class SpawnScan {
	private SpawnScan() {}

	public static void run(double cx, double cy, double cz, double r) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) { Exchange.emit("error", "msg", "no world for the spawn scan"); return; }
		// fire-immune, so magma blocks and soul fire count as the floor they are
		EntityType<?> type = null;
		// by name: the constants sit in EntityType in 1.21.11 and in EntityTypes in 26.2
		for (EntityType<?> t : BuiltInRegistries.ENTITY_TYPE) if (BuiltInRegistries.ENTITY_TYPE.getKey(t).getPath().equals("zombified_piglin")) type = t;
		StringBuilder out = new StringBuilder();
		Map<String, Integer> perFloor = new TreeMap<>();
		int spots = 0, unloaded = 0;
		int minY = Math.max(level.getMinY(), (int) Math.floor(cy - r)), maxY = Math.min(level.getMaxY(), (int) Math.ceil(cy + r));
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(), below = new BlockPos.MutableBlockPos();
		for (int x = (int) Math.floor(cx - r); x <= Math.ceil(cx + r); x++) {
			for (int z = (int) Math.floor(cz - r); z <= Math.ceil(cz + r); z++) {
				double dx = x + 0.5 - cx, dz = z + 0.5 - cz;
				if (dx * dx + dz * dz > r * r) continue;
				if (!level.hasChunk(x >> 4, z >> 4)) { unloaded++; continue; }
				for (int y = minY; y <= maxY; y++) {
					double dy = y + 0.5 - cy;
					if (dx * dx + dy * dy + dz * dz > r * r) continue;
					p.set(x, y, z); below.set(x, y - 1, z);
					BlockState floor = level.getBlockState(below);
					if (!floor.isValidSpawn(level, below, type) || !empty(level, p, type)) continue;
					int headroom = 1;
					while (headroom < 3 && empty(level, p.set(x, y + headroom, z), type)) headroom++;
					String id = BuiltInRegistries.BLOCK.getKey(floor.getBlock()).getPath();
					out.append(x).append(' ').append(y).append(' ').append(z).append(' ').append(id).append(' ').append(headroom).append('\n');
					perFloor.merge(id, 1, Integer::sum);
					spots++;
				}
			}
		}
		try {
			Files.writeString(Paths.get(AfkConfig.get().exchangeDir).resolve("spawnscan.txt"), out, StandardCharsets.UTF_8);
		} catch (IOException e) { Exchange.emit("error", "msg", "spawn scan not saved: " + e.getMessage()); return; }
		Exchange.emit("spawnscan", "x", cx, "y", cy, "z", cz, "r", r, "spots", spots, "columns_not_loaded", unloaded, "floor", perFloor);
	}

	private static boolean empty(ClientLevel level, BlockPos p, EntityType<?> type) {
		BlockState s = level.getBlockState(p);
		// a carpet or bottom slab passes vanilla's block test, but the mob's box then collides with it:
		// without this a spot stayed counted after it was covered
		if (!s.getCollisionShape(level, p).isEmpty()) return false;
		return NaturalSpawner.isValidEmptySpawnBlock(level, p, s, s.getFluidState(), type);
	}
}
