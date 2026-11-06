package net.milenco.buddy.client;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

/**
 * The mod without the autopilot's jar: the classes that name Baritone's and AltoClef's types are linked all the same
 * (Bari and Alto: their present() is asked every tick), so the verifier must never need one of those types to check
 * them. A Baritone type handed where its supertype is expected made it load that class, and without the jar that was a
 * NoClassDefFoundError on the first tick. The test classpath has no autopilot, as a mods folder without it.
 */
class AutopilotLinkTest {
	private static boolean onClasspath(String name) {
		try { Class.forName(name, false, AutopilotLinkTest.class.getClassLoader()); return true; }
		catch (ClassNotFoundException e) { return false; }
	}

	@Test
	void linksWithoutTheAutopilot() {
		assumeTrue(!onClasspath("baritone.api.BaritoneAPI") && !onClasspath("adris.altoclef.AltoClef"), "the autopilot is on the test classpath");
		for (String n : new String[] { "Bari", "Alto", "Alto$Run" }) {
			try {
				Class.forName("net.milenco.buddy.client." + n, true, getClass().getClassLoader());
			} catch (ExceptionInInitializerError e) {
				// linked; its static fields want a bootstrapped game (the items), which is not what this test is about
			} catch (LinkageError e) {
				fail(n + " does not link without the autopilot: " + e);
			} catch (ClassNotFoundException e) {
				fail(e.toString());
			}
		}
	}
}
