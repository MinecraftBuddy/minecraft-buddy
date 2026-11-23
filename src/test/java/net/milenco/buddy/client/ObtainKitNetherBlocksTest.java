package net.milenco.buddy.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The kit's stone blocks are not asked in the nether, which has no stone (cascade heel-233 r4: 20 minutes on 16 cobblestone). */
class ObtainKitNetherBlocksTest {
	static ObtainPlan.Have in(Nature.Dim dim) {
		return new ObtainPlan.Have() {
			public int inv(String item) { return item.equals("cobblestone") ? 11 : 0; }
			public Nature.Dim dim() { return dim; }
		};
	}

	@Test
	void blocksAreSkippedInTheNetherOnly() {
		ObtainKit.Need blocks = new ObtainKit.Need("blocks", List.of("cobblestone", "cobbled_deepslate"), 16);
		ObtainKit.Need more = new ObtainKit.Need("more blocks", List.of("cobblestone", "cobbled_deepslate"), 48);
		assertTrue(ObtainKit.skip(blocks, in(Nature.Dim.NETHER)));
		assertTrue(ObtainKit.skip(more, in(Nature.Dim.NETHER)));
		assertFalse(ObtainKit.skip(blocks, in(Nature.Dim.OVERWORLD)), "the overworld still asks them");
		assertFalse(ObtainKit.skip(blocks, in(Nature.Dim.END)), "the End still asks them");
	}
}
