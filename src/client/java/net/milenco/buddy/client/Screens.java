package net.milenco.buddy.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.InBedChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.milenco.buddy.mixin.client.BookEditAccessor;
import net.milenco.buddy.mixin.client.SignEditAccessor;

/**
 * The game's screens that are no menu, so that the panel can show and work them as the game does: the death screen
 * (respawn), the bed (leave it), a sign being written, a book and quill being written, a written book being read, and
 * any other screen by its name (close it). In his state as {@code screen}: {@code kind} and what that kind needs.
 * <p>
 * Commands: {@code respawn}; {@code wake}; {@code sign} {@code lines} (four, the screen sends them as it shuts, as the
 * game's Done does); {@code book} {@code pages} and an optional {@code title} (which signs it); {@code screenclose};
 * {@code pickblock} {@code x y z} (the middle button: the server puts that block's item in his hand when he has it).
 */
final class Screens {
	private Screens() {}

	static Map<String, Object> of(Minecraft mc, LocalPlayer p) {
		Screen sc = net.milenco.buddy.version.Game.openScreen(mc);
		Map<String, Object> o = new LinkedHashMap<>();
		if (sc instanceof DeathScreen || p.isDeadOrDying()) o.put("kind", "death");
		else if (sc instanceof InBedChatScreen || p.isSleeping()) o.put("kind", "bed");
		else if (sc instanceof AbstractSignEditScreen s) {
			SignEditAccessor a = (SignEditAccessor) s;
			BlockPos q = a.buddy$sign().getBlockPos();
			o.put("kind", "sign");
			o.put("x", q.getX()); o.put("y", q.getY()); o.put("z", q.getZ());
			o.put("front", a.buddy$slot() == SignTextSlot.FRONT);
			o.put("hanging", s.getClass().getSimpleName().contains("Hanging"));
			o.put("lines", List.of(a.buddy$messages()));
		} else if (sc instanceof BookEditScreen b) {
			BookEditAccessor a = (BookEditAccessor) b;
			o.put("kind", "book_edit");
			o.put("slot", a.buddy$hand() == InteractionHand.MAIN_HAND ? p.getInventory().getSelectedSlot() : 40);
			o.put("pages", new ArrayList<>(a.buddy$pages()));
		} else if (sc instanceof BookViewScreen && !(sc instanceof net.minecraft.client.gui.screens.inventory.LecternScreen)) {
			// a lectern's book is its menu's (MenuExtra), not a book in his hand
			o.put("kind", "book");
			o.put("pages", written(p.getMainHandItem().has(DataComponents.WRITTEN_BOOK_CONTENT) ? p.getMainHandItem() : p.getOffhandItem()));
		} else if (sc != null && !(sc instanceof AbstractContainerScreen) && !(sc instanceof ChatScreen)
				&& !(sc instanceof net.minecraft.client.gui.screens.inventory.LecternScreen)) {
			o.put("kind", "other");
			o.put("name", sc.getClass().getSimpleName());
		}
		return o.isEmpty() ? null : o;
	}

	/** A written book's pages as text, and its title and author first. */
	static Map<String, Object> written(ItemStack st) {
		var c = st.get(DataComponents.WRITTEN_BOOK_CONTENT);
		Map<String, Object> o = new LinkedHashMap<>();
		if (c != null) {
			o.put("title", c.title().raw());
			o.put("author", c.author());
			List<String> pages = new ArrayList<>();
			for (var pg : c.pages()) pages.add(pg.raw().getString());
			o.put("pages", pages);
		} else {
			var w = st.get(DataComponents.WRITABLE_BOOK_CONTENT);
			if (w != null) o.put("pages", w.getPages(false).toList());
		}
		return o;
	}

	static void command(String cmd, JsonObject o, Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.getConnection() == null) return;
		Screen sc = net.milenco.buddy.version.Game.openScreen(mc);
		switch (cmd) {
			case "respawn" -> { if (p.isDeadOrDying() || sc instanceof DeathScreen) { p.respawn(); mc.gui.setScreen(null); } }
			case "wake" -> mc.getConnection().send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.STOP_SLEEPING));
			case "sign" -> {
				if (!(sc instanceof AbstractSignEditScreen s) || !o.has("lines")) return;
				String[] m = ((SignEditAccessor) s).buddy$messages();
				var lines = o.getAsJsonArray("lines");
				for (int i = 0; i < m.length; i++) {
					String t = i < lines.size() ? lines.get(i).getAsString() : "";
					m[i] = t.length() > 80 ? t.substring(0, 80) : t;
				}
				// shutting the screen sends its lines, as the game's Done button does
				mc.gui.setScreen(null);
			}
			case "book" -> {
				if (!(sc instanceof BookEditScreen b) || !o.has("pages")) return;
				int slot = ((BookEditAccessor) b).buddy$hand() == InteractionHand.MAIN_HAND ? p.getInventory().getSelectedSlot() : 40;
				List<String> pages = new ArrayList<>();
				for (var e : o.getAsJsonArray("pages")) { String t = e.getAsString(); pages.add(t.length() > 1024 ? t.substring(0, 1024) : t); if (pages.size() >= 100) break; }
				String title = o.has("title") && !o.get("title").getAsString().isBlank() ? o.get("title").getAsString().trim() : null;
				if (title != null && title.length() > 32) title = title.substring(0, 32);
				mc.getConnection().send(new ServerboundEditBookPacket(slot, pages, Optional.ofNullable(title)));
				mc.gui.setScreen(null);
			}
			case "screenclose" -> { if (sc != null && !(sc instanceof AbstractContainerScreen)) mc.gui.setScreen(null); }
			case "pickblock" -> mc.getConnection().send(new ServerboundPickItemFromBlockPacket(new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt()), false));
			default -> { }
		}
		Details.soon();
	}
}
