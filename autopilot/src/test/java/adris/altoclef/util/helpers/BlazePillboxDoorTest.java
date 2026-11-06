package adris.altoclef.util.helpers;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import java.util.List;
import org.junit.Test;

// the way into a standing pillbox starts outside its look out's open side (BlazePillbox.door)
public class BlazePillboxDoorTest {

    @Test
    public void theBentBoxOpensStraightOnFromItsFrontCell() {
        // F west of B and the look out south of F: open to the south
        BlazePillbox.Plan plan = new BlazePillbox.Plan(new int[]{9, 57, 2}, new int[]{8, 57, 3}, List.of(), new int[]{8, 57, 2});
        assertArrayEquals(new int[]{8, 57, 4}, BlazePillbox.door(plan));
        // F north of B and the look out east of F: open to the east
        BlazePillbox.Plan east = new BlazePillbox.Plan(new int[]{7, 56, 4}, new int[]{8, 56, 3}, List.of(), new int[]{7, 56, 3});
        assertArrayEquals(new int[]{9, 56, 3}, BlazePillbox.door(east));
    }

    @Test
    public void theOneCellBoxIsEnteredFromItsLookOut() {
        BlazePillbox.Plan plan = new BlazePillbox.Plan(new int[]{0, 64, 0}, new int[]{1, 64, 0}, List.of());
        assertArrayEquals(new int[]{1, 64, 0}, BlazePillbox.door(plan));
    }

    @Test
    public void theRaisedBoxHasNoDoorOnTheGround() {
        BlazePillbox.Plan plan = new BlazePillbox.Plan(new int[]{9, 57, 2}, new int[]{8, 57, 3}, List.of(), new int[]{8, 57, 2}, null, 2);
        assertNull(BlazePillbox.door(plan));
    }
}
