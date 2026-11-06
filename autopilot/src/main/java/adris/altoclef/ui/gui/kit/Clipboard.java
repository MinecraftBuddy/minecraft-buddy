package adris.altoclef.ui.gui.kit;

// the game's clipboard behind a two method door so text editing tests can use a string
public interface Clipboard {

    String get();

    void set(String text);

    static Clipboard inMemory() {
        return new Clipboard() {
            private String s = "";

            @Override
            public String get() {
                return s;
            }

            @Override
            public void set(String text) {
                s = text == null ? "" : text;
            }
        };
    }
}
