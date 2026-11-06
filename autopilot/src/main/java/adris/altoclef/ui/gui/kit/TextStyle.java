package adris.altoclef.ui.gui.kit;

// a font face at a pixel size. tabular lays every digit out at the widest digit's advance, which is as close to
// a font's tnum as awt lets us get, and it is what keeps columns of coordinates from wobbling
public record TextStyle(Weight weight, int sizePx, boolean tabular) {

    public TextStyle(Weight weight, int sizePx) {
        this(weight, sizePx, false);
    }

    public TextStyle asTabular() {
        return tabular ? this : new TextStyle(weight, sizePx, true);
    }

    public TextStyle withSize(int px) {
        return new TextStyle(weight, px, tabular);
    }
}
