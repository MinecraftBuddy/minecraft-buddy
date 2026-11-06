package adris.altoclef.eventbus.events;

// a system chat line from the server (a death message, "Respawn point set"...), not a player's chat. ChatMessageEvent only ever
// sees players
public class SystemChatEvent {
    public final String message;

    public SystemChatEvent(String message) {
        this.message = message;
    }
}
