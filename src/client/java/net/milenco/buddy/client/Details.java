package net.milenco.buddy.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Mth;
import net.minecraft.util.StringDecomposer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.milenco.buddy.version.Game;

/**
 * What the alt has and is, for the web panel: typing commands, reading the buddy's chat and typing for them, throwing
 * things out of the inventory, and seeing the enchantments on items and armour and the effects the buddy has. While
 * the panel asks for it
 * ({@code {"cmd":"state"}}, every few seconds while it is open) the alt writes {@code state.json} in the exchange folder
 * once a second when it changed: every slot with its name, count, durability and enchantments (in the game's own
 * words, in his game's language), the armour and the off hand, his effects, health, food, experience and where he is.
 * {@code {"cmd":"drop","slot":n}} throws the stack of that slot (0 to 35 the inventory, 36 to 39 boots to helmet, 40 the
 * off hand) where he looks.
 */
final class Details {
	private Details() {}

	private static final Gson GSON = new Gson();
	private static long wantedUntil, nextWrite;
	private static String last = "";

	static void command(String cmd, JsonObject o, Minecraft mc) {
		LocalPlayer p = mc.player;
		if (cmd.equals("state")) { wantedUntil = System.currentTimeMillis() + 30_000; nextWrite = 0; last = ""; return; }
		if (cmd.equals("invclick") || cmd.equals("select")) { inventory(cmd, o, mc); return; }
		if (p == null || mc.gameMode == null) { Exchange.emit("error", "msg", "drop: not in a world"); return; }
		int slot = o.has("slot") ? o.get("slot").getAsInt() : -1;
		int menu = menuSlot(slot);
		if (menu < 0) { Exchange.emit("error", "msg", "drop: no slot " + slot); return; }
		if (p.containerMenu != p.inventoryMenu) p.closeContainer();
		ItemStack st = p.inventoryMenu.slots.get(menu).getItem();
		if (st.isEmpty()) { Exchange.emit("error", "msg", "drop: slot " + slot + " is empty"); return; }
		String what = AfkHelperClient.itemId(st) + " x" + st.getCount();
		Game.drop(mc.gameMode, p.inventoryMenu.containerId, menu, p);
		Exchange.emit("drop", "slot", slot, "what", what);
		nextWrite = 0;
	}

	/**
	 * The panel's hands in his inventory: moving items, throwing them out or selecting a hotbar slot, the way the game
	 * does it rather than with separate buttons. Every click on the page is one real click in his inventory screen, as
	 * the game makes it: {@code invclick}
	 * with {@code slot} (the panel's numbers, -999 outside the window), {@code button} and {@code mode}: pickup (left 0
	 * takes or puts the stack, right 1 half or one), quick (shift), swap (button = hotbar slot 0-8, or 40 the off hand),
	 * throw (button 0 one, 1 the stack), pickup_all (a double click), and drag ({@code slots}, {@code button} 0 shared out
	 * evenly, 1 one each). What hangs on the cursor goes into state.json as {@code carried}. {@code select} with
	 * {@code slot} 0-8 takes that hotbar slot in hand. With another menu open (a chest, a server menu such as /shards) a
	 * click carries {@code menu}, that menu's id, and {@code slot} is the menu's own number (the page has it from
	 * {@code menu} in state.json); without it the click is refused then. The jobs wait meanwhile (a lease of three
	 * seconds on his inventory, renewed by every click).
	 */
	private static void inventory(String cmd, JsonObject o, Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.gameMode == null) { Exchange.emit("error", "msg", cmd + ": not in a world"); return; }
		int slot = o.has("slot") ? o.get("slot").getAsInt() : -1;
		if (cmd.equals("select")) {
			if (slot < 0 || slot > 8) { Exchange.emit("error", "msg", "select: no hotbar slot " + slot); return; }
			p.getInventory().setSelectedSlot(slot);
			nextWrite = 0;
			return;
		}
		// A click meant for an open menu names that menu; its slots are the menu's own numbers then. The menu it was meant
		// for is gone (a click closed it, the server opened the next page): the same slot elsewhere may be a buy button.
		AbstractContainerMenu open = p.containerMenu;
		boolean inMenu = o.has("menu");
		if (inMenu && (open == p.inventoryMenu || open.containerId != o.get("menu").getAsInt())) {
			Exchange.emit("error", "msg", cmd + ": that menu is no longer open");
			nextWrite = 0; last = "";
			return;
		}
		if (!inMenu && open != p.inventoryMenu) { Exchange.emit("error", "msg", "invclick: another menu is open"); return; }
		Control.take(Control.Part.INVENTORY, "panel", Control.PANEL, 3000);
		// Follow shuts a menu nobody clicks in after a minute: a person clicking in it keeps it open
		if (inMenu) MenuLog.lastMenuCommand = System.currentTimeMillis();
		String mode = o.has("mode") ? o.get("mode").getAsString() : "pickup";
		int button = o.has("button") ? o.get("button").getAsInt() : 0;
		int id = open.containerId, size = open.slots.size();
		java.util.function.IntUnaryOperator at = inMenu ? i -> i >= 0 && i < size ? i : -1 : Details::menuSlot;
		if (mode.equals("drag")) {
			int type = button == 1 ? 1 : 0;
			mc.gameMode.handleContainerInput(id, -999, type << 2, net.minecraft.world.inventory.ContainerInput.QUICK_CRAFT, p);
			if (o.has("slots")) for (var e : o.getAsJsonArray("slots")) {
				int m = at.applyAsInt(e.getAsInt());
				if (m >= 0) mc.gameMode.handleContainerInput(id, m, 1 | type << 2, net.minecraft.world.inventory.ContainerInput.QUICK_CRAFT, p);
			}
			mc.gameMode.handleContainerInput(id, -999, 2 | type << 2, net.minecraft.world.inventory.ContainerInput.QUICK_CRAFT, p);
		} else {
			int menu = slot == -999 ? -999 : at.applyAsInt(slot);
			if (menu == -1) { Exchange.emit("error", "msg", "invclick: no slot " + slot); return; }
			if (mode.equals("swap")) button = button == 40 ? 40 : Math.max(0, Math.min(8, button));
			String kind = switch (mode) {
				case "quick" -> "QUICK_MOVE"; case "swap" -> "SWAP"; case "throw" -> "THROW"; case "pickup_all" -> "PICKUP_ALL";
				default -> "PICKUP";
			};
			if (!Game.input(mc.gameMode, id, menu, button, kind, p)) return;
		}
		nextWrite = 0;
		last = "";
	}

	/** The inventory's index (as the game numbers it) to the slot of the inventory menu. */
	private static int menuSlot(int i) {
		if (i >= 0 && i < 9) return 36 + i;
		if (i >= 9 && i < 36) return i;
		// 41-44 the 2x2 crafting grid, 45 its result (the panel's numbers; the menu has the grid at 1-4 and the result at 0)
		return switch (i) { case 36 -> 8; case 37 -> 7; case 38 -> 6; case 39 -> 5; case 40 -> 45; case 41 -> 1; case 42 -> 2; case 43 -> 3; case 44 -> 4; case 45 -> 0; default -> -1; };
	}

	/** Not in a world: state.json says so, for the panel's button to bring him back. */
	static void offline() {
		wantedUntil = System.currentTimeMillis() + 30_000;
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("t", System.currentTimeMillis());
		out.put("online", false);
		out.put("name", AfkHelperClient.mc().getUser().getName());
		out.put("stay_offline", AfkConfig.get().stayOffline);
		try {
			Path f = Paths.get(AfkConfig.get().exchangeDir).resolve("state.json"), tmp = f.resolveSibling("state.json.tmp");
			Files.writeString(tmp, GSON.toJson(out), StandardCharsets.UTF_8);
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception ignored) { }
		last = "";
	}

	/** Once a tick: the file, while the panel wants it. */
	static void tick(Minecraft mc) {
		long now = System.currentTimeMillis();
		LocalPlayer p = mc.player;
		MiniMap.tick(mc, now <= wantedUntil);
		MapMemory.tick(mc);
		if (p == null || now > wantedUntil || now < nextWrite) return;
		// four times a second while he is played or a Take control window watches him (a chest he opens shows live there)
		nextWrite = now + (Steer.active() || World3d.watched() ? 250 : 1000);
		Map<String, Object> s = new LinkedHashMap<>();
		List<Object> slots = new ArrayList<>();
		for (int i = 0; i < 36; i++) { ItemStack st = p.getInventory().getItem(i); if (!st.isEmpty()) slots.add(item(i, st, false)); }
		EquipmentSlot[] armour = { EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD };
		for (int k = 0; k < 4; k++) { ItemStack st = p.getItemBySlot(armour[k]); if (!st.isEmpty()) slots.add(item(36 + k, st, false)); }
		if (!p.getOffhandItem().isEmpty()) slots.add(item(40, p.getOffhandItem(), false));
		for (int k = 0; k < 5; k++) {
			ItemStack st = p.inventoryMenu.getSlot(k).getItem();
			if (!st.isEmpty()) slots.add(item(k == 0 ? 45 : 40 + k, st, false));
		}
		s.put("slots", slots);
		s.put("selected", p.getInventory().getSelectedSlot());
		// the cursor is the open menu's (a chest's, a server menu's), else the inventory's
		ItemStack carried = p.containerMenu.getCarried();
		if (!carried.isEmpty()) s.put("carried", item(-1, carried, false));
		s.put("menu_open", p.containerMenu != p.inventoryMenu);
		Map<String, Object> menu = menu(mc, p);
		if (menu != null) s.put("menu", menu);
		s.put("online", true);
		s.put("name", p.getGameProfile().name());
		// the game's version: the panel reads the screens' pictures from Mojang's jar of it
		s.put("mc", net.minecraft.SharedConstants.getCurrentVersion().name());
		// a screen of the game that is no menu (death, bed, a sign, a book): Screens
		try { s.put("screen", Screens.of(mc, p)); } catch (RuntimeException e) { s.put("screen", null); }
		s.put("doing", Arbiter.doing());
		List<Object> effects = new ArrayList<>();
		for (MobEffectInstance e : p.getActiveEffects()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", e.getEffect().unwrapKey().map(k -> k.identifier().toString()).orElse("?"));
			m.put("name", e.getEffect().value().getDisplayName().getString());
			m.put("level", e.getAmplifier() + 1);
			m.put("seconds", e.isInfiniteDuration() ? -1 : e.getDuration() / 20);
			effects.add(m);
		}
		s.put("effects", effects);
		s.put("health", Math.round(p.getHealth() * 10) / 10.0);
		s.put("max_health", Math.round(p.getMaxHealth() * 10) / 10.0);
		s.put("food", p.getFoodData().getFoodLevel());
		s.put("saturation", Math.round(p.getFoodData().getSaturationLevel() * 10) / 10.0);
		// the air bubbles above the food, which the game shows under water and while the air comes back
		s.put("air", p.getAirSupply());
		s.put("max_air", p.getMaxAirSupply());
		if (p.isEyeInFluid(net.minecraft.tags.FluidTags.WATER)) s.put("underwater", true);
		// what he holds the button for now (eating, a drawn bow, a raised shield), for the panel's view of him
		s.put("using", p.isUsingItem() ? net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(p.getUseItem().getItem()).getPath() : null);
		s.put("using_ticks", p.isUsingItem() ? p.getTicksUsingItem() : 0);
		s.put("blocking", p.isBlocking());
		if (p.isOnFire()) s.put("fire", true);
		// the line above the hotbar (You can sleep only at night, Respawn point set), as the game shows it for a while
		// with its colours: a plugin's line often carries § codes inside its text, which the game draws as colours
		var bar = actionBarText;
		if (bar != null && System.currentTimeMillis() - actionBarAt < 3000) {
			s.put("actionbar", net.minecraft.ChatFormatting.stripFormatting(bar.getString()));
			s.put("actionbar_runs", segments(bar));
		}
		s.put("xp_level", p.experienceLevel);
		s.put("xp_progress", Math.round(p.experienceProgress * 100) / 100.0);
		// the scoreboard at the right of the screen, for the 3D view, as the game shows it
		Map<String, Object> side = sidebar(mc, p);
		if (side != null) s.put("sidebar", side);
		// his skin and what he wears, for the figure in the panel's inventory and beside his name; the same fields the 3D
		// view's figures carry
		Map<String, Object> me = new java.util.LinkedHashMap<>();
		World3d.skin(p, me);
		World3d.gear(p, me);
		s.put("me", me);
		s.put("mode", Modes.current());
		s.put("settings", Modes.settings());
		s.put("building", Builder.isOn());
		// the schematics placed for the builder, each with its start block, for the panel's Build card
		if (Schematic.available()) s.put("placements", Schematic.list()); else s.put("litematica", false);
		// the game's clock, for the time and the day over the panel's map and in Take control: the
		// overworld's ticks since the world began, as a clock and F3 count them, also in the nether
		s.put("clock", p.level().getOverworldClockTime());
		s.put("render_distance", mc.options.renderDistance().get());
		s.put("needs", Needs.list());
		s.put("armor", p.getArmorValue());
		s.put("dim", p.level().dimension().identifier().toString());
		s.put("x", p.getBlockX()); s.put("y", p.getBlockY()); s.put("z", p.getBlockZ());
		s.put("yaw", Math.round(Mth.wrapDegrees(p.getYRot()))); s.put("pitch", Math.round(p.getXRot()));
		s.put("steered", Steer.active());
		// what the crosshair is on, for whoever steers him from the panel without a picture
		if (mc.hitResult instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK) {
			Map<String, Object> k = new LinkedHashMap<>();
			k.put("block", BuiltInRegistries.BLOCK.getKey(p.level().getBlockState(bh.getBlockPos()).getBlock()).getPath());
			k.put("x", bh.getBlockPos().getX()); k.put("y", bh.getBlockPos().getY()); k.put("z", bh.getBlockPos().getZ());
			s.put("looking_at", k);
		} else if (mc.hitResult instanceof EntityHitResult eh) {
			Map<String, Object> k = new LinkedHashMap<>();
			k.put("entity", BuiltInRegistries.ENTITY_TYPE.getKey(eh.getEntity().getType()).getPath());
			if (eh.getEntity() instanceof net.minecraft.world.entity.player.Player pl) k.put("name", pl.getGameProfile().name());
			s.put("looking_at", k);
		}
		s.put("server", AfkHelperClient.serverIp());
		s.put("profile", Profiles.key());
		s.put("follows", Follow.target() == null ? "" : Follow.target());
		s.put("base", Base.keeper() == null ? "" : Base.keeper());
		// the pen he farms, for the panel's follow card (0.4.80)
		Map<String, Object> pen = Pen.details(p);
		if (pen != null) s.put("pen", pen);
		String json = GSON.toJson(s);
		if (json.equals(last)) return;
		last = json;
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("t", now);
		out.putAll(s);
		try {
			Path f = Paths.get(AfkConfig.get().exchangeDir).resolve("state.json"), tmp = f.resolveSibling("state.json.tmp");
			Files.writeString(tmp, GSON.toJson(out), StandardCharsets.UTF_8);
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			Exchange.emit("error", "msg", "state.json: " + e.getMessage());
		}
	}

	/**
	 * The menu open in the game, for the panel to draw and click in (a chest menu a server opens, such as a shop): its
	 * id (a click names it, so that a click meant for a closed menu lands nowhere), kind, title with its colours, and the
	 * slots above his own 36, each as the server made it. His own 36 the panel takes from {@code slots}: the bottom of
	 * every menu is his inventory, 9 to 35 and then the hotbar.
	 */
	private static Map<String, Object> menu(Minecraft mc, LocalPlayer p) {
		AbstractContainerMenu m = p.containerMenu;
		if (m == p.inventoryMenu) return null;
		int top = Math.max(0, m.slots.size() - 36);
		Map<String, Object> o = new LinkedHashMap<>();
		o.put("id", m.containerId);
		String type = null;
		// a menu without a type (the horse's) says so with an exception
		try { var k = BuiltInRegistries.MENU.getKey(m.getType()); if (k != null) type = k.toString(); } catch (RuntimeException ignored) { }
		o.put("type", type);
		// a menu without a type (a horse's, a nautilus's) by its class, which the panel knows its screen by
		o.put("kind", m.getClass().getSimpleName());
		Component title = MenuLog.title(m.containerId);
		if (title == null && Game.openScreen(mc) != null) title = Game.openScreen(mc).getTitle();
		o.put("title", segments(title));
		o.put("size", top);
		o.put("columns", type == null ? 9 : switch (type) {
			case "minecraft:generic_3x3", "minecraft:crafter_3x3" -> 3;
			case "minecraft:hopper" -> 5;
			default -> 9;
		});
		List<Object> slots = new ArrayList<>();
		for (int i = 0; i < top; i++) { ItemStack st = m.slots.get(i).getItem(); if (!st.isEmpty()) slots.add(item(i, st, true)); }
		o.put("slots", slots);
		// where each slot stands on the game's screen (its own and his 36 below), so that the panel can draw the screen
		// as the game does; and what the screen shows besides its slots (MenuExtra)
		List<int[]> pos = new ArrayList<>();
		for (var sl : m.slots) pos.add(new int[] { sl.x, sl.y });
		o.put("pos", pos);
		try { o.put("extra", MenuExtra.of(mc, m)); } catch (RuntimeException e) { o.put("extra", Map.of("error", String.valueOf(e.getMessage()))); }
		return o;
	}

	/**
	 * The scoreboard the game draws at the right of the screen, as {@code Hud.displayScoreboardSidebar} builds it: the
	 * objective of his team's colour if there is one, else the sidebar's; its title, and at most fifteen lines, highest
	 * score first and then by name, each with its name as the team writes it and its score in the objective's format
	 * (red by default, blank or a fixed text when the server says so). Null when the game draws none.
	 */
	private static Map<String, Object> sidebar(Minecraft mc, LocalPlayer p) {
		if (mc.level == null) return null;
		net.minecraft.world.scores.Scoreboard board = mc.level.getScoreboard();
		net.minecraft.world.scores.Objective objective = null;
		net.minecraft.world.scores.PlayerTeam own = board.getPlayersTeam(p.getScoreboardName());
		if (own != null && own.getColor().isPresent()) objective = board.getDisplayObjective(own.getColor().get().displaySlot());
		if (objective == null) objective = board.getDisplayObjective(net.minecraft.world.scores.DisplaySlot.SIDEBAR);
		if (objective == null) return null;
		net.minecraft.network.chat.numbers.NumberFormat format = objective.numberFormatOrDefault(net.minecraft.network.chat.numbers.StyledFormat.SIDEBAR_DEFAULT);
		List<Object> lines = new ArrayList<>();
		board.listPlayerScores(objective).stream()
			.filter(e -> !e.isHidden())
			.sorted(java.util.Comparator.comparing(net.minecraft.world.scores.PlayerScoreEntry::value).reversed()
				.thenComparing(net.minecraft.world.scores.PlayerScoreEntry::owner, String.CASE_INSENSITIVE_ORDER))
			.limit(15)
			.forEach(e -> {
				Map<String, Object> l = new LinkedHashMap<>();
				l.put("name", segments(net.minecraft.world.scores.PlayerTeam.formatNameForTeam(board.getPlayersTeam(e.owner()), e.ownerName())));
				l.put("score", segments(e.formatValue(format)));
				lines.add(l);
			});
		Map<String, Object> o = new LinkedHashMap<>();
		o.put("title", segments(objective.getDisplayName()));
		o.put("lines", lines);
		return o;
	}

	/** An item as the panel draws it, outside a slot (a villager's trade, a stonecutter's choice). */
	static Map<String, Object> itemOf(ItemStack st) { return item(-1, st, true); }

	/** Write his state again at once (a menu's button changed what it shows). */
	private static volatile Component actionBarText;
	private static volatile long actionBarAt;

	/** The game shows a line above his hotbar: kept for the panel's view for three seconds, as the game fades it. */
	static void actionBar(net.minecraft.network.chat.Component msg) {
		actionBarText = msg;
		actionBarAt = System.currentTimeMillis();
		soon();
	}

	static void soon() { nextWrite = 0; last = ""; }

	/**
	 * A text as the game draws it, in runs of one style: text, colour ({@code c}, #rrggbb), bold, italic, underlined,
	 * struck, obfuscated. A run in a font of a resource pack ({@code f}) is pictures, not letters (a menu's background,
	 * a space that moves the rest), which the page leaves out.
	 */
	private static List<Map<String, Object>> segments(Component c) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (c == null) return out;
		drawn(c, (style, text) -> out.add(run(style, text)));
		return out;
	}

	/**
	 * The pieces of a text as the game's font draws them: a § code inside a piece (a plugin's line in the old form,
	 * "§6Text") changes the style from there on and is not drawn itself, as {@link StringDecomposer#iterateFormatted}
	 * does for the font. A piece without § goes as it is.
	 */
	private static void drawn(Component c, java.util.function.BiConsumer<Style, String> out) {
		c.<Object>visit((style, text) -> {
			if (text.isEmpty()) return Optional.empty();
			if (text.indexOf('\u00a7') < 0) { out.accept(style, text); return Optional.empty(); }
			StringBuilder b = new StringBuilder();
			Style[] at = { null };
			StringDecomposer.iterateFormatted(text, style, (pos, st, cp) -> {
				if (at[0] != null && !at[0].equals(st)) { out.accept(at[0], b.toString()); b.setLength(0); }
				at[0] = st;
				b.appendCodePoint(cp);
				return true;
			});
			if (!b.isEmpty()) out.accept(at[0], b.toString());
			return Optional.empty();
		}, Style.EMPTY);
	}

	private static Map<String, Object> run(Style style, String text) {
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("t", text);
		if (style.getColor() != null) r.put("c", String.format("#%06x", style.getColor().getValue() & 0xffffff));
		if (style.isBold()) r.put("b", 1);
		if (style.isItalic()) r.put("i", 1);
		if (style.isUnderlined()) r.put("u", 1);
		if (style.isStrikethrough()) r.put("s", 1);
		if (style.isObfuscated()) r.put("o", 1);
		if (!(style.getFont() instanceof FontDescription.Resource f && f.id().getNamespace().equals("minecraft"))) r.put("f", 1);
		return r;
	}

	/**
	 * A chat line as the game draws it, for the panel: the runs of {@link #segments}, each with what a click on it does
	 * ({@code k}: run, suggest, url or copy, and {@code v} the command, address or text) and what shows while the mouse
	 * rests on it ({@code h}, plain). Neighbouring runs of one look and one action are joined, so a line of colours is not
	 * a run per letter. Null for a line of one run without any look: the plain text says all of it.
	 */
	static List<Map<String, Object>> chatRuns(Component c) {
		if (c == null) return null;
		List<Map<String, Object>> out = new ArrayList<>();
		drawn(c, (style, text) -> {
			Map<String, Object> r = run(style, text);
			switch (style.getClickEvent()) {
				case ClickEvent.RunCommand(String command) -> { r.put("k", "run"); r.put("v", command); }
				case ClickEvent.SuggestCommand(String command) -> { r.put("k", "suggest"); r.put("v", command); }
				case ClickEvent.OpenUrl(java.net.URI uri) -> { r.put("k", "url"); r.put("v", uri.toString()); }
				case ClickEvent.CopyToClipboard(String value) -> { r.put("k", "copy"); r.put("v", value); }
				case null, default -> { }
			}
			if (style.getHoverEvent() instanceof HoverEvent.ShowText(Component hover)) {
				String h = hover.getString().strip();
				if (!h.isEmpty()) r.put("h", h.length() > 400 ? h.substring(0, 400) + "…" : h);
			}
			Map<String, Object> last = out.isEmpty() ? null : out.getLast();
			if (last != null && sameLook(last, r)) last.put("t", last.get("t") + text);
			else out.add(r);
		});
		if (out.isEmpty() || (out.size() == 1 && out.getFirst().size() == 1)) return null;
		return out;
	}

	private static boolean sameLook(Map<String, Object> a, Map<String, Object> b) {
		if (a.size() != b.size()) return false;
		for (Map.Entry<String, Object> e : b.entrySet()) if (!e.getKey().equals("t") && !e.getValue().equals(a.get(e.getKey()))) return false;
		return true;
	}

	/** One stack for state.json; {@code server}: it lies in a menu, so its name as the server styled it goes along. */
	private static Map<String, Object> item(int slot, ItemStack st, boolean server) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("slot", slot);
		m.put("id", AfkHelperClient.itemId(st));
		m.put("name", st.getHoverName().getString());
		// a name the server gave it (every button of a server menu has one), with its colours, and its lore as the tooltip
		// draws it; whether it shimmers and whether it has a tooltip at all (the glass panes between the buttons)
		boolean named = !java.util.Objects.equals(st.get(DataComponents.ITEM_NAME), st.getPrototype().get(DataComponents.ITEM_NAME));
		if (server || st.has(DataComponents.CUSTOM_NAME) || named) m.put("rich", segments(st.getStyledHoverName()));
		ItemLore lore = st.get(DataComponents.LORE);
		if (lore != null && !lore.lines().isEmpty()) {
			List<Object> lines = new ArrayList<>();
			for (Component line : lore.styledLines()) lines.add(segments(line));
			m.put("lore", lines);
		}
		if (st.hasFoil()) m.put("glint", true);
		TooltipDisplay shown = st.get(DataComponents.TOOLTIP_DISPLAY);
		if (shown != null && shown.hideTooltip()) m.put("hide_tooltip", true);
		if (shown != null && !shown.shows(DataComponents.ENCHANTMENTS)) m.put("hide_enchants", true);
		// for the web panel, which writes the game's own names in English: whether the name is a player's own, the rarity
		// (the name's colour), a potion's kind, and the enchantments by id
		if (st.has(DataComponents.CUSTOM_NAME)) m.put("custom_name", true);
		m.put("rarity", st.getRarity().getSerializedName());
		var potion = st.get(DataComponents.POTION_CONTENTS);
		if (potion != null) potion.potion().ifPresent(h -> m.put("potion", h.value().name()));
		m.put("count", st.getCount());
		// what the item carries besides its kind, as the game writes it (/give's [...]): for the panel's hover text
		try {
			var lvl = net.minecraft.client.Minecraft.getInstance().level;
			var patch = st.getComponentsPatch();
			if (lvl != null && !patch.isEmpty()) {
				var tag = net.minecraft.core.component.DataComponentPatch.CODEC.encodeStart(lvl.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), patch).result().orElse(null);
				if (tag != null) { String t = tag.toString(); m.put("nbt", t.length() > 2000 ? t.substring(0, 2000) + "…" : t); }
			}
		} catch (Exception ignored) { }
		if (st.isDamageableItem()) { m.put("durability", st.getMaxDamage() - st.getDamageValue()); m.put("max_durability", st.getMaxDamage()); }
		List<String> ench = new ArrayList<>();
		List<Map<String, Object>> ids = new ArrayList<>();
		for (ItemEnchantments e : new ItemEnchantments[] { st.get(DataComponents.ENCHANTMENTS), st.get(DataComponents.STORED_ENCHANTMENTS) }) {
			if (e == null) continue;
			for (var entry : e.entrySet()) {
				ench.add(Enchantment.getFullname(entry.getKey(), entry.getIntValue()).getString());
				Map<String, Object> one = new LinkedHashMap<>();
				one.put("id", entry.getKey().unwrapKey().map(k -> k.identifier().toString()).orElse("?"));
				one.put("level", entry.getIntValue());
				one.put("max", entry.getKey().value().getMaxLevel());
				ids.add(one);
			}
		}
		if (!ench.isEmpty()) { m.put("enchants", ench); m.put("ench", ids); }
		return m;
	}
}
