package br.com.singlehope.shanticrash.checks.recipe;

import br.com.singlehope.shanticrash.checks.Check;
import br.com.singlehope.shanticrash.checks.CheckOptions;
import br.com.singlehope.shanticrash.checks.CheckSettings;
import br.com.singlehope.shanticrash.packet.PacketContext;
import br.com.singlehope.shanticrash.packet.PacketKind;
import br.com.singlehope.shanticrash.packet.PacketReader;
import br.com.singlehope.shanticrash.packet.ServerProtocol;
import br.com.singlehope.shanticrash.utils.Identifiers;

/** Recipe book packets. Their frequency is limited in the flood category. */
public final class RecipeChecks {
    private static final int MAX_IDENTIFIER_CHARS = 32767;

    private RecipeChecks() {
    }

    /**
     * The craft request identifies a recipe differently per version: an int before 1.13, a resource
     * location until 1.21.1, and a display id from 1.21.2 on.
     */
    public static final class InvalidRequest extends Check {
        private int maxIdLength = 256;
        private int maxWindowId = 255;

        public InvalidRequest() {
            super("recipe", "invalid-request", "Pedido de receita com identificador ou janela inválidos.", PacketKind.CRAFT_RECIPE);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(1.0D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            maxIdLength = options.getInt("max-id-length", 256);
            maxWindowId = options.getInt("max-window-id", 255);
        }

        @Override
        protected void process(PacketContext context) {
            PacketReader reader = context.read();
            ServerProtocol protocol = context.protocol;
            int window = protocol.v1_21_2 ? reader.readVarInt() : reader.readUByte();
            if (window < 0 || window > maxWindowId) {
                reject(context, "janela " + window);
                return;
            }
            if (protocol.v1_13 && !protocol.v1_21_2) {
                String id = reader.readString(MAX_IDENTIFIER_CHARS);
                if (!Identifiers.isValidResourceLocation(id, maxIdLength)) {
                    reject(context, "identificador com " + id.length() + " caracteres");
                    return;
                }
            } else {
                int id = reader.readVarInt();
                if (id < 0) {
                    reject(context, "identificador " + id);
                    return;
                }
            }
            reader.readBoolean();
            reader.expectEnd();
        }
    }

    /** Recipe shown in the book, which has the same identifier rules as the craft request. */
    public static final class InvalidDisplayed extends Check {
        private int maxIdLength = 256;

        public InvalidDisplayed() {
            super("recipe", "invalid-displayed", "Receita exibida com identificador inválido.", PacketKind.RECIPE_DISPLAYED);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(1.0D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            maxIdLength = options.getInt("max-id-length", 256);
        }

        @Override
        protected void process(PacketContext context) {
            PacketReader reader = context.read();
            if (context.protocol.v1_21_2) {
                if (reader.readVarInt() < 0) {
                    reject(context, "identificador negativo");
                    return;
                }
            } else {
                String id = reader.readString(MAX_IDENTIFIER_CHARS);
                if (!Identifiers.isValidResourceLocation(id, maxIdLength)) {
                    reject(context, "identificador com " + id.length() + " caracteres");
                    return;
                }
            }
            reader.expectEnd();
        }
    }
}
