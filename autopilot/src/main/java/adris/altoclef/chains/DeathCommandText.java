package adris.altoclef.chains;

// kept out of DeathMenuChain so it can be tested without dragging the death screen along
public final class DeathCommandText {

    private DeathCommandText() {
    }

    // the death message goes into the death command before it is split on ";" and run with full permissions, and a
    // name tag is free text: a mob called "x; set altoButler true" would chain a command. ";" and "&" (the separator
    // between death commands) become commas and anything that is not printable becomes a space, so what comes out
    // is one harmless line
    public static String sanitize(String deathMessage) {
        if (deathMessage == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(deathMessage.length());
        for (int i = 0; i < deathMessage.length(); i++) {
            char c = deathMessage.charAt(i);
            if (c == ';' || c == '&') {
                out.append(',');
            } else if (Character.isISOControl(c) || (Character.isSpaceChar(c) && c != ' ')) {
                out.append(' ');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
