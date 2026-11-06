package baritone.behavior.trip;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

// the words the user reads. the question texts are the user's own, keep them that way
public class TripPromptTest {

    @Test
    public void questionText() {
        assertEquals("I can't finish this path without blocks, may I collect some so I can continue?",
                TripPrompt.question(new GatherPlan(Trip.Ask.STUCK, 6, 8, false)));
        assertEquals("I found a faster path, but it needs placing blocks. May I collect blocks so I can take it?",
                TripPrompt.question(new GatherPlan(Trip.Ask.FASTER, 6, 8, false)));
        assertEquals("I found a faster path, but it needs mining. May I make a pickaxe so I can take it?",
                TripPrompt.question(new GatherPlan(Trip.Ask.FASTER, 0, 0, true)));
        assertEquals("I found a faster path, but it needs mining and placing blocks. May I collect blocks and make a pickaxe so I can take it?",
                TripPrompt.question(new GatherPlan(Trip.Ask.FASTER, 6, 8, true)));
        assertEquals("(about 8 blocks, saves about 1m 20s)", TripPrompt.detail(new GatherPlan(Trip.Ask.FASTER, 6, 8, false), 1600));
        assertEquals("(about 8 blocks)", TripPrompt.detail(new GatherPlan(Trip.Ask.STUCK, 6, 8, false), 1600));
        assertEquals("45s", TripPrompt.duration(900));
    }
}
