package net.milenco.buddy.client;

/**
 * The buttons at the top of the menu: mod, AFK, mob farming and raid farm.
 * Everything works right away and saves itself, so you never have to press "Save" first.
 *
 * Mob farming and the raid farm exclude each other: while mob farming runs, raid mode is off,
 * and afterwards it comes back - but only if you have not switched it yourself in the meantime.
 */
public class Switches {
	private static Boolean raidBefore = null;    // raid mode as it was when mob farming went on
	private static Boolean drinkBefore = null;   // bottles as they were set before the raid farm or "finishing" touched them
	private static boolean finishing = false;

	// ---------- the mod as a whole ----------
	public static void setMod(boolean on) {
		AfkConfig c = AfkConfig.get();
		if (c.modEnabled == on) return;
		c.modEnabled = on;
		AfkConfig.save();
		if (!on) { Jobs.stop("mod off"); Wander.abort("mod off"); }
		Exchange.emit("mod", "on", on);
	}

	// ---------- AFK ----------
	public static void setAfk(boolean on) {
		AfkConfig c = AfkConfig.get();
		if (c.afk == on) return;
		c.afk = on;
		c.afkAuto = false;   // set by hand: then it does not go off by itself on movement
		AfkConfig.save();
		Exchange.emit("config_set", "key", "afk", "value", on, "reason", "button");
	}

	// ---------- mob farming ----------
	public static boolean mobFarmOn() { return Grinder.active() && !AfkConfig.get().raidMode; }

	public static void setMobFarm(boolean on) {
		AfkConfig c = AfkConfig.get();
		// on the alt a pen he farms goes first: the AFK farm would take him into its own way of hitting (a Telegram !farm)
		if (on && Pen.on()) { Exchange.emit("error", "msg", "switch mobfarm: stop the pen first"); return; }
		if (on) {
			if (raidBefore == null) raidBefore = c.raidMode;
			if (c.raidMode) { c.raidMode = false; AfkConfig.save(); }
			if (!Grinder.active()) Grinder.start();
		} else {
			if (Grinder.active()) Grinder.stop("button");
			restoreRaid();
		}
	}

	/** Put raid mode back as it was, unless you have touched it yourself in the meantime. */
	private static void restoreRaid() {
		AfkConfig c = AfkConfig.get();
		if (raidBefore == null) return;
		if (!c.raidMode && raidBefore) { c.raidMode = true; AfkConfig.save(); }
		raidBefore = null;
	}

	// ---------- woodcutting: off -> on -> finishing -> off ----------
	public static boolean woodOn() { return Lumberjack.isOn(); }
	public static void setWood(boolean on) { if (on) { if (!Lumberjack.isOn()) Lumberjack.start(); } else Lumberjack.stop("button"); }

	public static String woodState() { return !Lumberjack.isOn() ? "off" : Lumberjack.finishing() ? "finishing" : "on"; }

	// ---------- building the Litematica placement ----------
	public static boolean buildOn() { return Builder.isOn(); }
	public static void setBuild(boolean on) { if (on) Builder.start(); else Builder.stop("button"); }

	// ---------- planting and harvesting the crop under the crosshair ----------
	public static boolean cropOn() { return CropFarmer.isOn(); }
	public static void setCrop(boolean on) { if (on) CropFarmer.start(); else CropFarmer.stop("button"); }

	// ---------- walking the wheat field: stays on over a restart ----------
	public static boolean wheatOn() { return WheatFarm.isOn() || WheatFarm.wanted(); }
	public static void setWheat(boolean on) { if (on) WheatFarm.start(); else WheatFarm.stop("button"); }

	// ---------- raid farm: off -> on -> finishing -> off ----------
	public static String raidState() {
		if (finishing) return "finishing";
		return AfkConfig.get().raidMode && Grinder.active() ? "on" : "off";
	}

	public static void setRaid(boolean on) {
		AfkConfig c = AfkConfig.get();
		raidBefore = null;   // you are choosing yourself now, so nothing left to put back
		if (c.raidMode != on) { c.raidMode = on; AfkConfig.save(); }
		if (on) {
			/*
			 * Without bottles the farm only waits: if there is no raid, nothing happens. Whoever turns the
			 * raid farm on wants it to start one itself, so we turn drinking on and remember how it was
			 * set; when turning off it goes back to how you had it.
			 */
			if (drinkBefore == null) drinkBefore = c.raidDrink;
			if (!c.raidDrink) { c.raidDrink = true; AfkConfig.save(); }
			if (!Grinder.active()) Grinder.start();
		} else {
			if (Grinder.active()) Grinder.stop("raid farm off");
			if (drinkBefore != null) {
				if (c.raidDrink != drinkBefore) { c.raidDrink = drinkBefore; AfkConfig.save(); }
				drinkBefore = null;
			}
			finishing = false;
		}
	}

	/** Finish this raid: no new bottle any more, after that the farm stops by itself. */
	public static void startFinishing() {
		AfkConfig c = AfkConfig.get();
		if (!c.raidMode) return;
		finishing = true;
		if (drinkBefore == null) drinkBefore = c.raidDrink;
		if (c.raidDrink) { c.raidDrink = false; AfkConfig.save(); }
		int round = Math.max(1, RaidState.round());
	}

	private static void stopFinishing(boolean restore) {
		AfkConfig c = AfkConfig.get();
		finishing = false;
		if (drinkBefore != null) {
			if (restore && !c.raidDrink && drinkBefore) { c.raidDrink = true; AfkConfig.save(); }
			drinkBefore = null;
		}
	}

	/**
	 * Is there still a raid coming? Right after a bottle the raid has not started yet: you have Bad Omen
	 * and still have to enter a village. Then "finishing" must not stop the farm right away.
	 */
	private static boolean raidUnderWay() {
		if (RaidState.inProgress()) return true;
		var p = AfkHelperClient.mc().player;
		if (p != null && (p.hasEffect(net.minecraft.world.effect.MobEffects.BAD_OMEN)
				|| p.hasEffect(net.minecraft.world.effect.MobEffects.RAID_OMEN))) return true;
		long bottle = AfkHelperClient.lastBottle();
		if (bottle == 0 || RaidState.seenSinceBottle()) return false;   // no bottle open any more
		return AfkHelperClient.bottlePending(p);                         // bottle drunk, raid still on its way
	}

	/** Every second from the tick: finish the raid and only then put the bottle drinking back. */
	public static void tick() {
		if (!finishing) return;
		if (Grinder.active()) {
			if (!raidUnderWay()) Grinder.stop("raid rounded off");
			return;
		}
		// the farm is off: now the bottle drinking may go back on
		stopFinishing(true);
		restoreRaid();
	}
}
