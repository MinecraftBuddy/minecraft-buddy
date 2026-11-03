package net.milenco.buddy.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.milenco.buddy.version.Game;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * The keys the mod holds itself. Vanilla lets go of every key when a screen opens and resyncs them to the keyboard
 * when it closes, so a step, a nudge or a walk to the chest stopped the moment you opened the chat and did not come
 * back after closing it. Every start of the tick, before the player moves, they are pressed again, but only with the
 * chat or the Esc menu open: behind a chest, the inventory or a menu they stay released, like for a player, and come
 * back once it closes. The Esc menu used to release them too, while the farm kept hitting behind it: every switch to
 * the other side of the landing spot failed, and five minutes later (3 Oct 02:45) the server sent you to the AFK world.
 * The server does not see that menu, and it also opens by itself when the window loses focus.
 */
final class HeldKeys {
	private static final Set<KeyMapping> held = Collections.newSetFromMap(new IdentityHashMap<>());

	private HeldKeys() {}

	static void set(KeyMapping key, boolean down) {
		key.setDown(down);
		if (down) held.add(key); else held.remove(key);
	}

	/** Every key the mod holds let go: the web panel takes over the controls (Steer). */
	static void releaseAll() {
		for (KeyMapping key : held) key.setDown(false);
		held.clear();
	}

	static void startTick(Minecraft mc) {
		if (held.isEmpty()) return;
		var screen = Game.openScreen(mc);
		boolean down = screen == null || screen instanceof net.minecraft.client.gui.screens.ChatScreen
				|| screen instanceof net.minecraft.client.gui.screens.PauseScreen;
		for (KeyMapping key : held) key.setDown(down);
	}
}
