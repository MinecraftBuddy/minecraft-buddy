package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.kit.Button;
import adris.altoclef.ui.gui.kit.Theme;

// the basket's big blue button. it is the kit's primary button so it matches the one on the go to tab, it just
// starts out disabled because an empty basket has nothing to send
final class GetGoButton extends Button {

    GetGoButton(Theme theme, Runnable onClick) {
        super(theme, "", Kind.PRIMARY, null, onClick);
        setEnabled(false);
    }
}
