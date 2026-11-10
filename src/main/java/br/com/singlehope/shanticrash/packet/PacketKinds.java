package br.com.singlehope.shanticrash.packet;

import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;

import java.util.EnumMap;
import java.util.Map;

/** Translates PacketEvents packet types into {@link PacketKind}. */
public final class PacketKinds {
    private static final PacketKind[] PLAY = new PacketKind[PacketType.Play.Client.values().length];

    static {
        Map<PacketType.Play.Client, PacketKind> map = new EnumMap<>(PacketType.Play.Client.class);
        map.put(PacketType.Play.Client.CLICK_WINDOW, PacketKind.CLICK_WINDOW);
        map.put(PacketType.Play.Client.CLICK_WINDOW_BUTTON, PacketKind.CLICK_WINDOW_BUTTON);
        map.put(PacketType.Play.Client.CLOSE_WINDOW, PacketKind.CLOSE_WINDOW);
        map.put(PacketType.Play.Client.WINDOW_CONFIRMATION, PacketKind.WINDOW_CONFIRMATION);
        map.put(PacketType.Play.Client.CREATIVE_INVENTORY_ACTION, PacketKind.CREATIVE_SLOT);
        map.put(PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT, PacketKind.BLOCK_PLACE);
        map.put(PacketType.Play.Client.USE_ITEM, PacketKind.USE_ITEM);
        map.put(PacketType.Play.Client.PLAYER_DIGGING, PacketKind.DIGGING);
        map.put(PacketType.Play.Client.INTERACT_ENTITY, PacketKind.INTERACT_ENTITY);
        map.put(PacketType.Play.Client.ANIMATION, PacketKind.ANIMATION);
        map.put(PacketType.Play.Client.HELD_ITEM_CHANGE, PacketKind.HELD_ITEM_CHANGE);
        map.put(PacketType.Play.Client.PLAYER_POSITION, PacketKind.MOVE_POSITION);
        map.put(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION, PacketKind.MOVE_POSITION_ROTATION);
        map.put(PacketType.Play.Client.PLAYER_ROTATION, PacketKind.MOVE_ROTATION);
        map.put(PacketType.Play.Client.PLAYER_FLYING, PacketKind.MOVE_FLYING);
        map.put(PacketType.Play.Client.VEHICLE_MOVE, PacketKind.VEHICLE_MOVE);
        map.put(PacketType.Play.Client.STEER_VEHICLE, PacketKind.STEER_VEHICLE);
        map.put(PacketType.Play.Client.PLAYER_INPUT, PacketKind.STEER_VEHICLE);
        map.put(PacketType.Play.Client.STEER_BOAT, PacketKind.STEER_VEHICLE);
        map.put(PacketType.Play.Client.PLUGIN_MESSAGE, PacketKind.PLUGIN_MESSAGE);
        map.put(PacketType.Play.Client.EDIT_BOOK, PacketKind.EDIT_BOOK);
        map.put(PacketType.Play.Client.UPDATE_SIGN, PacketKind.UPDATE_SIGN);
        map.put(PacketType.Play.Client.NAME_ITEM, PacketKind.NAME_ITEM);
        map.put(PacketType.Play.Client.TAB_COMPLETE, PacketKind.TAB_COMPLETE);
        map.put(PacketType.Play.Client.CHAT_MESSAGE, PacketKind.CHAT_MESSAGE);
        map.put(PacketType.Play.Client.CHAT_COMMAND, PacketKind.CHAT_COMMAND);
        map.put(PacketType.Play.Client.CHAT_COMMAND_UNSIGNED, PacketKind.CHAT_COMMAND);
        map.put(PacketType.Play.Client.CRAFT_RECIPE_REQUEST, PacketKind.CRAFT_RECIPE);
        map.put(PacketType.Play.Client.SET_RECIPE_BOOK_STATE, PacketKind.RECIPE_BOOK);
        map.put(PacketType.Play.Client.RECIPE_BOOK_DATA, PacketKind.RECIPE_BOOK);
        map.put(PacketType.Play.Client.SET_DISPLAYED_RECIPE, PacketKind.RECIPE_DISPLAYED);
        map.put(PacketType.Play.Client.KEEP_ALIVE, PacketKind.KEEP_ALIVE);
        map.put(PacketType.Play.Client.PONG, PacketKind.PONG);
        map.put(PacketType.Play.Client.CLIENT_TICK_END, PacketKind.CLIENT_TICK_END);
        map.put(PacketType.Play.Client.ENTITY_ACTION, PacketKind.ENTITY_ACTION);
        map.put(PacketType.Play.Client.UPDATE_COMMAND_BLOCK, PacketKind.COMMAND_BLOCK_EDIT);
        map.put(PacketType.Play.Client.UPDATE_COMMAND_BLOCK_MINECART, PacketKind.COMMAND_BLOCK_EDIT);
        map.put(PacketType.Play.Client.UPDATE_JIGSAW_BLOCK, PacketKind.COMMAND_BLOCK_EDIT);
        map.put(PacketType.Play.Client.UPDATE_STRUCTURE_BLOCK, PacketKind.COMMAND_BLOCK_EDIT);
        map.put(PacketType.Play.Client.SELECT_TRADE, PacketKind.SELECT_TRADE);
        for (PacketType.Play.Client type : PacketType.Play.Client.values()) {
            PLAY[type.ordinal()] = map.getOrDefault(type, PacketKind.OTHER);
        }
    }

    private PacketKinds() {
    }

    public static PacketKind of(PacketTypeCommon type) {
        if (type instanceof PacketType.Play.Client) {
            return PLAY[((PacketType.Play.Client) type).ordinal()];
        }
        if (type instanceof PacketType.Handshaking.Client) {
            return PacketKind.HANDSHAKE;
        }
        if (type instanceof PacketType.Status.Client) {
            return PacketKind.STATUS;
        }
        if (type instanceof PacketType.Login.Client) {
            if (type == PacketType.Login.Client.LOGIN_START) {
                return PacketKind.LOGIN_START;
            }
            return type == PacketType.Login.Client.LOGIN_PLUGIN_RESPONSE
                    ? PacketKind.LOGIN_PLUGIN_RESPONSE : PacketKind.LOGIN_OTHER;
        }
        if (type instanceof PacketType.Configuration.Client) {
            return type == PacketType.Configuration.Client.PLUGIN_MESSAGE
                    ? PacketKind.CONFIG_PLUGIN_MESSAGE : PacketKind.CONFIG_OTHER;
        }
        return PacketKind.OTHER;
    }
}
