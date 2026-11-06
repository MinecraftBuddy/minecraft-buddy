package adris.altoclef.ui.gui.kit;

// layout needs text widths before anything is drawn, so measuring is its own thing the Theme carries around.
// both backends measure with the same awt font, so a preview pixel is a game pixel
public interface TextMeasurer {

    float measure(String s, TextStyle style);

    float ascent(TextStyle style);

    float descent(TextStyle style);

    default float lineHeight(TextStyle style) {
        return ascent(style) + descent(style);
    }

    // cuts with a single ellipsis so the result fits. the middle dot style ellipsis is not used, one plain "…"
    default String ellipsize(String s, TextStyle style, float maxWidth) {
        if (measure(s, style) <= maxWidth) {
            return s;
        }
        String dots = "…";
        float dotsW = measure(dots, style);
        int lo = 0;
        int hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (measure(s.substring(0, mid), style) + dotsW <= maxWidth) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo == 0 ? "" : s.substring(0, lo) + dots;
    }
}
