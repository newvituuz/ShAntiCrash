package br.com.singlehope.shanticrash.packet;

/**
 * Dense, protocol independent classification of serverbound packets.
 *
 * <p>Checks declare the kinds they care about, so dispatch is a single array lookup and the check
 * code does not depend on PacketEvents classes (which keeps it unit testable).
 */
public enum PacketKind {
    // BungeeCord forwarding stores profile data in the handshake and 1.19 adds keys to login start.
    HANDSHAKE(FloodGroup.NONE, false),
    STATUS(FloodGroup.NONE, true),
    LOGIN_START(FloodGroup.NONE, false),
    LOGIN_PLUGIN_RESPONSE(FloodGroup.NONE, false),
    LOGIN_OTHER(FloodGroup.NONE, false),
    CONFIG_PLUGIN_MESSAGE(FloodGroup.PLUGIN_MESSAGE, false),
    CONFIG_OTHER(FloodGroup.NONE, false),

    CLICK_WINDOW(FloodGroup.CLICK_WINDOW, false),
    CLICK_WINDOW_BUTTON(FloodGroup.CLICK_WINDOW_BUTTON, true),
    CLOSE_WINDOW(FloodGroup.CLOSE_WINDOW, true),
    WINDOW_CONFIRMATION(FloodGroup.KEEP_ALIVE, true),
    CREATIVE_SLOT(FloodGroup.CREATIVE_SLOT, false),
    BLOCK_PLACE(FloodGroup.BLOCK_PLACE, false),
    USE_ITEM(FloodGroup.USE_ITEM, true),
    DIGGING(FloodGroup.DIGGING, true),
    INTERACT_ENTITY(FloodGroup.INTERACT_ENTITY, true),
    ANIMATION(FloodGroup.ANIMATION, true),
    HELD_ITEM_CHANGE(FloodGroup.HELD_ITEM_CHANGE, true),
    MOVE_POSITION(FloodGroup.MOVEMENT, true),
    MOVE_POSITION_ROTATION(FloodGroup.MOVEMENT, true),
    MOVE_ROTATION(FloodGroup.MOVEMENT, true),
    MOVE_FLYING(FloodGroup.MOVEMENT, true),
    VEHICLE_MOVE(FloodGroup.VEHICLE, true),
    STEER_VEHICLE(FloodGroup.VEHICLE, true),
    PLUGIN_MESSAGE(FloodGroup.PLUGIN_MESSAGE, false),
    EDIT_BOOK(FloodGroup.EDIT_BOOK, false),
    UPDATE_SIGN(FloodGroup.UPDATE_SIGN, false),
    NAME_ITEM(FloodGroup.NAME_ITEM, false),
    TAB_COMPLETE(FloodGroup.TAB_COMPLETE, false),
    CHAT_MESSAGE(FloodGroup.CHAT, false),
    CHAT_COMMAND(FloodGroup.CHAT, false),
    CRAFT_RECIPE(FloodGroup.CRAFT_RECIPE, true),
    RECIPE_BOOK(FloodGroup.RECIPE_BOOK, true),
    RECIPE_DISPLAYED(FloodGroup.RECIPE_BOOK, true),
    KEEP_ALIVE(FloodGroup.KEEP_ALIVE, true),
    PONG(FloodGroup.KEEP_ALIVE, true),
    CLIENT_TICK_END(FloodGroup.CLIENT_TICK, true),
    ENTITY_ACTION(FloodGroup.ENTITY_ACTION, true),
    COMMAND_BLOCK_EDIT(FloodGroup.COMMAND_BLOCK, false),
    SELECT_TRADE(FloodGroup.CLICK_WINDOW_BUTTON, true),
    OTHER(FloodGroup.OTHER, false);

    private final FloodGroup group;
    private final boolean small;

    PacketKind(FloodGroup group, boolean small) {
        this.group = group;
        this.small = small;
    }

    /** The rate limiter bucket this kind consumes from. */
    public FloodGroup group() {
        return group;
    }

    /** Whether a legitimate packet of this kind is always a small fixed or almost fixed size. */
    public boolean isSmall() {
        return small;
    }

    public boolean isPlay() {
        return ordinal() >= CLICK_WINDOW.ordinal();
    }
}
