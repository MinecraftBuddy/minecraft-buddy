package net.milenco.buddy.client;

import com.google.gson.JsonObject;

/**
 * Something one server has that the buddy can take part in, in a jar of its own next to the core, so that the core knows
 * no server and no coordinates and can be shipped or used without any server's extras. The core finds a module through
 * the Fabric entrypoint {@code minecraft-buddy:module} in the module's {@code fabric.mod.json}.
 *
 * <p>A module is on for a server when that server's profile in {@code buddy.json} names it under {@code modules}, with
 * its settings: {@code "modules": {"parkour": {"dim": "events:arenas", "finish": [1676, 104, 1]}}}. Coordinates and
 * other facts of the server live there, never in the module. {@link Buddy} is what a module may use of the core.
 */
public interface BuddyModule {
	/** Its name: the key of its settings under a profile's {@code modules}, and the name of its exchange command. */
	String id();

	/**
	 * An exchange command ({@code {"cmd": ...}} from the panel or the Telegram bridge) that the core does not know.
	 * True when this module took it. Commands come whether or not the module is on for this server, so that it can say
	 * why it does nothing ({@link Buddy#settings} is null where it is off).
	 */
	default boolean command(String name, JsonObject o) { return false; }

	/** A line the server sent (not a player's chat), only on a server whose profile names this module. */
	default void heard(String line) {}

	/** Every client tick in a world, only on a server whose profile names this module. Keep it cheap. */
	default void tick() {}
}
