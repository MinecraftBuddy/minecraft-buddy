package net.milenco.buddy.client;

/** What the mod needs of the game's connect screen; ConnectScreenMixin adds it. */
public interface ConnectAttempt {
	/** Whether the attempt got as far as an open connection (then the game's own timeouts end it). */
	boolean buddy$connected();

	/** Stops the attempt as the screen's Cancel button does, without changing the screen. */
	void buddy$abort();
}
