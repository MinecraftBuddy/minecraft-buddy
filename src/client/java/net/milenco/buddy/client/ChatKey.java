package net.milenco.buddy.client;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.LocalChatSession;
import net.minecraft.world.entity.player.ProfileKeyPair;
import net.milenco.buddy.mixin.client.ChatSessionAccessor;

/**
 * Keeps chat working in a game that runs for days. The game signs every chat line with a key from Mojang that lasts two
 * days, and fetches the next key with the login it started with, which lasts one day. So a game that has run that long
 * cannot renew its key (Mojang's refusal is not even logged), and once the key has expired the server refuses every chat
 * line: "Chat disabled due to expired profile public key". Joining again does not help: only a new start of the game logs
 * in afresh. So once the key is due for renewal and still in use, the game leaves the server at a quiet moment and quits,
 * and the container starts it again, as after too many failed joins. A busy buddy delays that until shortly after the key
 * has expired (chat is dead then anyway); a fight, a fall or a flight always does.
 */
final class ChatKey {
	/** The game asks for a new key when it is due, and again every hour: this long after the due time, that has failed. */
	static final Duration FAILED = Duration.ofMinutes(10);
	/** This long after the key has expired, a job no longer delays the restart. */
	static final Duration LATE = Duration.ofMinutes(10);
	/** A game younger than this never quits for its key: a start that brought no new key would otherwise restart again and again. */
	private static final long MIN_UPTIME_MS = 6 * 3_600_000L;
	static final String REASON = "restarting the game so that chat keeps working (needed every two days)";

	private static long checkedAt;
	private static boolean quitting;

	private ChatKey() { }

	/** In the world, every tick; looks every five seconds. */
	static void tick(Minecraft mc, LocalPlayer p) {
		long now = System.currentTimeMillis();
		if (quitting || now - checkedAt < 5_000) return;
		checkedAt = now;
		AfkConfig c = AfkConfig.get();
		// the game is only started again, and joins, where quitting after failed joins is on
		if (!c.autoReconnect || c.reconnectMaxAttempts <= 0) return;
		if (ManagementFactory.getRuntimeMXBean().getUptime() < MIN_UPTIME_MS) return;
		var connection = mc.getConnection();
		LocalChatSession session = connection == null ? null : ((ChatSessionAccessor) connection).buddy$chatSession();
		if (session == null) return;      // no signed chat: an offline account, or no key to begin with
		ProfileKeyPair key = session.keyPair();
		boolean safe = !AfkHelperClient.inCombat() && (p.onGround() || p.isPassenger()) && !p.isInWater() && !p.isInLava()
			&& !p.isOnFire() && p.hurtTime == 0 && p.getHealth() > p.getMaxHealth() / 2;
		boolean quiet = !Jobs.anySteering() && !Control.panel();
		Instant expires = key.publicKey().data().expiresAt();
		if (!restartNow(Instant.now(), key.refreshedAfter(), expires, safe, quiet)) return;
		quitting = true;
		Exchange.emit("chat_key", "action", "restart", "renew_due", key.refreshedAfter().toString(), "expires", expires.toString(),
			"uptime_h", ManagementFactory.getRuntimeMXBean().getUptime() / 3_600_000L);
		AfkHelperClient.logOut(REASON, true);
	}

	/**
	 * Whether the game quits now for a new key: the key in use is past its renewal by {@link #FAILED}, so the game could not
	 * renew it, and the moment is safe, and the buddy idle or the key expired by {@link #LATE}.
	 */
	static boolean restartNow(Instant now, Instant renewDue, Instant expires, boolean safe, boolean quiet) {
		if (now.isBefore(renewDue.plus(FAILED)) || !safe) return false;
		return quiet || now.isAfter(expires.plus(LATE));
	}
}
