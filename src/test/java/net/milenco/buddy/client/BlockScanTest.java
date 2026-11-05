package net.milenco.buddy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

/**
 * The scan keeps the nearest places of each kind while it reads, and leaves unread what could not be kept: the places
 * kept, and their order, are those of a sort of every place found by distance, cut after KEEP (as the scan did before).
 */
class BlockScanTest {
	private static final String[] KINDS = { "lava", "stone", "nether_quartz_ore", "spawner" };

	/** A section of random blocks: per cell the kind (null for one not wanted) and whether a place there counts. */
	private record Section(int cx, int sy, int cz, String[] kind, boolean[] counts, Set<String> may) {
		int at(int x, int y, int z) { return (y << 8) | (z << 4) | x; }
	}

	private static Section section(Random r, int cx, int sy, int cz, boolean palette) {
		// how much of each kind: none, a little, much or all
		double[] share = new double[KINDS.length];
		for (int k = 0; k < KINDS.length; k++) share[k] = new double[] { 0, 0, 0.002, 0.05, 0.4, 1 }[r.nextInt(6)];
		String[] kind = new String[4096];
		boolean[] counts = new boolean[4096];
		Set<String> may = new HashSet<>();
		for (int i = 0; i < 4096; i++) {
			for (int k = 0; k < KINDS.length; k++) if (r.nextDouble() < share[k]) { kind[i] = KINDS[k]; break; }
			counts[i] = r.nextDouble() < 0.7;
			if (kind[i] != null) may.add(kind[i]);
		}
		// the palette may name a kind no longer there, and the global one names none: then every kind may be there
		if (r.nextInt(4) == 0) may.add(KINDS[r.nextInt(KINDS.length)]);
		if (!palette) may.addAll(List.of(KINDS));
		return new Section(cx, sy, cz, kind, counts, may);
	}

	/** As the scan did it before: every place that counts, in the order read, sorted by distance, the first KEEP. */
	private static Map<String, List<BlockPos>> fullSort(BlockPos centre, List<Section> order) {
		Map<String, List<BlockPos>> all = new HashMap<>();
		for (Section s : order)
			for (int y = 0; y < 16; y++)
				for (int z = 0; z < 16; z++)
					for (int x = 0; x < 16; x++) {
						int i = s.at(x, y, z);
						if (s.kind()[i] == null || !s.counts()[i]) continue;
						all.computeIfAbsent(s.kind()[i], k -> new ArrayList<>()).add(new BlockPos((s.cx() << 4) + x, (s.sy() << 4) + y, (s.cz() << 4) + z));
					}
		Map<String, List<BlockPos>> out = new HashMap<>();
		for (Map.Entry<String, List<BlockPos>> e : all.entrySet()) {
			List<BlockPos> l = e.getValue();
			l.sort(Comparator.comparingDouble(b -> b.distSqr(centre)));
			out.put(e.getKey(), List.copyOf(l.subList(0, Math.min(BlockScan.KEEP, l.size()))));
		}
		return out;
	}

	/** The round of the scan over these sections; {@code read} counts the cells it looked at. */
	private static Map<String, List<BlockPos>> round(BlockPos centre, List<Section> order, int[] read) {
		BlockScan.Round round = new BlockScan.Round(centre);
		for (Section s : order) {
			round.read(s.cx(), s.sy(), s.cz(), s.may(), new BlockScan.Round.Cells() {
				int last;

				@Override
				public String kind(int x, int y, int z) {
					read[0]++;
					last = s.at(x, y, z);
					return s.kind()[last];
				}

				@Override
				public boolean counts(int x, int y, int z) {
					assertEquals(last, s.at(x, y, z));
					return s.counts()[last];
				}
			});
		}
		return round.result();
	}

	private static List<Section> world(Random r, boolean palette) {
		List<Section> l = new ArrayList<>();
		for (int cx = -1; cx <= 1; cx++)
			for (int sy = -1; sy <= 1; sy++)
				for (int cz = -1; cz <= 1; cz++) l.add(section(r, cx, sy, cz, palette));
		return l;
	}

	@Test
	void keepsWhatTheFullSortKept() {
		Random r = new Random(263);
		for (int w = 0; w < 30; w++) {
			List<Section> sections = world(r, w % 5 != 0);
			BlockPos centre = new BlockPos(r.nextInt(80) - 40, r.nextInt(80) - 40, r.nextInt(80) - 40);
			// nearest first as the scan reads them, or in any order: the answer is the same
			List<Section> order = new ArrayList<>(sections);
			if (w % 2 == 0) Collections.shuffle(order, r);
			else {
				int cx = centre.getX() >> 4, cy = centre.getY() >> 4, cz = centre.getZ() >> 4;
				order.sort(Comparator.comparingLong(s -> (long) (s.cx() - cx) * (s.cx() - cx) + (long) (s.sy() - cy) * (s.sy() - cy)
					+ (long) (s.cz() - cz) * (s.cz() - cz)));
			}
			assertEquals(fullSort(centre, order), round(centre, order, new int[1]), "world " + w);
		}
	}

	@Test
	void aLavaSeaIsHardlyRead() {
		List<Section> sea = new ArrayList<>();
		for (int cx = -1; cx <= 1; cx++)
			for (int sy = -1; sy <= 1; sy++)
				for (int cz = -1; cz <= 1; cz++) {
					String[] kind = new String[4096];
					boolean[] counts = new boolean[4096];
					java.util.Arrays.fill(kind, "lava");
					java.util.Arrays.fill(counts, true);
					sea.add(new Section(cx, sy, cz, kind, counts, Set.of("lava")));
				}
		BlockPos centre = new BlockPos(7, 9, 4);
		sea.sort(Comparator.comparingLong(s -> (long) s.cx() * s.cx() + (long) s.sy() * s.sy() + (long) s.cz() * s.cz()));
		int[] read = new int[1];
		assertEquals(fullSort(centre, sea), round(centre, sea, read));
		assertTrue(read[0] < 27 * 4096 / 10, "read " + read[0] + " cells");
	}

	@Test
	void ofPlacesAsFarTheFirstFoundStays() {
		Random r = new Random(48);
		for (int keep : new int[] { 1, 5, 48 }) {
			for (int n = 0; n < 50; n++) {
				BlockPos centre = new BlockPos(0, 0, 0);
				List<BlockPos> found = new ArrayList<>();
				for (int i = r.nextInt(300); i > 0; i--) found.add(new BlockPos(r.nextInt(7) - 3, r.nextInt(7) - 3, r.nextInt(7) - 3));
				BlockScan.Nearest near = new BlockScan.Nearest(keep);
				for (BlockPos p : found) near.add(p, (long) p.distSqr(centre));
				List<BlockPos> sorted = new ArrayList<>(found);
				sorted.sort(Comparator.comparingDouble(b -> b.distSqr(centre)));
				assertEquals(sorted.subList(0, Math.min(keep, sorted.size())), near.nearestFirst());
			}
		}
	}
}
