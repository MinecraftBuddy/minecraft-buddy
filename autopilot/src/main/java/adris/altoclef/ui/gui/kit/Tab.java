package adris.altoclef.ui.gui.kit;

// one sidebar entry. the Shell owns the chrome (title, search field, status strip, toasts), the tab owns the content
public interface Tab {

    String id();

    String title();

    Icon icon();

    // built once, the Shell keeps it
    Widget content();

    default boolean hasSearch() {
        return false;
    }

    default String searchPlaceholder() {
        return "Search";
    }

    default void onSearch(String query) {
    }

    // enter pressed in the search field
    default void onSearchSubmit(String query) {
    }

    default void onShown() {
    }

    default void onHidden() {
    }
}
