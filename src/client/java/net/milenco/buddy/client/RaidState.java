package net.milenco.buddy.client;

import net.minecraft.world.BossEvent;

import java.util.Collection;
import java.util.Locale;

/** State of the raid, derived from the bossbar ("Overval", "Overval - overwinning"). */
public class RaidState {
	private static boolean inProgress = false, seenSinceBottle = false;
	private static int round = 0;          // which raid this is since the farm went on
	private static int wave = 0, remaining = -1, waves = 0;   // wave within this raid, raiders remaining, expected number of waves
	private static String lastName = "";
	private static int waveFromBar = 0, wavesFromBar = 0;
	// The bar = health of the raiders still standing, divided by the health the wave started with.
	// When a wave spawns it is therefore full; after that it drains. Every time it fills up again
	// is a new wave. Measuring rises did not work: a captain brings in mobs in the middle of a
	// wave, and then the mod counted six waves in two minutes.
	private static boolean barFull = false;
	private static long waveChanged = 0;
	private static float highestBar = 0;       // highest level this raid, to see whether the bar really fills up
	private static float reportedBar = -1;     // last logged level
	private static long reportedAt = 0;
	private static int level = 1;   // omen level of the bottle you drank (1-5)
	private static long victoryAt = 0, lastInProgress = 0, startedAt = 0;

	private static final float FULL = 0.98f;     // the bar does not get higher; the float from the packet is not exactly 1
	private static final float EMPTY_ENOUGH = 0.90f;   // only below this can the bar become "full" again

	public static boolean inProgress() { return inProgress; }
	/** Which raid this is since the farm went on. */
	public static int round() { return round; }
	/** Wave within the running raid (0 = no raid). */
	public static int wave() { return inProgress ? Math.max(1, wave) : 0; }
	public static void newSession() { round = 0; }
	/** Moment of the last victory (0 = none seen yet). */
	public static long victoryAt() { return victoryAt; }
	public static long lastInProgress() { return lastInProgress; }
	/** Has there been a raid since the last bottle? */
	public static boolean seenSinceBottle() { return seenSinceBottle; }
	public static void bottleDrunk() { seenSinceBottle = false; victoryAt = 0; }
	/** Level of the bottle just drunk (1-5). */
	public static void bottleLevel(int n) { level = Math.max(1, n); RaidLog.bottle(level); }

	public static void update(Collection<? extends BossEvent> events) {
		boolean raid = false, won = false;
		int left = -1;
		float progress = -1;
		for (BossEvent e : events) {
			String name = e.getName().getString().toLowerCase(Locale.ROOT);
			if (!(name.contains("overval") || name.contains("raid"))) continue;
			if (name.contains("overwinning") || name.contains("victory")) won = true;
			else if (name.contains("verloren") || name.contains("defeat")) won = true;   // also over
			else {
				raid = true;
				String raw = e.getName().getString();
				progress = e.getProgress();
				if (!raw.equals(lastName)) {   // so we can see exactly what the server puts in the bar
					lastName = raw;
					Exchange.emit("raid", "action", "bossbar", "text", raw, "progress", rounded(progress));
				}
				// If the bar says "3/8", that is the wave itself and we do not need to count anything
				java.util.regex.Matcher mm = java.util.regex.Pattern.compile("(\\d+)\\s*/\\s*(\\d+)").matcher(raw);
				if (mm.find()) { waveFromBar = Integer.parseInt(mm.group(1)); wavesFromBar = Integer.parseInt(mm.group(2)); }
				else { int n = fromName(e.getName()); if (n >= 0) left = n; }
			}
		}
		if (raid) {
			if (!inProgress) {   // just started
				round++; wave = 0; remaining = -1; waveFromBar = 0; wavesFromBar = 0;
				barFull = false; waveChanged = 0; highestBar = 0; reportedBar = -1;
				startedAt = System.currentTimeMillis();
				waves = expectedWaves();
			}
			long now = System.currentTimeMillis();
			// The server only puts the count in the bar with two raiders or fewer left; when the next wave
			// spawns the bar goes back to plain "Raid". Keeping the old number left the HUD on "1 left"
			// for the whole next wave, so no number means unknown again.
			remaining = left;
			if (progress >= 0) {
				if (progress > highestBar) highestBar = progress;
				// The bar can be full for a few packets in a row when spawning; only the first counts,
				// and only after it has really dropped through does it count again.
				if (progress >= FULL) {
					if (!barFull && now - waveChanged > 3000) {
						wave++; waveChanged = now;
						Exchange.emit("raid", "action", "new wave", "wave", wave, "from", waves, "progress", rounded(progress));
					}
					barFull = true;
				} else if (progress < EMPTY_ENOUGH) barFull = false;
				// read along the raw level: that way you can see afterwards whether the bar really fills up per wave
				if (reportedBar < 0 || Math.abs(progress - reportedBar) >= 0.15f || now - reportedAt > 10000) {
					reportedBar = progress; reportedAt = now;
					Exchange.emit("raid", "action", "bar", "progress", rounded(progress), "wave", wave, "left", remaining);
				}
			}
			if (waveFromBar > 0) { wave = waveFromBar; if (wavesFromBar > 0) waves = wavesFromBar; }
			inProgress = true; seenSinceBottle = true; lastInProgress = System.currentTimeMillis();
		}
		if (won || (!raid && inProgress)) {
			if (inProgress) {
				victoryAt = System.currentTimeMillis();
				Exchange.emit("raid", "action", "past", "won", won, "waves", wave, "highest_bar", rounded(highestBar));
				RaidLog.raid(startedAt, wave, level, won);
			}
			inProgress = false; wave = 0; remaining = -1; barFull = false; highestBar = 0; reportedBar = -1;
		}
	}

	private static double rounded(float f) { return Math.round(f * 100) / 100.0; }

	/** "Overvallers over: 7" / "Raiders Remaining: 7" -> 7. The bossbar is translated, so we take the number. */
	private static int fromName(net.minecraft.network.chat.Component name) {
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(name.getString());
		return m.find() ? Integer.parseInt(m.group(1)) : -1;
	}

	/** The number of waves depends on the difficulty; an omen of level 2 or higher gives one extra. */
	private static int expectedWaves() {
		int learned = RaidLog.learnedWaves(level);   // what this server really does at this level
		if (learned > 0) return learned;
		var mc = net.minecraft.client.Minecraft.getInstance();
		if (mc.level == null) return 0;
		int base = switch (mc.level.getDifficulty()) {
			case PEACEFUL, EASY -> 3;
			case NORMAL -> 5;
			case HARD -> 7;
		};
		int n = level;   // from the bottle we drank; the effect is already gone when the raid starts
		var p = mc.player;
		if (p != null) {
			var eff = p.getEffect(net.minecraft.world.effect.MobEffects.RAID_OMEN);
			if (eff == null) eff = p.getEffect(net.minecraft.world.effect.MobEffects.BAD_OMEN);
			if (eff != null) n = Math.max(n, eff.getAmplifier() + 1);
		}
		return n > 1 ? base + 1 : base;   // from level 2 vanilla spawns one bonus wave
	}
}
