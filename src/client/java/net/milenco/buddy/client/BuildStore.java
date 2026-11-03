package net.milenco.buddy.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the builder has to remember across a stop and a restart: the material chests you gave it (with what was in
 * them last time) and how far it got. File build.json in the exchange folder, so it lasts and the watcher can read
 * it. The world itself stays the truth for what is built: resuming compares schematic and world again, this file only
 * says which placement it was and where it stood.
 */
final class BuildStore {
	private BuildStore() {}

	static final class Chest {
		public int x, y, z;
		public String dim = "";
		/** Item id -> count, as it was the last time the chest was open. */
		public Map<String, Integer> contents = new LinkedHashMap<>();
		public long seen = 0;
		/** Found by the scan around you rather than marked: it leaves the list again when it is gone. */
		public boolean found = false;
		Chest() {}
		Chest(BlockPos p, String dim) { x = p.getX(); y = p.getY(); z = p.getZ(); this.dim = dim; }
		BlockPos pos() { return new BlockPos(x, y, z); }
		boolean at(BlockPos p, String d) { return x == p.getX() && y == p.getY() && z == p.getZ() && dim.equals(d); }
	}

	/** A scaffold block we put down ourselves: it goes again when the build is done. */
	static final class Spot {
		public int x, y, z;
		public String dim = "";
		Spot() {}
		Spot(BlockPos p, String dim) { x = p.getX(); y = p.getY(); z = p.getZ(); this.dim = dim; }
		BlockPos pos() { return new BlockPos(x, y, z); }
	}

	/** A block outside the build broken to reach a spot; it goes back once that block is placed. */
	static final class Temp {
		public int x, y, z;
		public String dim = "";
		public String block = "";
		Temp() {}
		Temp(BlockPos p, String dim, String block) { x = p.getX(); y = p.getY(); z = p.getZ(); this.dim = dim; this.block = block; }
		BlockPos pos() { return new BlockPos(x, y, z); }
	}

	static final class Data {
		public String placement = "";
		/** Lowest corner of that placement: the same file put down elsewhere is another build. */
		public String corner = "";
		public List<Chest> chests = new ArrayList<>();
		public List<Spot> scaffold = new ArrayList<>();
		public List<Temp> temp = new ArrayList<>();
		public int placed = 0;          // placed by the mod on this placement, over all runs
		public int done = 0;                // blocks that were right at the last scan
		public int total = 0;
		public int layer = 0;                // lowest layer that still had work
		public int spotX = 0;
		public int spotY = 0;
		public int spotZ = 0;
		public boolean running = false;
		/** Switched on and not switched off by the player: after a restart or a kick the alt carries on by itself. */
		public boolean wanted = false;
		public String stopReason = "";
		public long updated = 0;
		/**
		 * Per placement name, the items whose blocks the builder leaves out, as Litematica's material list hides an entry.
		 */
		public Map<String, List<String>> ignore = new LinkedHashMap<>();
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Data data = null;
	private static long mtime = -1;

	private static Path file() { return Path.of(AfkConfig.get().exchangeDir, "build.json"); }

	/** Reads the file again when someone else (Claude, the watcher) changed it in between. */
	static Data get() {
		try {
			Path f = file();
			long m = Files.exists(f) ? Files.getLastModifiedTime(f).toMillis() : 0;
			if (data == null || m != mtime) {
				Data d = m == 0 ? null : GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Data.class);
				data = d == null ? new Data() : d;
				if (data.chests == null) data.chests = new ArrayList<>();
				if (data.corner == null) data.corner = "";
				if (data.scaffold == null) data.scaffold = new ArrayList<>();
				if (data.temp == null) data.temp = new ArrayList<>();
				if (data.ignore == null) data.ignore = new LinkedHashMap<>();
				for (Temp t : data.temp) if (t.dim == null) t.dim = "";
				for (Spot sp : data.scaffold) if (sp.dim == null) sp.dim = "";
				for (Chest c : data.chests) { if (c.contents == null) c.contents = new LinkedHashMap<>(); if (c.dim == null) c.dim = ""; }
				mtime = m;
			}
		} catch (Exception e) {
			if (data == null) data = new Data();
		}
		return data;
	}

	/** Via a temporary file and a move: the watcher never reads half a file. */
	static void save() {
		Data d = get();
		d.updated = System.currentTimeMillis();
		try {
			Path f = file(), tmp = f.resolveSibling("build.json.tmp");
			Files.createDirectories(f.getParent());
			Files.writeString(tmp, GSON.toJson(d), StandardCharsets.UTF_8);
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			mtime = Files.getLastModifiedTime(f).toMillis();
		} catch (Exception e) {
			Exchange.emit("error", "where", "build.json", "notice", String.valueOf(e));
		}
	}

	/** The items left out of this placement (their ids). */
	static java.util.Set<String> ignored(String placement) {
		List<String> l = get().ignore.get(placement);
		return l == null ? java.util.Set.of() : new java.util.LinkedHashSet<>(l);
	}

	/** One item in or out of a placement's ignore list; saved at once. */
	static void ignore(String placement, String item, boolean on) {
		Data d = get();
		List<String> l = d.ignore.computeIfAbsent(placement, k -> new ArrayList<>());
		l.remove(item);
		if (on) l.add(item);
		if (l.isEmpty()) d.ignore.remove(placement);
		save();
	}

	/** Scaffold blocks in this world. */
	static java.util.Set<BlockPos> scaffold(String dim) {
		java.util.Set<BlockPos> out = new java.util.HashSet<>();
		for (Spot sp : get().scaffold) if (sp.dim.equals(dim)) out.add(sp.pos());
		return out;
	}

	static void addScaffold(BlockPos p, String dim) {
		for (Spot sp : get().scaffold) if (sp.dim.equals(dim) && sp.pos().equals(p)) return;
		get().scaffold.add(new Spot(p, dim));
		save();
	}

	static void removeScaffold(BlockPos p, String dim) {
		if (get().scaffold.removeIf(sp -> sp.dim.equals(dim) && sp.pos().equals(p))) save();
	}

	static Chest chest(BlockPos p, String dim) {
		for (Chest c : get().chests) if (c.at(p, dim)) return c;
		return null;
	}

	/** A chest the scan found; the caller saves. */
	static void addFound(BlockPos p, String dim) {
		Chest c = new Chest(p, dim);
		c.found = true;
		get().chests.add(c);
	}

	/** Drops found chests in this world that match; marked ones stay. The caller saves. */
	static int dropFound(String dim, java.util.function.Predicate<Chest> gone) {
		List<Chest> list = get().chests;
		int before = list.size();
		list.removeIf(c -> c.found && c.dim.equals(dim) && gone.test(c));
		return before - list.size();
	}

	/** Add the chest, or remove it if it was already on the list. True if it is on the list now. */
	static boolean toggle(BlockPos p, String dim) {
		Data d = get();
		Chest c = chest(p, dim);
		boolean added = c == null;
		if (added) d.chests.add(new Chest(p, dim)); else d.chests.remove(c);
		save();
		return added;
	}
}
