package br.com.singlehope.shanticrash.checks.book;

import br.com.singlehope.shanticrash.checks.Check;
import br.com.singlehope.shanticrash.checks.CheckOptions;
import br.com.singlehope.shanticrash.checks.CheckSettings;
import br.com.singlehope.shanticrash.checks.item.ItemChecks;
import br.com.singlehope.shanticrash.checks.payload.PayloadChecks;
import br.com.singlehope.shanticrash.item.ItemAnalyzer;
import br.com.singlehope.shanticrash.packet.PacketContext;
import br.com.singlehope.shanticrash.packet.PacketKind;
import br.com.singlehope.shanticrash.packet.PacketReader;
import br.com.singlehope.shanticrash.packet.ServerProtocol;

/** Writing books, signs and anvil names: everything that stores text from a client in the world. */
public final class BookChecks {
    private BookChecks() {
    }

    /**
     * The edit book packet since 1.17.1: {@code slot, pages, optional title}. Lengths are measured from
     * the prefixes, so no string is decoded.
     */
    public static final class Content extends Check {
        private int maxPages = 100;
        private int maxPageBytes = 3072;
        private int maxTotalBytes = 65536;
        private int maxTitleBytes = 384;
        private int maxSlot = 45;

        public Content() {
            super("book", "content", "Livro com páginas, título ou tamanho excessivos.", PacketKind.EDIT_BOOK);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(3.0D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            maxPages = options.getInt("max-pages", 100);
            maxPageBytes = options.getInt("max-page-bytes", 3072);
            maxTotalBytes = options.getInt("max-total-bytes", 65536);
            maxTitleBytes = options.getInt("max-title-bytes", 384);
            maxSlot = options.getInt("max-slot", 45);
        }

        @Override
        protected void process(PacketContext context) {
            if (!context.protocol.v1_17_1) {
                return;
            }
            PacketReader reader = context.read();
            int slot = reader.readVarInt();
            if (slot < 0 || slot > maxSlot) {
                reject(context, "slot " + slot);
                return;
            }
            int pages = reader.readVarInt();
            if (pages < 0 || pages > maxPages) {
                reject(context, pages + " páginas (limite " + maxPages + ")");
                return;
            }
            int total = 0;
            for (int i = 0; i < pages; i++) {
                int size = reader.skipString();
                if (size > maxPageBytes) {
                    reject(context, "página com " + size + " bytes (limite " + maxPageBytes + ")");
                    return;
                }
                total += size;
                if (total > maxTotalBytes) {
                    reject(context, "livro com " + total + " bytes (limite " + maxTotalBytes + ")");
                    return;
                }
            }
            if (reader.readBoolean()) {
                int title = reader.skipString();
                if (title > maxTitleBytes) {
                    reject(context, "título com " + title + " bytes (limite " + maxTitleBytes + ")");
                    return;
                }
            }
            reader.expectEnd();
        }
    }

    /**
     * Books before 1.17.1 travel as a whole item, in the edit book packet or, before 1.13, in the
     * {@code MC|BEdit} and {@code MC|BSign} channels. The item analysis does the structural work.
     */
    public static final class LegacyItem extends Check {
        private final ItemAnalyzer analyzer;
        private final ItemChecks.Limits limits;
        private int maxBytes = 65536;

        public LegacyItem(ItemAnalyzer analyzer, ItemChecks.Limits limits) {
            super("book", "legacy-item", "Livro antigo enviado como item com conteúdo abusivo.",
                    PacketKind.EDIT_BOOK, PacketKind.PLUGIN_MESSAGE);
            this.analyzer = analyzer;
            this.limits = limits;
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(3.0D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            maxBytes = options.getInt("max-bytes", 65536);
        }

        @Override
        protected void process(PacketContext context) {
            ServerProtocol protocol = context.protocol;
            if (protocol.v1_17_1) {
                return;
            }
            PacketReader reader = context.read();
            if (context.kind == PacketKind.PLUGIN_MESSAGE) {
                if (protocol.v1_13) {
                    return;
                }
                String channel = PayloadChecks.channelOf(context, reader);
                if (!PayloadChecks.isLegacyBookChannel(channel)) {
                    return;
                }
            }
            int size = reader.remaining();
            if (size > maxBytes) {
                reject(context, "livro com " + size + " bytes (limite " + maxBytes + ")");
                return;
            }
            limits.report(context, analyzer.analyzeEmbedded(context));
        }
    }

    /**
     * Sign text. The layout moved the "front" flag twice: it goes before the lines from 1.20 to 26.2
     * and after them from 26.3 on.
     */
    public static final class SignText extends Check {
        private static final int LINES = 4;
        private int maxLineBytes = 1536;

        public SignText() {
            super("book", "sign-text", "Placa com texto maior que o limite.", PacketKind.UPDATE_SIGN);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(3.0D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            maxLineBytes = options.getInt("max-line-bytes", 1536);
        }

        @Override
        protected void process(PacketContext context) {
            PacketReader reader = context.read();
            ServerProtocol protocol = context.protocol;
            reader.skip(8);
            if (protocol.v1_20 && !protocol.v26_3) {
                reader.readBoolean();
            }
            for (int i = 0; i < LINES; i++) {
                int size = reader.skipString();
                if (size > maxLineBytes) {
                    reject(context, "linha com " + size + " bytes (limite " + maxLineBytes + ")");
                    return;
                }
            }
            if (protocol.v26_3) {
                reader.readBoolean();
            }
            reader.expectEnd();
        }
    }

    /** Names typed in an anvil, which are limited to 50 characters since forever. */
    public static final class ItemName extends Check {
        private int maxBytes = 1024;

        public ItemName() {
            super("book", "item-name", "Nome de item na bigorna maior que o limite.", PacketKind.NAME_ITEM);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(3.0D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            maxBytes = options.getInt("max-bytes", 1024);
        }

        @Override
        protected void process(PacketContext context) {
            PacketReader reader = context.read();
            int size = reader.skipString();
            if (size > maxBytes) {
                reject(context, "nome com " + size + " bytes (limite " + maxBytes + ")");
                return;
            }
            reader.expectEnd();
        }
    }
}
