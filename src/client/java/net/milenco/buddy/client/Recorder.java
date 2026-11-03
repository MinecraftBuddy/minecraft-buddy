package net.milenco.buddy.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.phys.EntityHitResult;
import net.milenco.buddy.version.Game;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Next to the menus ({@link MenuLog}): what the player does with them and around them, to {@code recording.jsonl}.
 * Every click in a menu, every block they use (with the text of a sign), what they type on a sign, the commands
 * they send, and every line, title, action bar and boss bar text the server shows. And what stands around them that
 * a server puts there: floating texts (a list of the richest players is one), figures you can click, and where the
 * player is, so that a portal that throws you somewhere else shows up as a jump. With the menus that is enough to
 * see afterwards how something on the server works, step by step. Only reads; behind the same setting {@code menuLog}.
 */
public final class Recorder {
	private Recorder() {}

	private static final Gson GSON = new Gson();

	private static boolean on() { return AfkConfig.get().menuLog; }

	private static void write(String kind, JsonObject o) {
		o.addProperty("t", System.currentTimeMillis());
		o.addProperty("kind", kind);
		// where the player stood: a click on a figure at spawn and the same click elsewhere are not the same thing
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && mc.isSameThread() && !o.has("dim")) { o.addProperty("dim", mc.player.level().dimension().identifier().toString()); o.addProperty("px", round(mc.player.getX())); o.addProperty("py", round(mc.player.getY())); o.addProperty("pz", round(mc.player.getZ())); }
		try { Files.writeString(Path.of(AfkConfig.get().exchangeDir, "recording.jsonl"), GSON.toJson(o) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
		catch (IOException ignored) { }
	}

	private static JsonObject item(ItemStack st) {
		JsonObject o = new JsonObject();
		o.addProperty("item", BuiltInRegistries.ITEM.getKey(st.getItem()).toString());
		o.addProperty("count", st.getCount());
		o.addProperty("item_name", st.getHoverName().getString());
		return o;
	}

	/**
	 * When a block or an entity was last used, recording or not: a menu may be on its way. A click in his own inventory
	 * sent before the server has opened it lands in the chest's menu, where the server drops it, and his game kept the
	 * chestplate on that the server never put on (the scenarios of 23 Oct 2026). Stash.wearBest waits for it.
	 */
	public static volatile long lastUseAt;

	/** Every packet on its way out; only the few that are something the player did are written down. */
	public static void sent(Packet<?> packet) {
		if (packet instanceof ServerboundUseItemOnPacket || packet instanceof ServerboundInteractPacket) lastUseAt = System.currentTimeMillis();
		if (!on()) return;
		// off the game thread nothing of the world may be read; what the player does is sent from the game thread
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p == null || !mc.isSameThread()) return;
		try {
			if (packet instanceof ServerboundContainerClickPacket click) clicked(p, click);
			else if (packet instanceof ServerboundUseItemOnPacket use) used(mc, p, use);
			// the packet does not say which entity in every version; what the crosshair is on does
			else if (packet instanceof ServerboundInteractPacket && mc.hitResult instanceof EntityHitResult hit) write("entity_click", describe(mc, hit.getEntity()));
			else if (packet instanceof ServerboundSignUpdatePacket sign) {
				JsonObject o = new JsonObject();
				pos(o, Game.signPos(sign));
				JsonArray lines = new JsonArray();
				for (String line : Game.signTyped(sign)) lines.add(line);
				o.add("lines", lines);
				write("sign_typed", o);
			}
		} catch (RuntimeException e) {
			// a recording must never break the click it records
			Exchange.emit("error", "where", "recording", "notice", String.valueOf(e));
		}
	}

	private static void clicked(LocalPlayer p, ServerboundContainerClickPacket click) {
		JsonObject o = new JsonObject();
		o.addProperty("id", click.containerId());
		o.addProperty("slot", click.slotNum());
		o.addProperty("button", click.buttonNum());
		o.addProperty("mode", Game.clickKind(click));
		AbstractContainerMenu menu = p.containerMenu;
		if (menu.containerId == click.containerId()) {
			o.addProperty("title", MenuLog.titleOf(menu.containerId));
			int slot = click.slotNum();
			o.addProperty("own_inventory", slot >= menu.slots.size() - 36);
			// vanilla has already moved the item by now: what lay there is what the menu log saw last, this is what is left
			if (slot >= 0 && slot < menu.slots.size() && !menu.slots.get(slot).getItem().isEmpty()) o.add("in_slot", item(menu.slots.get(slot).getItem()));
			if (!menu.getCarried().isEmpty()) o.add("on_cursor", item(menu.getCarried()));
		}
		write("click", o);
	}

	private static void used(Minecraft mc, LocalPlayer p, ServerboundUseItemOnPacket use) {
		if (mc.level == null) return;
		BlockPos at = Game.useHit(use).getBlockPos();
		JsonObject o = new JsonObject();
		pos(o, at);
		o.addProperty("block", BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(at).getBlock()).toString());
		o.addProperty("side", Game.useHit(use).getDirection().getName());
		o.addProperty("hand", Game.useHand(use).name());
		o.addProperty("crouched", p.isShiftKeyDown());
		ItemStack held = p.getItemInHand(Game.useHand(use));
		if (!held.isEmpty()) o.add("held", item(held));
		if (mc.level.getBlockEntity(at) instanceof SignBlockEntity sign) {
			o.add("sign", lines(Game.signLines(sign, true)));
			o.add("sign_back", lines(Game.signLines(sign, false)));
		}
		write("block", o);
	}

	private static JsonArray lines(Component[] text) {
		JsonArray out = new JsonArray();
		for (Component line : text) out.add(line.getString());
		return out;
	}

	private static void pos(JsonObject o, BlockPos at) { o.addProperty("x", at.getX()); o.addProperty("y", at.getY()); o.addProperty("z", at.getZ()); }

	/** A line in chat, an action bar text, a title: as plain text, and as the server built it (colours, hover, click). */
	public static void shown(String kind, Component text) {
		// the sleepers' count above the hotbar, by either packet (Sleepers)
		if ("actionbar".equals(kind) && text != null) Sleepers.overlay(text);
		if (!on() || text == null) return;
		String plain = text.getString();
		JsonObject o = new JsonObject();
		o.addProperty("text", plain);
		LocalPlayer p = Minecraft.getInstance().player;
		if (p != null) {
			try { ComponentSerialization.CODEC.encodeStart(p.registryAccess().createSerializationContext(JsonOps.INSTANCE), text).result().ifPresent(j -> o.add("json", j)); }
			catch (RuntimeException ignored) { }
		}
		write(kind, o);
	}

	static void command(String command) {
		if (!on()) return;
		JsonObject o = new JsonObject();
		o.addProperty("text", "/" + command);
		write("command", o);
	}

	// ---------- what stands around the player ----------

	/** Further away than this a floating text is not readable anyway. */
	private static final int RANGE = 48;
	/** A list that refreshes every minute is written this often and no more. */
	private static final int MAX_VERSIONS = 12;

	private static final Map<UUID, Integer> seen = new HashMap<>(), versions = new HashMap<>();
	private static int ticks = 0;
	private static String lastDim = null, lastBars = null;
	private static double lastX, lastY, lastZ;
	private static boolean inPortal = false;

	private static double round(double v) { return Math.round(v * 100) / 100.0; }

	static void tick(Minecraft mc, LocalPlayer p) {
		if (!on()) { if (lastDim != null) { seen.clear(); versions.clear(); lastDim = null; lastBars = null; } return; }
		if (++ticks % 20 != 0 || mc.level == null) return;
		try {
			place(mc, p);
			if (ticks % 40 == 0) scan(mc, p);
		} catch (RuntimeException e) {
			Exchange.emit("error", "where", "recording", "notice", String.valueOf(e));
		}
	}

	/** Where the player is, whenever that changed much: a teleport shows up as two places far apart a second from each other. */
	private static void place(Minecraft mc, LocalPlayer p) {
		String dim = p.level().dimension().identifier().toString();
		String feet = BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(p.blockPosition()).getBlock()).toString();
		boolean portal = feet.contains("portal") || feet.contains("gateway");
		double moved = lastDim == null ? Double.MAX_VALUE : p.position().distanceTo(new net.minecraft.world.phys.Vec3(lastX, lastY, lastZ));
		if (dim.equals(lastDim) && moved < 6 && portal == inPortal) return;
		lastDim = dim; lastX = p.getX(); lastY = p.getY(); lastZ = p.getZ(); inPortal = portal;
		JsonObject o = new JsonObject();
		o.addProperty("feet_block", feet);
		write("place", o);
	}

	private static void scan(Minecraft mc, LocalPlayer p) {
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e == p || e.distanceToSqr(p) > RANGE * RANGE) continue;
			// a player the tab list does not show is a figure the server put there
			boolean figure = e instanceof Player pl && !AfkHelperClient.listed(pl);
			if (!(e instanceof Display || e instanceof Interaction || figure || (!(e instanceof Player) && e.hasCustomName()))) continue;
			JsonObject o = describe(mc, e);
			int print = o.toString().hashCode();
			Integer before = seen.get(e.getUUID());
			if (before != null && before == print) continue;
			int nr = versions.merge(e.getUUID(), 1, Integer::sum);
			if (nr > MAX_VERSIONS) continue;
			seen.put(e.getUUID(), print);
			write("world", o);
		}
	}

	/** An entity as the server sent it: its kind, its place, its name, and every value that differs from the default. */
	private static JsonObject describe(Minecraft mc, Entity e) {
		RegistryOps<JsonElement> ops = e.level().registryAccess().createSerializationContext(JsonOps.INSTANCE);
		JsonObject o = new JsonObject();
		o.addProperty("uuid", e.getUUID().toString());
		o.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
		o.addProperty("x", round(e.getX())); o.addProperty("y", round(e.getY())); o.addProperty("z", round(e.getZ()));
		// a figure turns its head to whoever walks by: its rotation would make every look a new version
		if (!(e instanceof Player)) { o.addProperty("yaw", Math.round(e.getYRot())); o.addProperty("pitch", Math.round(e.getXRot())); }
		if (e.getCustomName() != null) o.add("name", value(e.getCustomName(), ops));
		if (e instanceof Player pl) {
			o.addProperty("player", pl.getGameProfile().name());
			var conn = mc.getConnection();
			o.addProperty("in_tab_list", conn != null && conn.getPlayerInfo(e.getUUID()) != null);
			// the skin: a signed blob that says which texture to fetch
			for (var prop : pl.getGameProfile().properties().get("textures")) { o.addProperty("skin", prop.value()); break; }
		}
		JsonArray data = new JsonArray();
		for (var v : e.getEntityData().getNonDefaultValues()) {
			JsonObject d = new JsonObject();
			d.addProperty("id", v.id());
			d.add("value", value(v.value(), ops));
			data.add(d);
		}
		if (!data.isEmpty()) o.add("data", data);
		if (e instanceof LivingEntity living) {
			JsonObject worn = new JsonObject();
			for (EquipmentSlot slot : EquipmentSlot.values()) {
				ItemStack st;
				try { st = living.getItemBySlot(slot); } catch (RuntimeException ex) { continue; }   // a slot this kind of entity does not have
				if (!st.isEmpty()) worn.add(slot.getName(), value(st, ops));
			}
			if (!worn.isEmpty()) o.add("wears", worn);
		}
		return o;
	}

	private static JsonElement value(Object v, RegistryOps<JsonElement> ops) {
		if (v instanceof Optional<?> opt) return opt.isPresent() ? value(opt.get(), ops) : JsonNull.INSTANCE;
		if (v instanceof Component c) {
			JsonObject o = new JsonObject();
			o.addProperty("text", c.getString());
			try { ComponentSerialization.CODEC.encodeStart(ops, c).result().ifPresent(j -> o.add("json", j)); } catch (RuntimeException ignored) { }
			return o;
		}
		if (v instanceof ItemStack st) {
			if (st.isEmpty()) return JsonNull.INSTANCE;
			JsonObject o = item(st);
			// a chest that is no chest is an item with a model of its own: that stands in its components
			try { if (!st.getComponentsPatch().isEmpty()) DataComponentPatch.CODEC.encodeStart(ops, st.getComponentsPatch()).result().ifPresent(j -> o.add("comp", j)); } catch (RuntimeException ignored) { }
			return o;
		}
		if (v instanceof Number n) return new JsonPrimitive(n);
		if (v instanceof Boolean b) return new JsonPrimitive(b);
		return new JsonPrimitive(String.valueOf(v));
	}

	/** The bars at the top of the screen, whenever their texts change. */
	public static void bossBars(Collection<LerpingBossEvent> bars) {
		if (!on()) return;
		JsonArray texts = new JsonArray();
		for (LerpingBossEvent bar : bars) texts.add(bar.getName().getString());
		String now = texts.toString();
		if (now.equals(lastBars)) return;
		lastBars = now;
		JsonObject o = new JsonObject();
		o.add("texts", texts);
		write("bossbar", o);
	}
}
