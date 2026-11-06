package adris.altoclef.tasks;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

// a parent keeps its hands off the drops while a running miner below it sweeps them, and only sees down through running
// resource tasks to find one
public class ResourceTaskDropOwnerTest {

    // stand-in for a task in the chain: resource task or not, running or not, owns its drops or not
    private record Node(boolean resource, boolean active, boolean owns, Node sub) {
    }

    private static boolean ownerBelow(Node first) {
        return ResourceTask.ownerBelow(first, Node::sub, n -> n.resource() && n.active(), Node::owns) != null;
    }

    private static Node miner(Node sub) {
        return new Node(true, true, true, sub);
    }

    private static Node resource(Node sub) {
        return new Node(true, true, false, sub);
    }

    @Test
    public void aMinerRightUnderUsOwnsTheDrops() {
        assertTrue(ownerBelow(miner(null)));
    }

    @Test
    public void aMinerUnderAPlainResourceTaskStillCounts() {
        // catalogued > collect cobblestone > mine and collect
        assertTrue(ownerBelow(resource(miner(null))));
    }

    @Test
    public void aStoppedMinerDoesNotCount() {
        assertFalse(ownerBelow(new Node(true, false, true, null)));
        assertFalse(ownerBelow(resource(new Node(true, false, true, null))));
    }

    @Test
    public void somethingElseInBetweenStopsTheWalk() {
        // a pickup or goto in the middle: whatever miner is under it was not handed out by us
        assertFalse(ownerBelow(new Node(false, true, false, miner(null))));
        assertFalse(ownerBelow(resource(new Node(false, true, false, miner(null)))));
    }

    @Test
    public void aStoppedResourceTaskInBetweenStopsTheWalk() {
        assertFalse(ownerBelow(new Node(true, false, false, miner(null))));
    }

    @Test
    public void noOwnerNoSkip() {
        assertFalse(ownerBelow(null));
        assertFalse(ownerBelow(resource(resource(null))));
    }

    @Test
    public void theOwnerFoundIsTheFirstMinerDown() {
        Node deep = miner(null);
        Node shallow = miner(deep);
        assertSame(shallow, ResourceTask.ownerBelow(resource(shallow), Node::sub, n -> n.resource() && n.active(), Node::owns));
    }

    @Test
    public void onlyTheMinersOwnItemsAreHandsOff() {
        // sticks over a dead bush miner: the bush drops sticks, the miner only sweeps bushes, so the sticks stay ours
        String[] owned = {"dead_bush"};
        assertTrue(ResourceTask.contains(owned, "dead_bush"));
        assertFalse(ResourceTask.contains(owned, "stick"));
        assertFalse(ResourceTask.contains(new String[0], "cobblestone"));
    }

    @Test
    public void aMinerCoversTheBlocksItBreaksAnyway() {
        // logs and flowers: the catalogue's mineIfPresent list is the same as the miner's own
        assertTrue(ResourceTask.coversAll(new String[]{"birch_log"}, new String[]{"birch_log"}));
        assertTrue(ResourceTask.coversAll(new String[]{"dirt"}, new String[]{"grass_block", "dirt"}));
        assertTrue(ResourceTask.coversAll(new String[0], new String[]{"dirt"}));
    }

    @Test
    public void aBlockTheMinerSkipsStillGetsTheExtraMiner() {
        // saplings come out of leaves, a placed sapling is not on the miner's list
        assertFalse(ResourceTask.coversAll(new String[]{"oak_sapling"}, new String[]{"oak_leaves", "mangrove_propagule"}));
        assertFalse(ResourceTask.coversAll(new String[]{"oak_log", "spruce_log"}, new String[]{"oak_log"}));
    }
}
