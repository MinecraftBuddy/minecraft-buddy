package net.milenco.buddy.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.milenco.buddy.version.Game;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sends {@code /homes} and clicks a home by name in the server's menu (the wheat farm on its way to the field, the
 * panic on its way home). A click there teleports at once, where {@code /home <name>} makes you stand still for three
 * seconds first.
 */
public final class MenuPick {
	private MenuPick() {}

	/** More pages than this and the name is not going to turn up either. */
	private static final int MAX_PAGES = 20;

	private static String command = null, want = null, lastPage = null;
	private static boolean send = false;
	private static int menuId = -1, pages = 0;
	private static long until = 0, clickAt = 0;

	/** @param cmd the server command that opens the menu, with its slash */
	public static void go(String cmd, String name) {
		command = cmd; want = name.trim(); send = true;
		if (cmd.equals(Profiles.cmd("homes", null))) AfkHelperClient.homeAsked(want);
	}

	/** Start of the tick, the way vanilla sends a click in a menu. */
	static void startTick(Minecraft mc) {
		if (want == null) return;
		LocalPlayer p = mc.player;
		if (p == null || mc.gameMode == null) { want = null; return; }
		long now = System.currentTimeMillis();
		if (send) {
			send = false; menuId = -1; pages = 0; lastPage = null; until = now + 5000;
			AfkHelperClient.sendChat(command);
			return;
		}
		if (p.containerMenu == p.inventoryMenu) {
			// closed again by the player: then they changed their mind
			if (menuId >= 0) want = null;
			else if (now > until) {  Exchange.emit("menu choice", "action", "no menu", "menu", command, "name", want); want = null; }
			return;
		}
		AbstractContainerMenu menu = p.containerMenu;
		if (menu.containerId != menuId) {
			// the items arrive a tick after the menu; a tick or two is all the wait there is, speed is the point here
			menuId = menu.containerId;
			waitForPage(now);
			return;
		}
		if (now < clickAt) return;
		int own = menu.slots.size() - 36;   // the bottom 36 slots are your own inventory
		String w = want.toLowerCase(Locale.ROOT);
		List<String> names = new ArrayList<>();
		int exact = -1, word = -1, part = -1, next = -1;
		for (int i = 0; i < Math.max(0, own); i++) {
			ItemStack st = menu.slots.get(i).getItem();
			if (st.isEmpty()) continue;
			String n = st.getHoverName().getString().trim();
			if (n.isEmpty()) continue;
			names.add(n);
			String l = n.toLowerCase(Locale.ROOT);
			if (exact < 0 && l.equals(w)) exact = i;
			// "Home: base" must win from "base2" when you ask for base
			if (word < 0 && List.of(l.split("[^\\p{L}\\p{N}_-]+")).contains(w)) word = i;
			if (part < 0 && l.contains(w)) part = i;
			if (next < 0 && (Profiles.knowsText("nextPage") ? Profiles.has("nextPage", n) : l.contains("next"))) next = i;
		}
		String page = String.join(" | ", names);
		int found = exact >= 0 ? exact : word >= 0 ? word : part;
		if (found >= 0) {
			Game.click(mc.gameMode, menuId, found, p);
			Exchange.emit("menu choice", "action", "clicked", "menu", command, "name", want, "slot", found, "page", pages + 1, "items", page);
			want = null;
			return;
		}
		// the same items as before the click on "next": that was the last page (or the new one is still on its way)
		if (next >= 0 && !page.equals(lastPage) && pages < MAX_PAGES) {
			Game.click(mc.gameMode, menuId, next, p);
			Exchange.emit("menu choice", "action", "next page", "menu", command, "name", want, "slot", next, "page", pages + 1, "items", page);
			lastPage = page; pages++;
			waitForPage(now);
			return;
		}
		if (now > until) {
			// the menu stays open, so you can still pick one yourself
			Exchange.emit("menu choice", "action", "not found", "menu", command, "name", want, "page", pages + 1, "items", page);
			want = null;
		}
	}

	private static void waitForPage(long now) {
		clickAt = now + ThreadLocalRandom.current().nextLong(60, 130);
		until = clickAt + 2500;
	}
}
