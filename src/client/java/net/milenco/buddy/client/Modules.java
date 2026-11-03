package net.milenco.buddy.client;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;

import net.fabricmc.loader.api.FabricLoader;

/** The modules in the mods folder ({@link BuddyModule}), found at the start, and what reaches them. */
final class Modules {
	private Modules() {}

	private static final List<BuddyModule> loaded = new ArrayList<>();

	static void load() {
		for (BuddyModule m : FabricLoader.getInstance().getEntrypoints("minecraft-buddy:module", BuddyModule.class)) {
			loaded.add(m);
			Exchange.emit("module", "action", "loaded", "id", m.id());
		}
	}

	/** The ids of the modules in the mods folder (the panel and !status show them). */
	static List<String> ids() { return loaded.stream().map(BuddyModule::id).toList(); }

	/** A server line, to the modules this server's profile names. */
	static void heard(String line) {
		for (BuddyModule m : loaded) {
			if (Buddy.settings(m.id()) == null) continue;
			try { m.heard(line); } catch (RuntimeException e) { failed(m, e); }
		}
	}

	/** A client tick in a world, to the modules this server's profile names. */
	static void tick() {
		if (loaded.isEmpty()) return;
		for (BuddyModule m : loaded) {
			if (Buddy.settings(m.id()) == null) continue;
			try { m.tick(); } catch (RuntimeException e) { failed(m, e); }
		}
	}

	/** An exchange command the core does not know: true when a module took it. */
	static boolean command(String name, JsonObject o) {
		for (BuddyModule m : loaded) {
			try { if (m.command(name, o)) return true; } catch (RuntimeException e) { failed(m, e); return true; }
		}
		return false;
	}

	/** A module's mistake must not take the core down with it. */
	private static void failed(BuddyModule m, RuntimeException e) {
		Exchange.emit("error", "msg", "module " + m.id() + ": " + e);
	}
}
