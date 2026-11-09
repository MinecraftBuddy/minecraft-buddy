package net.milenco.buddy.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.cubemob.MagmaCube;
import net.minecraft.world.entity.monster.cubemob.Slime;

/**
 * Things seen on the way during an obtain order, for the practice rig's reading of the overworld's phases: each slime newly in
 * view (where, its size, how far, the biome) and each swamp newly in view, one event "seen" apiece. Written only while an
 * order runs and the autopilot's sight_log switch is on (the rig sets it); the buddy acts on none of it. What was seen is a
 * memory of this run only (RunMemories: a fresh run forgets it).
 */
final class Sightings {
	private Sightings() {}

	// (a slime is tracked by the client within about 48 blocks across: the server's tracking range for it)
	private static final double SLIME_RANGE = 48;
	// (a swamp sampled round the buddy at these distances in eight directions; a new swamp is one this far from every earlier one)
	private static final int[] RINGS = {0, 16, 32, 48};
	private static final double SWAMP_APART = 128;
	private static final java.util.Set<java.util.UUID> SEEN = new java.util.HashSet<>();
	private static final java.util.List<long[]> SWAMPS = new java.util.ArrayList<>();
	private static int tick;

	static long remembered() { return SEEN.size() + SWAMPS.size(); }

	static void forgetRun() {
		SEEN.clear();
		SWAMPS.clear();
	}

	/** Once a client tick; looks once a second. */
	static void tick(Minecraft mc) {
		if (++tick % 20 != 0 || mc.player == null || mc.level == null || !Obtain.on() || !Alto.sightLog()) return;
		var p = mc.player;
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!(e instanceof Slime s) || e instanceof MagmaCube || !s.isAlive()) continue;
			double dx = e.getX() - p.getX(), dz = e.getZ() - p.getZ();
			double across = Math.sqrt(dx * dx + dz * dz);
			if (across > SLIME_RANGE || !SEEN.add(e.getUUID())) continue;
			BlockPos at = e.blockPosition();
			Exchange.emit("seen", "kind", "slime", "x", at.getX(), "y", at.getY(), "z", at.getZ(), "size", s.getSize(),
				"dist", Math.round(p.distanceTo(e) * 10) / 10.0, "biome", mc.level.getBiome(at).getRegisteredName(),
				"buddy_y", (int) Math.floor(p.getY()));
		}
		BlockPos here = p.blockPosition();
		for (int r : RINGS) {
			for (int k = 0; k < (r == 0 ? 1 : 8); k++) {
				double a = Math.PI / 4 * k;
				BlockPos at = new BlockPos(here.getX() + (int) Math.round(Math.cos(a) * r), here.getY(), here.getZ() + (int) Math.round(Math.sin(a) * r));
				if (!mc.level.hasChunkAt(at)) continue;
				String biome = mc.level.getBiome(at).getRegisteredName();
				if (!biome.contains("swamp") || nearSwamp(at)) continue;
				SWAMPS.add(new long[]{at.getX(), at.getZ()});
				Exchange.emit("seen", "kind", "swamp", "x", at.getX(), "y", at.getY(), "z", at.getZ(), "dist", r, "biome", biome,
					"buddy_y", here.getY());
			}
		}
	}

	private static boolean nearSwamp(BlockPos at) {
		for (long[] s : SWAMPS) if (Math.hypot(s[0] - at.getX(), s[1] - at.getZ()) < SWAMP_APART) return true;
		return false;
	}
}
