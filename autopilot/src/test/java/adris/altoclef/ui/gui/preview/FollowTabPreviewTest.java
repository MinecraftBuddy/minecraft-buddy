package adris.altoclef.ui.gui.preview;

import adris.altoclef.ui.gui.kit.Tab;
import adris.altoclef.ui.gui.kit.VirtualList;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.model.EntityGroups.GroupRow;
import adris.altoclef.ui.gui.tabs.FollowTab;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class FollowTabPreviewTest {

    private FollowTab tab;

    private List<Tab> tabs(Preview p) {
        tab = new FollowTab(p.theme, p.facade, p.ctx);
        return List.of(tab);
    }

    private Preview preview(FakeFacade f, int w, int h) throws Exception {
        Preview p = new Preview(w, h, f, this::tabs);
        p.settle();
        p.advance(300);
        return p;
    }

    private static float rowX(VirtualList<?> l) {
        return l.x() + l.w() / 3f;
    }

    private static float rowY(VirtualList<?> l, int row) {
        return l.y() + l.rowHeight() * row + l.rowHeight() / 2f;
    }

    // the tab's two lists, found through the public widget tree: players first, entities second
    private static final class Lists {
        private final List<VirtualList<?>> found = new ArrayList<>();

        Lists(FollowTab tab) {
            for (Widget k : tab.content().children()) {
                if (k instanceof VirtualList<?> l) {
                    found.add(l);
                }
            }
        }

        VirtualList<?> players() {
            return found.get(0);
        }

        VirtualList<?> entities() {
            return found.get(1);
        }

        int groupIndex(String typeId) {
            List<?> rows = entities().items();
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i) instanceof GroupRow g && g.group().typeId().equals(typeId)) {
                    return i;
                }
            }
            throw new AssertionError("no group " + typeId);
        }
    }

    private static void tick(Preview p, int n) {
        for (int i = 0; i < n; i++) {
            p.tick();
        }
    }

    @Test
    public void followStates() throws Exception {
        FakeFacade f = new FakeFacade();
        Preview p = preview(f, 1600, 900);
        p.savePanel("follow_default");

        f.world.following = "Steve";
        tick(p, 10);
        p.advance(200);
        p.savePanel("follow_following_steve");

        // hovering a player row shows the ghost Follow button
        Lists lists = new Lists(tab);
        p.hover(rowX(lists.players()), rowY(lists.players(), 2));
        p.advance(100);
        p.savePanel("follow_hover_player");

        // the zombie group expanded
        f.world.following = null;
        tick(p, 10);
        int zombie = lists.groupIndex("minecraft:zombie");
        p.click(rowX(lists.entities()), rowY(lists.entities(), zombie));
        p.advance(40);
        p.savePanel("follow_chevron_mid");
        p.advance(300);
        p.hover(rowX(lists.entities()), rowY(lists.entities(), zombie + 2));
        p.savePanel("follow_zombie_expanded");

        // hovering a group header shows "Follow all"
        p.hover(rowX(lists.entities()), rowY(lists.entities(), zombie));
        p.advance(100);
        p.savePanel("follow_hover_group");

        // following every zombie marks the group
        f.world.followType("minecraft:zombie");
        tick(p, 10);
        p.hover(-10, -10);
        p.advance(100);
        p.savePanel("follow_following_group");

        // search
        f.world.following = null;
        tick(p, 10);
        tab.onSearch("zom");
        p.advance(300);
        p.savePanel("follow_search_zom");

        tab.onSearch("zzz");
        p.advance(300);
        p.savePanel("follow_search_none");
    }

    @Test
    public void emptyWorld() throws Exception {
        FakeFacade f = new FakeFacade();
        f.world.players.clear();
        f.world.entities.clear();
        Preview p = preview(f, 1600, 900);
        p.savePanel("follow_empty");
    }

    @Test
    public void smallWindow() throws Exception {
        FakeFacade f = new FakeFacade();
        f.world.following = "Steve";
        Preview p = preview(f, 960, 540);
        p.savePanel("follow_960x540");
        f.world.following = null;
        tick(p, 10);
        p.savePanel("follow_960x540_idle");
    }
}
