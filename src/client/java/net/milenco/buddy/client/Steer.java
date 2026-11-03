package net.milenco.buddy.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.milenco.buddy.version.Game;

/**
 * Steering from the web panel: the buddy with WASD and the mouse from the website, for instance to move the buddy for a
 * while. The panel's server writes steer.json in the exchange folder whenever the page sends its keys,
 * and again every quarter of a second while the page has control: what is held (w a s d, jump, sneak, sprint, the left
 * mouse button), how far the mouse turned the buddy since the page took control, a count of right clicks and the hotbar
 * slot. It is read in the tick in which the file changed. While it is fresh (under {@link #FRESH} ms old) no job moves
 * the buddy: the following, the base and the farms wait and their keys are let go. When it goes stale (the page closed
 * or gave control back, the connection dropped) every key is let go and the jobs carry on as they were; who followed
 * someone follows again, so "stay" (volg blijf) before giving control back keeps the buddy at the spot where you stopped.
 */
final class Steer {
	private Steer() {}

	static final long FRESH = 1000;
	/** The most the buddy turns in one tick: a mouse flicked across the page does not snap the view round. */
	private static final float TURN = 40f;

	private static long seen = Long.MIN_VALUE, written;
	private static String session = "";
	private static boolean on, up, down, left, right, jump, sneak, sprint, attack, breaking, use;
	private static float yaw0, pitch0, yaw, pitch;
	private static int uses, usesDone, slot = -1, slotSet = -1;
	/** Ticks until the right button held uses again: four, as the game's own use key (rightClickDelay). */
	private static int useDelay;
	/** The block the page's 3D view has under its crosshair and its side (31 Oct 2026): a click goes there, as the
	 *  person sees it, when it is within reach. The game's own crosshair (hitResult) of a client without a window
	 *  went to nothing now and then while the buddy stood still. */
	private static BlockPos target;
	private static Direction targetFace;
	/** Or the entity the crosshair is on (a villager, a mob, a player), by its number in the buddy's game. */
	private static int targetEntity = -1;

	static boolean active() { return on; }

	/** Frames a second while the page plays the buddy (the buddy's own setting otherwise). */
	private static final int STEER_FPS = 40;

	/** The panel holds the left button on a block: the game's own stop of the breaking stays out (ContinueAttackMixin). */
	static boolean breakingNow() { return on && breaking; }

	private static Path file() { return Paths.get(AfkConfig.get().exchangeDir).resolve("steer.json"); }

	/** The file again, if it changed since the last look. */
	private static void read(LocalPlayer p) {
		Path f = file();
		long m;
		try { m = Files.getLastModifiedTime(f).toMillis(); } catch (IOException e) { m = -1; }
		if (m == seen) return;
		seen = m;
		written = 0;
		if (m < 0) return;
		try {
			JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
			if (!o.has("on") || !o.get("on").getAsBoolean()) return;
			String s = o.has("session") ? o.get("session").getAsString() : "";
			int clicks = o.has("right") ? o.get("right").getAsInt() : 0;
			if (!s.equals(session)) {
				// a new hold of the controls: the turn counts from where the buddy looks now, and old right clicks are done
				session = s;
				yaw0 = p.getYRot();
				pitch0 = p.getXRot();
				usesDone = clicks;
				slotSet = -1;
			}
			Set<String> keys = Set.of((o.has("keys") ? o.get("keys").getAsString() : "").split("\\s+"));
			up = keys.contains("w"); down = keys.contains("s"); left = keys.contains("a"); right = keys.contains("d");
			jump = keys.contains("jump"); sneak = keys.contains("sneak"); sprint = keys.contains("sprint"); attack = keys.contains("left");
			// the right button held: the game's use key down, so that eating, a bow, a shield or a spyglass go on as in the game
			use = keys.contains("use");
			yaw = o.has("yaw") ? o.get("yaw").getAsFloat() : 0;
			pitch = o.has("pitch") ? o.get("pitch").getAsFloat() : 0;
			uses = clicks;
			slot = o.has("slot") ? o.get("slot").getAsInt() : -1;
			target = null;
			targetEntity = -1;
			if (o.has("target") && o.get("target").isJsonObject() && o.getAsJsonObject("target").has("entity")) {
				targetEntity = o.getAsJsonObject("target").get("entity").getAsInt();
			} else if (o.has("target") && o.get("target").isJsonObject()) {
				JsonObject tg = o.getAsJsonObject("target");
				Direction d = Direction.byName(tg.get("face").getAsString());
				if (d != null) { target = new BlockPos(tg.get("x").getAsInt(), tg.get("y").getAsInt(), tg.get("z").getAsInt()); targetFace = d; }
			}
			written = o.has("t") ? o.get("t").getAsLong() : 0;
		} catch (IOException | RuntimeException e) {
			// the server writes the file whole (a new file renamed over the old), so this is a bad line: no steering
		}
	}

	/** Every tick, before the jobs: true while the page steers the buddy, and then no job moves the buddy. */
	static boolean tick(Minecraft mc, LocalPlayer p) {
		read(p);
		boolean fresh = written > 0 && System.currentTimeMillis() - written < FRESH;
		if (fresh && !on) {
			on = true;
			// the buddy's game draws ten frames a second (maxFps:10, to spare the machine), and so does two ticks in one
			// frame: the places then reach the page in pairs, and the view moved unevenly. Forty while
			// played, without touching the options file.
			mc.getFramerateLimitTracker().setFramerateLimit(Math.max(STEER_FPS, mc.options.framerateLimit().get()));
			HeldKeys.releaseAll();
			Exchange.emit("steer", "action", "on");
		} else if (!fresh && on) {
			on = false;
			mc.getFramerateLimitTracker().setFramerateLimit(mc.options.framerateLimit().get());
			keys(mc, false, false, false, false, false, false, false);
			if (breaking && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
			breaking = false;
			Exchange.emit("steer", "action", "loose", "x", p.getBlockX(), "y", p.getBlockY(), "z", p.getBlockZ());
			return false;
		}
		if (!on) return false;
		for (Control.Part part : new Control.Part[] { Control.Part.MOVE, Control.Part.LOOK, Control.Part.HAND })
			Control.take(part, "panel", Control.PANEL, FRESH);
		keys(mc, up, down, left, right, jump, sneak, sprint);
		float wantYaw = yaw0 + yaw, wantPitch = Mth.clamp(pitch0 + pitch, -90f, 90f);
		MouseTurn.toward(p, p.getYRot() + Mth.clamp(Mth.wrapDegrees(wantYaw - p.getYRot()), -TURN, TURN),
			p.getXRot() + Mth.clamp(wantPitch - p.getXRot(), -TURN, TURN));
		// the page's slot when the page changes it: the server may choose another (a middle click picks the block's item
		// from the hotbar), and the page then takes that over from the state
		if (slot >= 0 && slot < 9 && slot != slotSet) { slotSet = slot; if (p.getInventory().getSelectedSlot() != slot) p.getInventory().setSelectedSlot(slot); }
		return true;
	}

	private static void keys(Minecraft mc, boolean w, boolean s, boolean a, boolean d, boolean space, boolean shift, boolean run) {
		HeldKeys.set(mc.options.keyUp, w);
		HeldKeys.set(mc.options.keyDown, s);
		HeldKeys.set(mc.options.keyLeft, a);
		HeldKeys.set(mc.options.keyRight, d);
		HeldKeys.set(mc.options.keyJump, space);
		HeldKeys.set(mc.options.keyShift, shift);
		HeldKeys.set(mc.options.keySprint, run);
		// the game's use key only while something is in use (eating, a bow, a shield): then the game goes on with it and lets
		// go when the button does. Otherwise the game would click again by itself, at its own crosshair, at once and every
		// four ticks, besides the click of the page (blocks were placed faster than the game places them)
		HeldKeys.set(mc.options.keyUse, use && on && mc.player != null && mc.player.isUsingItem());
	}

	/**
	 * The start of the tick, where vanilla clicks: a right click uses what the crosshair is on (an entity, a block, else
	 * the item in hand), the left button held breaks the block looked at or hits what stands there, once the swing
	 * has charged.
	 */
	static void startTick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (!on || p == null || mc.gameMode == null) return;
		if (Game.openScreen(mc) != null) {
			// a screen of the game stands open (a menu the server closed without the client noticing, a chat box): a click
			// would do nothing, so it is said, once per click
			if (uses != usesDone) { usesDone = uses; Exchange.emit("steer", "action", "use refused", "screen", Game.openScreen(mc).getClass().getSimpleName()); }
			return;
		}
		HitResult hit = mc.hitResult;
		// the page's entity, where it stands, within reach
		if (targetEntity >= 0) {
			Entity e = p.level().getEntity(targetEntity);
			hit = e != null && e.isAlive() && p.isWithinEntityInteractionRange(e, 1.0) ? new EntityHitResult(e, e.getBoundingBox().getCenter()) : null;
		}
		BlockHitResult block = hit instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK ? b : null;
		// the page's target, where the page shows it: the middle of that side of the block, within reach
		if (target != null && targetEntity < 0 && !(hit instanceof EntityHitResult) && !p.level().getBlockState(target).isAir()) {
			Vec3 at = Vec3.atCenterOf(target).add(targetFace.getStepX() * 0.5, targetFace.getStepY() * 0.5, targetFace.getStepZ() * 0.5);
			if (p.getEyePosition().distanceTo(at) <= p.blockInteractionRange() + 0.5) block = new BlockHitResult(at, targetFace, target, false);
			else block = null;
		}
		boolean click = uses != usesDone;
		if (click) usesDone = uses;
		if (useDelay > 0) useDelay--;
		// held: again every four ticks, as the game does, while the item is not in use already (eating, drawing a bow)
		if (click || use && useDelay == 0 && !p.isUsingItem()) {
			useDelay = 4;
			InteractionResult r = InteractionResult.PASS;
			// an entity only on the click itself, not again every four ticks of the button held: on a server far away its menu
			// comes a few hundred milliseconds after the click, and every repeat before that opened it once more, so that it
			// came back after each close (seen at an NPC)
			if (click && hit instanceof EntityHitResult eh) r = Game.interactEntity(mc.gameMode, p, eh.getEntity(), eh, InteractionHand.MAIN_HAND);
			if (!r.consumesAction() && block != null) r = AfkHelperClient.useOn(p, InteractionHand.MAIN_HAND, block);
			if (!r.consumesAction()) r = mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
			if (r.consumesAction()) Game.swingUse(p, InteractionHand.MAIN_HAND);
			// started eating or drawing: the use key down at once, or the game lets go of it this very tick
			if (use && p.isUsingItem()) HeldKeys.set(mc.options.keyUse, true);
			// a line per click, not per repeat of the button held
			if (click) Exchange.emit("steer", "action", "use", "on", hit instanceof EntityHitResult ? "entity" : block != null ? BuiltInRegistries.BLOCK.getKey(p.level().getBlockState(block.getBlockPos()).getBlock()).getPath() : "air",
				"x", block != null ? block.getBlockPos().getX() : 0, "y", block != null ? block.getBlockPos().getY() : 0, "z", block != null ? block.getBlockPos().getZ() : 0,
				"page", target != null || targetEntity >= 0, "result", r.getClass().getSimpleName());
		}
		if (attack && hit instanceof EntityHitResult eh) {
			if (breaking) { mc.gameMode.stopDestroyBlock(); breaking = false; }
			if (p.getAttackStrengthScale(0.5f) >= 1f) { mc.gameMode.attack(p, eh.getEntity()); Game.swingAttack(p); }
		} else if (attack && block != null) {
			// a new press begins anew, as the game's own click does (startAttack): continueDestroyBlock alone, on the block
			// of a press that was let go, counted on in the buddy's game without telling the server, which never broke it
			boolean go = breaking ? mc.gameMode.continueDestroyBlock(block.getBlockPos(), block.getDirection())
				: mc.gameMode.startDestroyBlock(block.getBlockPos(), block.getDirection());
			if (go) Game.swingAttack(p);
			breaking = true;
		} else if (breaking) {
			mc.gameMode.stopDestroyBlock();
			breaking = false;
		}
	}
}
