package net.milenco.buddy.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

/**
 * Ends the JVM as soon as Minecraft has finished shutting down.
 *
 * Other mods leave non-daemon threads behind that nobody stops (SeedCrackerX: two fixed pools of five threads,
 * JEI: its "Deduplicating Run Executor"). After main returned, those kept the JVM alive, so quitting with Cmd+Q
 * hung for 15 seconds until Minecraft's ClientShutdownWatchdog halted the process, wrote a "Client shutdown from
 * post-main" crash report and Prism reported a crash. Now a daemon thread waits for the render thread to end
 * (by then the world, options and logs are saved) and exits normally.
 */
final class QuickExit {
	private QuickExit() { }

	static void register() {
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
			Thread render = Thread.currentThread();
			Thread t = new Thread(() -> {
				try { render.join(); } catch (InterruptedException e) { return; }
				System.exit(0);
			}, "buddy-quick-exit");
			t.setDaemon(true);   // if the JVM ends by itself first, this thread must not hold it up
			t.start();
		});
	}
}
