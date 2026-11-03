package net.milenco.buddy.client;

/**
 * Which block of a pit the alt may break: only one that lies between the player's work (0.4.44). Made after the alt had
 * broken whole walls one block past the room the player carved, as if the alt did not see the shape being made: the alt
 * no longer takes the ring of one around the player's box, and inside the box only a block that has, along x or along z,
 * something open on both sides within {@link #REACH}: a block the player broke or the open part of the pit, inside that
 * box. A wall the player left standing has rock behind it and is never taken; neither is the inner corner of an L the
 * player did not dig. A block the player put
 * back (see {@code Work}) stops the look along a line.
 */
final class PitShape {
	private PitShape() {}

	/** How far the alt looks along a line for an opening on either side. */
	static final int REACH = 5;

	interface Cells {
		/** No collision: broken by the player or already open. */
		boolean open(int x, int y, int z);
		/** The player put a block back here after the alt broke it. */
		boolean fixed(int x, int y, int z);
	}

	/** Is this block between the player's work, in the box xMin..xMax, zMin..zMax? */
	static boolean between(Cells c, int x, int y, int z, int xMin, int xMax, int zMin, int zMax) {
		if (x < xMin || x > xMax || z < zMin || z > zMax) return false;
		return (side(c, x, y, z, 1, 0, xMin, xMax, zMin, zMax) && side(c, x, y, z, -1, 0, xMin, xMax, zMin, zMax))
			|| (side(c, x, y, z, 0, 1, xMin, xMax, zMin, zMax) && side(c, x, y, z, 0, -1, xMin, xMax, zMin, zMax));
	}

	private static boolean side(Cells c, int x, int y, int z, int dx, int dz, int xMin, int xMax, int zMin, int zMax) {
		for (int k = 1; k <= REACH; k++) {
			int nx = x + dx * k, nz = z + dz * k;
			if (nx < xMin || nx > xMax || nz < zMin || nz > zMax) return false;
			if (c.fixed(nx, y, nz)) return false;
			if (c.open(nx, y, nz)) return true;
		}
		return false;
	}
}
