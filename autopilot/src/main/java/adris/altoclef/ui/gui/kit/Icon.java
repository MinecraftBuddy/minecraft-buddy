package adris.altoclef.ui.gui.kit;

// every glyph the gui needs. drawn as vector strokes by Icons into the atlas at the pixel size asked for, so no pngs
public enum Icon {
    GET,            // a parcel: cube with a tape stripe
    GOTO,           // a path from a green dot to a red one
    FOLLOW,         // a person with an arrow leading off to the right
    WAYPOINTS,      // flag
    HELP,           // question mark in a ring
    SETTINGS,       // two sliders
    STATS,          // three bars, ascending
    SCHEMATICS,     // a wall of bricks, what a schematic turns into
    WARNING,        // triangle with a bang
    SEARCH,         // magnifier
    CLOSE,          // x
    PLUS,
    MINUS,
    CHEVRON_DOWN,
    CHEVRON_RIGHT,
    CHECK,
    COPY,           // two overlapping squares
    RESET,          // counter clockwise arrow
    STOP,           // filled square
    KEY,            // keyboard key outline
    DOT,            // filled circle (busy)
    RING,           // hollow circle (idle)
    TRASH,
    PIN,            // my position
    PLAYER,         // head and shoulders
    ENTITY,         // paw
    BOAT, PARKOUR, BOLT, RUN, PICKAXE, BLOCK, WRENCH, BACKPACK, WATER, LADDER, EYE,
    CHEVRON_UP,
    CHEVRON_LEFT,
    ROTATE_LEFT,    // a turn about a dot (the dot is what tells it from RESET)
    ROTATE_RIGHT,
    FLIP,           // two wings either side of a dashed axis
    LEVEL_UP,       // a chevron over a bar: one layer up
    LEVEL_DOWN,
    MOVE,           // four arrows out of a cross, the move gizmo
    // the title screen's dock and corner buttons
    GEAR,           // options
    GLOBE,          // multiplayer
    PEOPLE,         // friends: one in front, one peeking over its shoulder
    CLOUD,          // realms
    PUZZLE,         // a button some other mod put on the title screen
    POWER,          // quit
    LANGUAGE,       // a small globe and an A
    ACCESSIBILITY,  // arms out in a ring
    SPEAKER,        // menu music on: a speaker and two waves
    SPEAKER_OFF     // menu music off: the same speaker and an x
}
