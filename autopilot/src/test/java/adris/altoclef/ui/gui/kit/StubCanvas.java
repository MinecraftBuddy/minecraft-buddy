package adris.altoclef.ui.gui.kit;

import java.util.ArrayList;
import java.util.List;

// records what was drawn and measures text as 0.6 em per char, so tests know exactly where every glyph boundary is
public class StubCanvas implements Canvas {

    public record Call(String kind, String text, float x, float y, float w, float h, int argb) {
    }

    public final List<Call> calls = new ArrayList<>();
    public int clipDepth;
    private final int width;
    private final int height;

    public StubCanvas() {
        this(1920, 1080);
    }

    public StubCanvas(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public static Theme theme() {
        return new Theme(1f, new StubCanvas());
    }

    public long count(String kind) {
        return calls.stream().filter(c -> c.kind().equals(kind)).count();
    }

    public List<String> texts() {
        List<String> out = new ArrayList<>();
        for (Call c : calls) {
            if (c.kind().equals("text")) {
                out.add(c.text());
            }
        }
        return out;
    }

    public Call textCall(String s) {
        for (Call c : calls) {
            if (c.kind().equals("text") && c.text().equals(s)) {
                return c;
            }
        }
        return null;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public void fillRect(float x, float y, float w, float h, int argb) {
        calls.add(new Call("fillRect", null, x, y, w, h, argb));
    }

    @Override
    public void fillRoundRect(float x, float y, float w, float h, float radius, int argb) {
        calls.add(new Call("fillRoundRect", null, x, y, w, h, argb));
    }

    @Override
    public void strokeRoundRect(float x, float y, float w, float h, float radius, int argb) {
        calls.add(new Call("strokeRoundRect", null, x, y, w, h, argb));
    }

    @Override
    public void shadow(float x, float y, float w, float h, float radius, int argb) {
        calls.add(new Call("shadow", null, x, y, w, h, argb));
    }

    @Override
    public float text(String s, float x, float baseline, TextStyle style, int argb) {
        calls.add(new Call("text", s, x, baseline, 0, 0, argb));
        return measure(s, style);
    }

    @Override
    public void icon(Icon icon, float x, float y, float size, int argb) {
        calls.add(new Call("icon", icon.name(), x, y, size, size, argb));
    }

    @Override
    public void itemIcon(String itemId, float x, float y, float size) {
        calls.add(new Call("itemIcon", itemId, x, y, size, size, 0));
    }

    @Override
    public void image(PixelImage image, float x, float y, float w, float h, int tint) {
        calls.add(new Call("image", image.width() + "x" + image.height() + "@" + image.version(), x, y, w, h, tint));
    }

    @Override
    public void pushClip(float x, float y, float w, float h) {
        clipDepth++;
    }

    @Override
    public void popClip() {
        clipDepth--;
    }

    @Override
    public float measure(String s, TextStyle style) {
        return 0.6f * style.sizePx() * s.length();
    }

    @Override
    public float ascent(TextStyle style) {
        return 0.75f * style.sizePx();
    }

    @Override
    public float descent(TextStyle style) {
        return 0.25f * style.sizePx();
    }
}
