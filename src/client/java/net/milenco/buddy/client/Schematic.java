package net.milenco.buddy.client;

import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The bridge to Litematica, through reflection. Litematica has no API of its own, and a compile dependency would tie
 * both builds to one Litematica version per Minecraft version. Litematica's own class and method names are never
 * remapped, so these lookups work the same in 26.2 and in 1.21.11; what they return (the schematic world, block
 * positions) are ordinary Minecraft objects. Without Litematica everything here simply says "not available".
 */
final class Schematic {
	private Schematic() {}

	/** One enabled placement: its name and the box around all its sub-regions. */
	record Placement(String name, BlockPos min, BlockPos max) {
		boolean contains(BlockPos p, int margin) {
			return p.getX() >= min.getX() - margin && p.getX() <= max.getX() + margin && p.getY() >= min.getY() - margin
				&& p.getY() <= max.getY() + margin && p.getZ() >= min.getZ() - margin && p.getZ() <= max.getZ() + margin;
		}
		double distance(BlockPos p) {
			int dx = Math.max(0, Math.max(min.getX() - p.getX(), p.getX() - max.getX()));
			int dy = Math.max(0, Math.max(min.getY() - p.getY(), p.getY() - max.getY()));
			int dz = Math.max(0, Math.max(min.getZ() - p.getZ(), p.getZ() - max.getZ()));
			return Math.sqrt(dx * dx + dy * dy + dz * dz);
		}
	}

	/** Larger boxes are not searched block by block; the box itself then stands in for its blocks. */
	private static final long MAX_SEARCH_VOLUME = 2_000_000;

	/**
	 * Distance from p to the nearest block this placement puts down (not its air). Whatever is nearer than
	 * {@code within} is not searched past. Falls back to the box when none of its blocks is loaded.
	 */
	static double blockDistance(Placement pl, BlockPos p, double within) {
		Level schem = world();
		long volume = (long) (pl.max().getX() - pl.min().getX() + 1) * (pl.max().getY() - pl.min().getY() + 1) * (pl.max().getZ() - pl.min().getZ() + 1);
		if (schem == null || volume > MAX_SEARCH_VOLUME) return pl.distance(p);
		double best = Double.MAX_VALUE;
		for (BlockPos q : BlockPos.betweenClosed(pl.min(), pl.max())) {
			double d = q.distSqr(p);
			if (d < best && !schem.getBlockState(q).isAir()) {
				best = d;
				if (best <= within * within) break;
			}
		}
		return best == Double.MAX_VALUE ? pl.distance(p) : Math.sqrt(best);
	}

	private static boolean tried = false, ok = false;
	private static Method schematicWorld, placementManager, allPlacements, selectedPlacement, enabled, name, enclosingBox, pos1, pos2;
	private static Method layerRange, withinRange;   // optional: an older malilib keeps LayerRange elsewhere
	private static Method origin, rotation, mirror, schematicFile, locked, setOrigin;   // optional: for the panel's list and moving the start block
	private static Method subRegionBoxes;   // optional: an older Litematica may lack it
	private static Object placementEnabled;
	private static String lastSkip = "";

	static boolean available() { init(); return ok; }

	private static void init() {
		if (tried) return;
		tried = true;
		if (!FabricLoader.getInstance().isModLoaded("litematica")) return;
		try {
			schematicWorld = Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler").getMethod("getSchematicWorld");
			Class<?> data = Class.forName("fi.dy.masa.litematica.data.DataManager");
			placementManager = data.getMethod("getSchematicPlacementManager");
			Class<?> manager = Class.forName("fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager");
			allPlacements = manager.getMethod("getAllSchematicsPlacements");
			selectedPlacement = manager.getMethod("getSelectedSchematicPlacement");
			Class<?> placement = Class.forName("fi.dy.masa.litematica.schematic.placement.SchematicPlacement");
			enabled = placement.getMethod("isEnabled");
			name = placement.getMethod("getName");
			enclosingBox = placement.getMethod("getEclosingBox");   // sic: that is how Litematica spells it
			Class<?> box = Class.forName("fi.dy.masa.litematica.selection.Box");
			pos1 = box.getMethod("getPos1");
			pos2 = box.getMethod("getPos2");
			ok = true;
		} catch (ReflectiveOperationException | LinkageError e) {
			Exchange.emit("build", "action", "litematica not usable", "notice", String.valueOf(e));
			return;
		}
		try {
			Class<?> required = Class.forName("fi.dy.masa.litematica.schematic.placement.SubRegionPlacement$RequiredEnabled");
			subRegionBoxes = Class.forName("fi.dy.masa.litematica.schematic.placement.SchematicPlacement").getMethod("getSubRegionBoxes", required);
			placementEnabled = required.getField("PLACEMENT_ENABLED").get(null);
		} catch (ReflectiveOperationException | LinkageError e) {
			subRegionBoxes = null; placementEnabled = null;
		}
		try {
			Class<?> placement = Class.forName("fi.dy.masa.litematica.schematic.placement.SchematicPlacement");
			origin = placement.getMethod("getOrigin");
			rotation = placement.getMethod("getRotation");
			mirror = placement.getMethod("getMirror");
			schematicFile = placement.getMethod("getSchematicFile");
			locked = placement.getMethod("isLocked");
			for (Method m : placement.getMethods()) if (m.getName().equals("setOrigin") && m.getParameterCount() == 2) setOrigin = m;
		} catch (ReflectiveOperationException | LinkageError e) {
			origin = null;
		}
		try {
			layerRange = Class.forName("fi.dy.masa.litematica.data.DataManager").getMethod("getRenderLayerRange");
			withinRange = layerRange.getReturnType().getMethod("isPositionWithinRange", int.class, int.class, int.class);
		} catch (ReflectiveOperationException | LinkageError e) {
			layerRange = null; withinRange = null;
		}
	}

	/** The world Litematica keeps with the blocks of all placements; air wherever no schematic is. Null without Litematica. */
	static Level world() {
		if (!available()) return null;
		try { return (Level) schematicWorld.invoke(null); } catch (ReflectiveOperationException | ClassCastException e) { return null; }
	}

	/** All enabled placements with a box. */
	static List<Placement> placements() {
		List<Placement> out = new ArrayList<>();
		if (!available()) return out;
		try {
			Object manager = placementManager.invoke(null);
			for (Object pl : (List<?>) allPlacements.invoke(manager)) {
				Placement p = read(pl);
				if (p != null) out.add(p);
			}
		} catch (ReflectiveOperationException | ClassCastException e) {
			Exchange.emit("build", "action", "reading the placements failed", "notice", String.valueOf(e));
		}
		return out;
	}

	/** What Litematica itself holds, raw, for the event when no placement is usable: count, selected, per placement enabled and box. */
	static String describe() {
		if (!available()) return "not available";
		try {
			Object manager = placementManager.invoke(null);
			List<?> all = (List<?>) allPlacements.invoke(manager);
			StringBuilder sb = new StringBuilder("count=" + all.size() + " selected=" + (selectedPlacement.invoke(manager) != null));
			for (Object pl : all) {
				Object box = enclosingBox.invoke(pl);
				sb.append(" | ").append(name.invoke(pl)).append(" on=").append(enabled.invoke(pl))
					.append(" box=").append(box == null ? "null" : pos1.invoke(box) + ".." + pos2.invoke(box))
					.append(" regions=").append(subRegionBoxes == null ? "?" : ((Map<?, ?>) subRegionBoxes.invoke(pl, placementEnabled)).size());
			}
			return sb.toString();
		} catch (ReflectiveOperationException | RuntimeException e) {
			return String.valueOf(e);
		}
	}

	private static Placement read(Object pl) throws ReflectiveOperationException {
		if (pl == null) return null;
		if (!(Boolean) enabled.invoke(pl)) return skip(pl, "switched off");
		BlockPos[] c = corners(pl);
		if (c == null) return skip(pl, subRegionBoxes != null ? "no regions on" : "no box");
		return new Placement(String.valueOf(name.invoke(pl)), c[0], c[1]);
	}

	/**
	 * The box around a placement, lowest corner first, or null. Litematica only keeps its enclosing box while "render
	 * enclosing box" is on, which it is not by default, so a placement on screen came back as no placement at all: the box
	 * is therefore made from the enabled sub-regions.
	 */
	private static BlockPos[] corners(Object pl) throws ReflectiveOperationException {
		Iterable<?> boxes = subRegionBoxes != null ? ((Map<?, ?>) subRegionBoxes.invoke(pl, placementEnabled)).values()
			: java.util.Collections.singletonList(enclosingBox.invoke(pl));
		BlockPos min = null, max = null;
		for (Object box : boxes) {
			if (box == null) continue;
			BlockPos a = (BlockPos) pos1.invoke(box), b = (BlockPos) pos2.invoke(box);
			if (a == null || b == null) continue;
			BlockPos lo = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
			BlockPos hi = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
			min = min == null ? lo : new BlockPos(Math.min(min.getX(), lo.getX()), Math.min(min.getY(), lo.getY()), Math.min(min.getZ(), lo.getZ()));
			max = max == null ? hi : new BlockPos(Math.max(max.getX(), hi.getX()), Math.max(max.getY(), hi.getY()), Math.max(max.getZ(), hi.getZ()));
		}
		return min == null ? null : new BlockPos[] { min, max };
	}

	/**
	 * Every placement as the panel shows it, in the settings' drop-down, so that one sees where its start block is and
	 * can set it: its name and file, its start block (Litematica's origin: the block "place" puts at the spot asked
	 * for, which the schematic turns and mirrors around), the turn, the mirror, whether it is on and locked, and the
	 * box it fills. Read once a second for state.json, so it logs nothing, where {@link #placements()} reports a
	 * placement it skips.
	 */
	static List<Map<String, Object>> list() {
		List<Map<String, Object>> out = new ArrayList<>();
		if (!available()) return out;
		try {
			Object manager = placementManager.invoke(null), selected = selectedPlacement.invoke(manager);
			for (Object pl : (List<?>) allPlacements.invoke(manager)) {
				Map<String, Object> m = new java.util.LinkedHashMap<>();
				m.put("name", String.valueOf(name.invoke(pl)));
				if (origin != null) {
					Object f = schematicFile.invoke(pl);
					if (f instanceof java.nio.file.Path path && path.getFileName() != null) m.put("file", path.getFileName().toString());
					if (origin.invoke(pl) instanceof BlockPos o) m.put("origin", List.of(o.getX(), o.getY(), o.getZ()));
					m.put("rotate", switch (String.valueOf(rotation.invoke(pl))) { case "CLOCKWISE_90" -> 90; case "CLOCKWISE_180" -> 180; case "COUNTERCLOCKWISE_90" -> 270; default -> 0; });
					m.put("mirror", switch (String.valueOf(mirror.invoke(pl))) { case "LEFT_RIGHT" -> "left-right"; case "FRONT_BACK" -> "front-back"; default -> ""; });
					m.put("locked", locked.invoke(pl));
				}
				m.put("on", enabled.invoke(pl));
				m.put("selected", pl == selected);
				java.util.Set<String> ign = BuildStore.ignored(String.valueOf(name.invoke(pl)));
				if (!ign.isEmpty()) m.put("ignored", new ArrayList<>(ign));
				BlockPos[] c = corners(pl);
				if (c != null) { m.put("from", List.of(c[0].getX(), c[0].getY(), c[0].getZ())); m.put("until", List.of(c[1].getX(), c[1].getY(), c[1].getZ())); }
				out.add(m);
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			// a half-made placement while Litematica loads one: the next second reads it again
		}
		return out;
	}

	/** Logs once per cause why a placement is skipped, so "no placement" can be explained from events.jsonl. */
	private static Placement skip(Object pl, String why) throws ReflectiveOperationException {
		String key = name.invoke(pl) + ":" + why;
		if (!key.equals(lastSkip)) {
			lastSkip = key;
			Exchange.emit("build", "action", "placement skipped", "name", String.valueOf(name.invoke(pl)), "reason", why);
		}
		return null;
	}

	// ---------- placements from outside ----------

	/** Where Litematica keeps its schematic files; the Telegram bridge saves attachments here. */
	static java.nio.file.Path folder() { return FabricLoader.getInstance().getGameDir().resolve("schematics"); }

	/**
	 * A placement made without Litematica's screens, for the buddy that has no screen: "place" puts a schematic
	 * file from the schematics folder down with its origin at x y z (or where you stand, "here"), "move" puts a placement's
	 * origin somewhere else, "materials" writes its material list, "ignore" leaves an item's blocks out of it, "remove"
	 * removes one placement by its name or every placement, "list" only reports. The answer is a placement event; the bridge shows it in Telegram.
	 */
	static void command(JsonObject o, LocalPlayer p) {
		String action = o.has("action") ? o.get("action").getAsString() : "list";
		if (!available()) { Exchange.emit("placement", "action", "failed", "reason", "litematica not available"); return; }
		if (p == null) { Exchange.emit("placement", "action", "failed", "reason", "not in a world"); return; }
		try {
			Object manager = placementManager.invoke(null);
			Class<?> placementClass = Class.forName("fi.dy.masa.litematica.schematic.placement.SchematicPlacement");
			switch (action) {
				case "place" -> {
					String file = o.get("file").getAsString();
					// a name from outside never leaves the schematics folder
					if (file.contains("/") || file.contains("\\") || file.contains("..")) { Exchange.emit("placement", "action", "failed", "reason", "invalid file name", "file", file); return; }
					if (!java.nio.file.Files.isRegularFile(folder().resolve(file))) { Exchange.emit("placement", "action", "failed", "reason", "file not found", "file", file); return; }
					BlockPos origin = o.has("here") && o.get("here").getAsBoolean() ? p.blockPosition()
						: new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
					Class<?> schematicClass = Class.forName("fi.dy.masa.litematica.schematic.LitematicaSchematic");
					Object schematic = null;
					// an older Litematica takes the folder as a File, a newer one as a Path
					for (Method m : schematicClass.getMethods()) {
						if (!m.getName().equals("createFromFile") || m.getParameterCount() != 2) continue;
						schematic = m.invoke(null, m.getParameterTypes()[0] == java.io.File.class ? folder().toFile() : folder(), file);
						break;
					}
					if (schematic == null) { Exchange.emit("placement", "action", "failed", "reason", "file not readable", "file", file); return; }
					String placementName = file.replaceFirst("\\.[A-Za-z]+$", "");
					Object placement = placementClass.getMethod("createFor", schematicClass, BlockPos.class, String.class, boolean.class, boolean.class)
						.invoke(null, schematic, origin, placementName, true, true);
					manager.getClass().getMethod("addSchematicPlacement", placementClass, boolean.class).invoke(manager, placement, false);
					manager.getClass().getMethod("setSelectedSchematicPlacement", placementClass).invoke(manager, placement);
					// turned and mirrored as asked (the web panel): "rotate" 90, 180 or 270 degrees clockwise, "mirror"
					// left-right or front-back; the feedback only speaks for a locked placement, which a new one is not
					int turn = o.has("rotate") ? Math.floorMod(o.get("rotate").getAsInt(), 360) : 0;
					net.minecraft.world.level.block.Rotation rotation = switch (turn) {
						case 90 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_90;
						case 180 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_180;
						case 270 -> net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90;
						default -> net.minecraft.world.level.block.Rotation.NONE;
					};
					String mirrorName = o.has("mirror") ? o.get("mirror").getAsString() : "";
					net.minecraft.world.level.block.Mirror mirror = mirrorName.equals("left-right") ? net.minecraft.world.level.block.Mirror.LEFT_RIGHT
						: mirrorName.equals("front-back") ? net.minecraft.world.level.block.Mirror.FRONT_BACK : net.minecraft.world.level.block.Mirror.NONE;
					for (Method m : placementClass.getMethods()) {
						if (m.getParameterCount() != 2) continue;
						if (m.getName().equals("setRotation") && rotation != net.minecraft.world.level.block.Rotation.NONE) m.invoke(placement, rotation, null);
						if (m.getName().equals("setMirror") && mirror != net.minecraft.world.level.block.Mirror.NONE) m.invoke(placement, mirror, null);
					}
					persist();
					Placement made = read(placement);
					Exchange.emit("placement", "action", "set", "name", placementName, "file", file, "origin", origin.toShortString(), "rotate", turn, "mirror", mirrorName,
						"from", made == null ? "?" : made.min().toShortString(), "until", made == null ? "?" : made.max().toShortString());
				}
				case "move" -> {
					// the start block somewhere else, without placing the schematic anew: "name" (else the selected
					// placement, else the only one) and "here" or x y z; the schematic keeps its turn and mirror and
					// moves along
					Object pl = pick(manager, o);
					if (pl == null) return;
					if (setOrigin == null) { Exchange.emit("placement", "action", "failed", "reason", "this Litematica cannot move a placement"); return; }
					if ((Boolean) locked.invoke(pl)) { Exchange.emit("placement", "action", "failed", "reason", "placement locked", "name", String.valueOf(name.invoke(pl))); return; }
					BlockPos to = o.has("here") && o.get("here").getAsBoolean() ? p.blockPosition()
						: new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
					// the second argument only speaks for a locked placement, which this one is not
					setOrigin.invoke(pl, to, null);
					persist();
					BlockPos now = (BlockPos) origin.invoke(pl);
					BlockPos[] c = corners(pl);
					Exchange.emit("placement", "action", "moved", "name", String.valueOf(name.invoke(pl)), "origin", now.toShortString(),
						"from", c == null ? "?" : c[0].toShortString(), "until", c == null ? "?" : c[1].toShortString());
				}
				case "materials" -> {
					Object pl = pick(manager, o);
					if (pl != null) materials(pl, p);
				}
				case "ignore" -> {
					// an item in or out of the placement's ignore list (the builder leaves those blocks out), then the list anew
					Object pl = pick(manager, o);
					if (pl == null) return;
					String item = o.has("item") ? o.get("item").getAsString() : "";
					if (net.minecraft.resources.Identifier.tryParse(item) == null) { Exchange.emit("placement", "action", "failed", "reason", "no item", "item", item); return; }
					boolean on = !o.has("on") || o.get("on").getAsBoolean();
					BuildStore.ignore(String.valueOf(name.invoke(pl)), item, on);
					Exchange.emit("placement", "action", on ? "ignored" : "not ignored", "name", String.valueOf(name.invoke(pl)), "item", item);
					materials(pl, p);
				}
				case "remove" -> {
					// one placement by its name, or every one
					if (o.has("name")) {
						Object pl = pick(manager, o);
						if (pl == null) return;
						manager.getClass().getMethod("removeSchematicPlacement", placementClass).invoke(manager, pl);
						persist();
						Exchange.emit("placement", "action", "gone", "name", String.valueOf(name.invoke(pl)), "count", 1);
						break;
					}
					List<?> all = new ArrayList<>((List<?>) allPlacements.invoke(manager));
					Method remove = manager.getClass().getMethod("removeSchematicPlacement", placementClass);
					for (Object pl : all) remove.invoke(manager, pl);
					persist();
					Exchange.emit("placement", "action", "gone", "count", all.size());
				}
				default -> {
					StringBuilder sb = new StringBuilder();
					for (Placement pl : placements()) sb.append(sb.length() > 0 ? " | " : "").append(pl.name()).append(" ").append(pl.min().toShortString()).append(" .. ").append(pl.max().toShortString());
					Exchange.emit("placement", "action", "list", "count", ((List<?>) allPlacements.invoke(manager)).size(), "placements", sb.toString());
				}
			}
			Details.soon();   // the panel's Build card shows the new start block at once
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			Throwable cause = e instanceof java.lang.reflect.InvocationTargetException t && t.getCause() != null ? t.getCause() : e;
			Exchange.emit("placement", "action", "failed", "reason", String.valueOf(cause));
		}
	}

	/**
	 * Within the layers Litematica shows? Whoever limits the render layers there (one layer at a time) wants the
	 * builder to stay within them too. Without that option everything counts.
	 */
	interface Layers { boolean contains(int x, int y, int z); }
	/** The placement a command names ("name"), else the selected one, else the only one; null after a failed event. */
	private static Object pick(Object manager, JsonObject o) throws ReflectiveOperationException {
		List<?> all = (List<?>) allPlacements.invoke(manager);
		String want = o.has("name") ? o.get("name").getAsString() : "";
		Object pl = null;
		for (Object q : all) if (!want.isEmpty() && want.equals(String.valueOf(name.invoke(q)))) { pl = q; break; }
		if (pl == null && want.isEmpty()) pl = all.size() == 1 ? all.get(0) : selectedPlacement.invoke(manager);
		if (pl == null) Exchange.emit("placement", "action", "failed", "reason", want.isEmpty() ? "no placement" : "placement not found", "name", want);
		return pl;
	}

	/**
	 * A placement's material list, as Litematica's (the panel shows it in a modal), into
	 * materials.json: per item what the whole schematic takes (Litematica's own count from the file, so also where the
	 * schematic is not loaded), how much of it is still missing in the world where it is loaded, what they carry, what
	 * the builder's chests held when last seen, and whether the item is ignored.
	 */
	private static void materials(Object pl, LocalPlayer p) throws ReflectiveOperationException {
		String plName = String.valueOf(name.invoke(pl));
		Object schematic = pl.getClass().getMethod("getSchematic").invoke(pl);
		Class<?> utils = Class.forName("fi.dy.masa.litematica.materials.MaterialListUtils");
		List<?> entries = (List<?>) utils.getMethod("createMaterialListFor", schematic.getClass()).invoke(null, schematic);
		Map<net.minecraft.world.item.Item, int[]> rows = new java.util.LinkedHashMap<>();   // total, right in the world
		for (Object e : entries) {
			net.minecraft.world.item.ItemStack st = (net.minecraft.world.item.ItemStack) e.getClass().getMethod("getStack").invoke(e);
			int total = (Integer) e.getClass().getMethod("getCountTotal").invoke(e);
			if (!st.isEmpty() && total > 0) rows.computeIfAbsent(st.getItem(), k -> new int[2])[0] += total;
		}
		// what already stands right, block by block where both worlds are loaded, in Litematica's items per block
		Object cache = null; Method forState = null, multiple = null, items = null;
		try {
			Class<?> mc = Class.forName("fi.dy.masa.litematica.materials.MaterialCache");
			cache = mc.getMethod("getInstance").invoke(null);
			forState = mc.getMethod("getRequiredBuildItemForState", net.minecraft.world.level.block.state.BlockState.class);
			multiple = mc.getMethod("requiresMultipleItems", net.minecraft.world.level.block.state.BlockState.class);
			items = mc.getMethod("getItems", net.minecraft.world.level.block.state.BlockState.class);
		} catch (ReflectiveOperationException | LinkageError e) { cache = null; }
		Level schem = world(), here = p.level();
		BlockPos[] c = corners(pl);
		int unloaded = 0;
		if (schem != null && c != null) {
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
			for (int y = c[0].getY(); y <= c[1].getY(); y++) for (int z = c[0].getZ(); z <= c[1].getZ(); z++) for (int x = c[0].getX(); x <= c[1].getX(); x++) {
				m.set(x, y, z);
				if (!schem.isLoaded(m) || !here.isLoaded(m)) { unloaded++; continue; }
				net.minecraft.world.level.block.state.BlockState want = schem.getBlockState(m);
				if (want.isAir() || !Builder.matches(want, here.getBlockState(m))) continue;
				List<net.minecraft.world.item.ItemStack> got = new ArrayList<>();
				if (cache == null) got.add(new net.minecraft.world.item.ItemStack(Builder.itemFor(want)));
				else if ((Boolean) multiple.invoke(cache, want)) for (Object s : (List<?>) items.invoke(cache, want)) got.add((net.minecraft.world.item.ItemStack) s);
				else got.add((net.minecraft.world.item.ItemStack) forState.invoke(cache, want));
				for (net.minecraft.world.item.ItemStack st : got) { int[] r = st.isEmpty() ? null : rows.get(st.getItem()); if (r != null) r[1] += st.getCount(); }
			}
		}
		String dim = here.dimension().identifier().toString();
		java.util.Set<String> ignored = BuildStore.ignored(plName);
		List<Map<String, Object>> out = new ArrayList<>();
		for (var en : rows.entrySet()) {
			String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(en.getKey()).toString();
			int inChests = 0;
			for (BuildStore.Chest ch : BuildStore.get().chests) if (ch.dim.equals(dim)) inChests += ch.contents.getOrDefault(id, 0);
			Map<String, Object> r = new java.util.LinkedHashMap<>();
			r.put("item", id);
			r.put("total", en.getValue()[0]);
			r.put("missing", Math.max(0, en.getValue()[0] - en.getValue()[1]));
			r.put("have", Lumberjack.countInInventory(p, en.getKey()));
			r.put("chests", inChests);
			r.put("ignored", ignored.contains(id));
			out.add(r);
		}
		Map<String, Object> file = new java.util.LinkedHashMap<>();
		file.put("t", System.currentTimeMillis());
		file.put("name", plName);
		file.put("unloaded", unloaded);
		file.put("rows", out);
		try {
			java.nio.file.Path f = java.nio.file.Path.of(AfkConfig.get().exchangeDir, "materials.json"), tmp = f.resolveSibling("materials.json.tmp");
			java.nio.file.Files.writeString(tmp, new com.google.gson.Gson().toJson(file), java.nio.charset.StandardCharsets.UTF_8);
			java.nio.file.Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (java.io.IOException e) {
			Exchange.emit("error", "where", "materials.json", "notice", String.valueOf(e));
			return;
		}
		Exchange.emit("placement", "action", "materials", "name", plName, "items", out.size(), "unloaded", unloaded);
	}

	/**
	 * Litematica writes its placements to disk when you leave the world, and the alt is stopped by killing the game.
	 * A placement made from Telegram was gone after the restart and the removed one of the floor above was back:
	 * the builder resumed on that one, found it done and went home.
	 */
	private static void persist() {
		try { Class.forName("fi.dy.masa.litematica.data.DataManager").getMethod("save").invoke(null); }
		catch (ReflectiveOperationException | RuntimeException | LinkageError e) { Exchange.emit("error", "where", "saving the placement", "notice", String.valueOf(e)); }
	}

	private static final Layers ALL = (x, y, z) -> true;

	/** Fetched once per scan: the range object stays the same, only asking it goes per block. */
	static Layers layers() {
		if (withinRange == null) return ALL;
		try {
			Object range = layerRange.invoke(null);
			if (range == null) return ALL;
			return (x, y, z) -> {
				try { return (Boolean) withinRange.invoke(range, x, y, z); } catch (ReflectiveOperationException e) { return true; }
			};
		} catch (ReflectiveOperationException e) { return ALL; }
	}
}
