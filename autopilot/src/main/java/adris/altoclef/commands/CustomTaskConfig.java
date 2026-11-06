package adris.altoclef.commands;

import adris.altoclef.util.helpers.ConfigHelper;

// configs/CustomTasks.json. gson fills it in by field name, so the fields stay as they were
class CustomTaskConfig {

    private static CustomTaskConfig _instance;

    // the command is always called custom now, this only stays so old files still load
    public String prefix = "custom2";
    public CustomTaskEntry[] customTasks = new CustomTaskEntry[0];

    // loaded on first use instead of at startup: the commands exist before altoclef does, and this would otherwise
    // write the file into the game folder on every launch whether anyone wanted custom tasks or not
    static synchronized CustomTaskConfig get() {
        if (_instance == null) {
            ConfigHelper.loadConfig("configs/CustomTasks.json", CustomTaskConfig::new, CustomTaskConfig.class, newConfig -> _instance = newConfig);
        }
        if (_instance == null) {
            _instance = new CustomTaskConfig();
        }
        return _instance;
    }

    static class CustomTaskEntry {
        public String name;
        public String description;
        public CustomSubTaskEntry[] tasks;

        static class CustomSubTaskEntry {
            public String command;
            public String[][] parameters;
        }
    }
}
