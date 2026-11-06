package adris.altoclef.ui.gui.facade;

import java.util.List;

// the settings tab's view of baritone.api.Settings. java-only settings are never in here. every change goes the
// same road as #set: parse, apply, AltoClefBridge.userChanged, save
public interface SettingsAccess {

    enum Kind { BOOLEAN, INTEGER, LONG, FLOAT, DOUBLE, STRING, COLOR, ENUM, KEY, OTHER }

    // options holds the enum constants for ENUM, empty otherwise. value and defaultValue are the #set text forms
    record SettingView(String name, Kind kind, String typeName, String value, String defaultValue, boolean modified,
                       List<String> options) {
    }

    // one snapshot, sorted by name, built when the screen opens
    List<SettingView> all();

    // the current state of one setting after a change, null if there is no such setting
    SettingView get(String name);

    Result set(String name, String text);

    Result reset(String name);

    Result toggle(String name);

    // plain text from the javadoc, "" when there is none
    String description(String name);
}
