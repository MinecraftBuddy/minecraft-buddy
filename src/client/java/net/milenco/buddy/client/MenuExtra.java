package net.milenco.buddy.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.network.protocol.game.ServerboundSetBeaconPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

/**
 * What a menu's own screen shows besides its slots, and what its buttons do, so that the panel can draw and work every
 * screen of the game (villagers, anvils, smithing tables and so on): a furnace's flame and arrow, a brewing stand's bubbles and fuel, an anvil's cost and
 * name, an enchanting table's three offers, a villager's trades, a stonecutter's and a loom's choices, a beacon's
 * effects, a crafter's switched-off slots. The slots themselves and their places come with the menu (Details.menu).
 * <p>
 * Commands, each naming the open menu ({@code menu}, its id) so that a click meant for a closed menu does nothing:
 * {@code menubutton} {@code id} (an enchanting offer 0 to 2, a stonecutter's or a loom's choice, as the game's
 * {@code handleInventoryButtonClick}); {@code trade} {@code index} (a villager's trade: the game's own selection, which
 * also moves what it costs into the slots); {@code rename} {@code text} (the anvil's name field); {@code beacon}
 * {@code primary}, {@code secondary} (effect ids, or empty); {@code slotstate} {@code slot}, {@code enabled} (a crafter's
 * slot switched off or on).
 */
final class MenuExtra {
	private MenuExtra() {}

	static Map<String, Object> of(Minecraft mc, AbstractContainerMenu m) {
		Map<String, Object> o = new LinkedHashMap<>();
		var level = mc.level;
		if (m instanceof AbstractFurnaceMenu f) {
			o.put("burn", f.getBurnProgress());
			o.put("lit", f.isLit() ? f.getLitProgress() : 0f);
		} else if (m instanceof BrewingStandMenu b) {
			o.put("brew", b.getBrewingTicks());
			o.put("brew_total", b.getTotalBrewingTicks());
			o.put("fuel", b.getFuel());
			o.put("fuel_total", b.getTotalFuel());
		} else if (m instanceof AnvilMenu a) {
			o.put("cost", a.getCost());
			o.put("name", anvilName);
		} else if (m instanceof EnchantmentMenu e) {
			List<Object> offers = new ArrayList<>();
			var reg = level == null ? null : level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
			for (int i = 0; i < 3; i++) {
				Map<String, Object> x = new LinkedHashMap<>();
				x.put("cost", e.costs[i]);
				String clue = null;
				if (reg != null && e.enchantClue[i] >= 0) {
					Enchantment en = reg.byId(e.enchantClue[i]);
					if (en != null) clue = Enchantment.getFullname(reg.wrapAsHolder(en), e.levelClue[i]).getString();
				}
				x.put("clue", clue);
				offers.add(x);
			}
			o.put("offers", offers);
			o.put("lapis", e.getGoldCount());
			o.put("seed", e.getEnchantmentSeed());
		} else if (m instanceof MerchantMenu mm) {
			List<Object> offers = new ArrayList<>();
			var list = mm.getOffers();
			for (int i = 0; i < list.size(); i++) {
				var of = list.get(i);
				Map<String, Object> x = new LinkedHashMap<>();
				x.put("a", stack(of.getCostA()));
				x.put("base", of.getBaseCostA().getCount());
				ItemStack b = of.getCostB();
				if (!b.isEmpty()) x.put("b", stack(b));
				x.put("result", stack(of.getResult()));
				x.put("uses", of.getUses());
				x.put("max", of.getMaxUses());
				x.put("out", of.isOutOfStock());
				x.put("xp", of.getXp());
				offers.add(x);
			}
			o.put("offers", offers);
			o.put("level", mm.getTraderLevel());
			o.put("xp", mm.getTraderXp());
			o.put("future_xp", mm.getFutureTraderXp());
			o.put("bar", mm.showProgressBar());
			o.put("selected", traded);
		} else if (m instanceof StonecutterMenu s) {
			List<Object> rs = new ArrayList<>();
			var ctx = level == null ? null : SlotDisplayContext.fromLevel(level);
			for (var en : s.getVisibleRecipes().entries()) rs.add(ctx == null ? null : stack(en.recipe().optionDisplay().resolveForFirstStack(ctx)));
			o.put("recipes", rs);
			o.put("selected", s.getSelectedRecipeIndex());
		} else if (m instanceof LoomMenu l) {
			List<Object> ps = new ArrayList<>();
			for (var h : l.getSelectablePatterns()) ps.add(h.unwrapKey().map(k -> k.identifier().toString()).orElse("?"));
			o.put("patterns", ps);
			o.put("selected", l.getSelectedBannerPatternIndex());
		} else if (m instanceof BeaconMenu b) {
			o.put("levels", b.getLevels());
			o.put("primary", effect(b.getPrimaryEffect()));
			o.put("secondary", effect(b.getSecondaryEffect()));
			o.put("payment", b.hasPayment());
		} else if (m instanceof net.minecraft.world.inventory.LecternMenu l) {
			o.put("page", l.getPage());
			o.put("book", Screens.written(l.getBook()));
		} else if (m instanceof CrafterMenu c) {
			List<Integer> off = new ArrayList<>();
			for (int i = 0; i < 9; i++) if (c.isSlotDisabled(i)) off.add(i);
			o.put("disabled", off);
			o.put("powered", c.isPowered());
		}
		return o;
	}

	/** The name typed in the anvil, and the trade picked at the villager (the game keeps neither where we can ask). */
	private static String anvilName = "";
	private static int traded = -1, menuOf = -1;

	private static Map<String, Object> stack(ItemStack st) {
		if (st == null || st.isEmpty()) return null;
		return Details.itemOf(st);
	}

	private static String effect(Holder<MobEffect> h) {
		return h == null ? null : BuiltInRegistries.MOB_EFFECT.getKey(h.value()).toString();
	}

	static void command(String cmd, JsonObject o, Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.getConnection() == null) return;
		AbstractContainerMenu m = p.containerMenu;
		if (m == p.inventoryMenu || !o.has("menu") || m.containerId != o.get("menu").getAsInt()) {
			Exchange.emit("error", "msg", cmd + ": that menu is no longer open");
			return;
		}
		if (menuOf != m.containerId) { menuOf = m.containerId; anvilName = ""; traded = -1; }
		MenuLog.lastMenuCommand = System.currentTimeMillis();
		Control.take(Control.Part.INVENTORY, "panel", Control.PANEL, 3000);
		switch (cmd) {
			case "menubutton" -> {
				int id = o.get("id").getAsInt();
				if (m.clickMenuButton(p, id)) mc.gameMode.handleInventoryButtonClick(m.containerId, id);
				else Exchange.emit("error", "msg", "menubutton: the game refuses button " + id);
			}
			case "trade" -> {
				if (!(m instanceof MerchantMenu mm)) return;
				int i = o.get("index").getAsInt();
				if (i < 0 || i >= mm.getOffers().size()) return;
				// as the game's own screen does when a trade is clicked
				traded = i;
				mm.setSelectionHint(i);
				mm.tryMoveItems(i);
				mc.getConnection().send(new ServerboundSelectTradePacket(i));
			}
			case "rename" -> {
				if (!(m instanceof AnvilMenu a)) return;
				String t = o.has("text") ? o.get("text").getAsString() : "";
				if (t.length() > AnvilMenu.MAX_NAME_LENGTH) t = t.substring(0, AnvilMenu.MAX_NAME_LENGTH);
				anvilName = t;
				if (a.setItemName(t)) mc.getConnection().send(new ServerboundRenameItemPacket(t));
			}
			case "slotstate" -> {
				// the crafter: a click on an empty slot switches it off or on, as its own screen does
				if (!(m instanceof CrafterMenu c)) return;
				int slot = o.get("slot").getAsInt();
				if (slot < 0 || slot > 8) return;
				boolean enabled = o.get("enabled").getAsBoolean();
				c.setSlotState(slot, enabled);
				mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket(slot, m.containerId, enabled));
			}
			case "beacon" -> {
				if (!(m instanceof BeaconMenu b)) return;
				var primary = effectOf(o, "primary");
				var secondary = effectOf(o, "secondary");
				// the server throws the buddy off for a choice the beacon refuses, with nothing in the payment slot too ("tried to
				// set invalid beacon effects", Paper on the test server, 31 Oct 2026): asked here first, as the game's Done
				// button is only lit then
				if (!b.hasPayment()) { Exchange.emit("error", "msg", "beacon: nothing in the payment slot"); return; }
				if (!net.minecraft.world.level.block.entity.BeaconBlockEntity.validateEffects(primary.orElse(null), secondary.orElse(null), b.getLevels())) {
					Exchange.emit("error", "msg", "beacon: that effect does not fit a beacon of level " + b.getLevels());
					return;
				}
				mc.getConnection().send(new ServerboundSetBeaconPacket(primary, secondary));
				mc.player.closeContainer();
			}
			default -> { }
		}
		Details.soon();
	}

	private static Optional<Holder<MobEffect>> effectOf(JsonObject o, String key) {
		if (!o.has(key) || o.get(key).isJsonNull() || o.get(key).getAsString().isEmpty()) return Optional.empty();
		return BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(o.get(key).getAsString())).map(h -> (Holder<MobEffect>) h);
	}
}
