package net.milenco.buddy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Pits, cells and rings of mob farms, and places that are none, drawn in characters (0.4.80). A cell is one character:
 * <pre>
 * '#' a full block      'G' glass (full)      '.' air (also everything never set)
 * 'H' a hopper, its boxes as 26.3's HopperBlock.makeShapes makes them (a rim to 1.0, the inner floor at 11/16, an
 *     opening 12/16 wide, the funnel under it)
 * '_' a bottom slab     '^' a top slab        't' 'T' a closed trapdoor at the bottom and at the top (3/16; OPENS, as
 *                                             every trapdoor is to LevelWorld, and the walker never opens one)
 * 'F' a fence (collision 1.5 high, outline 1.0)   'D' a fence gate (the same, and the walker opens it: OPENABLE)
 * 'W' water (a source)  'w' flowing water     'L' lava              'C' a chest (1/16 inset, 0.875 high)
 * 'O' a half of a door (26.3's DoorBlock: a slab 3/16 thick along the south face, the whole height; the walker opens it)
 * 'I' a half of an iron door (the same, and no hand opens it)
 * 'f' farmland (15/16 high)   'M' mud or soul sand (14/16 high)
 * 'Q' waterlogged stairs (a bottom half and the north half over it)   'S' the same stairs dry
 * 'R' a waterlogged full block (mangrove roots)   'l' a lily pad (1.5/16 high, 1/16 in from each side)
 * 'c' a carpet (1/16 high)   'm' a magma block (HAZARD)   'b' a waterlogged bottom slab   'P' a waterlogged fence
 * 'n' a lantern standing (26.3's LanternBlock: its body 6/16 wide to 7/16, its cap 4/16 wide on it to 9/16)
 * 'N' the same waterlogged   'k' a candle (2/16 wide, 6/16 high)   'K' the same waterlogged
 * 'h' a chain lying flat along x (6.5/16 to 9.5/16 high and across, hanging in its cell)
 * </pre>
 * Every fluid is HAZARD too, as LevelWorld has it (Grinder.hazard). {@code stand} is the rule of Follow.floorY: 1.8 of
 * head room, no hazard, no lava beside it, no block that holds a fluid under the cell, and through a door or gate only
 * where the walker opens it. The ring players stand on is at y 10 throughout (its blocks at y 9 and lower); a mob is a zombie
 * unless said otherwise.
 */
class PenShapeTest {
	private static final double ZW = 0.6, ZH = 1.95, ZE = 1.74;
	/** The inner floor of a hopper: a zombie stands in its bowl. */
	private static final double BOWL = 11 / 16.0;

	/** A world of characters. */
	private static final class Chars implements PenShape.World {
		private final Map<BlockPos, Character> cells = new HashMap<>();

		Chars fill(int x1, int y1, int z1, int x2, int y2, int z2, char c) {
			for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
				for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
					for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
						if (c == '.') cells.remove(new BlockPos(x, y, z));
						else cells.put(new BlockPos(x, y, z), c);
					}
			return this;
		}

		Chars set(int x, int y, int z, char c) { return fill(x, y, z, x, y, z, c); }

		char at(int x, int y, int z) { return cells.getOrDefault(new BlockPos(x, y, z), '.'); }

		private static List<AABB> local(char c, boolean outline) {
			double s = 1 / 16.0;
			return switch (c) {
				case '#', 'G' -> List.of(new AABB(0, 0, 0, 1, 1, 1));
				case 'H' -> List.of(new AABB(0, 10 * s, 0, 1, 11 * s, 1), new AABB(0, 11 * s, 0, 1, 1, 2 * s), new AABB(0, 11 * s, 14 * s, 1, 1, 1),
					new AABB(0, 11 * s, 2 * s, 2 * s, 1, 14 * s), new AABB(14 * s, 11 * s, 2 * s, 1, 1, 14 * s),
					new AABB(4 * s, 4 * s, 4 * s, 12 * s, 10 * s, 12 * s), new AABB(6 * s, 0, 6 * s, 10 * s, 4 * s, 10 * s));
				case '_', 'b' -> List.of(new AABB(0, 0, 0, 1, 0.5, 1));
				case '^' -> List.of(new AABB(0, 0.5, 0, 1, 1, 1));
				case 't' -> List.of(new AABB(0, 0, 0, 1, 3 * s, 1));
				case 'T' -> List.of(new AABB(0, 13 * s, 0, 1, 1, 1));
				case 'F', 'D', 'P' -> List.of(new AABB(0, 0, 0, 1, outline ? 1 : 1.5, 1));
				case 'C' -> List.of(new AABB(s, 0, s, 15 * s, 0.875, 15 * s));
				case 'O', 'I' -> List.of(new AABB(0, 0, 13 * s, 1, 1, 1));
				case 'f' -> List.of(new AABB(0, 0, 0, 1, 15 * s, 1));
				case 'M' -> List.of(new AABB(0, 0, 0, 1, 14 * s, 1));
				case 'Q', 'S' -> List.of(new AABB(0, 0, 0, 1, 0.5, 1), new AABB(0, 0.5, 0, 1, 1, 0.5));
				case 'R', 'm' -> List.of(new AABB(0, 0, 0, 1, 1, 1));
				case 'l' -> List.of(new AABB(s, 0, s, 15 * s, 1.5 * s, 15 * s));
				case 'c' -> List.of(new AABB(0, 0, 0, 1, s, 1));
				case 'n', 'N' -> List.of(new AABB(5 * s, 0, 5 * s, 11 * s, 7 * s, 11 * s), new AABB(6 * s, 7 * s, 6 * s, 10 * s, 9 * s, 10 * s));
				case 'k', 'K' -> List.of(new AABB(7 * s, 0, 7 * s, 9 * s, 6 * s, 9 * s));
				case 'h' -> List.of(new AABB(0, 6.5 * s, 6.5 * s, 1, 9.5 * s, 9.5 * s));
				default -> List.of();
			};
		}

		private List<AABB> boxes(int x, int y, int z, boolean outline) {
			List<AABB> out = new ArrayList<>();
			for (AABB b : local(at(x, y, z), outline)) out.add(b.move(x, y, z));
			return out;
		}

		@Override public List<AABB> collision(int x, int y, int z) { return boxes(x, y, z, false); }

		@Override public List<AABB> outline(int x, int y, int z) { return boxes(x, y, z, true); }

		@Override public int flags(int x, int y, int z) {
			return switch (at(x, y, z)) {
				case 'H', 'C' -> PenShape.COLLECTOR;
				case 'W', 'Q', 'R', 'b', 'P', 'N', 'K' -> PenShape.FLUID | PenShape.WATER | PenShape.HAZARD;
				case 'w' -> PenShape.FLUID | PenShape.WATER | PenShape.FLOWING | PenShape.HAZARD;
				case 'L' -> PenShape.FLUID | PenShape.HAZARD;
				case 'm' -> PenShape.HAZARD;
				case 'D', 'O' -> PenShape.OPENS | PenShape.OPENABLE;
				case 'I', 't', 'T' -> PenShape.OPENS;
				default -> 0;
			};
		}

		private boolean through(int x, int y, int z) { return (flags(x, y, z) & PenShape.OPENABLE) != 0; }

		private boolean passable(int x, int y, int z) {
			if (through(x, y, z)) return true;
			return (flags(x, y, z) & PenShape.FLUID) == 0 && collision(x, y, z).isEmpty();
		}

		private static double top(List<AABB> b) { double t = Double.NEGATIVE_INFINITY; for (AABB a : b) t = Math.max(t, a.maxY); return t; }

		private static double bottom(List<AABB> b) { double t = Double.POSITIVE_INFINITY; for (AABB a : b) t = Math.min(t, a.minY); return t; }

		@Override public double stand(int x, int y, int z) {
			List<AABB> own = through(x, y, z) ? List.of() : collision(x, y, z);
			double feet;
			int under;
			if (!own.isEmpty()) {
				if (top(own) - y > 0.6 || bottom(own) - y > 0.01) return Double.NaN;
				feet = top(own); under = y;
			} else {
				if ((flags(x, y, z) & PenShape.FLUID) != 0) return Double.NaN;
				if (through(x, y - 1, z)) return Double.NaN;
				List<AABB> below = collision(x, y - 1, z);
				if (below.isEmpty() || top(below) - (y - 1) <= 0.6) return Double.NaN;
				feet = top(below); under = y - 1;
			}
			if (under != y && (flags(x, under, z) & PenShape.FLUID) != 0) return Double.NaN;
			for (int yy = y + 1; yy < feet + 1.8; yy++) if (!passable(x, yy, z)) return Double.NaN;
			if (((flags(x, y, z) | flags(x, y - 1, z)) & PenShape.HAZARD) != 0) return Double.NaN;
			for (int[] d : new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) if (at(x + d[0], y, z + d[1]) == 'L') return Double.NaN;
			return feet;
		}

		@Override public boolean loaded(int x, int z) { return true; }
	}

	/** A floor of stone from x, z 0 to 14, its top at the ring's height 10. */
	private static Chars ground() { return new Chars().fill(0, 5, 0, 14, 9, 14, '#'); }

	/** A pit dug into the ground: columns x1..x2, z1..z2, depth deep, with these blocks at its bottom. */
	private static Chars pit(Chars w, int x1, int z1, int x2, int z2, int deep, char bottom) {
		w.fill(x1, 10 - deep, z1, x2, 9, z2, '.');
		if (bottom != '#') w.fill(x1, 9 - deep, z1, x2, 9 - deep, z2, bottom);
		return w;
	}

	/** A glass shaft round the columns, from this height ten up. */
	private static Chars shaft(Chars w, int x1, int z1, int x2, int z2, int from) {
		w.fill(x1 - 1, from, z1 - 1, x2 + 1, from + 9, z2 + 1, 'G');
		w.fill(x1, from, z1, x2, from + 9, z2, '.');
		return w;
	}

	private static PenShape.Mob zombie(double x, double z, double feet) {
		return new PenShape.Mob(ZW, ZH, ZE, new AABB(x - ZW / 2, feet, z - ZW / 2, x + ZW / 2, feet + ZH, z + ZW / 2), true);
	}

	private record Seen(PenShape.Flood flood, PenShape.Evidence ev, PenShape.Result result) {}

	private static Seen look(PenShape.World w, PenShape.Mob m, boolean weakened, int crowd) {
		double F = PenShape.floor(w, m);
		PenShape.Flood f = PenShape.flood(w, m, F);
		if (!f.closed()) return new Seen(f, null, null);
		PenShape.Evidence ev = PenShape.evidence(w, f, m, weakened, crowd, false);
		PenShape.Search s = new PenShape.Search(w, f, m, ev);
		while (!s.step(50_000_000L)) { }
		return new Seen(f, ev, s.result());
	}

	private static int sides(PenShape.Result r) {
		int n = 0;
		for (PenShape.Spot s : r.ring()) if (s != null) n++;
		return n;
	}

	@Test
	void aOneByOnePitWithAHopperAndAShaft() {
		Chars w = shaft(pit(ground(), 7, 7, 7, 7, 2, 'H'), 7, 7, 7, 7, 12);
		Seen s = look(w, zombie(7.5, 7.5, 7 + BOWL), false, 1);
		assertTrue(s.flood().closed(), s.flood().refusal());
		assertEquals(1, s.flood().cols().size());
		assertTrue(s.ev().shaft(), "shaft");
		assertTrue(s.ev().collector(), "collector");
		assertTrue(s.ev().enough(), "points " + s.ev().points());
		assertEquals(4, sides(s.result()));
		assertEquals("pit", s.result().kind());
		for (PenShape.Spot sp : s.result().spots()) assertTrue(sp.columns() > 0);
	}

	@Test
	void aTwoByTwoPitHasARingOfFour() {
		Chars w = shaft(pit(ground(), 7, 7, 8, 8, 2, 'H'), 7, 7, 8, 8, 12);
		Seen s = look(w, zombie(7.5, 7.5, 7 + BOWL), false, 1);
		assertTrue(s.flood().closed(), s.flood().refusal());
		assertEquals(4, s.flood().cols().size());
		assertEquals("2x2", s.flood().size());
		assertEquals(4, sides(s.result()));
		for (PenShape.Spot sp : s.result().ring()) {
			assertNotNull(sp);
			assertTrue(sp.seen(), "a mob in the pit sees the ring");
			assertFalse(sp.creeper() && sp.feet() < 9, "no creeper spot at the floor");
		}
	}

	@Test
	void aThreeByThreePitTakesTwoSides() {
		Chars w = pit(ground(), 6, 6, 8, 8, 2, 'H');
		Seen s = look(w, zombie(7.5, 7.5, 7 + BOWL), false, 2);
		assertTrue(s.flood().closed(), s.flood().refusal());
		assertEquals(9, s.flood().cols().size());
		PenShape.Spot south = s.result().ring()[2], north = s.result().ring()[0];
		assertNotNull(south);
		assertNotNull(north);
		assertEquals(6, south.columns(), "the middle of a side reaches two rows");
		assertEquals(9, Long.bitCount(south.reach() | north.reach()), "two opposite sides reach all nine");
	}

	@Test
	void aShallowPitKeepsHimOneBlockBack() {
		// one deep: a zombie on the hoppers reaches the cells beside the pit, not the cells one further
		Chars w = pit(ground(), 7, 7, 8, 8, 1, 'H');
		Seen s = look(w, zombie(7.5, 7.5, 8 + BOWL), true, 1);
		assertTrue(s.flood().closed(), s.flood().refusal());
		assertFalse(s.result().spots().isEmpty());
		for (PenShape.Spot sp : s.result().spots()) {
			int x = sp.cell().getX(), z = sp.cell().getZ();
			boolean beside = x >= 6 && x <= 9 && z >= 6 && z <= 9;
			assertFalse(beside, "a cell beside the pit at " + sp.cell());
		}
	}

	/** A cell of one in a wall: the mob on the ground at y 10 in a box of stone, a slit at its feet on the south side. */
	private static Chars wallCell() {
		Chars w = ground();
		w.fill(6, 10, 6, 8, 13, 8, '#').fill(7, 10, 7, 7, 12, 7, '.');
		w.set(7, 10, 8, '^');   // a top slab: the slit is y 10.0 to 10.5
		return w;
	}

	@Test
	void aWallCellWithASlitIsHitFromTheTrench() {
		// south of the slit a trench one deep: crouched in it, the eye is at the height of the slit
		Chars w = wallCell().fill(6, 9, 9, 8, 9, 9, '.');
		Seen s = look(w, zombie(7.5, 7.5, 10), true, 1);
		assertTrue(s.flood().closed(), s.flood().refusal());
		assertEquals(1, s.flood().cols().size());
		assertFalse(s.result().spots().isEmpty(), "a spot in the trench");
		for (PenShape.Spot sp : s.result().spots()) {
			assertEquals(2, sp.side(), "only the side with the slit: " + sp.cell());
			assertEquals(9, sp.cell().getY(), "in the trench: " + sp.cell());
		}
	}

	@Test
	void aSlitAtTheFeetOnHisOwnLevelReachesNothing() {
		// the same cell with the ground in front of it level with the mob's: from there no crosshair gets under the slab
		Seen s = look(wallCell(), zombie(7.5, 7.5, 10), true, 1);
		assertTrue(s.flood().closed(), s.flood().refusal());
		assertTrue(s.result().spots().isEmpty(), "no spot: " + s.result().spots());
	}

	@Test
	void aTrapdoorAtTheKneesLeavesAGapHeReachesCrouched() {
		// a top trapdoor leaves 13/16 open under it: a crouched player two back reaches the mob's legs through it
		Chars w = ground();
		w.fill(6, 10, 6, 8, 13, 8, '#').fill(7, 10, 7, 7, 12, 7, '.');
		w.set(7, 10, 8, 'T');
		Seen s = look(w, zombie(7.5, 7.5, 10), true, 1);
		assertTrue(s.flood().closed(), s.flood().refusal());
		assertFalse(s.result().spots().isEmpty());
		for (PenShape.Spot sp : s.result().spots()) assertTrue(sp.crouch(), "crouched: " + sp.cell());
	}

	@Test
	void theFenceTopsAreNoPlaceToStand() {
		// a ring of fences round a 2x2 floor: on a fence top a zombie lands a hit; behind the fence a blow reaches over it
		Chars w = ground();
		w.fill(6, 10, 6, 9, 10, 9, 'F').fill(7, 10, 7, 8, 10, 8, '.');
		w.fill(7, 9, 7, 8, 9, 8, 'H');
		Seen s = look(w, zombie(7.5, 7.5, 10), true, 1);
		assertTrue(s.flood().closed(), s.flood().refusal());
		assertFalse(s.result().spots().isEmpty());
		for (PenShape.Spot sp : s.result().spots()) assertFalse(sp.cell().getY() == 11 && w.at(sp.cell().getX(), 10, sp.cell().getZ()) == 'F', "on a fence top: " + sp.cell());
		assertEquals("ring", s.result().kind());
	}

	@Test
	void aTwoByOnePenWithASlit() {
		// a slit in the east wall at the mobs' feet (a top slab: open from 8.0 to 8.5) with a trench behind it: the
		// slit's column is a wall to a zombie, so the flood does not run out through it
		Chars w = pit(ground(), 7, 7, 8, 7, 2, 'H');
		w.set(9, 8, 7, '^').fill(10, 8, 7, 11, 9, 7, '.');
		Seen s = look(w, zombie(7.5, 7.5, 7 + BOWL), false, 2);
		assertTrue(s.flood().closed(), s.flood().refusal());
		assertEquals(2, s.flood().cols().size());
		assertEquals("2x1", s.flood().size());
	}

	@Test
	void openGroundIsNoPen() {
		Seen s = look(ground(), zombie(7.5, 7.5, 10), false, 1);
		assertNotNull(s.flood().refusal());
		assertNull(s.result());
	}

	@Test
	void aRoomCornerIsNoPen() {
		// a room of 5 by 5 with the zombie in its corner
		Chars w = ground().fill(4, 10, 4, 10, 12, 10, '#').fill(5, 10, 5, 9, 12, 9, '.');
		Seen s = look(w, zombie(5.5, 5.5, 10), false, 1);
		assertEquals("larger than 3x3", s.flood().refusal());
	}

	@Test
	void aFourByFourPitIsNoPen() {
		Seen s = look(pit(ground(), 5, 5, 8, 8, 2, 'H'), zombie(5.5, 5.5, 7 + BOWL), false, 1);
		assertEquals("larger than 3x3", s.flood().refusal());
	}

	@Test
	void aCaveNookIsNoFarm() {
		// a hole of one with no hopper, no shaft and one zombie at full health: closed, but nothing says farm
		Chars w = pit(ground(), 7, 7, 7, 7, 2, '#');
		Seen s = look(w, zombie(7.5, 7.5, 8), false, 1);
		assertTrue(s.flood().closed(), s.flood().refusal());
		assertFalse(s.ev().enough(), "points " + s.ev().points() + " " + s.ev().why());
	}

	@Test
	void lavaOnTheRimLeavesNowhereToStand() {
		// lava where the feet would be on the rim: nobody stands in it or beside it, and from three back the mob is out of
		// reach; the same pit without the lava has its spots
		Chars w = pit(ground(), 7, 7, 7, 7, 2, 'H');
		w.fill(6, 10, 6, 8, 10, 8, 'L').set(7, 10, 7, '.');
		Seen s = look(w, zombie(7.5, 7.5, 7 + BOWL), false, 1);
		assertTrue(s.flood().closed(), s.flood().refusal());
		assertTrue(s.result().spots().isEmpty(), "spots: " + s.result().spots());
		Seen t = look(pit(ground(), 7, 7, 7, 7, 2, 'H'), zombie(7.5, 7.5, 7 + BOWL), false, 1);
		assertFalse(t.result().spots().isEmpty());
	}

	@Test
	void lavaUnderTheRimKeepsHimTwoBack() {
		// lava in place of the rim's blocks, under where the feet would be (the VPS pit N4): the rim is no floor, but two
		// deep a mob is in reach from two back
		Chars w = pit(ground(), 7, 7, 7, 7, 2, 'H');
		w.fill(6, 9, 6, 8, 9, 8, 'L').set(7, 9, 7, '.');
		Seen s = look(w, zombie(7.5, 7.5, 7 + BOWL), false, 1);
		assertFalse(s.result().spots().isEmpty());
		for (PenShape.Spot sp : s.result().spots()) {
			int x = sp.cell().getX(), z = sp.cell().getZ();
			assertFalse(x >= 6 && x <= 8 && z >= 6 && z <= 8, "on the lava: " + sp.cell());
		}
	}

	@Test
	void standingOverTheShaftIsInIt() {
		Chars w = shaft(pit(ground(), 7, 7, 7, 7, 2, 'H'), 7, 7, 7, 7, 12);
		Seen s = look(w, zombie(7.5, 7.5, 7 + BOWL), false, 1);
		assertEquals("you stand in it", PenShape.inside(s.flood(), s.ev().top(), new Vec3(7.5, 22, 7.5), new Vec3(5.5, 10, 7.5)));
		assertEquals("{me} stands in it", PenShape.inside(s.flood(), s.ev().top(), new Vec3(3.5, 10, 7.5), new Vec3(7.5, 9, 7.5)));
		assertNull(PenShape.inside(s.flood(), s.ev().top(), new Vec3(5.5, 10, 7.5), new Vec3(9.5, 10, 7.5)));
	}

	@Test
	void aGateInTheWallIsNeverAPlaceToStand() {
		// a 2x2 cell on the ground behind fences, its gate on the south side
		Chars w = ground();
		w.fill(6, 10, 6, 9, 10, 9, 'F').fill(7, 10, 7, 8, 10, 8, '.').set(7, 10, 9, 'D');
		w.fill(7, 9, 7, 8, 9, 8, 'H');
		Seen s = look(w, zombie(7.5, 7.5, 10), true, 1);
		assertTrue(s.flood().closed(), s.flood().refusal());
		for (PenShape.Spot sp : s.result().spots()) {
			BlockPos c = sp.cell();
			assertFalse(c.getX() == 7 && c.getZ() == 9 && (c.getY() == 10 || c.getY() == 11), "the gate: " + c);
		}
	}

	@Test
	void aHoleBesideTheMobsIsNoPen() {
		// the pit of one beside a column that goes down for good
		Chars w = pit(ground(), 7, 7, 7, 7, 2, 'H');
		w.fill(8, 0, 7, 8, 9, 7, '.');
		Seen s = look(w, zombie(7.5, 7.5, 7 + BOWL), false, 1);
		assertEquals("a hole beside the mobs", s.flood().refusal());
	}

	@Test
	void aPitOverItsCollectionRoomIsAPen() {
		// a 1x1 hopper pit two deep whose walls stand over the room of its chest (y 5 and 6): the walls' blocks stop the
		// zombie, and the room's floor under them is no drop beside it
		Chars w = pit(ground(), 7, 7, 7, 7, 2, 'H');
		w.fill(4, 4, 4, 10, 4, 10, '#').fill(4, 5, 4, 10, 6, 10, '.').set(7, 6, 7, 'C');
		PenShape.Mob m = zombie(7.5, 7.5, 7 + BOWL);
		Seen s = look(w, m, false, 1);
		assertTrue(s.flood().closed(), s.flood().refusal());
		assertEquals(1, s.flood().cols().size());
		assertTrue(s.ev().collector(), "the hopper over its chest");
		assertEquals(4, sides(s.result()));
		assertEquals("in a pit", fight(w, m, new Vec3(5.5, 10, 7.5), 10), "the fight sees the fall into it");
		// a gap one high under the walls instead of a room: the same
		Chars g = pit(ground(), 7, 7, 7, 7, 2, 'H');
		g.fill(4, 6, 4, 10, 6, 10, '.').set(7, 6, 7, 'C');
		PenShape.Flood f = PenShape.flood(g, m, PenShape.floor(g, m));
		assertTrue(f.closed(), f.refusal());
		assertEquals(1, f.cols().size());
	}

	@Test
	void theRimOfAPitThreeDeepIsFoundOverAChestOrHoppers() {
		// three deep over a chest (0.875) the rim is four over the floor's block, which the search did not reach; a
		// crouched eye on the rim reaches the zombie's head
		Seen s = look(pit(ground(), 7, 7, 7, 7, 3, 'C'), zombie(7.5, 7.5, 6.875), false, 1);
		assertTrue(s.flood().closed(), s.flood().refusal());
		assertFalse(s.result().spots().isEmpty(), "spots on the rim");
		for (PenShape.Spot sp : s.result().spots()) {
			assertEquals(10, sp.cell().getY(), "on the rim: " + sp.cell());
			assertTrue(sp.crouch(), "crouched at the edge: " + sp.cell());
		}
		// an enderman (2.9 high) in a 2x2 three deep over hoppers, deep enough that it does not reach the rim: hit from there
		PenShape.Mob enderman = new PenShape.Mob(0.6, 2.9, 2.55, new AABB(7.2, 6 + BOWL, 7.2, 7.8, 6 + BOWL + 2.9, 7.8), true);
		Seen e = look(pit(ground(), 7, 7, 8, 8, 3, 'H'), enderman, false, 1);
		assertTrue(e.flood().closed(), e.flood().refusal());
		assertEquals(4, sides(e.result()));
		for (PenShape.Spot sp : e.result().ring()) assertEquals(4, sp.columns(), "a ring spot reaches all four: " + sp.cell());
	}

	@Test
	void aCrosshairAndAMobBothSeeOverAFence() {
		// 26.3's clip looks at the shape of each cell it passes through, nothing else: a ray at 1.25 runs in the cell over
		// the fence, where its collision (1.5) reaches without being looked at, for a mob's sight as for the crosshair
		Chars w = new Chars().set(0, 0, 0, 'F');
		Vec3 a = new Vec3(-2, 1.25, 0.5), b = new Vec3(3, 1.25, 0.5);
		assertTrue(PenShape.clear(w, a, b, true), "outline: a fence is one high");
		assertTrue(PenShape.clear(w, a, b, false), "collision: nothing of the cell over the fence is in the way");
		assertEquals(1, PenShape.clearInfo(w, a, b, true), "over the fence");
		assertFalse(PenShape.clear(w, new Vec3(-2, 0.75, 0.5), new Vec3(3, 0.75, 0.5), false), "through the fence's own cell");
	}

	@Test
	void aMobFallingDownTheShaftHasAFloorBelow() {
		Chars w = shaft(pit(ground(), 7, 7, 7, 7, 2, 'H'), 7, 7, 7, 7, 12);
		PenShape.Mob falling = new PenShape.Mob(ZW, ZH, ZE, new AABB(7.2, 10.5, 7.2, 7.8, 12.45, 7.8), false);
		assertEquals(7 + BOWL, PenShape.floor(w, falling), 1e-9, "in the hopper's bowl");
		PenShape.Mob high = new PenShape.Mob(ZW, ZH, ZE, new AABB(7.2, 16, 7.2, 7.8, 17.95, 7.8), false);
		assertTrue(Double.isNaN(PenShape.floor(w, high)), "more than four up: no floor yet");
	}

	/** What the fight makes of a mob there, for the buddy with the feet here on this ground: why it leaves it, or null. */
	private static String fight(PenShape.World w, PenShape.Mob m, Vec3 feet, double ground) {
		return PenShape.pitFoe(PenShape.pit(w, m), feet, ground);
	}

	@Test
	void aHoleOneDeepIsNoPitForTheFight() {
		// a 1x1 hole one deep in stone with nothing that makes it a farm: closed, but stepped down into and out of again
		Chars w = pit(ground(), 7, 7, 7, 7, 1, '#');
		PenShape.Mob m = zombie(7.5, 7.5, 9);
		assertTrue(PenShape.flood(w, m, 9).closed(), "a closed space all the same");
		assertNull(fight(w, m, new Vec3(5.5, 10, 7.5), 10));
		// half way up a jump the feet are higher, the ground jumped from is not
		assertNull(fight(w, m, new Vec3(5.5, 10.9, 7.5), 10));
	}

	@Test
	void aRoomWithADoorAtHisLevelIsNoPitForTheFight() {
		// a room of 3 by 3 inside, its doorway on the south side shut by a door: the door's slab closes the flood
		Chars w = ground().fill(5, 10, 5, 9, 12, 9, '#').fill(6, 10, 6, 8, 12, 8, '.').fill(7, 10, 9, 7, 11, 9, 'O');
		PenShape.Mob m = zombie(7.5, 7.5, 10);
		assertTrue(PenShape.flood(w, m, 10).closed(), PenShape.flood(w, m, 10).refusal());
		assertNull(fight(w, m, new Vec3(7.5, 10, 11.5), 10));
	}

	@Test
	void aWaterHoleInAFieldIsNoPitForTheFight() {
		// farmland all round a hole of water one deep: the farmland's 15/16 closes it, and it is waded in and out of
		Chars w = ground().fill(0, 9, 0, 14, 9, 14, 'f').set(7, 9, 7, 'W');
		PenShape.Mob m = zombie(7.5, 7.5, 9);
		assertTrue(PenShape.flood(w, m, 9).closed(), PenShape.flood(w, m, 9).refusal());
		assertNull(fight(w, m, new Vec3(5.5, 9 + 15 / 16.0, 7.5), 9 + 15 / 16.0));
	}

	@Test
	void aPitTwoDeepIsOneForTheFight() {
		// the cave nook of above, two deep: no farm, but the way to it is a fall into it
		Chars w = pit(ground(), 7, 7, 7, 7, 2, '#');
		PenShape.Mob m = zombie(7.5, 7.5, 8);
		assertEquals("in a pit", fight(w, m, new Vec3(5.5, 10, 7.5), 10));
		// down in it, the buddy fights what is there alongside
		assertNull(fight(w, m, new Vec3(7.5, 8, 7.5), 8));
	}

	@Test
	void aHopperPitOneDeepIsOneForTheFight() {
		// one deep, the mobs in the hoppers' bowl 1.31 below the rim: no fall to stay in (a step onto a hopper's rim and
		// a jump out), but a farm by its hoppers
		Chars w = pit(ground(), 7, 7, 8, 8, 1, 'H');
		PenShape.Mob m = zombie(7.5, 7.5, 8 + BOWL);
		assertEquals("in a pen", fight(w, m, new Vec3(5.5, 10, 7.5), 10));
		PenShape.Pit pit = PenShape.pit(w, m);
		assertNotNull(pit);
		assertTrue(pit.farm(), "a farm by its hoppers");
	}

	@Test
	void aHoleOneDeepSeenFromHigherUpIsNoPitForTheFight() {
		// the hole of one in stone, seen from a bottom slab and from a block up a slope: its rim is a jump from its floor
		// and within a jump of the ground stood on
		Chars w = pit(ground(), 7, 7, 7, 7, 1, '#');
		PenShape.Mob m = zombie(7.5, 7.5, 9);
		assertNull(fight(w, m, new Vec3(3.5, 10.5, 7.5), 10.5));
		assertNull(fight(w, m, new Vec3(3.5, 11, 7.5), 11));
		// a dip of three by three one deep, from a block up
		assertNull(fight(pit(ground(), 6, 6, 8, 8, 1, '#'), zombie(7.5, 7.5, 9), new Vec3(2.5, 11, 7.5), 11));
	}

	@Test
	void aCraterHeClimbsOutOfInStepsIsNoPitForTheFight() {
		// a hole two deep with a step one deep beside it: from its floor a jump onto the step, from there onto the rim
		Chars w = pit(ground(), 7, 7, 7, 7, 2, '#').set(6, 9, 7, '.');
		assertNull(fight(w, zombie(7.5, 7.5, 8), new Vec3(3.5, 10, 7.5), 10));
	}

	@Test
	void aStepThatIsAHoleOfItsOwnIsNoWayOut() {
		// two deep beside a ledge one higher that is a hole itself, two under a rim at 11: the ledge gets nowhere
		Chars w = ground().fill(0, 10, 0, 14, 10, 14, '#');
		w.fill(7, 8, 7, 7, 10, 7, '.').fill(8, 9, 7, 8, 10, 7, '.');
		assertEquals("in a pit", fight(w, zombie(7.5, 7.5, 8), new Vec3(4.5, 11, 7.5), 11));
		assertEquals(9, PenShape.pit(w, zombie(7.5, 7.5, 8)).up(), 1e-9, "the climb ends on the ledge");
	}

	@Test
	void aDipOneDeepSeenFromTwoUpIsNoPitForTheFight() {
		// the dip of one at the foot of a stair of whole blocks up to the buddy (11, 12 and 13 to the west, a slab on the
		// first step), the dip's rim and the stair walled in to 13 so that only the stair leads up to that ground: out
		// onto the rim and up the stair, however high up the stand is (until the third review of 22 Oct 2026 only the rim
		// counted, against the ground stood on, and from 11.5 up it was a pit; until the review of the fourth round the rim
		// ran on to open ground at 10, and the test held without a single jump up the stair)
		Chars w = pit(ground(), 7, 7, 7, 7, 1, '#').fill(0, 10, 0, 14, 12, 14, '#');
		w.fill(6, 10, 6, 10, 12, 8, '.').fill(5, 11, 6, 5, 12, 8, '.').fill(4, 12, 6, 4, 12, 8, '.').set(5, 11, 7, '_');
		PenShape.Mob m = zombie(7.5, 7.5, 9);
		assertEquals(Double.POSITIVE_INFINITY, PenShape.pit(w, m).up(), "out at the top of the stair");
		assertNull(fight(w, m, new Vec3(5.5, 11.5, 7.5), 11.5));
		assertNull(fight(w, m, new Vec3(4.5, 12, 7.5), 12));
		assertNull(fight(w, m, new Vec3(3.5, 13, 7.5), 13));
		// a dip of three by three one deep in a trough at 10, walled to 12, from two up: a step at 11 and the top at 12
		Chars dip = pit(ground(), 6, 6, 8, 8, 1, '#').fill(0, 10, 0, 14, 11, 14, '#');
		dip.fill(5, 10, 5, 9, 11, 9, '.').fill(4, 11, 5, 4, 11, 9, '.');
		PenShape.Mob z = zombie(7.5, 7.5, 9);
		assertEquals(Double.POSITIVE_INFINITY, PenShape.pit(dip, z).up(), "out at the top of the step");
		assertNull(fight(dip, z, new Vec3(2.5, 12, 7.5), 12));
	}

	@Test
	void aCraterWithTwoStepsIsNoPitForTheFight() {
		// three deep (a creeper's crater): the floor at 7, a step at 8 beside it, one at 9 beside that, the rim at 10
		Chars w = ground().fill(7, 7, 7, 7, 9, 7, '.').fill(6, 8, 7, 6, 9, 7, '.').set(5, 9, 7, '.');
		PenShape.Mob m = zombie(7.5, 7.5, 7);
		assertTrue(PenShape.flood(w, m, 7).closed(), "closed for the zombie: its steps are a block high");
		assertNull(fight(w, m, new Vec3(3.5, 10, 7.5), 10));
		assertEquals(Double.POSITIVE_INFINITY, PenShape.pit(w, m).up(), "out onto open ground");
		// lava beside its first step: the walker takes no step beside lava (Follow.floorY), so the crater is a pit
		w.set(6, 8, 6, 'L');
		assertEquals("in a pit", fight(w, m, new Vec3(3.5, 10, 7.5), 10));
	}

	@Test
	void aPitWhoseClimbEndsBelowHisGroundIsOneForTheFight() {
		// the check of the third review (22 Oct 2026): a pit two by one, three deep, on a slope, the high side at 11 and the
		// low one at 10; out of it to the west a step at 8 and a ledge at 9, with 11 all round them. Seen from the low east
		// rim the climb tops out at 9, within a jump of the buddy's ground and no way back up to it
		Chars w = ground().fill(0, 10, 0, 5, 10, 14, '#');
		w.fill(6, 7, 7, 7, 9, 7, '.').fill(5, 8, 7, 5, 10, 7, '.').fill(4, 9, 7, 4, 10, 7, '.');
		PenShape.Mob m = zombie(6.5, 7.5, 7);
		assertEquals(9, PenShape.pit(w, m).up(), 1e-9, "the climb ends on the ledge");
		assertEquals("in a pit", fight(w, m, new Vec3(8.5, 10, 7.5), 10));
	}

	@Test
	void aClimbUpToAClosedLedgeIsNoWayBack() {
		// the review of the fourth round (22 Oct 2026): the slope above with its high side at 12 and the stair one step
		// longer, 8, 9 and a ledge at 10, the high side all round. The climb tops out at the buddy's own height, but never
		// stands where the buddy stands: down there the way up ends at the ledge, two under the high side and three cells
		// from the low one (until then the top of the climb was held against that ground, and the fight walked the buddy in)
		Chars w = ground().fill(0, 10, 0, 5, 11, 14, '#');
		w.fill(6, 7, 7, 7, 9, 7, '.').fill(5, 8, 7, 5, 11, 7, '.').fill(4, 9, 7, 4, 11, 7, '.').fill(3, 10, 7, 3, 11, 7, '.');
		PenShape.Mob m = zombie(6.5, 7.5, 7);
		assertEquals(10, PenShape.pit(w, m).up(), 1e-9, "the climb ends on the ledge, at the buddy's height");
		assertEquals("in a pit", fight(w, m, new Vec3(8.5, 10, 7.5), 10), "on the low east rim");
		assertEquals("in a pit", fight(w, m, new Vec3(9.5, 10, 7.5), 10), "a cell farther");
		assertEquals("in a pit", fight(w, m, new Vec3(6.5, 10, 5.5), 10), "on the north rim");
		// standing on the ledge, the buddy walks down the stair to the mob and back up
		assertNull(fight(w, m, new Vec3(3.5, 10, 7.5), 10), "on the ledge");
		// a closed ledge over the buddy's ground: steps 8, 9 and 10 to the west, the ledge at 11 north of the last, walls to 13
		Chars hi = ground().fill(7, 7, 7, 7, 9, 7, '.').fill(6, 8, 7, 6, 9, 7, '.').set(5, 9, 7, '.');
		hi.fill(3, 10, 5, 6, 12, 8, '#').fill(4, 10, 7, 6, 12, 7, '.').fill(4, 11, 6, 4, 12, 6, '.');
		PenShape.Mob z = zombie(7.5, 7.5, 7);
		assertEquals(11, PenShape.pit(hi, z).up(), 1e-9, "the climb ends on the ledge, over the buddy's ground");
		assertEquals("in a pit", fight(hi, z, new Vec3(8.5, 10, 7.5), 10));
	}

	@Test
	void aClosedCourtyardHeStandsInIsNoPitForTheFight() {
		// the review of the fourth round: a courtyard of seven by seven at 10, walled to 13 all round, the pit three
		// deep in it with two steps down to its floor and a stair in its corner. The climb stays in the courtyard and
		// stands where the buddy stands, also on the stair's low half, half a block under the top the climb records for its
		// column. On the wall the courtyard is the trap
		Chars w = ground().fill(3, 10, 3, 11, 12, 11, '#').fill(4, 10, 4, 10, 12, 10, '.');
		w.fill(7, 7, 7, 7, 9, 7, '.').fill(6, 8, 7, 6, 9, 7, '.').set(5, 9, 7, '.').set(9, 10, 9, 'S');
		PenShape.Mob m = zombie(7.5, 7.5, 7);
		assertEquals(11, PenShape.pit(w, m).up(), 1e-9, "a closed climb, up to the stair's top");
		assertNull(fight(w, m, new Vec3(9.5, 10, 6.5), 10), "on its floor");
		assertNull(fight(w, m, new Vec3(10.7, 10, 7.5), 10), "flush against its wall");
		assertNull(fight(w, m, new Vec3(9.5, 10.5, 9.81), 10.5), "on the stair's low half");
		assertEquals("in a pit", fight(w, m, new Vec3(11.5, 13, 7.5), 13), "on the wall");
	}

	@Test
	void theClimbGetsHimBackOnlyWhereItStood() {
		// Climb.backUp (the review of the fourth round): a closed climb that stood in (3, 7) at 10 and in (9, 9) at 11,
		// the top of a stair whose low half is at 10.5
		Map<Long, List<Double>> at = new HashMap<>();
		at.put(PenShape.key(3, 7), List.of(10.0));
		at.put(PenShape.key(9, 9), List.of(11.0));
		PenShape.Climb c = new PenShape.Climb(11, at);
		assertTrue(c.backUp(new Vec3(3.5, 10, 7.5), 10), "where it stood");
		assertTrue(c.backUp(new Vec3(4.2, 10, 7.5), 10), "half over it");
		assertFalse(c.backUp(new Vec3(4.5, 10, 7.5), 10), "a cell beside it");
		assertFalse(c.backUp(new Vec3(2.7, 10, 7.5), 10), "a box that only touches its column");
		// and along z: the inset on both sides of the box
		assertTrue(c.backUp(new Vec3(3.5, 10, 6.8), 10), "half over it, along z");
		assertFalse(c.backUp(new Vec3(3.5, 10, 6.7), 10), "a box that only touches its row");
		assertFalse(c.backUp(new Vec3(3.5, 10, 8.3), 10), "a box that only touches its row from the south");
		assertFalse(c.backUp(new Vec3(4.3, 10, 7.5), 10), "a box that only touches its column from the east");
		assertFalse(c.backUp(new Vec3(3.5, 11, 7.5), 11), "on a block over where it stood");
		assertTrue(c.backUp(new Vec3(9.5, 10.5, 9.81), 10.5), "on the stair's low half");
		assertFalse(c.backUp(new Vec3(9.5, 10.25, 9.5), 10.25), "more than a step under it, as in a cauldron");
		assertTrue(new PenShape.Climb(Double.POSITIVE_INFINITY, Map.of()).backUp(new Vec3(0.5, 50, 0.5), 50), "open ground");
	}

	@Test
	void aJumpUnderABlockIsNoWayOut() {
		// the crater with two steps, a block two over its first step: from that step the jump onto the second wants room up
		// to 10.85 (Follow.roomToJump), so once in, there is no getting farther than the first step
		Chars w = ground().fill(7, 7, 7, 7, 9, 7, '.').fill(6, 8, 7, 6, 9, 7, '.').set(5, 9, 7, '.');
		PenShape.Mob m = zombie(7.5, 7.5, 7);
		assertNull(fight(w, m, new Vec3(3.5, 10, 7.5), 10), "without the block the climb gets out");
		w.set(6, 10, 7, '#');
		assertEquals("in a pit", fight(w, m, new Vec3(3.5, 10, 7.5), 10));
	}

	@Test
	void aHoleInTheFloorOfAPitThreeDeepIsOneForTheFight() {
		// a hole one deep in the middle of a pit of five by five three deep: out of the hole onto the big pit's floor, two
		// cells from the hole, and no farther, for its walls are three high; the fall is into the big pit
		Chars w = ground().fill(5, 7, 5, 9, 9, 9, '.').set(7, 6, 7, '.');
		PenShape.Mob m = zombie(7.5, 7.5, 6);
		assertEquals("in a pit", fight(w, m, new Vec3(3.5, 10, 7.5), 10));
		assertEquals(7, PenShape.pit(w, m).up(), 1e-9, "the climb ends on the big pit's floor");
		// four by four, the hole off its middle: the same
		Chars four = ground().fill(5, 7, 5, 8, 9, 8, '.').set(6, 6, 6, '.');
		assertEquals("in a pit", fight(four, zombie(6.5, 6.5, 6), new Vec3(3.5, 10, 6.5), 10));
	}

	@Test
	void aNookSeenFromATrenchIsAPitFromItsRim() {
		// a nook two deep seen from a trench one lower than its rim: no pit from there, for every way into it goes over its
		// rim, and on the rim it is one, so out of reach there the fight is given up before any drop
		Chars w = pit(ground(), 7, 7, 7, 7, 2, '#').fill(0, 9, 0, 3, 9, 14, '.');
		PenShape.Mob m = zombie(7.5, 7.5, 8);
		assertNull(fight(w, m, new Vec3(3.5, 9, 7.5), 9));
		assertEquals("in a pit", fight(w, m, new Vec3(5.5, 10, 7.5), 10));
	}

	@Test
	void waterHeSwimsOutOfIsNoPitForTheFight() {
		// a water hole two deep in a field: a swim up and a climb onto the farmland
		Chars f = ground().fill(0, 9, 0, 14, 9, 14, 'f').set(7, 9, 7, 'W').set(7, 8, 7, 'W');
		assertNull(fight(f, zombie(7.5, 7.5, 8), new Vec3(5.5, 9 + 15 / 16.0, 7.5), 9 + 15 / 16.0));
		// a pit two deep full of water: the same
		Chars pool = pit(ground(), 7, 7, 8, 8, 2, '#').fill(7, 8, 7, 8, 9, 8, 'W');
		assertNull(fight(pool, zombie(7.5, 7.5, 8), new Vec3(5.5, 10, 7.5), 10));
		// one layer of water on its floor lifts nobody out of it
		Chars layer = pit(ground(), 7, 7, 8, 8, 2, '#').fill(7, 8, 7, 8, 8, 8, 'W');
		assertEquals("in a pit", fight(layer, zombie(7.5, 7.5, 8), new Vec3(5.5, 10, 7.5), 10));
	}

	@Test
	void waterOverMudHeSwimsOutOfIsNoPitForTheFight() {
		// a floor of mud or soul sand (14/16) under two blocks of water: the water stands over the cell above the mud
		// (until the third review of 22 Oct 2026 its run began in the mud's own cell, which holds none)
		Chars w = pit(ground(), 7, 7, 7, 7, 2, '#').set(7, 7, 7, 'M').fill(7, 8, 7, 7, 9, 7, 'W');
		assertNull(fight(w, zombie(7.5, 7.5, 7.875), new Vec3(5.5, 10, 7.5), 10));
		// a hole two deep in a swamp all of mud, full of water: a swim up and a climb onto the mud's top, past the
		// eighth of a block between two stacked blocks of it (no room to stand in, no top of a wall)
		Chars swamp = new Chars().fill(0, 5, 0, 14, 9, 14, 'M').fill(7, 8, 7, 7, 9, 7, 'W');
		assertNull(fight(swamp, zombie(7.5, 7.5, 7.875), new Vec3(5.5, 9.875, 7.5), 9.875));
		// the same hole dry: two deep, a pit
		Chars dry = new Chars().fill(0, 5, 0, 14, 9, 14, 'M').fill(7, 8, 7, 7, 9, 7, '.');
		assertEquals("in a pit", fight(dry, zombie(7.5, 7.5, 7.875), new Vec3(5.5, 9.875, 7.5), 9.875));
		// stone under it and walls of mud: the same as the swamp
		Chars walls = ground().fill(0, 8, 0, 14, 9, 14, 'M').fill(7, 8, 7, 7, 9, 7, 'W');
		assertNull(fight(walls, zombie(7.5, 7.5, 8), new Vec3(5.5, 9.875, 7.5), 9.875));
	}

	/**
	 * Ground at 11, a pit of three by one two deep (x 7..9, z 7, its floor at 9), a step west of it at 10, and from the
	 * step a corridor west (x 0..5) walled to 12 on both sides, with these blocks in it at y 10 and under them at 9.
	 */
	private static Chars lane(char floor, char under) {
		Chars w = new Chars().fill(0, 5, 0, 14, 10, 14, '#');
		w.fill(7, 9, 7, 9, 10, 7, '.').set(6, 10, 7, '.');
		w.fill(0, 11, 6, 6, 11, 6, '#').fill(0, 11, 8, 6, 11, 8, '#');
		return w.fill(0, 9, 7, 5, 9, 7, under).fill(0, 10, 7, 5, 10, 7, floor);
	}

	@Test
	void aCorridorOfLowBlocksTheWalkerRefusesIsNoWayOut() {
		// the review of the fourth round (22 Oct 2026): the corridor runs on past the climb's range, but the walker
		// stands on no low block in it that holds a fluid or lies on one or on a block that burns (Follow.floorY:
		// Grinder.hazard on the cell and the one under it; nor is there water to swim in, under a lily pad or in
		// roots), as moss carpets lie on mangrove roots at the water line of a swamp: down there the buddy gets no farther
		// than the step
		PenShape.Mob m = zombie(7.5, 7.5, 9);
		Vec3 rim = new Vec3(10.5, 11, 7.5);
		assertEquals("in a pit", fight(lane('c', 'R'), m, rim, 11), "carpets on waterlogged roots");
		assertEquals(10, PenShape.pit(lane('c', 'R'), m).up(), 1e-9, "no farther than the step");
		assertEquals("in a pit", fight(lane('l', 'W'), m, rim, 11), "lily pads on water");
		assertEquals("in a pit", fight(lane('_', 'L'), m, rim, 11), "slabs over lava");
		assertEquals("in a pit", fight(lane('c', 'm'), m, rim, 11), "carpets on magma");
		assertEquals("in a pit", fight(lane('b', '#'), m, rim, 11), "waterlogged slabs");
		// a lantern is two boxes, neither of them both from its cell's bottom and to its top (the check of the review of
		// the fourth round): its cell's own all the same, as Follow.floorY takes it, so a waterlogged one is no floor
		assertEquals("in a pit", fight(lane('N', '#'), m, rim, 11), "waterlogged lanterns");
		// a low block on a fence is the cell's own all the same, though the fence's 1.5 is the column's top, and a chain
		// lying flat hangs in its cell, over stone or over air: floorY stands on neither (the review of 22 Oct 2026)
		assertEquals("in a pit", fight(lane('K', 'F'), m, rim, 11), "waterlogged candles on fences");
		assertEquals("in a pit", fight(lane('h', '#'), m, rim, 11), "chains over stone");
		assertEquals("in a pit", fight(lane('h', '.'), m, rim, 11), "chains over air");
		// on stone the same corridor is a way out onto open ground
		assertNull(fight(lane('c', '#'), m, rim, 11), "carpets on stone");
		assertNull(fight(lane('_', '#'), m, rim, 11), "slabs on stone");
		assertNull(fight(lane('n', '#'), m, rim, 11), "dry lanterns on stone");
		assertNull(fight(lane('k', 'F'), m, rim, 11), "dry candles on fences");
	}

	@Test
	void aFenceTopIsAFloorOverTheFence() {
		// a fence's or wall's 1.5 reaches into the cell over it from its own: the buddy stands on the fence, so one that holds
		// water is no floor, and water over it is water to swim in, as over a whole block (until the review of the
		// fourth round, 22 Oct 2026, its top was taken for a low block of the cell over it, and the fence itself not
		// looked at)
		assertEquals(List.of(11.5), PenShape.floorsBeside(ground().set(3, 10, 3, 'F'), 3, 3, 10.5, Double.NaN), "a fence");
		assertEquals(List.of(), PenShape.floorsBeside(ground().set(3, 10, 3, 'P'), 3, 3, 10.5, Double.NaN), "a waterlogged fence");
		assertEquals(List.of(11.5), PenShape.floorsBeside(ground().set(3, 10, 3, 'F').set(3, 11, 3, 'W'), 3, 3, 10.5, Double.NaN), "water over a fence");
	}

	/** A pit of one two deep at 7, 7 with a cell of its east wall at the floor, 8, 8, 7, and the one over it, set to these. */
	private static Chars doorway(char low, char high) {
		return pit(ground(), 7, 7, 7, 7, 2, '#').set(8, 8, 7, low).set(8, 9, 7, high);
	}

	/** And a corridor beyond it, east, that leads up to the ground by a step. */
	private static Chars corridor(Chars w) { return w.fill(9, 8, 7, 11, 9, 7, '.').set(12, 9, 7, '.'); }

	@Test
	void onlyADoorHeOpensWithGroundBeyondItLetsHimOut() {
		// what the walker opens (Follow.openable: a door a hand opens, a fence gate), with room for a player in the doorway and
		// ground beyond it; until the third review of 22 Oct 2026 every door, gate or trapdoor did, at any height
		PenShape.Mob m = zombie(7.5, 7.5, 8);
		Vec3 rim = new Vec3(4.5, 10, 7.5);
		assertNull(fight(corridor(doorway('O', 'O')), m, rim, 10), "a door to a corridor");
		assertNull(fight(corridor(doorway('D', '.')), m, rim, 10), "a gate with air over it to a corridor");
		assertEquals("in a pit", fight(doorway('O', 'O'), m, rim, 10), "a door to rock");
		assertEquals("in a pit", fight(doorway('I', 'I'), m, rim, 10), "an iron door to rock");
		assertEquals("in a pit", fight(corridor(doorway('I', 'I')), m, rim, 10), "an iron door to a corridor");
		assertEquals("in a pit", fight(corridor(doorway('D', '#')), m, rim, 10), "a gate under a block");
		assertEquals("in a pit", fight(corridor(doorway('T', '#')), m, rim, 10), "a top trapdoor's slit at the floor, under a block that would stop the buddy even with the trapdoor open");
		assertEquals("in a pit", fight(corridor(doorway('T', 'T')), m, rim, 10), "trapdoors the walker never opens");
		assertTrue(PenShape.pit(corridor(doorway('O', 'O')), m).door());
		assertFalse(PenShape.pit(corridor(doorway('I', 'I')), m).door());
		assertFalse(PenShape.pit(corridor(doorway('T', 'T')), m).door());
		// a closed bottom trapdoor under a block two up: there is room for a player on it (World.stand) and the zombie's
		// head meets the block, but it is no door the walker opens (throughDoor's own OPENABLE: the iron doors above never come
		// to it, World.stand refuses them first), and over it a walk leads into a corridor that ends blind (the review of
		// the fourth round, 22 Oct 2026)
		Chars blind = doorway('t', '.').set(8, 10, 7, '#').fill(9, 8, 7, 10, 9, 7, '.');
		assertTrue(PenShape.flood(blind, m, 8).closed(), PenShape.flood(blind, m, 8).refusal());
		assertEquals(1, PenShape.flood(blind, m, 8).cols().size(), "the trapdoor's cell is a wall to the zombie");
		assertFalse(PenShape.pit(blind, m).door(), "no door the walker opens");
		assertEquals("in a pit", fight(blind, m, rim, 10), "a bottom trapdoor to a corridor that ends blind");
	}

	@Test
	void aFencedCellAtHisLevelIsNoPitForTheFight() {
		// fences round three by three at the buddy's level, nothing that makes it a farm
		Chars w = ground().fill(5, 10, 5, 9, 10, 9, 'F').fill(6, 10, 6, 8, 10, 8, '.');
		assertNull(fight(w, zombie(7.5, 7.5, 10), new Vec3(7.5, 10, 11.5), 10));
	}

	@Test
	void aCellUnderADropShaftBehindADoorIsOneForTheFight() {
		// a kill cell at the buddy's level under a drop shaft, a door in its wall and no collector: a farm by its shaft alone, as
		// after a restart, which a walk through the door would lead into
		Chars w = ground().fill(6, 10, 6, 8, 11, 8, '#').fill(7, 10, 7, 7, 11, 7, '.').set(7, 10, 8, 'O').set(7, 11, 8, 'O');
		shaft(w, 7, 7, 7, 7, 12);
		PenShape.Mob m = zombie(7.5, 7.5, 10);
		PenShape.Pit pit = PenShape.pit(w, m);
		assertNotNull(pit);
		assertTrue(pit.farm(), "a farm by its shaft");
		assertEquals("in a pen", fight(w, m, new Vec3(7.5, 10, 11.5), 10));
	}

	/** The evidence as the gesture takes it: with water where the mobs' feet are as a collector when it runs on. */
	private static PenShape.Evidence gesture(PenShape.World w, PenShape.Mob m) {
		PenShape.Flood f = PenShape.flood(w, m, PenShape.floor(w, m));
		assertTrue(f.closed(), f.refusal());
		return PenShape.evidence(w, f, m, false, 1, false);
	}

	@Test
	void stillWaterIsNoCollector() {
		// a base's pool of two by two one deep: a zombie that stands in it or floats in it is no mob of a farm
		Chars pool = pit(ground(), 7, 7, 8, 8, 1, '#').fill(7, 9, 7, 8, 9, 8, 'W');
		assertFalse(gesture(pool, zombie(7.5, 7.5, 9)).enough(), "standing in the pool");
		assertFalse(gesture(pool, dropping(7.5, 7.5, 9.4)).enough(), "floating in the pool");
		// a field's water hole
		Chars hole = ground().fill(0, 9, 0, 14, 9, 14, 'f').set(7, 9, 7, 'W');
		assertFalse(gesture(hole, zombie(7.5, 7.5, 9)).enough(), "in a field's water hole");
	}

	@Test
	void aStreamOutUnderTheWallIsACollector() {
		// a cell of one at the buddy's level, a source at the mob's feet whose water runs east under its wall (a gap one high) and
		// on: flowing water there, as in the game (until the third review of 22 Oct 2026 this test drew the stream as
		// sources, still water, which carries nothing off)
		Chars w = ground().fill(6, 10, 6, 8, 12, 8, '#').fill(7, 10, 7, 7, 12, 7, '.');
		w.set(7, 10, 7, 'W').set(8, 10, 7, 'w').set(9, 10, 7, 'w');
		PenShape.Mob m = zombie(7.5, 7.5, 10);
		PenShape.Flood f = PenShape.flood(w, m, 10);
		assertTrue(f.closed(), f.refusal());
		assertEquals(1, f.cols().size());
		assertTrue(PenShape.collector(w, f), "the stream carries the drops off");
		assertTrue(gesture(w, m).enough());
		// sources under the wall and beyond it: water that stands
		w.set(8, 10, 7, 'W').set(9, 10, 7, 'W');
		assertFalse(PenShape.collector(w, f), "still water under the wall");
	}

	@Test
	void stillWaterEdgedWithWaterloggedBlocksIsNoCollector() {
		// a pool of two by two one deep whose south wall is waterlogged stairs, as steps into a pool are: a source in the
		// stairs, still water
		Chars stairs = pit(ground(), 7, 7, 8, 8, 1, '#').fill(7, 9, 7, 8, 9, 8, 'W').fill(7, 9, 9, 8, 9, 9, 'Q');
		assertFalse(gesture(stairs, zombie(7.5, 7.5, 9)).enough(), "a pool with waterlogged stairs");
		// walled all round by waterlogged mangrove roots
		Chars roots = ground().fill(6, 9, 6, 9, 9, 9, 'R').fill(7, 9, 7, 8, 9, 8, 'W');
		assertFalse(gesture(roots, zombie(7.5, 7.5, 9)).enough(), "a pool among waterlogged roots");
		// its third row under a wall one up (a deck, a house's edge): open water, but still
		Chars deck = pit(ground(), 7, 7, 8, 9, 1, '#').fill(7, 9, 7, 8, 9, 9, 'W').fill(7, 10, 9, 8, 12, 9, '#');
		PenShape.Flood f = PenShape.flood(deck, zombie(7.5, 7.5, 9), 9);
		assertTrue(f.closed(), f.refusal());
		assertEquals(4, f.cols().size(), "the row under the deck is a wall to the zombie");
		assertFalse(gesture(deck, zombie(7.5, 7.5, 9)).enough(), "a pool under a deck");
		// a base's pond of four by four with lily pads on two rows: closed three by three, water under the pads
		Chars pond = pit(ground(), 6, 6, 9, 9, 1, '#').fill(6, 9, 6, 9, 9, 9, 'W').fill(6, 10, 9, 9, 10, 9, 'l').fill(9, 10, 6, 9, 10, 9, 'l');
		PenShape.Flood p = PenShape.flood(pond, zombie(7.5, 7.5, 9), 9);
		assertTrue(p.closed(), p.refusal());
		assertEquals(9, p.cols().size());
		assertFalse(gesture(pond, zombie(7.5, 7.5, 9)).enough(), "a pond with lily pads");
	}

	/** A zombie in the air, its feet at this height: no floor under it within four (each test checks that first). */
	private static PenShape.Mob dropping(double x, double z, double feet) {
		return new PenShape.Mob(ZW, ZH, ZE, new AABB(x - ZW / 2, feet, z - ZW / 2, x + ZW / 2, feet + ZH, z + ZW / 2), false);
	}

	@Test
	void aMobDroppingDownAOneByOneShaftIsInAShaft() {
		Chars w = shaft(pit(ground(), 7, 7, 7, 7, 2, 'H'), 7, 7, 7, 7, 12);
		PenShape.Mob m = dropping(7.5, 7.5, 16);
		assertTrue(Double.isNaN(PenShape.floor(w, m)));
		assertTrue(PenShape.inShaft(w, m));
		// its feet still in the open band over the ring, its head already between the glass
		PenShape.Mob low = dropping(7.5, 7.5, 11.7);
		assertTrue(Double.isNaN(PenShape.floor(w, low)));
		assertTrue(PenShape.inShaft(w, low));
	}

	@Test
	void aMobInAQuarterOfATwoByTwoShaftIsInAShaft() {
		// its own column is one of four: the other three are the shaft's too, open, and the glass closes all of them
		Chars w = shaft(pit(ground(), 7, 7, 8, 8, 2, 'H'), 7, 7, 8, 8, 12);
		PenShape.Mob m = dropping(7.5, 7.5, 16);
		assertTrue(Double.isNaN(PenShape.floor(w, m)));
		assertTrue(PenShape.inShaft(w, m));
	}

	@Test
	void aMobInDeepWaterIsInNoShaft() {
		// a drowned in the middle of a basin of 5 by 5 full of water, more than four above its bottom
		Chars w = ground().fill(4, 10, 4, 10, 20, 10, '#').fill(5, 10, 5, 9, 20, 9, 'W');
		PenShape.Mob m = dropping(7.5, 7.5, 15);
		assertTrue(Double.isNaN(PenShape.floor(w, m)), "water is no floor");
		assertFalse(PenShape.inShaft(w, m));
	}

	@Test
	void aMobOverLavaIsInNoShaft() {
		// half in a lake of lava six deep: no floor in it, and the lake and the air over it run on
		Chars w = ground().fill(2, 10, 2, 12, 15, 12, 'L');
		PenShape.Mob m = dropping(7.5, 7.5, 15.5);
		assertTrue(Double.isNaN(PenShape.floor(w, m)), "lava is no floor");
		assertFalse(PenShape.inShaft(w, m));
	}

	@Test
	void aMobInTheOpenAirIsInNoShaft() {
		PenShape.Mob m = dropping(7.5, 7.5, 20);
		assertTrue(Double.isNaN(PenShape.floor(ground(), m)));
		assertFalse(PenShape.inShaft(ground(), m));
	}

	@Test
	void theHitsKeepAHumanPace() {
		PenShape.Pace pace = new PenShape.Pace();
		Random r = new Random(20261022L);
		for (double cooldown : new double[] { 625, 1000, 250 }) {
			double bound = Math.max(PenShape.MIN_GAP, PenShape.Pace.gate(cooldown));
			// the tick a blow comes on, as Pen.hitTick takes them: the first whose end is the gap on, and none before the
			// strength reads STRONG
			int strong = (int) Math.ceil(PenShape.STRONG * cooldown / 50 - 1.5);
			int[] ticks = new int[100];
			long[] gaps = new long[4000];
			for (int i = 0; i < gaps.length; i++) {
				gaps[i] = pace.gap(cooldown, r);
				assertTrue(gaps[i] >= bound, "under the strength's gate or the six a second: " + gaps[i]);
				ticks[Math.min(99, Math.max(strong, (int) Math.ceil(gaps[i] / 50.0)))]++;
			}
			// a gap under the gate bounces back: the gate's own tick holds fewer blows than the next one (when such gaps
			// waited for it, it held a fifth of them, two to four times what the ticks after it did)
			assertTrue(ticks[strong] < ticks[strong + 1], "on the gate's tick " + ticks[strong] + ", on the next " + ticks[strong + 1] + " for a cooldown of " + cooldown);
			Arrays.sort(gaps);
			double median = gaps[gaps.length / 2];
			double want = Math.max(PenShape.MIN_GAP, cooldown) * 1.1;
			assertTrue(median > want * 0.9 && median < want * 1.25, "median " + median + " for a cooldown of " + cooldown);
		}
	}
}
