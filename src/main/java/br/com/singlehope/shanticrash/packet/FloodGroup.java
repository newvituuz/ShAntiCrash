package br.com.singlehope.shanticrash.packet;

/** Independent rate limiter buckets. Movement packets share one because they are alternatives. */
public enum FloodGroup {
    NONE,
    MOVEMENT,
    VEHICLE,
    CLICK_WINDOW,
    CLICK_WINDOW_BUTTON,
    CLOSE_WINDOW,
    CREATIVE_SLOT,
    BLOCK_PLACE,
    USE_ITEM,
    DIGGING,
    INTERACT_ENTITY,
    ANIMATION,
    HELD_ITEM_CHANGE,
    PLUGIN_MESSAGE,
    EDIT_BOOK,
    UPDATE_SIGN,
    NAME_ITEM,
    TAB_COMPLETE,
    CHAT,
    CRAFT_RECIPE,
    RECIPE_BOOK,
    KEEP_ALIVE,
    CLIENT_TICK,
    ENTITY_ACTION,
    COMMAND_BLOCK,
    OTHER
}
