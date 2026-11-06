package adris.altoclef.ui.gui.kit;

// ease out for things arriving, in-out for things that travel. nothing bounces, we are not a toy
public final class Easing {

    private Easing() {
    }

    public static float outCubic(float t) {
        t = clamp01(t);
        float u = 1f - t;
        return 1f - u * u * u;
    }

    public static float inOutCubic(float t) {
        t = clamp01(t);
        if (t < 0.5f) {
            return 4f * t * t * t;
        }
        float u = -2f * t + 2f;
        return 1f - u * u * u / 2f;
    }

    static float clamp01(float t) {
        return t < 0f ? 0f : Math.min(t, 1f);
    }
}
