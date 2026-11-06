package adris.altoclef.ui.gui.kit;

import adris.altoclef.ui.gui.facade.Result;

// the tabs are built before the shell (the shell takes them in its constructor) but they want the shell for
// toasts and overlays. so they get this, and the screen attaches the real one right after. calls before that
// go nowhere, which only happens in a constructor nobody should be toasting from anyway
public final class DeferredContext implements ShellContext {

    private final Theme theme;
    private final Clipboard clipboard;
    private ShellContext real;

    public DeferredContext(Theme theme, Clipboard clipboard) {
        this.theme = theme;
        this.clipboard = clipboard;
    }

    public void attach(ShellContext shell) {
        real = shell;
    }

    @Override
    public Theme theme() {
        return theme;
    }

    @Override
    public Clipboard clipboard() {
        return clipboard;
    }

    @Override
    public void toast(Result result) {
        if (real != null) {
            real.toast(result);
        }
    }

    @Override
    public void toast(String line, String command) {
        if (real != null) {
            real.toast(line, command);
        }
    }

    @Override
    public void focus(Widget widget) {
        if (real != null) {
            real.focus(widget);
        }
    }

    @Override
    public void blur() {
        if (real != null) {
            real.blur();
        }
    }

    @Override
    public void openOverlay(Widget overlay) {
        if (real != null) {
            real.openOverlay(overlay);
        }
    }

    @Override
    public void closeOverlay() {
        if (real != null) {
            real.closeOverlay();
        }
    }

    @Override
    public void requestClose() {
        if (real != null) {
            real.requestClose();
        }
    }

    @Override
    public void openKeyRebound(int key) {
        if (real != null) {
            real.openKeyRebound(key);
        }
    }
}
