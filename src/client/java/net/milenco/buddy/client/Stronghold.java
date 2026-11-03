package net.milenco.buddy.client;

/**
 * Where the stronghold is, from two throws of an eye of ender (AltoClef's way, and every speedrunner's): each throw is a
 * line from where it was thrown along the way the eye flew, and the stronghold lies where the two lines cross. Pure, and
 * tested.
 */
final class Stronghold {
	private Stronghold() {}

	/**
	 * The crossing of two lines on the ground, each a point and a direction (x, z); null when they run nearly the same
	 * way (under two degrees apart) or cross behind either throw. An eye flies straight at its target, and its place comes
	 * to the client to a 4096th of a block: over the twelve blocks it flies the direction is good to a tenth of a degree,
	 * so 200 blocks between the throws at 1800 from the stronghold (3.6 degrees) puts it within some fifty blocks.
	 */
	static double[] cross(double x1, double z1, double dx1, double dz1, double x2, double z2, double dx2, double dz2) {
		double l1 = Math.hypot(dx1, dz1), l2 = Math.hypot(dx2, dz2);
		if (l1 < 1e-6 || l2 < 1e-6) return null;
		dx1 /= l1; dz1 /= l1; dx2 /= l2; dz2 /= l2;
		double det = dx1 * dz2 - dz1 * dx2;
		if (Math.abs(det) < Math.sin(Math.toRadians(2))) return null;
		double t = ((x2 - x1) * dz2 - (z2 - z1) * dx2) / det;
		double u = ((x2 - x1) * dz1 - (z2 - z1) * dx1) / det;
		if (t < 0 || u < 0) return null;
		return new double[] { x1 + t * dx1, z1 + t * dz1 };
	}
}
