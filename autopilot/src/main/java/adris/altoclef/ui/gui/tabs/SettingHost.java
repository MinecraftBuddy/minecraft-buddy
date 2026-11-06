package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.facade.SettingsAccess.SettingView;

// what a settings row may ask of the tab around it. set and reset toast and refresh every other view of the setting
// themselves, the row only needs to know whether it worked
interface SettingHost {

    Result set(String name, String text);

    Result reset(String name);

    String description(String name);

    // the row under the mouse (or being typed in), for the detail strip at the bottom
    void hover(SettingView view);

    boolean mouseOverRows(float x, float y);
}
