package net.milenco.buddy.client;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * The world around him, for the panel's 3D view: the chunks his game holds, drawn in the browser with the game's own
 * block models and textures, as a server list site's 3D map does with the region files its bot kept; the view keeps up
 * by itself.
 * <p>
 * <b>The copy</b>: {@code {"cmd":"world3d","radius":6}} writes {@code world3d.bin} in the exchange folder: gzip of "MQ3D";
 * an int and that many bytes of JSON (the base this copy starts, its seq, the game's version, the dimension, where he is
 * and where he looks, the lowest y and the number of sections, every block state and biome so far by number, the players
 * and other entities); an int with the number of chunks; and per chunk its x and z (ints), a short with how many sections
 * follow, and per section its index from the bottom (a byte), 4096 block numbers and 64 biome numbers (unsigned shorts, x
 * fastest, then z, then y, as the game keeps them). Sections of air alone are left out.
 * <p>
 * <b>Keeping up</b>: while a page asks ({@code {"cmd":"world3d","live":true}}, at least every 30 seconds), every half second
 * {@code world3d-ent.json} has where he and the others are, and whatever changed in the blocks goes into
 * {@code world3d-live/<seq>.bin}: gzip of "MQ3L", the JSON (base, seq, the seq before it, a reset when he changed
 * dimension, the world's height as in the copy ({@code minY}, {@code sections}) and its light ({@code skylight},
 * {@code ambient}), the block states and biomes that are new since the last file, the chunks that left his radius), the number
 * of chunks, and per chunk x, z, a byte that says whether the chunk is whole (sections not named are air), the number of
 * sections, and per section its index, a byte that says it is air now, and else its numbers as above. A chunk that came
 * into his radius, or that the server sent again, comes whole; a block that changed brings its section. The numbers of
 * block states and biomes hold for the whole session, so a page reads the files one after the other. A page that comes
 * back after the files stopped, or finds a gap, takes the copy again; a new copy takes the place of the files before it.
 * <p>
 * <b>Watching closely</b> ({@code {"cmd":"world3d","watch":true}}, which the panel sends every few seconds while its Take
 * control window is open, for {@link #WATCH_MS}): the positions file every tick, as while he is played, so that a page
 * that watches sees him turn, swing and eat as the game moved him; and {@link Details} writes state.json four times a
 * second. The positions file carries {@code breaks} too: the blocks broken around him since the last one (the game's
 * level event 2001, his own and the other players'), each {@code [x, y, z, state]}, for the page to throw their bits.
 * <p>
 * Sections are copied on the game's thread (a few milliseconds); everything else happens on a thread of its own, in the
 * order it was asked for.
 */
public final class World3d {
	private World3d() {}

	static final int MAX_RADIUS = 12;
	private static final long LIVE_MS = 30_000;
	/** How long one ask to watch closely lasts (the panel asks every four seconds while its Take control window is open). */
	private static final long WATCH_MS = 8_000;
	/** Chunks that come into his radius in one round: the nearest first, so that a teleport does not make one huge file. */
	private static final int MAX_NEW_CHUNKS = 40;
	/** Past this many bytes of files since the copy, the next round makes a new copy instead. */
	private static final long CHAIN_LIMIT = 24L << 20;
	private static final Gson GSON = new Gson();
	private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "buddy-world3d");
		t.setDaemon(true);
		return t;
	});
	private static final AtomicInteger pending = new AtomicInteger();

	// the writer's own
	private static final Map<BlockState, Integer> states = new IdentityHashMap<>();
	private static final List<String> stateNames = new ArrayList<>();
	private static final Map<Holder<Biome>, Integer> biomes = new IdentityHashMap<>();
	private static final List<String> biomeNames = new ArrayList<>();
	private static long lastSeq, chainBytes;
	private static volatile boolean chainFull;

	// the game thread's own
	private static long liveUntil, base, watchUntil;
	/** Blocks broken around him since the last positions file: x, y, z and the state (at most 64 between two files). */
	private static final List<Object> broken = new ArrayList<>();
	private static int radius = 6, tick;
	/** His game's ticks since it started, every one: the page draws his places by these (twenty a second, as the game
	 *  moved him), not by the clock of the moment a file was written, which runs unevenly when two ticks fall in one frame. */
	private static long gameTicks;
	private static ClientLevel level0;
	private static boolean resetNext;
	private static final Set<Long> sent = new HashSet<>();
	private static final Set<Long> dirty = new HashSet<>();
	/** Chunks whose light came after their blocks, by the game tick from which their sections go again. */
	private static final Map<Long, Long> relight = new HashMap<>();

	/** A section to write: its index, a copy (null when it is air now), and its sky and block light as the game holds
	 *  them (2048 bytes of nibbles each; null for full daylight and for no block light). */
	private record Sec(int index, LevelChunkSection copy, byte[] sky, byte[] blk) {
		boolean anything() { return copy != null || sky != null || blk != null; }
	}

	/** A section of a chunk with its light, as the game holds it now. */
	private static Sec sec(ClientLevel level, int cx, int cz, int index, LevelChunkSection s) {
		var sp = SectionPos.of(cx, level.getSectionYFromSectionIndex(index), cz);
		var le = level.getLightEngine();
		byte[] sky = null, blk = null;
		var sl = le.getLayerListener(LightLayer.SKY).getDataLayerData(sp);
		if (sl != null) { sky = sl.getData().clone(); if (all(sky, (byte) 0xFF)) sky = null; }
		var bl = le.getLayerListener(LightLayer.BLOCK).getDataLayerData(sp);
		if (bl != null && !bl.isEmpty()) { blk = bl.getData().clone(); if (all(blk, (byte) 0)) blk = null; }
		return new Sec(index, s == null || s.hasOnlyAir() ? null : s.copy(), sky, blk);
	}

	private static boolean all(byte[] a, byte v) {
		for (byte b : a) if (b != v) return false;
		return true;
	}

	/** The server sends light that changed: those sections go again, light and all (the light of a torch reaches the
	 *  sections around it, whose blocks did not change). Masks count from the section below the world. */
	public static void lightChanged(int cx, int cz, java.util.BitSet... masks) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null || liveUntil < System.currentTimeMillis()) return;
		for (var mask : masks)
			for (int i = mask.nextSetBit(0); i >= 0; i = mask.nextSetBit(i + 1)) {
				int sy = level.getMinSectionY() - 1 + i;
				if (sy >= level.getMinSectionY() && sy <= level.getMaxSectionY()) dirty.add(SectionPos.asLong(cx, sy, cz));
			}
	}
	private record Out(int x, int z, boolean whole, List<Sec> secs) {}

	/** The block entities of a chunk as his game has them (what the server sends: a sign's text, a banner's patterns, a
	 *  head's owner, a spawner's mob; a chest's contents only when it is opened), by "x,y,z": type and SNBT. */
	private static Map<String, Object> blockEntities(ClientLevel level, int cx, int cz) {
		Map<String, Object> out = new LinkedHashMap<>();
		for (var be : level.getChunk(cx, cz).getBlockEntities().values()) {
			String nbt;
			try {
				nbt = be.saveCustomOnly(level.registryAccess()).toString();
			} catch (Exception e) {
				nbt = "";
			}
			if (nbt.length() > 4000) nbt = nbt.substring(0, 4000) + "…";
			BlockPos q = be.getBlockPos();
			out.put(q.getX() + "," + q.getY() + "," + q.getZ(), Map.of("type", String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType())), "nbt", nbt));
		}
		return out;
	}

	/** A page's Take control window is open: everything that moves is written as often as while he is played, so that
	 *  the window shows the buddy's own work or another driver's too, sleeping, eating and block breaking included. */
	static boolean watched() { return System.currentTimeMillis() < watchUntil; }

	/** A block broken near him, his own or another player's (ClientLevel.addDestroyBlockEffect, the game's thread). */
	public static void broke(BlockPos pos, BlockState state) {
		if (System.currentTimeMillis() > liveUntil || broken.size() >= 64 || state.isAir()) return;
		broken.add(List.of(pos.getX(), pos.getY(), pos.getZ(), BlockStateParser.serialize(state)));
	}

	static void command(JsonObject o, Minecraft mc) {
		if (o.has("watch") && o.get("watch").getAsBoolean()) {
			watchUntil = System.currentTimeMillis() + WATCH_MS;
			return;
		}
		if (o.has("live") && o.get("live").getAsBoolean()) {
			long now = System.currentTimeMillis();
			boolean was = now < liveUntil;
			liveUntil = now + LIVE_MS;
			// what changed while nobody looked was not kept: a new copy, which a page then reads
			if (!was || base == 0) snapshot(mc, radius);
			return;
		}
		if (o.has("radius")) radius = Mth.clamp(o.get("radius").getAsInt(), 1, MAX_RADIUS);
		snapshot(mc, radius);
	}

	/** A block changed in his world (the game's thread). */
	public static void changed(BlockPos pos) {
		if (System.currentTimeMillis() > liveUntil) return;
		dirty.add(SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
	}

	/** Others breaking blocks, by the breaker's number: where, which of the ten stages, when last told (the game's thread). */
	private static final Map<Integer, long[]> breaking = new HashMap<>();

	/** The server tells that someone breaks a block (or stops: a stage outside 0 to 9). */
	public static void progress(int id, BlockPos pos, int stage) {
		if (stage < 0 || stage > 9) breaking.remove(id);
		else breaking.put(id, new long[] { pos.getX(), pos.getY(), pos.getZ(), stage, System.currentTimeMillis() });
	}

	/** The cracks to draw: his own breaking and the others', each x, y, z and a stage of 0 to 9. */
	private static List<int[]> cracks(Minecraft mc) {
		List<int[]> out = new ArrayList<>();
		var gm = mc.gameMode;
		if (gm != null && gm.isDestroying() && gm.getDestroyStage() >= 0) {
			BlockPos q = ((net.milenco.buddy.mixin.client.DestroyAccessor) gm).buddy$destroyBlockPos();
			if (q != null) out.add(new int[] { q.getX(), q.getY(), q.getZ(), Math.min(9, gm.getDestroyStage()) });
		}
		long now = System.currentTimeMillis();
		breaking.values().removeIf(v -> now - v[4] > 10_000);
		// his own breaking comes from the game mode above; the level hears of it too, under his own number
		int me = mc.player == null ? -1 : mc.player.getId();
		for (var en : breaking.entrySet()) if (en.getKey() != me) { long[] v = en.getValue(); out.add(new int[] { (int) v[0], (int) v[1], (int) v[2], (int) v[3] }); }
		return out;
	}

	/** The server sent a chunk (again): it goes whole in the next round. */
	public static void chunkLoaded(ChunkPos pos) {
		sent.remove(pos.pack());
		// its light comes a moment later: the game queues it behind the chunk and shows it at its next light update, and a
		// section read before that was sent as full daylight without block light. A mark queued behind the chunk's light
		// sends its sections again a few ticks after, or some chunks were drawn without their light
		ClientLevel level = Minecraft.getInstance().level;
		long k = pos.pack();
		if (level != null && liveUntil > System.currentTimeMillis()) level.queueLightUpdate(() -> relight.put(k, gameTicks + 4));
	}

	private static void snapshot(Minecraft mc, int r) {
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		if (p == null || level == null) { Exchange.emit("world3d", "error", "not in a world"); return; }
		long t0 = System.currentTimeMillis();
		int pcx = p.getBlockX() >> 4, pcz = p.getBlockZ() >> 4;
		List<Out> chunks = new ArrayList<>();
		Map<String, Object> bes = new LinkedHashMap<>();
		sent.clear();
		dirty.clear();
		for (int cx = pcx - r; cx <= pcx + r; cx++)
			for (int cz = pcz - r; cz <= pcz + r; cz++) {
				if (!level.hasChunk(cx, cz)) continue;
				chunks.add(whole(level, cx, cz));
				sent.add(ChunkPos.pack(cx, cz));
				Map<String, Object> be = blockEntities(level, cx, cz);
				if (!be.isEmpty()) bes.put(cx + "," + cz, be);
			}
		level0 = level;
		resetNext = false;
		chainFull = false;
		base = Math.max(base + 1, t0);
		Map<String, Object> meta = common(level, p);
		meta.put("base", base);
		meta.put("version", SharedConstants.getCurrentVersion().name());
		meta.put("name", p.getGameProfile().name());
		meta.put("minY", level.getMinY());
		meta.put("sections", level.getSectionsCount());
		meta.put("radius", r);
		meta.put("light", 1);
		meta.put("be", bes);
		long copied = System.currentTimeMillis() - t0;
		submit(() -> writeCopy(chunks, meta, t0, copied));
	}

	/** Once a tick. */
	static void tick(Minecraft mc) {
		gameTicks++;
		long now = System.currentTimeMillis();
		// while the page drives him, five times a second, so that the view keeps up with his steps
		// played from the panel or watched closely every tick (his place drives the view), else every half second
		if (now > liveUntil || base == 0) { relight.clear(); broken.clear(); return; }
		if (++tick % (Steer.active() || watched() ? 1 : 10) != 0) return;
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		if (p == null || level == null) return;
		if (chainFull) { snapshot(mc, radius); return; }
		if (pending.get() > 3) return;
		if (level != level0) { resetNext = true; sent.clear(); dirty.clear(); level0 = level; }
		int pcx = p.getBlockX() >> 4, pcz = p.getBlockZ() >> 4;
		Set<Long> want = new HashSet<>();
		for (int cx = pcx - radius; cx <= pcx + radius; cx++)
			for (int cz = pcz - radius; cz <= pcz + radius; cz++)
				if (level.hasChunk(cx, cz)) want.add(ChunkPos.pack(cx, cz));
		List<Long> removed = new ArrayList<>();
		for (long k : sent) if (!want.contains(k)) removed.add(k);
		List<Long> added = new ArrayList<>();
		for (long k : want) if (!sent.contains(k)) added.add(k);
		added.sort(Comparator.comparingInt(k -> Math.max(Math.abs(ChunkPos.getX(k) - pcx), Math.abs(ChunkPos.getZ(k) - pcz))));
		if (added.size() > MAX_NEW_CHUNKS) added = new ArrayList<>(added.subList(0, MAX_NEW_CHUNKS));
		List<Out> outs = new ArrayList<>();
		for (long k : added) { outs.add(whole(level, ChunkPos.getX(k), ChunkPos.getZ(k))); sent.add(k); }
		removed.forEach(sent::remove);
		// a chunk whose light has come since it was sent: all its sections again, light and all
		for (var it = relight.entrySet().iterator(); it.hasNext();) {
			var en = it.next();
			if (en.getValue() > gameTicks) continue;
			it.remove();
			long k = en.getKey();
			if (!sent.contains(k)) continue;
			for (int sy = level.getMinSectionY(); sy <= level.getMaxSectionY(); sy++) dirty.add(SectionPos.asLong(ChunkPos.getX(k), sy, ChunkPos.getZ(k)));
		}
		Map<Long, List<Sec>> partial = new HashMap<>();
		for (long s : dirty) {
			long k = ChunkPos.pack(SectionPos.x(s), SectionPos.z(s));
			if (!sent.contains(k) || added.contains(k)) continue;
			int index = level.getSectionIndexFromSectionY(SectionPos.y(s));
			if (index < 0 || index >= level.getSectionsCount()) continue;
			LevelChunkSection sec = level.getChunk(SectionPos.x(s), SectionPos.z(s)).getSections()[index];
			partial.computeIfAbsent(k, x -> new ArrayList<>()).add(sec(level, SectionPos.x(s), SectionPos.z(s), index, sec));
		}
		dirty.clear();
		for (var e : partial.entrySet()) outs.add(new Out(ChunkPos.getX(e.getKey()), ChunkPos.getZ(e.getKey()), false, e.getValue()));
		Map<String, Object> ents = common(level, p);
		ents.put("base", base);
		ents.put("cracks", cracks(mc));
		if (!broken.isEmpty()) { ents.put("breaks", new ArrayList<>(broken)); broken.clear(); }
		submit(() -> writeJson("world3d-ent.json", ents));
		if (outs.isEmpty() && removed.isEmpty() && !resetNext) return;
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("base", base);
		meta.put("dim", Work.dimOf(level));
		meta.put("reset", resetNext);
		// the new dimension's height: without it a page kept the old one's, and drew the nether (from y 0) 64 blocks too
		// low, its bedrock roof a floor under their feet
		meta.put("minY", level.getMinY());
		meta.put("sections", level.getSectionsCount());
		meta.put("skylight", level.dimensionType().hasSkyLight());
		meta.put("ambient", level.dimensionType().ambientLight());
		meta.put("light", 1);
		List<int[]> gone = new ArrayList<>();
		for (long k : removed) gone.add(new int[] { ChunkPos.getX(k), ChunkPos.getZ(k) });
		meta.put("removed", gone);
		Map<String, Object> bes = new LinkedHashMap<>();
		for (Out o : outs) bes.put(o.x() + "," + o.z(), blockEntities(level, o.x(), o.z()));
		meta.put("be", bes);
		resetNext = false;
		submit(() -> writeChanges(outs, meta));
	}

	private static Out whole(ClientLevel level, int cx, int cz) {
		LevelChunkSection[] s = level.getChunk(cx, cz).getSections();
		List<Sec> secs = new ArrayList<>();
		// the air too where its light is not plain daylight (the air over a floor lit by torches at night)
		for (int i = 0; i < s.length; i++) { Sec x = sec(level, cx, cz, i, s[i]); if (x.anything()) secs.add(x); }
		return new Out(cx, cz, true, secs);
	}

	/** Where he is and looks, and who and what is around him. */
	private static Map<String, Object> common(ClientLevel level, LocalPlayer p) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("dim", Work.dimOf(level));
		m.put("time", level.getDefaultClockTime());
		m.put("skylight", level.dimensionType().hasSkyLight());
		m.put("ambient", level.dimensionType().ambientLight());
		m.put("x", p.getX());
		m.put("y", p.getY());
		m.put("z", p.getZ());
		m.put("eye", p.getEyeHeight());
		skin(p, m);
		m.put("yaw", p.getYRot());
		m.put("pitch", p.getXRot());
		// his own motion, for the view to carry him on between two messages, and his crouch
		var dm = p.getDeltaMovement();
		m.put("vx", round(dm.x)); m.put("vy", round(dm.y)); m.put("vz", round(dm.z));
		if (p.isCrouching()) m.put("crouch", true);
		if (p.isOnFire()) m.put("fire", true);
		gear(p, m);
		m.put("body", round(p.yBodyRot));
		m.put("at", System.currentTimeMillis());
		m.put("tk", gameTicks);
		// what he uses (eating, drinking, a bow, a shield) and with which hand, for the view through his eyes
		if (p.isUsingItem()) {
			m.put("using", BuiltInRegistries.ITEM.getKey(p.getUseItem().getItem()).getPath());
			if (p.getUsedItemHand() == net.minecraft.world.InteractionHand.OFF_HAND) m.put("use_hand", "off");
		}
		if (p.isSwinging()) m.put("swing", round(p.getSwingAnimation(0)));
		double reach = (radius + 1) * 16;
		List<Object> players = new ArrayList<>(), entities = new ArrayList<>();
		for (Entity e : level.entitiesForRendering()) {
			if (e == p || Math.abs(e.getX() - p.getX()) > reach || Math.abs(e.getZ() - p.getZ()) > reach) continue;
			String kind = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
			// what has no body of its own: floating texts, displays, the click boxes of figures
			if (kind.endsWith("_display") || kind.equals("interaction") || kind.equals("marker")) continue;
			Map<String, Object> o = new LinkedHashMap<>();
			o.put("id", e.getId());
			o.put("x", round(e.getX()));
			o.put("y", round(e.getY()));
			o.put("z", round(e.getZ()));
			o.put("yaw", round(e.getYRot()));
			// a body, a head and how hurt: the model turns as the game turns it, the hover says the health
			if (e instanceof net.minecraft.world.entity.LivingEntity le) {
				o.put("body", round(le.yBodyRot));
				o.put("head", round(le.getYHeadRot()));
				o.put("pitch", round(le.getXRot()));
				o.put("hp", round(le.getHealth()));
				o.put("max_hp", round(le.getMaxHealth()));
				if (le.hurtTime > 0) o.put("hurt", true);
				if (le.isBaby()) o.put("baby", true);
				if (le.isCrouching()) o.put("crouch", true);
				if (le.walkAnimation.speed() > 0.01f) { o.put("walk", round(le.walkAnimation.position())); o.put("walk_speed", round(le.walkAnimation.speed())); }
				if (le.isSwinging()) o.put("swing", round(le.getSwingAnimation(0)));
				gear(le, o);
			}
			if (e.isOnFire()) o.put("fire", true);
			if (e instanceof Player pl) {
				skin(pl, o);
				// a figure the server put there is no player: it is drawn as any other figure
				if (!AfkHelperClient.listed(pl)) { o.put("kind", "figure"); o.put("w", 0.6); o.put("h", 1.8); entities.add(o); continue; }
				o.put("name", pl.getGameProfile().name());
				players.add(o);
				continue;
			}
			if (entities.size() >= 600) continue;
			o.put("kind", kind);
			o.put("w", round(e.getBbWidth()));
			o.put("h", round(e.getBbHeight()));
			if (e instanceof Enemy) o.put("hostile", true);
			if (e instanceof ItemEntity it) o.put("item", BuiltInRegistries.ITEM.getKey(it.getItem().getItem()).getPath());
			looks(e, o);
			entities.add(o);
		}
		m.put("players", players);
		m.put("entities", entities);
		m.put("beacons", beacons(level));
		return m;
	}

	private static List<Object> beacons = List.of();
	private static long beaconsAt = -100;

	/**
	 * The beams the game draws now: per beacon in a chunk the page
	 * has, its place and its parts, each its colour (#rrggbb) and height, as {@code BeaconBeamOwner.getBeamSections} gives
	 * them to the game's renderer (empty while the beacon has no pyramid or a block shuts the sky off). The renderer draws
	 * the last part on to y + 2048. Looked up once a second: the game itself checks a beam a few blocks a tick.
	 */
	private static List<Object> beacons(ClientLevel level) {
		if (gameTicks - beaconsAt < 20) return beacons;
		beaconsAt = gameTicks;
		List<Object> out = new ArrayList<>();
		for (long k : sent) {
			if (!level.hasChunk(ChunkPos.getX(k), ChunkPos.getZ(k))) continue;
			for (var be : level.getChunk(ChunkPos.getX(k), ChunkPos.getZ(k)).getBlockEntities().values()) {
				if (!(be instanceof net.minecraft.world.level.block.entity.BeaconBeamOwner owner)) continue;
				var parts = owner.getBeamSections();
				if (parts.isEmpty()) continue;
				List<Object> beam = new ArrayList<>();
				for (var part : parts) beam.add(List.of(String.format("#%06x", part.getColor() & 0xffffff), part.getHeight()));
				BlockPos q = be.getBlockPos();
				out.add(Map.of("x", q.getX(), "y", q.getY(), "z", q.getZ(), "beam", beam));
			}
		}
		beacons = out;
		return out;
	}

	/** What a living thing wears and holds, for the 3D view: per armour slot (head, chest, legs, feet) its equipment
	 *  asset ("netherite", "leather"), a dyed piece's colour, and the items in its hands. */
	static void gear(net.minecraft.world.entity.LivingEntity le, Map<String, Object> o) {
		var slots = new net.minecraft.world.entity.EquipmentSlot[] { net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
			net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET };
		List<Object> armor = new ArrayList<>();
		boolean any = false;
		for (var sl : slots) {
			var st = le.getItemBySlot(sl);
			var eq = st.isEmpty() ? null : st.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
			String asset = eq == null ? null : eq.assetId().map(k -> k.identifier().getPath()).orElse(null);
			if (asset == null) { armor.add(null); continue; }
			any = true;
			var dyed = st.get(net.minecraft.core.component.DataComponents.DYED_COLOR);
			armor.add(dyed == null ? asset : asset + "#" + String.format("%06x", dyed.rgb() & 0xffffff));
		}
		if (any) o.put("armor", armor);
		var main = le.getMainHandItem();
		if (!main.isEmpty()) o.put("hand", BuiltInRegistries.ITEM.getKey(main.getItem()).getPath());
		var off = le.getOffhandItem();
		if (!off.isEmpty()) o.put("offhand", BuiltInRegistries.ITEM.getKey(off.getItem()).getPath());
	}

	/** What the 3D view needs to draw a thing that is no mob as the game draws it, where its kind does not say it all
	 *  (cushions, item frames and the like): a
	 *  cushion's colour; for what hangs on a wall the way it faces and its box (a painting is as wide as its picture, a frame
	 *  thin against its wall, and their place is their middle, not their foot); a frame's item, its turn and whether the
	 *  frame is hidden; a painting's picture and size in blocks; the block a falling block or a minecart carries; how an
	 *  arrow or a minecart leans; the item a thrown thing is drawn as. Only what differs from the plain one. */
	static void looks(Entity e, Map<String, Object> o) {
		if (e instanceof net.minecraft.world.entity.decoration.Cushion c) o.put("color", c.getColor().getName());
		if (e instanceof net.minecraft.world.entity.decoration.HangingEntity h) {
			o.put("facing", h.getDirection().getName());
			var b = e.getBoundingBox();
			o.put("box", new double[] { round(b.minX - e.getX()), round(b.minY - e.getY()), round(b.minZ - e.getZ()),
				round(b.maxX - e.getX()), round(b.maxY - e.getY()), round(b.maxZ - e.getZ()) });
		}
		if (e instanceof net.minecraft.world.entity.decoration.ItemFrame f) {
			var st = f.getItem();
			if (!st.isEmpty()) o.put("item", BuiltInRegistries.ITEM.getKey(st.getItem()).getPath());
			if (f.getRotation() != 0) o.put("rotation", f.getRotation());
			if (f.isInvisible()) o.put("invisible", true);
		}
		if (e instanceof net.minecraft.world.entity.decoration.painting.Painting pa) {
			var v = pa.getVariant().value();
			o.put("variant", v.assetId().toString());
			o.put("size", new int[] { v.width(), v.height() });
		}
		if (e instanceof net.minecraft.world.entity.item.FallingBlockEntity fb) o.put("block", BlockStateParser.serialize(fb.getBlockState()));
		if (e instanceof net.minecraft.world.entity.vehicle.minecart.AbstractMinecart mc) {
			// a chest, a furnace (lit or not), a hopper, or what a command set: drawn in the cart as the game draws it
			var bs = mc.getDisplayBlockState();
			if (!bs.isAir()) o.put("block", BlockStateParser.serialize(bs));
			if (mc.getDisplayOffset() != mc.getDefaultDisplayOffset()) o.put("offset", mc.getDisplayOffset());
		}
		if (e instanceof net.minecraft.world.entity.vehicle.minecart.AbstractMinecart || e instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow)
			o.put("pitch", round(e.getXRot()));
		if (e instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal ec && !ec.showsBottom()) o.put("bottom", false);
		// snowballs, eggs, ender pearls, potions, fireballs, eyes of ender, fireworks: the game draws their item
		if (e instanceof net.minecraft.world.entity.projectile.ItemSupplier is && !(e instanceof ItemEntity)) {
			var st = is.getItem();
			if (!st.isEmpty()) o.put("item", BuiltInRegistries.ITEM.getKey(st.getItem()).getPath());
		}
	}

	/** A player's skin for the 3D view: the address the game downloaded it from, or its built-in one, and the slim arms. */
	static void skin(Player pl, Map<String, Object> o) {
		if (!(pl instanceof net.minecraft.client.player.AbstractClientPlayer ap)) return;
		var sk = ap.getSkin();
		if (sk.body() instanceof net.minecraft.core.ClientAsset.DownloadedTexture dt) o.put("skin", dt.url());
		else if (sk.body() != null) o.put("skin_default", sk.body().texturePath().toString());
		if (sk.model() == net.minecraft.world.entity.player.PlayerModelType.SLIM) o.put("slim", true);
	}

	private interface Job { void run() throws Exception; }

	private static void submit(Job r) {
		pending.incrementAndGet();
		WRITER.execute(() -> {
			try {
				r.run();
			} catch (Exception e) {
				Exchange.emit("world3d", "error", String.valueOf(e.getMessage()));
			} finally {
				pending.decrementAndGet();
			}
		});
	}

	// ---------- the writer thread ----------

	private static Path dir() { return Paths.get(AfkConfig.get().exchangeDir); }

	private static long nextSeq() { return lastSeq = Math.max(lastSeq + 1, System.currentTimeMillis()); }

	private static int state(BlockState s) {
		Integer n = states.get(s);
		if (n == null) { n = stateNames.size(); states.put(s, n); stateNames.add(BlockStateParser.serialize(s)); }
		return n;
	}

	private static int biome(Holder<Biome> h) {
		Integer n = biomes.get(h);
		if (n == null) {
			n = biomeNames.size();
			biomes.put(h, n);
			biomeNames.add(h.unwrapKey().map(k -> k.identifier().toString()).orElse("minecraft:plains"));
		}
		return n;
	}

	/** A section's numbers: 4096 block states, then 64 biomes. */
	private static short[] numbers(LevelChunkSection s) {
		short[] out = new short[4096 + 64];
		var st = s.getStates();
		for (int y = 0, i = 0; y < 16; y++)
			for (int z = 0; z < 16; z++)
				for (int x = 0; x < 16; x++, i++) out[i] = (short) state(st.get(x, y, z));
		var bs = s.getBiomes();
		for (int y = 0, i = 4096; y < 4; y++)
			for (int z = 0; z < 4; z++)
				for (int x = 0; x < 4; x++, i++) out[i] = (short) biome(bs.get(x, y, z));
		return out;
	}

	private static void writeCopy(List<Out> chunks, Map<String, Object> meta, long t0, long copied) throws IOException {
		List<short[]> nums = new ArrayList<>();
		for (Out c : chunks) for (Sec s : c.secs()) nums.add(s.copy() == null ? null : numbers(s.copy()));
		long seq = nextSeq();
		meta.put("seq", seq);
		meta.put("states", new ArrayList<>(stateNames));
		meta.put("biomes", new ArrayList<>(biomeNames));
		Path f = dir().resolve("world3d.bin");
		long bytes = write(f, out -> {
			out.writeBytes("MQ3D");
			json(out, meta);
			out.writeInt(chunks.size());
			int k = 0;
			for (Out c : chunks) {
				out.writeInt(c.x());
				out.writeInt(c.z());
				out.writeShort(c.secs().size());
				for (Sec s : c.secs()) {
					out.writeByte(s.index());
					short[] n = nums.get(k++);
					// 0: blocks and light, 2: light only (air)
					out.writeByte(n == null ? 2 : 0);
					if (n != null) for (short v : n) out.writeShort(v);
					light(out, s);
				}
			}
		});
		// the files of the copy before go: a page with them takes this copy
		Path live = dir().resolve("world3d-live");
		Files.createDirectories(live);
		try (var list = Files.list(live)) {
			for (Path q : list.toList()) Files.deleteIfExists(q);
		}
		chainBytes = 0;
		Exchange.emit("world3d", "chunks", chunks.size(), "sections", nums.size(), "states", stateNames.size(),
			"bytes", bytes, "copy_ms", copied, "ms", System.currentTimeMillis() - t0);
	}

	private static void writeChanges(List<Out> chunks, Map<String, Object> meta) throws IOException {
		int sf = stateNames.size(), bf = biomeNames.size();
		Map<Sec, short[]> nums = new IdentityHashMap<>();
		for (Out c : chunks) for (Sec s : c.secs()) if (s.copy() != null) nums.put(s, numbers(s.copy()));
		meta.put("prev", lastSeq);
		long seq = nextSeq();
		meta.put("seq", seq);
		meta.put("states_from", sf);
		meta.put("states", new ArrayList<>(stateNames.subList(sf, stateNames.size())));
		meta.put("biomes_from", bf);
		meta.put("biomes", new ArrayList<>(biomeNames.subList(bf, biomeNames.size())));
		Path live = dir().resolve("world3d-live");
		Files.createDirectories(live);
		long bytes = write(live.resolve(seq + ".bin"), out -> {
			out.writeBytes("MQ3L");
			json(out, meta);
			out.writeInt(chunks.size());
			for (Out c : chunks) {
				out.writeInt(c.x());
				out.writeInt(c.z());
				out.writeByte(c.whole() ? 1 : 0);
				out.writeShort(c.secs().size());
				for (Sec s : c.secs()) {
					out.writeByte(s.index());
					short[] n = nums.get(s);
					// 0: blocks and light, 1: air without light of its own, 2: air with light
					boolean lit = s.sky() != null || s.blk() != null;
					out.writeByte(n != null ? 0 : lit ? 2 : 1);
					if (n != null) for (short v : n) out.writeShort(v);
					if (n != null || lit) light(out, s);
				}
			}
		});
		chainBytes += bytes;
		if (chainBytes > CHAIN_LIMIT) chainFull = true;
	}

	/** A section's light: a byte saying which follow (1 sky, 2 block), then 2048 bytes of each. */
	private static void light(DataOutputStream out, Sec s) throws IOException {
		out.writeByte((s.sky() != null ? 1 : 0) | (s.blk() != null ? 2 : 0));
		if (s.sky() != null) out.write(s.sky());
		if (s.blk() != null) out.write(s.blk());
	}

	private static void writeJson(String name, Map<String, Object> m) {
		try {
			Path f = dir().resolve(name), tmp = f.resolveSibling(name + ".tmp");
			Files.writeString(tmp, GSON.toJson(m), StandardCharsets.UTF_8);
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Exchange.emit("world3d", "error", name + ": " + e.getMessage());
		}
	}

	private static void json(DataOutputStream out, Map<String, Object> m) throws IOException {
		byte[] b = GSON.toJson(m).getBytes(StandardCharsets.UTF_8);
		out.writeInt(b.length);
		out.write(b);
	}

	private interface Body { void write(DataOutputStream out) throws IOException; }

	/** Gzip of what the body writes, put in place at once; its size. */
	private static long write(Path f, Body body) throws IOException {
		ByteArrayOutputStream raw = new ByteArrayOutputStream(1 << 16);
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(raw, 1 << 16), 1 << 16))) {
			body.write(out);
		}
		Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
		try (OutputStream o = Files.newOutputStream(tmp)) { raw.writeTo(o); }
		Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		return raw.size();
	}

	private static double round(double v) { return Math.round(v * 100) / 100.0; }
}
