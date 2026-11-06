package adris.altoclef.ui.gui.facade;

import adris.altoclef.ui.gui.kit.Clipboard;

// everything the gui knows about the game, behind interfaces the preview and the tests can fake
public interface BotFacade {

    SettingsAccess settings();

    CommandsAccess commands();

    ItemsAccess items();

    WorldAccess world();

    StatusAccess status();

    KeyNames keyNames();

    Clipboard clipboard();

    // the stats tab's numbers. the default keeps older fakes compiling until they bother
    default StatsAccess stats() {
        return StatsAccess.NONE;
    }

    // the schematics tab's folder, costs and import buttons. same deal as stats: the default keeps older fakes compiling
    default SchematicsAccess schematics() {
        return SchematicsAccess.NONE;
    }
}
