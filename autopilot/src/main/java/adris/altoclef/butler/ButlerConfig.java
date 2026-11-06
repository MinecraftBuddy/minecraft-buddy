package adris.altoclef.butler;

import adris.altoclef.util.helpers.ConfigHelper;

public class ButlerConfig {

    private static ButlerConfig _instance = new ButlerConfig();

    static {
        ConfigHelper.loadConfig("configs/butler.json", ButlerConfig::new, ButlerConfig.class, newConfig -> _instance = newConfig);
    }

    /**
     * If true, the blacklist rejects users even when they are on the whitelist.
     * <p>
     * There is no useButlerWhitelist anymore: the whitelist is always required (empty means nobody). An old
     * butler.json that still has it loads fine, the value is ignored.
     */
    public boolean useButlerBlacklist = true;
    /**
     * Servers have different messaging plugins that change the way messages are displayed.
     * Rather than attempt to implement all of them and introduce a big security risk,
     * you may define custom whisper formats that the butler will watch out for.
     * <p>
     * The default "{from} {to} {message}" is not text-matched at all, the butler already knows who sent what to whom.
     * Any other format is matched against "sender receiver message" for the message part only, the sender always
     * stays the real one.
     * <p>
     * Within curly brackets are three special parts:
     * <p>
     * {from}: Who the message was sent from
     * {to}: Who the message was sent to, butler will ignore if this is not your username.
     * {message}: The message.
     * <p>
     * <p>
     * WARNING: The butler will only accept non-chat messages as commands, but don't make this too lenient,
     * else you may risk unauthorized control to the bot. Basically, make sure that only whispers can
     * create the following messages.
     */
    public String[] whisperFormats = new String[]{
            "{from} {to} {message}"
    };
    /**
     * If set to true, will print information about whispers that are parsed and those
     * that have failed parsing.
     * <p>
     * Enable this if you need help setting up the whisper format. Off by default, otherwise every whisper anyone
     * sends you would print in your chat now that the butler is on.
     * <p>
     * This replaces whisperFormatDebug, which defaulted to true and is saved in every butler.json that ever loaded,
     * so an old file that still has it loads fine and the value is ignored.
     */
    public boolean debugWhispers = false;
    /**
     * Determines if failure messages should be sent to a non-authorized entity attempting to use butler.
     * Off by default so the bot stays quiet to strangers, and when it is on a name gets at most one answer
     * every half minute.
     * <p>
     * This replaces sendAuthorizationResponse, which defaulted to true and got saved into every butler.json that
     * ever loaded, so an old file that still has it loads fine and the value is ignored.
     */
    public boolean replyToUnauthorized = false;
    /**
     * The response sent in a failed execution due to non-authorization
     * {from}: the username of the player who triggered the failed authorization response
     */
    public String failedAuthorizationResposne = "Sorry {from} but you are not authorized!";
    /**
     * Use this to choose if the prefix should be required in messages
     * <p>
     * Disable this if you want to be able to send normal messages and not butler commands.
     */
    public boolean requirePrefixMsg = false;

    public static ButlerConfig getInstance() {
        return _instance;
    }
}
