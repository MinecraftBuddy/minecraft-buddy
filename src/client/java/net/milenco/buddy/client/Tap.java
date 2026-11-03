package net.milenco.buddy.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The tap: the player he follows hits a block with his bare hand and lets go within a second, with the alt near, and
 * the alt takes up the job that block stands for. Only a bare-handed tap counts, and the alt has to stand close. It
 * works on any server: the server tells everyone near that a player starts breaking a block (its number, the block, the
 * stage) and when he stops (stage -1), and nothing else is needed. The alt answers: a nod for on, one crouch for off, a
 * shake for a block he has no job for.
 *
 * <p>What a block means: a crop, farmland or sugar cane keeps this field; a log chops along; stone or an ore digs
 * along; a furnace, blast furnace or smoker is kept (Ovens, which learns what was in it); a chest or barrel is one of
 * his; a bed is the home (the base): the alt walks to it and types the profile's sethome. The same kind once more is
 * off. On ground where the player may not break nothing is sent, and there a tap does nothing; in creative a tap breaks
 * the block, so it is for survival.
 */
final class Tap {
	private Tap() {}

	static final double NEAR = 6;
	static final long TAP_MS = 1000, CHECK_MS = 200;

	private record Ev(int id, BlockPos pos, int progress, long at) {}

	/** From the packet's thread (BlockProgressMixin): read on the next tick. */
	private static final ConcurrentLinkedQueue<Ev> queue = new ConcurrentLinkedQueue<>();

	private record Start(BlockPos pos, long at, boolean bare, String name, BlockState state) {}

	private static final Map<Integer, Start> starts = new HashMap<>();
	private record Pending(BlockPos pos, String name, BlockState state, long checkAt) {}
	private static final List<Pending> pending = new ArrayList<>();

	static void progress(int id, BlockPos pos, int progress) {
		queue.add(new Ev(id, pos.immutable(), progress, System.currentTimeMillis()));
		if (queue.size() > 200) queue.poll();
	}

	/** Every tick while he follows: the taps of the player he follows, and the walk to a new home. */
	static boolean tick(Minecraft mc, LocalPlayer p) {
		String target = Follow.target();
		Level level = p.level();
		Ev e;
		while ((e = queue.poll()) != null) {
			if (target == null) continue;
			Entity who = level.getEntity(e.id());
			// a stage is 0 to 9; the stop the server sends as -1 arrives as 255 (the packet's byte has no sign)
			if (e.progress() >= 0 && e.progress() < 10) {
				if (!(who instanceof Player pl) || !pl.getGameProfile().name().equalsIgnoreCase(target)) continue;
				if (!starts.containsKey(e.id()) || !starts.get(e.id()).pos().equals(e.pos()))
					starts.put(e.id(), new Start(e.pos(), e.at(), pl.getMainHandItem().isEmpty(), pl.getGameProfile().name(), level.getBlockState(e.pos())));
				continue;
			}
			Start s = starts.remove(e.id());
			if (s == null || !s.pos().equals(e.pos()) || !s.bare() || e.at() - s.at() > TAP_MS) continue;
			if (!(who instanceof Player pl) || pl.distanceTo(p) > NEAR) continue;
			// the block must still stand a moment later: a block broken also ends with -1
			pending.add(new Pending(s.pos(), s.name(), s.state(), e.at() + CHECK_MS));
		}
		long now = System.currentTimeMillis();
		for (var it = pending.iterator(); it.hasNext(); ) {
			Pending q = it.next();
			if (now < q.checkAt()) continue;
			it.remove();
			BlockState st = level.getBlockState(q.pos());
			if (st.isAir() || st.getBlock() != q.state().getBlock()) continue;
			tapped(mc, p, q.pos(), st, q.name());
		}
		return homeTick(mc, p, now);
	}

	private static void tapped(Minecraft mc, LocalPlayer p, BlockPos pos, BlockState st, String who) {
		Level level = p.level();
		Exchange.emit("tap", "action", "tapped", "block", AfkHelperClient.itemId(st.getBlock().asItem().getDefaultInstance()), "x", pos.getX(), "y", pos.getY(), "z", pos.getZ(), "by", who);
		if (st.getBlock() instanceof BedBlock) { setHome(level, p, pos, who); return; }
		if (st.getBlock() instanceof AbstractFurnaceBlock) { Ovens.tapped(level, pos, who); return; }
		if (fieldTap(level, pos, st)) { answer(Work.tapToggle("harvest", level, pos), "fields"); return; }
		if (st.is(BlockTags.LOGS)) { answer(Work.tapToggle("chop", level, pos), "chop"); return; }
		if (diggable(st)) { answer(Work.tapToggle("dig", level, pos), "dig"); return; }
		if (level.getBlockEntity(pos) instanceof Container) {
			Stash.learnTapped(level, pos, who);
			Follow.respond(Follow.YES);
			return;
		}
		Exchange.emit("tap", "action", "unknown", "x", pos.getX(), "y", pos.getY(), "z", pos.getZ());
		Follow.respond(Follow.NO);
	}

	private static void answer(boolean on, String job) {
		if (on) Pen.stop("tap");   // a job switched on with a tap ends the pen: his newest instruction wins
		Exchange.emit("tap", "action", on ? "on" : "off", "task", job);
		Follow.respond(on ? Follow.YES : Follow.OFF);
	}

	static boolean field(BlockState st) {
		return st.getBlock() instanceof CropBlock || st.getBlock() instanceof FarmlandBlock || st.getBlock() instanceof StemBlock
			|| st.is(Blocks.SUGAR_CANE) || st.is(Blocks.NETHER_WART) || st.is(Blocks.MELON) || st.is(Blocks.PUMPKIN);
	}

	/**
	 * A tap that means a field. A crop or sugar cane breaks at the first blow, bare-handed too, and then the server sends
	 * no tap at all (seen on the test server, 20 Oct 2026): so farmland, or dirt or sand with a crop, sugar cane or
	 * farmland beside or on it, is the field. Stone beside a field still means digging.
	 */
	static boolean fieldTap(Level level, BlockPos pos, BlockState st) {
		if (field(st)) return true;
		if (!(st.is(BlockTags.DIRT) || st.is(BlockTags.SAND))) return false;
		for (Direction d : Direction.values()) {
			BlockState n = level.getBlockState(pos.relative(d));
			if (n.getBlock() instanceof CropBlock || n.getBlock() instanceof FarmlandBlock || n.getBlock() instanceof StemBlock
					|| n.is(Blocks.SUGAR_CANE) || n.is(Blocks.NETHER_WART)) return true;
		}
		return false;
	}

	static boolean diggable(BlockState st) {
		String id = AfkHelperClient.itemId(st.getBlock().asItem().getDefaultInstance());
		return st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(BlockTags.BASE_STONE_NETHER) || id.endsWith("_ore") || id.equals("ancient_debris")
			|| id.equals("cobblestone") || id.equals("cobbled_deepslate") || Work.ground(st);
	}

	// ---------- the home ----------

	private static BlockPos homeBed;
	private static String homeFor;
	private static long homeSince;

	/** The bed is the home: walk to it, and set the server's home there by the profile's plain sethome. */
	private static void setHome(Level level, LocalPlayer p, BlockPos bed, String who) {
		Pen.stop("tap");
		homeBed = bed.immutable();
		homeFor = who;
		homeSince = System.currentTimeMillis();
		Exchange.emit("tap", "action", "home", "x", bed.getX(), "y", bed.getY(), "z", bed.getZ());
	}

	private static boolean homeTick(Minecraft mc, LocalPlayer p, long now) {
		if (homeBed == null) return false;
		if (now - homeSince > 30_000) { Exchange.emit("tap", "action", "home failed", "reason", "cannot get there"); homeBed = null; Follow.respond(Follow.NO); return false; }
		Vec3 at = Vec3.atBottomCenterOf(homeBed);
		if (p.position().distanceTo(at) > 2.5) {
			if (!Work.approach(mc, p, "home-bed", at, now)) { Exchange.emit("tap", "action", "home failed", "reason", "cannot get there"); homeBed = null; Follow.respond(Follow.NO); return false; }
			return true;
		}
		Follow.stand();
		String cmd = Profiles.cmd("sethome", null);
		if (cmd != null) AfkHelperClient.sendChat(cmd);
		saveBase(p.level(), homeBed, homeFor);
		Exchange.emit("tap", "action", "home set", "x", homeBed.getX(), "y", homeBed.getY(), "z", homeBed.getZ(), "command", cmd == null ? "" : cmd);
		homeBed = null;
		Follow.respond(Follow.YES);
		return true;
	}

	// ---------- the base on file ----------

	private static Path baseFile() { return Paths.get(AfkConfig.get().exchangeDir).resolve("base.json"); }

	record BaseEntry(String owner, BlockPos bed) {}

	/** The base on this server whose bed is nearest to this spot, within the radius and in this dimension, or null. */
	static BaseEntry baseAt(String dim, Vec3 at, double radius) {
		String prefix = Profiles.key() + "|";
		BaseEntry best = null;
		double bestD = radius;
		for (var e : readBase().entrySet()) {
			if (!e.getKey().startsWith(prefix) || !(e.getValue() instanceof JsonObject b) || !b.get("dim").getAsString().equals(dim)) continue;
			BlockPos bed = new BlockPos(b.get("x").getAsInt(), b.get("y").getAsInt(), b.get("z").getAsInt());
			double d = Vec3.atCenterOf(bed).distanceTo(at);
			if (d <= bestD) { bestD = d; best = new BaseEntry(e.getKey().substring(prefix.length()), bed); }
		}
		return best;
	}

	/** The bed of the player on this server, as the middle of his base. */
	static BlockPos base(String owner, String dim) {
		JsonObject b = readBase().get(Profiles.key() + "|" + owner.toLowerCase(Locale.ROOT)) instanceof JsonObject o ? o : null;
		if (b == null || !b.get("dim").getAsString().equals(dim)) return null;
		return new BlockPos(b.get("x").getAsInt(), b.get("y").getAsInt(), b.get("z").getAsInt());
	}

	private static JsonObject cache;
	private static long cacheAt;

	private static JsonObject readBase() {
		// read four times a second at most: the base is asked every tick
		long now = System.currentTimeMillis();
		if (cache != null && now - cacheAt < 250) return cache;
		cacheAt = now;
		cache = readBaseFile();
		return cache;
	}

	private static JsonObject readBaseFile() {
		try {
			Path f = baseFile();
			if (Files.exists(f)) return JsonParser.parseString(Files.readString(f)).getAsJsonObject();
		} catch (Exception ex) {
			Exchange.emit("error", "msg", "base.json: " + ex.getMessage());
		}
		return new JsonObject();
	}

	private static void saveBase(Level level, BlockPos bed, String owner) {
		JsonObject all = readBase();
		JsonObject b = new JsonObject();
		b.addProperty("dim", Work.dimOf(level));
		b.addProperty("x", bed.getX());
		b.addProperty("y", bed.getY());
		b.addProperty("z", bed.getZ());
		all.add(Profiles.key() + "|" + owner.toLowerCase(Locale.ROOT), b);
		write(all);
	}

	/** Forget the home of this player on this server; true when there was one. */
	static boolean forgetBase(String owner) {
		JsonObject all = readBase();
		boolean had = all.remove(Profiles.key() + "|" + owner.toLowerCase(Locale.ROOT)) != null;
		if (had) write(all);
		return had;
	}

	private static void write(JsonObject all) {
		try {
			Path f = baseFile(), tmp = f.resolveSibling("base.json.tmp");
			Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(all));
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			cache = null;
		} catch (Exception ex) {
			Exchange.emit("error", "msg", "writing base.json: " + ex.getMessage());
		}
	}

	static void stop() { homeBed = null; pending.clear(); starts.clear(); }
}
