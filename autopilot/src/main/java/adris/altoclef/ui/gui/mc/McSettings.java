package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.facade.SettingsAccess;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.model.Wording;
import baritone.Baritone;
import baritone.altoclef.AltoClefBridge;
import baritone.api.Settings;
import baritone.api.utils.SettingsUtil;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

// SettingsAccess over the real Settings. every change takes the road #set takes: parse and apply, tell AltoClef the
// user just touched it (so a running task does not hand the old value back), save settings.txt
final class McSettings implements SettingsAccess {

    private static final String DESCRIPTIONS = "/adris/altoclef/ui/gui/settings-descriptions.properties";

    private Properties descriptions;

    // pulled out so a test can check the mapping without a live Settings
    static Kind kindOf(String name, Class<?> valueClass) {
        if ("guiKey".equals(name)) {
            return Kind.KEY;
        }
        if (valueClass == Boolean.class || valueClass == boolean.class) {
            return Kind.BOOLEAN;
        }
        if (valueClass == Integer.class || valueClass == int.class) {
            return Kind.INTEGER;
        }
        if (valueClass == Long.class || valueClass == long.class) {
            return Kind.LONG;
        }
        if (valueClass == Float.class || valueClass == float.class) {
            return Kind.FLOAT;
        }
        if (valueClass == Double.class || valueClass == double.class) {
            return Kind.DOUBLE;
        }
        if (valueClass == String.class) {
            return Kind.STRING;
        }
        if (valueClass == java.awt.Color.class) {
            return Kind.COLOR;
        }
        if (valueClass != null && valueClass.isEnum()) {
            return Kind.ENUM;
        }
        return Kind.OTHER;
    }

    @Override
    public List<SettingView> all() {
        List<SettingView> out = new ArrayList<>();
        try {
            for (Settings.Setting<?> s : Baritone.settings().allSettings) {
                if (s.isJavaOnly()) {
                    continue;
                }
                SettingView v = view(s);
                if (v != null) {
                    out.add(v);
                }
            }
            out.sort(Comparator.comparing(v -> v.name().toLowerCase(Locale.ROOT)));
        } catch (Throwable t) {
            Safe.report("settings list", t);
        }
        return out;
    }

    @Override
    public SettingView get(String name) {
        try {
            Settings.Setting<?> s = find(name);
            return s == null || s.isJavaOnly() ? null : view(s);
        } catch (Throwable t) {
            Safe.report("settings get", t);
            return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static SettingView view(Settings.Setting<?> s) {
        try {
            Class<?> cls = s.getValueClass();
            Kind kind = kindOf(s.getName(), cls);
            List<String> options = new ArrayList<>();
            if (kind == Kind.ENUM) {
                for (Object c : cls.getEnumConstants()) {
                    options.add(((Enum<?>) c).name());
                }
            }
            // the equals check is stricter than #set modified, which compares references and so calls a boxed
            // number set back to its own default "modified"
            boolean modified = s.value != null && !Objects.equals(s.value, s.defaultValue);
            return new SettingView(s.getName(), kind, SettingsUtil.settingTypeToString((Settings.Setting) s),
                    SettingsUtil.settingValueToString((Settings.Setting) s),
                    SettingsUtil.settingDefaultToString((Settings.Setting) s), modified, List.copyOf(options));
        } catch (Throwable t) {
            // one setting with a type nobody can print should not take the whole tab down
            Safe.report("setting view " + s.getName(), t);
            return null;
        }
    }

    private static Settings.Setting<?> find(String name) {
        return name == null ? null : Baritone.settings().findByLowerName(name.toLowerCase(Locale.US));
    }

    @Override
    public Result set(String name, String text) {
        try {
            Settings.Setting<?> s = find(name);
            if (s == null || s.isJavaOnly()) {
                return Result.fail("There is no setting called " + name + ".");
            }
            String command = "set " + s.getName() + " " + text;
            try {
                SettingsUtil.parseAndApply(Baritone.settings(), s.getName().toLowerCase(Locale.US), text);
            } catch (Throwable t) {
                return Result.fail(cleanError(t, s), command);
            }
            changed(s);
            return Result.ok("Set " + s.getName() + " to " + valueOf(s), command);
        } catch (Throwable t) {
            Safe.report("settings set", t);
            return Result.fail("Could not change " + name + ".");
        }
    }

    @Override
    public Result reset(String name) {
        try {
            Settings.Setting<?> s = find(name);
            if (s == null || s.isJavaOnly()) {
                return Result.fail("There is no setting called " + name + ".");
            }
            s.reset();
            changed(s);
            return Result.ok("Reset " + s.getName() + " to " + valueOf(s), "set reset " + s.getName());
        } catch (Throwable t) {
            Safe.report("settings reset", t);
            return Result.fail("Could not reset " + name + ".");
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Result toggle(String name) {
        try {
            Settings.Setting<?> s = find(name);
            if (s == null || s.isJavaOnly()) {
                return Result.fail("There is no setting called " + name + ".");
            }
            if (s.getValueClass() != Boolean.class) {
                return Result.fail(s.getName() + " is not an on/off setting.");
            }
            Settings.Setting<Boolean> b = (Settings.Setting<Boolean>) s;
            b.value ^= true;
            changed(s);
            return Result.ok("Toggled " + s.getName() + " to " + b.value, "set toggle " + s.getName());
        } catch (Throwable t) {
            Safe.report("settings toggle", t);
            return Result.fail("Could not toggle " + name + ".");
        }
    }

    @Override
    public String description(String name) {
        try {
            if (descriptions == null) {
                descriptions = load();
            }
            String d = descriptions.getProperty(name);
            if (d == null && name != null) {
                d = descriptions.getProperty(name.toLowerCase(Locale.US));
            }
            return d == null ? "" : Wording.soprano(d);
        } catch (Throwable t) {
            Safe.report("settings description", t);
            return "";
        }
    }

    private static Properties load() {
        Properties p = new Properties();
        // the generator may not have run yet, no file just means no descriptions
        try (InputStream in = McSettings.class.getResourceAsStream(DESCRIPTIONS)) {
            if (in != null) {
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            Safe.report("settings descriptions file", t);
        }
        return p;
    }

    // the two calls #set makes after it changes something
    private static void changed(Settings.Setting<?> s) {
        AltoClefBridge.userChanged(s);
        AltoClefBridge.saveSettings(Baritone.settings());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String valueOf(Settings.Setting<?> s) {
        return SettingsUtil.settingValueToString((Settings.Setting) s);
    }

    private static String cleanError(Throwable t, Settings.Setting<?> s) {
        if (t instanceof NumberFormatException) {
            return "Not a number. Try something like 64 or 1.5.";
        }
        String m = t.getMessage();
        if (m == null || m.isBlank() || m.length() > 100) {
            return "That is not a valid value for " + s.getName() + ".";
        }
        return Wording.soprano(m);
    }
}
