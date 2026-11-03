package net.milenco.buddy.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.milenco.buddy.version.Game;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Writes down every menu the server opens: its title, its kind and what lies in each slot, with the item's name,
 * its lore and its components as the server sent them. Lasting, in {@code menus.jsonl}: a menu is only worth
 * something next to the ones seen days before. Only reads; behind the setting {@code menuLog}.
 *
 * The alt can also be told to click a slot or close the menu ({@code menuklik}, {@code menusluit}), to walk
 * through the menus without a screen. Never on the main account: a click in a server menu can spend money.
 */
public final class MenuLog {
	private MenuLog() {}

	private static final Gson GSON = new Gson();
	/** A menu that keeps changing (a crate that spins) gets this many shots and no more. */
	private static final int MAX_SHOTS = 30;
	/** The items arrive a tick or more after the menu, and some menus fill in steps: wait until it stands still. */
	private static final long SETTLE_MS = 200;
	/** A menu that never stands still is written this often anyway. */
	private static final long MOVING_MS = 1000;

	// what the server said when it opened the menu; the menu object itself does not keep its title
	private static int openId = -1;
	private static String openType = null;
	private static Component openTitle = null;

	private static int seenId = -1, shots = 0;
	private static long seenAt = 0, pending = 0, written = 0, pendingSince = 0, lastWrite = 0;

	private static String lastCommand = null, lastClickName = null;
	private static long lastCommandAt = 0, lastClickAt = 0;
	private static int lastClick = -1;

	private static int clickSlot = -1, clickId = -1, clickButton = 0;
	private static boolean close = false, clickShift = false;

	private static Path file() { return Path.of(AfkConfig.get().exchangeDir, "menus.jsonl"); }

	/** From the open-screen packet, on the game thread. */
	public static void opened(int id, MenuType<?> type, Component title) {
		openId = id; openTitle = title;
		var key = type == null ? null : BuiltInRegistries.MENU.getKey(type);
		openType = key == null ? null : key.toString();
	}

	/** A command that went to the server, typed or sent by the mod: the menu that follows is most likely its answer. */
	static void sent(String command) {
		lastCommand = "/" + command; lastCommandAt = System.currentTimeMillis();
		// the click before this command has nothing to do with the menu that comes now; it used to be written with it
		lastClick = -1;
	}

	/** The title the server gave the menu with this id, or null when it is not the one that was opened last. */
	static String titleOf(int id) { return openId == id && openTitle != null ? openTitle.getString() : null; }

	/** The same title with its colours, for the web panel. */
	static Component title(int id) { return openId == id ? openTitle : null; }

	static void tick(LocalPlayer p) {
		AbstractContainerMenu menu = p.containerMenu;
		if (!AfkConfig.get().menuLog || menu == p.inventoryMenu) { seenId = -1; return; }
		long now = System.currentTimeMillis();
		if (menu.containerId != seenId) { seenId = menu.containerId; seenAt = now; shots = 0; pending = 0; written = 0; pendingSince = now; lastWrite = 0; }
		if (shots >= MAX_SHOTS) return;
		int top = Math.max(0, menu.slots.size() - 36);   // the bottom 36 slots are your own inventory
		long print = 1;
		int filled = 0;
		for (int i = 0; i < top; i++) {
			ItemStack st = menu.slots.get(i).getItem();
			if (st.isEmpty()) continue;
			filled++;
			print = print * 31 + i;
			print = print * 31 + st.getItem().hashCode();
			print = print * 31 + st.getCount();
			print = print * 31 + st.getComponentsPatch().hashCode();
		}
		if (print != pending) { pending = print; pendingSince = now; }
		if (pending == written) return;
		// still empty right after opening: the contents are on their way
		if (filled == 0 && now - seenAt < MOVING_MS) return;
		if (now - pendingSince < SETTLE_MS && now - Math.max(lastWrite, seenAt) < MOVING_MS) return;
		write(p, menu, top, now);
		written = pending; lastWrite = now; shots++;
	}

	private static void write(LocalPlayer p, AbstractContainerMenu menu, int top, long now) {
		var ops = p.registryAccess().createSerializationContext(JsonOps.INSTANCE);
		JsonObject o = new JsonObject();
		o.addProperty("t", now);
		o.addProperty("player", p.getGameProfile().name());
		o.addProperty("id", menu.containerId);
		o.addProperty("shot", shots);
		boolean known = openId == menu.containerId;
		String type = known ? openType : null;
		if (type == null) {
			// a player inventory has no type and says so with an exception
			try { type = String.valueOf(BuiltInRegistries.MENU.getKey(menu.getType())); } catch (RuntimeException ignored) { }
		}
		o.addProperty("kind", type);
		String title = known && openTitle != null ? openTitle.getString() : null;
		o.addProperty("title", title);
		if (known && openTitle != null) ComponentSerialization.CODEC.encodeStart(ops, openTitle).result().ifPresent(j -> o.add("title_json", j));
		o.addProperty("slots", top);
		if (lastCommand != null && now - lastCommandAt < 15000) o.addProperty("via", lastCommand);
		if (lastClick >= 0 && now - lastClickAt < 15000) { o.addProperty("click", lastClick); o.addProperty("click_name", lastClickName); }
		JsonArray items = new JsonArray();
		for (int i = 0; i < top; i++) {
			ItemStack st = menu.slots.get(i).getItem();
			if (st.isEmpty()) continue;
			JsonObject e = new JsonObject();
			e.addProperty("slot", i);
			e.addProperty("item", BuiltInRegistries.ITEM.getKey(st.getItem()).toString());
			e.addProperty("count", st.getCount());
			e.addProperty("item_name", st.getHoverName().getString());
			ItemLore lore = st.get(DataComponents.LORE);
			if (lore != null && !lore.lines().isEmpty()) {
				JsonArray lines = new JsonArray();
				for (Component line : lore.lines()) lines.add(line.getString());
				e.add("lore", lines);
			}
			// colours, custom models, heads, glint: everything the server set on the item, as it would be written in a command
			DataComponentPatch patch = st.getComponentsPatch();
			if (!patch.isEmpty()) DataComponentPatch.CODEC.encodeStart(ops, patch).result().ifPresent((JsonElement j) -> e.add("comp", j));
			items.add(e);
		}
		o.add("items", items);
		try { Files.writeString(file(), GSON.toJson(o) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
		catch (IOException ignored) { }
		Exchange.emit("menu", "id", menu.containerId, "nr", shots, "title", title, "kind", type, "slots", top, "filled", items.size());
	}

	/** When a session last clicked in or closed a menu through commands.jsonl: Follow leaves such a menu open meanwhile. */
	static long lastMenuCommand;

	/** {@code menuklik} with {@code slot} (and {@code id} to click only in that menu, {@code knop: rechts}, {@code shift}) and {@code menusluit}, from commands.jsonl. */
	static void command(JsonObject o) {
		String cmd = o.get("cmd").getAsString();
		lastMenuCommand = System.currentTimeMillis();
		if (cmd.equals("menuclose")) { close = true; return; }
		if (!o.has("slot")) { Exchange.emit("error", "msg", "menu click without a slot"); return; }
		clickSlot = o.get("slot").getAsInt();
		clickId = o.has("id") ? o.get("id").getAsInt() : -1;
		// some buttons do one thing on a left click and another on a right or shift click ("Shift + rechtsklik om te verwijderen")
		clickButton = o.has("button") && o.get("button").getAsString().equals("right") ? 1 : 0;
		clickShift = o.has("shift") && o.get("shift").getAsBoolean();
	}

	/** Start of the tick, the way vanilla sends a click in a menu. */
	static void startTick(Minecraft mc) {
		if (clickSlot < 0 && !close) return;
		int slot = clickSlot, id = clickId, button = clickButton;
		boolean closing = close, shift = clickShift;
		clickSlot = -1; clickId = -1; close = false; clickButton = 0; clickShift = false;
		LocalPlayer p = mc.player;
		if (p == null || mc.gameMode == null) return;
		AbstractContainerMenu menu = p.containerMenu;
		if (menu == p.inventoryMenu) {
			// his own inventory shut on the page: what he holds on the cursor and in the crafting grid goes back, as
			// closing the game's inventory screen does; not while another screen is open (a sign, the death screen)
			if (closing && Game.openScreen(mc) == null) { p.closeContainer(); Exchange.emit("menu click", "action", "inventory closed"); return; }
			Exchange.emit("menu click", "action", "no menu");
			return;
		}
		if (closing) { p.closeContainer(); Exchange.emit("menu click", "action", "closed", "id", menu.containerId); return; }
		// the menu the click was meant for is gone: the same slot in another menu may be a buy button
		if (id >= 0 && id != menu.containerId) { Exchange.emit("menu click", "action", "another menu", "id", menu.containerId, "slot", slot); return; }
		if (slot >= menu.slots.size() - 36) { Exchange.emit("menu click", "action", "outside the menu", "id", menu.containerId, "slot", slot); return; }
		ItemStack st = menu.slots.get(slot).getItem();
		if (st.isEmpty()) { Exchange.emit("menu click", "action", "empty slot", "id", menu.containerId, "slot", slot); return; }
		String name = st.getHoverName().getString();
		Game.click(mc.gameMode, menu.containerId, slot, button, shift, p);
		lastClick = slot; lastClickName = name; lastClickAt = System.currentTimeMillis();
		Exchange.emit("menu click", "action", "clicked", "id", menu.containerId, "slot", slot, "name", name, "button", button == 1 ? "right" : "left", "shift", shift);
	}
}
