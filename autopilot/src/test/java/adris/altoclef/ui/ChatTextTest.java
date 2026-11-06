package adris.altoclef.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// what a reply looks like on the wire, and the queue that paces it. the game itself is not needed for any of it
public class ChatTextTest {

    @Test
    public void whisperLineIsWhatTheChatBoxWouldSend() {
        assertEquals("/msg ayvyr ` Command Finished: get cobblestone 4", ChatText.whisperLine("ayvyr", "` Command Finished: get cobblestone 4"));
    }

    @Test
    public void thingsTheServerKicksForAreGone() {
        assertEquals("a b c", ChatText.clean("a\n b\t\tc"));
        assertEquals("ab", ChatText.clean("a§b"));
        assertEquals("ab", ChatText.clean("a\u0000b\u007f"));
        assertEquals("hi", ChatText.clean("  hi  "));
        assertEquals("", ChatText.clean(null));
        assertEquals("", ChatText.clean(" \n\t "));
    }

    @Test
    public void longMessagesAreCutAtTheChatLimit() {
        String cut = ChatText.clean("x".repeat(1000));
        assertEquals(ChatText.MAX_LENGTH, cut.length());
        // a whole emoji or none of it
        String emoji = "a".repeat(ChatText.MAX_LENGTH - 1) + "😀";
        assertEquals(ChatText.MAX_LENGTH - 1, ChatText.clean(emoji).length());
    }

    @Test
    public void aWhisperToNobodyOrWithNothingToSayIsNotSent() {
        assertNull(ChatText.whisperLine(null, "hi"));
        assertNull(ChatText.whisperLine("", "hi"));
        assertNull(ChatText.whisperLine("bob", "  "));
        assertNull(ChatText.whisperLine("bob", null));
        // a "name" with a space in it would smuggle extra words into the command
        assertNull(ChatText.whisperLine("bob get", "hi"));
        assertNull(ChatText.whisperLine("a_very_long_name_indeed", "hi"));
        assertFalse(ChatText.isSendableName("b\nob"));
        assertTrue(ChatText.isSendableName(".Bedrock"));
    }

    @Test
    public void aBackedUpQueueDropsTheUnimportantFirst() {
        MessageSender sender = new MessageSender();
        for (int i = 0; i < MessageSender.MAX_QUEUED; i++) {
            sender.enqueueWhisper("bob", "line " + i, MessagePriority.OPTIONAL);
        }
        assertEquals(MessageSender.MAX_QUEUED, sender.queued());
        // an optional line or a stranger's answer does not get in
        sender.enqueueWhisper("bob", "more", MessagePriority.OPTIONAL);
        sender.enqueueWhisper("eve", "no", MessagePriority.UNAUTHORIZED);
        assertEquals(MessageSender.MAX_QUEUED, sender.queued());
        // a failure pushes out the oldest optional one
        sender.enqueueWhisper("bob", "TASK FAILED", MessagePriority.ASAP);
        assertEquals(MessageSender.MAX_QUEUED, sender.queued());
        assertEquals("/msg bob line 1", sender.nextLine());
    }

    @Test
    public void aFullQueueOfImportantThingsIsKeptAsItIs() {
        MessageSender sender = new MessageSender();
        for (int i = 0; i < MessageSender.MAX_QUEUED; i++) {
            sender.enqueueWhisper("bob", "line " + i, MessagePriority.ASAP);
        }
        sender.enqueueWhisper("bob", "late", MessagePriority.ASAP);
        assertEquals(MessageSender.MAX_QUEUED, sender.queued());
        assertEquals("/msg bob line 0", sender.nextLine());
    }

    @Test
    public void messagesLeaveInTheOrderTheyWereAskedFor() {
        MessageSender sender = new MessageSender();
        sender.enqueueWhisper("bob", "executing", MessagePriority.TIMELY);
        sender.enqueueWhisper("bob", "inventory", MessagePriority.OPTIONAL);
        sender.enqueueWhisper("bob", "failed", MessagePriority.ASAP);
        sender.enqueueWhisper("", "nobody", MessagePriority.ASAP);
        sender.enqueueChat("/op me", MessagePriority.ASAP);
        sender.enqueueChat("hello all", MessagePriority.TIMELY);
        assertEquals("/msg bob executing", sender.nextLine());
        assertEquals("/msg bob inventory", sender.nextLine());
        assertEquals("/msg bob failed", sender.nextLine());
        // the nameless whisper and the slash in public chat were skipped, not sent
        assertEquals("hello all", sender.nextLine());
        assertNull(sender.nextLine());
    }
}
