package net.milenco.buddy.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * Turning like a real mouse: vanilla converts a mouse movement into whole "pixels" times a fixed step that depends
 * on your sensitivity. We round the wanted turn to that step and use the same function as the mouse (turn).
 */
public class MouseTurn {
	/** Value per mouse pixel for Entity.turn (vanilla: (sensitivity*0.6+0.2)^3 * 8). */
	static double perPixel() {
		double s = Minecraft.getInstance().options.sensitivity().get() * 0.6 + 0.2;
		return s * s * s * 8.0;
	}

	/** Turn toward this view direction, rounded to whole mouse pixels. Returns true if a turn was made. */
	public static boolean toward(LocalPlayer p, float yaw, float pitch) { return toward(p, yaw, pitch, 0.5f); }

	/**
	 * With a dead zone of its own: a click on a chest with an item frame on it has a strip of a sixteenth to hit, about
	 * half a degree at three blocks, so there the aim goes to a pixel (22 Oct 2026). The target does not move, so no stutter.
	 */
	public static boolean toward(LocalPlayer p, float yaw, float pitch, float dead) {
		double e = perPixel(), deg = e * 0.15;
		float dy = Mth.wrapDegrees(yaw - p.getYRot()), dp = Mth.clamp(pitch, -90f, 90f) - p.getXRot();
		/*
		 * Dead zone of half a degree. Without it the buddy stuttered while planting: the target got a fresh
		 * half-degree noise every tick, and at a normal sensitivity that is already a few mouse pixels
		 * back and forth, twenty times per second (26 Sep).
		 */
		if (Math.abs(dy) < dead && Math.abs(dp) < dead) return false;
		long px = Math.round(dy / deg);
		long py = Math.round(dp / deg);
		if (px == 0 && py == 0) return false;
		p.turn(px * e, py * e);
		return true;
	}
}
