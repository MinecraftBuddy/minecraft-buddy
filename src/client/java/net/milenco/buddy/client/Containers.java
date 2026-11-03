package net.milenco.buddy.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What lay in every container he had open, by its block, so that the panel can show a chest's contents. The server
 * sends a chest's items only to who has it open, and then into the open menu, never into the block, so the 3D view
 * cannot read them from the world. This keeps the last look per block in
 * {@code containers.json} in the exchange folder (server, dimension, place, the items by slot, when), which the panel
 * shows when the mouse is on that block. A menu counts as the block's only when it opened within two seconds of a click
 * on that block (his own, the panel's, or a job's), so a server menu typed while he looks at a chest is not taken for
 * it. A double chest is kept under both halves. An item with more than its kind (a name, enchantments, what a shulker
 * box holds) carries its components as the game writes them, a fourth element of its slot, so the panel can give the
 * container's data with its items to copy, for instance to set the same thing up on a test server.
 */
public final class Containers {
	private Containers() {}

	private static final int MAX = 3000;
	private static final long OPEN_WITHIN_MS = 2000, SAVE_AFTER_MS = 1500;
	private static final Gson GSON = new Gson();
	// key: server|dim|x,y,z
	private static final Map<String, JsonObject> seen = new LinkedHashMap<>(64, 0.75f, true);
	private static boolean loaded = false;
	private static BlockPos usedPos = null;
	private static long usedAt = 0, dirtyAt = 0;
	private static int menuId = -1, lastHash = 0;
	private static BlockPos menuPos = null;

	/** A click on a block (GameModeMixin): the next container menu that opens soon after is that block's. */
	public static void used(BlockPos pos) {
		usedPos = pos == null ? null : pos.immutable();
		usedAt = System.currentTimeMillis();
	}

	static void tick(Minecraft mc, LocalPlayer p) {
		long now = System.currentTimeMillis();
		if (dirtyAt > 0 && now - dirtyAt > SAVE_AFTER_MS) save();
		AbstractContainerMenu m = p.containerMenu;
		if (m == null || m == p.inventoryMenu || mc.level == null) { menuId = -1; menuPos = null; return; }
		if (m.containerId != menuId) {
			menuId = m.containerId;
			menuPos = null;
			lastHash = 0;
			if (usedPos != null && now - usedAt < OPEN_WITHIN_MS && mc.level.getBlockEntity(usedPos) instanceof Container) menuPos = usedPos;
			usedPos = null;
		}
		if (menuPos == null) return;
		List<int[]> idx = new ArrayList<>();
		List<String> ids = new ArrayList<>();
		List<ItemStack> stacks = new ArrayList<>();
		int size = 0, hash = 1;
		for (Slot s : m.slots) {
			if (s.container == p.getInventory()) continue;
			int n = size++;
			ItemStack st = s.getItem();
			if (st.isEmpty()) continue;
			String id = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
			idx.add(new int[]{n, st.getCount()});
			ids.add(id);
			stacks.add(st);
			// the components by their hash each tick; written out only when something changed
			hash = 31 * hash + (n * 977 + id.hashCode()) * 31 + st.getCount() + st.getComponentsPatch().hashCode();
		}
		hash = 31 * hash + size;
		if (hash == lastHash || size == 0) return;
		lastHash = hash;
		load();
		JsonArray slots = new JsonArray();
		for (int i = 0; i < idx.size(); i++) {
			JsonArray a = new JsonArray();
			a.add(idx.get(i)[0]); a.add(ids.get(i)); a.add(idx.get(i)[1]);
			String comp = components(mc, stacks.get(i));
			if (comp != null) a.add(comp);
			slots.add(a);
		}
		String dim = Work.dimOf(mc.level), server = Profiles.key();
		BlockState st = mc.level.getBlockState(menuPos);
		List<BlockPos> at = new ArrayList<>(List.of(menuPos));
		if (st.getBlock() instanceof ChestBlock && st.hasProperty(ChestBlock.TYPE) && st.getValue(ChestBlock.TYPE) != ChestType.SINGLE)
			at.add(menuPos.relative(ChestBlock.getConnectedDirection(st)));
		for (BlockPos q : at) {
			JsonObject o = new JsonObject();
			o.addProperty("server", server);
			o.addProperty("dim", dim);
			o.addProperty("x", q.getX());
			o.addProperty("y", q.getY());
			o.addProperty("z", q.getZ());
			o.addProperty("block", BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(q).getBlock()).toString());
			o.addProperty("size", size);
			o.addProperty("seen", now);
			o.add("slots", slots);
			seen.put(server + "|" + dim + "|" + q.getX() + "," + q.getY() + "," + q.getZ(), o);
		}
		while (seen.size() > MAX) seen.remove(seen.keySet().iterator().next());
		dirtyAt = now;
	}

	/** What the item carries besides its kind, as SNBT in the form of an item's {@code components} (null: nothing). */
	private static String components(Minecraft mc, ItemStack st) {
		var patch = st.getComponentsPatch();
		if (patch.isEmpty() || mc.level == null) return null;
		try {
			var tag = net.minecraft.core.component.DataComponentPatch.CODEC
				.encodeStart(mc.level.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), patch).result().orElse(null);
			if (tag == null) return null;
			String t = tag.toString();
			// a shulker box full of books can be long: past this it is left out rather than cut into something invalid
			return t.length() > 50_000 ? null : t;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static Path file() { return Paths.get(AfkConfig.get().exchangeDir).resolve("containers.json"); }

	private static void load() {
		if (loaded) return;
		loaded = true;
		try {
			Path f = file();
			if (!Files.exists(f)) return;
			JsonElement list = JsonParser.parseString(Files.readString(f)).getAsJsonObject().get("containers");
			if (list == null) return;
			for (JsonElement el : list.getAsJsonArray()) {
				JsonObject o = el.getAsJsonObject();
				seen.put(o.get("server").getAsString() + "|" + o.get("dim").getAsString() + "|" + o.get("x").getAsInt() + "," + o.get("y").getAsInt() + "," + o.get("z").getAsInt(), o);
			}
		} catch (Exception e) {
			Exchange.emit("error", "msg", "containers.json: " + e.getMessage());
		}
	}

	private static void save() {
		dirtyAt = 0;
		JsonArray a = new JsonArray();
		for (JsonObject o : seen.values()) a.add(o);
		JsonObject root = new JsonObject();
		root.add("containers", a);
		try {
			Path f = file(), tmp = f.resolveSibling("containers.json.tmp");
			Files.writeString(tmp, GSON.toJson(root));
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			Exchange.emit("error", "msg", "writing containers.json: " + e.getMessage());
		}
	}
}
