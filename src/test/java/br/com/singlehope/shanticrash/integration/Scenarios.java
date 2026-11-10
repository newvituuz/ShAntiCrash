package br.com.singlehope.shanticrash.integration;

import br.com.singlehope.shanticrash.support.Wire;
import com.github.retrooper.packetevents.protocol.component.ComponentType;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;

import java.util.ArrayList;
import java.util.List;

/** What is sent to the server per protocol, and which check must react to it. */
final class Scenarios {
    /** Action performed by a connected bot. */
    interface Attack {
        void run(ProtocolBot bot) throws Exception;
    }

    /** One test case. An empty {@code expectedCheck} means that nothing may be detected. */
    static final class Scenario {
        final String name;
        final String expectedCheck;
        final boolean creative;
        final Attack attack;

        Scenario(String name, String expectedCheck, boolean creative, Attack attack) {
            this.name = name;
            this.expectedCheck = expectedCheck;
            this.creative = creative;
            this.attack = attack;
        }
    }

    private final int protocol;
    private final ClientVersion client;
    private final boolean named;

    Scenarios(ClientVersion client) {
        this.client = client;
        this.protocol = client.getProtocolVersion();
        this.named = protocol < 764;
    }

    private boolean v(int minimum) {
        return protocol >= minimum;
    }

    // ------------------------------------------------------------------ items

    private byte[] legacyItem(byte[] nbt) {
        Wire w = Wire.create();
        if (v(404)) {
            w.bool(true).v(1).b(1);
        } else if (v(393)) {
            w.s(1).b(1);
        } else {
            w.s(1).b(1).s(0);
        }
        return w.raw(nbt).toBytes();
    }

    private int component(ComponentType<?> type) {
        return type.getId(client);
    }

    /** An item with one component, in the format the creative packet uses. */
    private byte[] componentItem(ComponentType<?> type, byte[] value, boolean lengthPrefix) {
        Wire w = Wire.create().v(1).v(1).v(1).v(0).v(component(type));
        return lengthPrefix ? w.prefixed(value).toBytes() : w.raw(value).toBytes();
    }

    /** An item holding this NBT, in the layout of the protocol. */
    private byte[] creativeItem(byte[] nbt) {
        return v(766) ? componentItem(ComponentTypes.CUSTOM_DATA, nbt, v(770)) : legacyItem(nbt);
    }

    private byte[] clickItem(byte[] nbt) {
        return v(766) ? componentItem(ComponentTypes.CUSTOM_DATA, nbt, false) : legacyItem(nbt);
    }

    private byte[] nestedContainers(int levels) {
        if (!v(766)) {
            return legacyItem(Wire.nbtNestedContainers(levels, named));
        }
        int container = component(ComponentTypes.CONTAINER);
        Wire value = Wire.create();
        for (int i = 0; i < levels; i++) {
            if (v(775)) {
                // Optional template: present, item id, count, one component.
                value.v(1).bool(true).v(1).v(1).v(1).v(0).v(container);
            } else {
                value.v(1).v(1).v(1).v(1).v(0).v(container);
            }
        }
        value.v(0);
        return componentItem(ComponentTypes.CONTAINER, value.toBytes(), v(770));
    }

    private byte[] creativeSlot(byte[] item) {
        return Wire.create().s(36).raw(item).toBytes();
    }

    private byte[] emptyItem() {
        if (v(404)) {
            return new byte[]{0};
        }
        return Wire.create().s(-1).toBytes();
    }

    // ------------------------------------------------------------------ packets

    private Wire clickHeader(int window, int slot, int button, int mode) {
        Wire w = Wire.create();
        if (v(768)) {
            w.v(window);
        } else {
            w.b(window);
        }
        if (v(756)) {
            w.v(1);
        }
        w.s(slot).b(button);
        if (!v(755)) {
            w.s(1);
        }
        return w.v(mode);
    }

    private byte[] click(int window, int slot, int button, int mode, byte[] carried) {
        Wire w = clickHeader(window, slot, button, mode);
        if (v(770)) {
            return w.v(0).bool(false).toBytes();
        }
        if (v(755)) {
            w.v(0);
        }
        return w.raw(carried).toBytes();
    }

    private byte[] position(double x, double y, double z) {
        return Wire.create().d(x).d(y).d(z).b(1).toBytes();
    }

    private byte[] pluginMessage(String channel, byte[] data) {
        return Wire.create().str(channel).raw(data).toBytes();
    }

    private byte[] chatOrCommand(String text) {
        return Wire.create().str(text).toBytes();
    }

    private byte[] tab(String text) {
        Wire w = Wire.create();
        if (v(393)) {
            return w.v(1).str(text).toBytes();
        }
        w.str(text);
        if (v(107)) {
            w.bool(false);
        }
        return w.bool(false).toBytes();
    }

    private PacketType.Play.Client commandPacket() {
        if (v(766)) {
            return PacketType.Play.Client.CHAT_COMMAND_UNSIGNED;
        }
        return v(759) ? PacketType.Play.Client.CHAT_COMMAND : PacketType.Play.Client.CHAT_MESSAGE;
    }

    private String commandText(String command) {
        return v(759) ? command.substring(1) : command;
    }

    // ------------------------------------------------------------------ scenarios

    List<Scenario> all() {
        List<Scenario> list = new ArrayList<>();

        list.add(new Scenario("trafego-normal", "", false, bot -> {
            for (int i = 0; i < 20; i++) {
                bot.send(bot.playId(PacketType.Play.Client.PLAYER_POSITION), position(0.5 + i * 0.01, 64, 0.5));
                bot.send(bot.playId(PacketType.Play.Client.PLAYER_ROTATION), Wire.create().f(i).f(0).b(1).toBytes());
                if (v(768)) {
                    bot.send(bot.playId(PacketType.Play.Client.CLIENT_TICK_END), new byte[0]);
                }
                Thread.sleep(50L);
            }
            bot.send(bot.playId(PacketType.Play.Client.CLICK_WINDOW), click(0, 9, 0, 0, emptyItem()));
            bot.send(bot.playId(PacketType.Play.Client.HELD_ITEM_CHANGE), Wire.create().s(1).toBytes());
            bot.send(bot.playId(PacketType.Play.Client.TAB_COMPLETE), tab("/gamemode "));
            bot.send(bot.playId(PacketType.Play.Client.PLUGIN_MESSAGE),
                    pluginMessage(v(393) ? "minecraft:brand" : "MC|Brand", Wire.create().str("vanilla").toBytes()));
            Thread.sleep(500L);
        }));

        list.add(new Scenario("posicao-nan", "position.invalid-numbers", false, bot ->
                bot.send(bot.playId(PacketType.Play.Client.PLAYER_POSITION), position(Double.NaN, 64, 0))));
        list.add(new Scenario("posicao-infinita", "position.invalid-numbers", false, bot ->
                bot.send(bot.playId(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION),
                        Wire.create().d(Double.POSITIVE_INFINITY).d(64).d(0).f(0).f(0).b(1).toBytes())));
        list.add(new Scenario("posicao-fora-do-mundo", "position.bounds", false, bot ->
                bot.send(bot.playId(PacketType.Play.Client.PLAYER_POSITION), position(9.0E9, 64, 0))));
        list.add(new Scenario("posicao-formato", "position.format", false, bot ->
                bot.send(bot.playId(PacketType.Play.Client.PLAYER_POSITION), new byte[12])));
        list.add(new Scenario("rotacao-invalida", "position.rotation", false, bot ->
                bot.send(bot.playId(PacketType.Play.Client.PLAYER_ROTATION), Wire.create().f(0).f(900F).b(1).toBytes())));

        list.add(new Scenario("frame-gigante", "netty.frame-size", false, bot ->
                bot.send(bot.playId(PacketType.Play.Client.PLUGIN_MESSAGE),
                        pluginMessage(v(393) ? "a:b" : "x", new byte[300_000]))));
        list.add(new Scenario("frame-truncado", "netty.malformed", false, bot ->
                bot.send(bot.playId(PacketType.Play.Client.CLICK_WINDOW), new byte[]{0})));

        list.add(new Scenario("nbt-profundo", "item.nbt-depth", true, bot ->
                bot.send(bot.playId(PacketType.Play.Client.CREATIVE_INVENTORY_ACTION), creativeSlot(creativeItem(Wire.nbtDeep(300, named))))));
        list.add(new Scenario("nbt-muitos-nos", "item.nbt-nodes", true, bot ->
                bot.send(bot.playId(PacketType.Play.Client.CREATIVE_INVENTORY_ACTION), creativeSlot(creativeItem(Wire.nbtManyNodes(40, 1000, named))))));
        list.add(new Scenario("nbt-string", "item.string-length", true, bot ->
                bot.send(bot.playId(PacketType.Play.Client.CREATIVE_INVENTORY_ACTION), creativeSlot(creativeItem(Wire.nbtLongString(40_000, named))))));
        list.add(new Scenario("nbt-array", "item.array-length", true, bot ->
                bot.send(bot.playId(PacketType.Play.Client.CREATIVE_INVENTORY_ACTION), creativeSlot(creativeItem(Wire.nbtBigArray(20_000, named))))));
        list.add(new Scenario("nbt-lista", "item.list-length", true, bot ->
                bot.send(bot.playId(PacketType.Play.Client.CREATIVE_INVENTORY_ACTION), creativeSlot(creativeItem(Wire.nbtBigList(5_000, named))))));
        list.add(new Scenario("itens-aninhados", "item.item-depth", true, bot ->
                bot.send(bot.playId(PacketType.Play.Client.CREATIVE_INVENTORY_ACTION), creativeSlot(nestedContainers(40)))));
        list.add(new Scenario("criativo-slot-invalido", "creative.invalid-slot", true, bot ->
                bot.send(bot.playId(PacketType.Play.Client.CREATIVE_INVENTORY_ACTION), Wire.create().s(5000).raw(emptyItem()).toBytes())));
        list.add(new Scenario("criativo-normal", "", true, bot -> {
            byte[] nbt = Wire.create().nbtRoot(named).nbtByte("a", 1).nbtEnd().toBytes();
            bot.send(bot.playId(PacketType.Play.Client.CREATIVE_INVENTORY_ACTION), creativeSlot(creativeItem(nbt)));
            Thread.sleep(500L);
        }));
        list.add(new Scenario("sobrevivencia-criativo", "creative.not-creative", false, bot -> {
            byte[] slot = creativeSlot(emptyItem());
            for (int i = 0; i < 12; i++) {
                bot.send(bot.playId(PacketType.Play.Client.CREATIVE_INVENTORY_ACTION), slot);
                Thread.sleep(30L);
            }
        }));

        list.add(new Scenario("janela-slot-invalido", "window.invalid-slot", false, bot ->
                bot.send(bot.playId(PacketType.Play.Client.CLICK_WINDOW), click(0, 20_000, 0, 0, emptyItem()))));
        list.add(new Scenario("janela-modo-invalido", "window.invalid-mode", false, bot ->
                bot.send(bot.playId(PacketType.Play.Client.CLICK_WINDOW), click(0, 1, 0, 42, emptyItem()))));
        if (!v(770)) {
            list.add(new Scenario("clique-item-pesado", "", false, bot -> {
                // Passes the limits, but is heavy enough to have its data removed before the server reads it.
                byte[] nbt = Wire.create().nbtRoot(named).nbtString("s", Wire.repeat('x', 6000)).nbtEnd().toBytes();
                bot.send(bot.playId(PacketType.Play.Client.CLICK_WINDOW), click(0, 9, 0, 0, clickItem(nbt)));
                Thread.sleep(800L);
            }));
        }
        list.add(new Scenario("flood-cliques", "flood.packet-limits", false, bot ->
                bot.sendBurst(bot.playId(PacketType.Play.Client.CLICK_WINDOW), click(0, 9, 0, 0, emptyItem()), 3000)));
        list.add(new Scenario("flood-movimento", "flood.packet-limits", false, bot -> {
            if (v(777)) {
                bot.sendBurst(bot.playId(PacketType.Play.Client.PLAYER_ROTATION), Wire.create().f(0).f(0).b(1).toBytes(), 3000);
            } else {
                bot.sendBurst(bot.playId(PacketType.Play.Client.PLAYER_POSITION), position(0.5, 64, 0.5), 3000);
            }
        }));

        list.add(new Scenario("canal-invalido", "payload.invalid-channel", false, bot ->
                bot.send(bot.playId(PacketType.Play.Client.PLUGIN_MESSAGE), pluginMessage(v(393) ? "Canal Inválido" : "a\u0001", new byte[1]))));
        list.add(new Scenario("payload-grande", "payload.size", false, bot ->
                bot.send(bot.playId(PacketType.Play.Client.PLUGIN_MESSAGE), pluginMessage(v(393) ? "a:b" : "x", new byte[40_000]))));
        list.add(new Scenario("registro-canais", "payload.register", false, bot -> {
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < 1500; i++) {
                names.append("a:").append(i).append('\0');
            }
            bot.send(bot.playId(PacketType.Play.Client.PLUGIN_MESSAGE),
                    pluginMessage(v(393) ? "minecraft:register" : "REGISTER", names.toString().getBytes()));
        }));
        list.add(new Scenario("marca-grande", "payload.brand", false, bot ->
                bot.send(bot.playId(PacketType.Play.Client.PLUGIN_MESSAGE), pluginMessage(v(393) ? "minecraft:brand" : "MC|Brand", new byte[600]))));

        if (v(756)) {
            list.add(new Scenario("livro-paginas", "book.content", false, bot -> {
                Wire w = Wire.create().v(0).v(150);
                for (int i = 0; i < 150; i++) {
                    w.str("a");
                }
                bot.send(bot.playId(PacketType.Play.Client.EDIT_BOOK), w.bool(false).toBytes());
            }));
        } else if (!v(393)) {
            list.add(new Scenario("livro-paginas", "item.book-content", false, bot ->
                    bot.send(bot.playId(PacketType.Play.Client.PLUGIN_MESSAGE),
                            pluginMessage("MC|BEdit", legacyItem(Wire.nbtBook(200, 4, named))))));
        } else {
            list.add(new Scenario("livro-paginas", "item.book-content", false, bot ->
                    bot.send(bot.playId(PacketType.Play.Client.EDIT_BOOK),
                            Wire.create().raw(legacyItem(Wire.nbtBook(200, 4, named))).bool(false).v(0).toBytes())));
        }

        list.add(new Scenario("tab-gigante", "tab.length", false, bot ->
                bot.send(bot.playId(PacketType.Play.Client.TAB_COMPLETE), tab(Wire.repeat('a', 12_000)))));
        list.add(new Scenario("tab-padrao", "tab.pattern", false, bot ->
                bot.send(bot.playId(PacketType.Play.Client.TAB_COMPLETE), tab("//calc for(i=0;i<9;i++)"))));
        list.add(new Scenario("comando-bloqueado", "command.blocked", false, bot ->
                bot.send(bot.playId(commandPacket()), chatOrCommand(commandText("//calc 1+1")))));
        list.add(new Scenario("comando-gigante", "command.length", false, bot ->
                bot.send(bot.playId(commandPacket()), chatOrCommand(commandText("/" + Wire.repeat('a', 12_000))))));

        if (v(335)) {
            list.add(new Scenario("receita-invalida", "recipe.invalid-request", false, bot -> {
                Wire w = Wire.create();
                if (v(768)) {
                    w.v(0).v(-1);
                } else if (v(393)) {
                    w.b(0).str("Inválido Aqui");
                } else {
                    w.b(0).v(-1);
                }
                bot.send(bot.playId(PacketType.Play.Client.CRAFT_RECIPE_REQUEST), w.bool(false).toBytes());
            }));
        }
        return list;
    }
}
