package br.com.singlehope.shanticrash.packet.decoder;

import br.com.singlehope.shanticrash.item.ItemLimits;
import br.com.singlehope.shanticrash.item.ItemReport;
import br.com.singlehope.shanticrash.item.ModernItemReader;
import br.com.singlehope.shanticrash.item.NbtScanner;
import br.com.singlehope.shanticrash.packet.MalformedPacketException;
import br.com.singlehope.shanticrash.packet.PacketContext;
import br.com.singlehope.shanticrash.packet.PacketReader;
import br.com.singlehope.shanticrash.packet.ServerProtocol;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.component.ComponentType;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.FoodProperties;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import io.netty.buffer.ByteBuf;

import java.util.Arrays;

/**
 * Walks data component items without recursion.
 *
 * <p>PacketEvents can read a whole item, but the code path recurses for each nested item and allocates
 * a map sized by the counts in the frame before knowing whether the depth is acceptable. So the
 * structure that can nest is handled here, with an explicit stack and every limit tested before
 * descending, and PacketEvents only reads the values of components that can not contain items.
 */
public final class PacketEventsItemReader implements ModernItemReader {
    private static final int FRAME_ITEMS = 0;
    private static final int FRAME_PATCH = 1;
    private static final int FRAME_LENGTH_CHECK = 2;
    private static final int FRAME_FOOD_TAIL = 3;

    /** count, id, patch. */
    private static final int FORMAT_STACK = 0;
    /** id, count, patch. Used in nested positions since 26.1. */
    private static final int FORMAT_TEMPLATE = 1;
    /** boolean, then template. */
    private static final int FORMAT_OPTIONAL_TEMPLATE = 2;

    private static final class Stack {
        int[] kind = new int[16];
        int[] first = new int[16];
        int[] second = new int[16];
        int[] third = new int[16];
        int[] depth = new int[16];
        int size;
        final NbtScanner scanner = new NbtScanner();

        void push(int frameKind, int a, int b, int c, int itemDepth) {
            if (size == kind.length) {
                int grown = size * 2;
                kind = Arrays.copyOf(kind, grown);
                first = Arrays.copyOf(first, grown);
                second = Arrays.copyOf(second, grown);
                third = Arrays.copyOf(third, grown);
                depth = Arrays.copyOf(depth, grown);
            }
            kind[size] = frameKind;
            first[size] = a;
            second[size] = b;
            third[size] = c;
            depth[size] = itemDepth;
            size++;
        }
    }

    private final ThreadLocal<Stack> stacks = ThreadLocal.withInitial(Stack::new);
    private final ComponentValueInspector inspector = new ComponentValueInspector();

    /**
     * Loads the registries during startup. They are initialized on first use and take several hundred
     * milliseconds, which would otherwise happen on a Netty thread and exceed the processing time limit
     * on the first item received.
     */
    public static void warmUp(ClientVersion version) {
        ItemTypes.getRegistry().getById(version, 1);
        ComponentTypes.getRegistry().getById(version, 0);
    }

    @Override
    public boolean read(PacketContext context, PacketReader reader, boolean lengthPrefixed,
                        ItemLimits limits, ItemReport report) {
        ByteBuf buffer = reader.buffer();
        int savedReaderIndex = buffer.readerIndex();
        int start = reader.position();
        buffer.readerIndex(start);
        try {
            Stack stack = stacks.get();
            BoundedPacketWrapper wrapper = new BoundedPacketWrapper((PacketReceiveEvent) context.event, stack.scanner, limits, report);
            boolean ok = walk(wrapper, stack, context.protocol, lengthPrefixed, limits, report, start);
            reader.position(buffer.readerIndex());
            if (!ok) {
                return false;
            }
            int size = reader.position() - start;
            if (size > report.maxItemBytes) {
                report.maxItemBytes = size;
            }
            if (size > limits.maxItemBytes) {
                return report.fail(ItemReport.Violation.ITEM_BYTES, size, limits.maxItemBytes);
            }
            return true;
        } catch (BoundedPacketWrapper.LimitExceeded e) {
            return false;
        } catch (MalformedPacketException e) {
            return report.malformed(e.getMessage());
        } catch (IndexOutOfBoundsException e) {
            return report.malformed("item truncado");
        } catch (StackOverflowError e) {
            return report.fail(ItemReport.Violation.TEXT_COMPLEXITY, Long.MAX_VALUE, limits.maxTextNodes);
        } catch (RuntimeException e) {
            return report.undecodable(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            buffer.readerIndex(savedReaderIndex);
        }
    }

    private boolean walk(BoundedPacketWrapper wrapper, Stack stack, ServerProtocol protocol, boolean lengthPrefixed,
                         ItemLimits limits, ItemReport report, int start) {
        stack.size = 0;
        stack.push(FRAME_ITEMS, 1, FORMAT_STACK, lengthPrefixed ? 1 : 0, 1);

        int headerEnd = -1;
        boolean hasData = false;
        int steps = 0;
        while (stack.size > 0) {
            if ((++steps & 0xFF) == 0 && report.deadlineNanos != 0 && System.nanoTime() > report.deadlineNanos) {
                return report.fail(ItemReport.Violation.PROCESSING_TIME, limits.maxProcessingNanos, limits.maxProcessingNanos);
            }
            int top = stack.size - 1;
            switch (stack.kind[top]) {
                case FRAME_ITEMS: {
                    if (stack.first[top] == 0) {
                        stack.size--;
                        break;
                    }
                    stack.first[top]--;
                    int format = stack.second[top];
                    boolean prefixed = stack.third[top] == 1;
                    int itemDepth = stack.depth[top];
                    int amount;
                    if (format == FORMAT_STACK) {
                        amount = wrapper.readVarInt();
                        if (amount <= 0) {
                            if (amount < 0) {
                                return report.fail(ItemReport.Violation.ITEM_AMOUNT, amount, limits.maxAmount);
                            }
                            break;
                        }
                        wrapper.readMappedEntity(ItemTypes.getRegistry());
                    } else {
                        if (format == FORMAT_OPTIONAL_TEMPLATE && !wrapper.readBoolean()) {
                            break;
                        }
                        wrapper.readMappedEntity(ItemTypes.getRegistry());
                        amount = wrapper.readVarInt();
                    }
                    if (itemDepth > report.maxItemDepth) {
                        report.maxItemDepth = itemDepth;
                    }
                    if (itemDepth > limits.maxItemDepth) {
                        return report.fail(ItemReport.Violation.ITEM_DEPTH, itemDepth, limits.maxItemDepth);
                    }
                    report.recordAmount(amount);
                    if (amount < 0 || amount > limits.maxAmount) {
                        return report.fail(ItemReport.Violation.ITEM_AMOUNT, amount, limits.maxAmount);
                    }
                    if (++report.items > limits.maxItemsPerPacket) {
                        return report.fail(ItemReport.Violation.ITEM_COUNT, report.items, limits.maxItemsPerPacket);
                    }
                    int isTopLevel = stack.size == 1 ? 1 : 0;
                    if (isTopLevel == 1) {
                        headerEnd = wrapper.readerIndex();
                    }
                    int present = wrapper.readVarInt();
                    int absent = wrapper.readVarInt();
                    if (present < 0 || absent < 0) {
                        return report.malformed("contagem de componentes negativa");
                    }
                    long totalComponents = (long) present + absent;
                    if (present > limits.maxComponents || absent > limits.maxComponents
                            || totalComponents > limits.maxComponents) {
                        return report.fail(ItemReport.Violation.COMPONENT_COUNT,
                                totalComponents, limits.maxComponents);
                    }
                    report.components += (int) totalComponents;
                    if (isTopLevel == 1) {
                        hasData = present + absent > 0;
                    }
                    stack.push(FRAME_PATCH, present, absent, prefixed ? 1 : 0, itemDepth);
                    break;
                }
                case FRAME_PATCH: {
                    if (stack.first[top] > 0) {
                        stack.first[top]--;
                        if (!readComponent(wrapper, protocol, stack, top, limits, report)) {
                            return false;
                        }
                    } else if (stack.second[top] > 0) {
                        stack.second[top]--;
                        wrapper.readMappedEntity(ComponentTypes.getRegistry());
                    } else {
                        stack.size--;
                    }
                    break;
                }
                case FRAME_LENGTH_CHECK: {
                    if (wrapper.readerIndex() != stack.first[top]) {
                        return report.malformed("componente não ocupa o tamanho declarado");
                    }
                    stack.size--;
                    break;
                }
                case FRAME_FOOD_TAIL: {
                    stack.size--;
                    wrapper.readList(FoodProperties.PossibleEffect::read);
                    break;
                }
                default:
                    return report.malformed("estado interno inválido");
            }
        }
        int end = wrapper.readerIndex();
        if (headerEnd < 0) {
            headerEnd = end;
        }
        report.addTopLevelItem(start, headerEnd, end, hasData);
        return true;
    }

    /** Reads a component, pushing frames if it can contain more items. */
    private boolean readComponent(BoundedPacketWrapper wrapper, ServerProtocol protocol, Stack stack, int top,
                                  ItemLimits limits, ItemReport report) {
        ComponentType<?> type = wrapper.readMappedEntity(ComponentTypes.getRegistry());
        boolean prefixed = stack.third[top] == 1;
        int itemDepth = stack.depth[top];
        int expectedEnd = -1;
        if (prefixed) {
            int size = wrapper.readVarInt();
            if (size < 0 || size > wrapper.readable()) {
                return report.malformed("componente maior que o pacote");
            }
            expectedEnd = wrapper.readerIndex() + size;
        }

        int format = protocol.v26_1 ? FORMAT_TEMPLATE : FORMAT_STACK;
        boolean nested = true;
        int nestedItems = 0;
        if (type == ComponentTypes.CONTAINER) {
            nestedItems = wrapper.readVarInt();
            format = protocol.v26_1 ? FORMAT_OPTIONAL_TEMPLATE : FORMAT_STACK;
        } else if (type == ComponentTypes.BUNDLE_CONTENTS || type == ComponentTypes.CHARGED_PROJECTILES) {
            nestedItems = wrapper.readVarInt();
        } else if (type == ComponentTypes.USE_REMAINDER || type == ComponentTypes.SULFUR_CUBE_CONTENT) {
            nestedItems = 1;
        } else if (type == ComponentTypes.POT_DECORATIONS && protocol.v26_3) {
            nestedItems = 4;
            format = FORMAT_OPTIONAL_TEMPLATE;
        } else if (type == ComponentTypes.FOOD && protocol.v1_21 && !protocol.v1_21_2) {
            wrapper.readVarInt();
            wrapper.readFloat();
            wrapper.readBoolean();
            wrapper.readFloat();
            if (prefixed) {
                stack.push(FRAME_LENGTH_CHECK, expectedEnd, 0, 0, itemDepth);
            }
            stack.push(FRAME_FOOD_TAIL, 0, 0, 0, itemDepth);
            if (wrapper.readBoolean()) {
                stack.push(FRAME_ITEMS, 1, FORMAT_STACK, 0, itemDepth + 1);
            }
            return true;
        } else {
            nested = false;
        }

        if (nested) {
            if (nestedItems < 0) {
                return report.malformed("lista de itens com tamanho negativo");
            }
            int componentMax = limits.maxListLength;
            if (type == ComponentTypes.CONTAINER) {
                componentMax = Math.min(componentMax, limits.maxContainerItems);
            } else if (type == ComponentTypes.CHARGED_PROJECTILES) {
                componentMax = Math.min(componentMax, limits.maxChargedProjectiles);
            } else if (type == ComponentTypes.BUNDLE_CONTENTS) {
                componentMax = Math.min(componentMax, limits.maxBundleItems);
            }
            if (nestedItems > componentMax) {
                report.note = String.valueOf(type);
                return report.fail(ItemReport.Violation.LIST_LENGTH, nestedItems, componentMax);
            }
            if (nestedItems > wrapper.readable()) {
                return report.malformed("lista de itens maior que o pacote");
            }
            if (nestedItems > report.maxListLength) {
                report.maxListLength = nestedItems;
            }
            if (prefixed) {
                stack.push(FRAME_LENGTH_CHECK, expectedEnd, 0, 0, itemDepth);
            }
            stack.push(FRAME_ITEMS, nestedItems, format, 0, itemDepth + 1);
            return true;
        }

        wrapper.itemDepth(itemDepth);
        Object value = type.read(wrapper);
        if (prefixed && wrapper.readerIndex() != expectedEnd) {
            return report.malformed("componente não ocupa o tamanho declarado");
        }
        return inspector.inspect(value, limits, report);
    }
}
