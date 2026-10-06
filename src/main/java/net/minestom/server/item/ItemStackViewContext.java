package net.minestom.server.item;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.DataComponentValue;
import net.kyori.adventure.text.event.DataComponentValueConverterRegistry;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.gson.GsonDataComponentValue;
import net.minestom.server.ServerFlag;
import net.minestom.server.adventure.MinestomAdventure;
import net.minestom.server.adventure.MinestomDataComponentValue;
import net.minestom.server.adventure.serializer.nbt.NbtDataComponentValue;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.codec.TranscoderProxy;
import net.minestom.server.component.DataComponent;
import net.minestom.server.component.DataComponentMap;
import net.minestom.server.entity.Player;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.registry.Registries;
import net.minestom.server.registry.RegistryTranscoder;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Provides the item stack view active during packet serialization.
 */
@ApiStatus.Internal
public final class ItemStackViewContext {
    private static final ScopedValue<Context> CONTEXT = ScopedValue.newInstance();
    private static final ScopedValue<Boolean> SUPPRESS_ITEM_TRANSLATION = ScopedValue.newInstance();

    private ItemStackViewContext() {
    }

    public static NetworkBuffer.Type<ItemStack> networkType(NetworkBuffer.Type<ItemStack> delegate) {
        return new NetworkBuffer.Type<>() {
            @Override
            public void write(NetworkBuffer buffer, ItemStack value) {
                mapOutbound(value, mapped -> {
                    delegate.write(buffer, mapped);
                    return null;
                });
            }

            @Override
            public ItemStack read(NetworkBuffer buffer) {
                return delegate.read(buffer);
            }
        };
    }

    public static ItemStack outbound(ItemStack itemStack) {
        return mapOutbound(itemStack, Function.identity());
    }

    public static ItemStack inbound(ItemStack itemStack) {
        return mapInbound(itemStack);
    }

    /** Explicitly opts a codec into client presentation without changing registry ownership. */
    public static <D> Transcoder<D> clientTranscoder(Transcoder<D> coder, @Nullable Registries registries) {
        return registries != null ? new RegistryTranscoder<>(coder, registries, false, true) : new ClientTranscoder<>(coder);
    }

    public static boolean isClientTranscoder(Transcoder<?> coder) {
        return coder instanceof ClientTranscoder<?> ||
                (coder instanceof RegistryTranscoder<?> registryCoder && registryCoder.itemStackView());
    }

    /** Imports a decoded creative item, recursively visiting typed stacks before their parents. */
    public static ItemStack importItemStack(ItemStack itemStack, ItemStackImporter importer,
                                            Player player, Registries registries) {
        Objects.requireNonNull(itemStack, "itemStack");
        Objects.requireNonNull(importer, "importer");
        Objects.requireNonNull(player, "player");
        if (importer == ItemStackImporter.PASSTHROUGH || itemStack.isAir()) return itemStack;

        // Reuse the component codecs' traversal, including registry-backed components, without
        // allocating wire bytes or imposing the outbound view's count/material invariants.
        final var canonicalCoder = new RegistryTranscoder<>(Transcoder.JAVA, registries);
        final Object encoded = ItemStack.CODEC.encode(canonicalCoder, itemStack).orElseThrow();
        final var clientCoder = clientTranscoder(Transcoder.JAVA, registries);
        return ScopedValue.where(CONTEXT,
                new Context(Direction.INBOUND, ItemStackView.PASSTHROUGH, importer, player, null))
                .call(() -> ItemStack.CODEC.decode(clientCoder, encoded).orElseThrow());
    }

    /**
     * Applies the active view to a modern Adventure {@code show_item} payload.
     * <p>
     * Adventure deliberately keeps these payloads independent from Minestom's
     * {@link ItemStack} type. Decode the components we know how to represent,
     * preserve everything else as raw NBT, and merge the viewed patch back into
     * the original payload so an implementation-specific component is never
     * silently discarded.
     */
    public static HoverEvent.ShowItem mapOutbound(HoverEvent.ShowItem showItem, @Nullable Registries registries) {
        Objects.requireNonNull(showItem, "showItem");
        if (!CONTEXT.isBound() || CONTEXT.get().direction != Direction.OUTBOUND)
            return showItem;

        final Context context = CONTEXT.get();
        if (context.callbackDepth != 0 || !context.activeShowItems.add(showItem))
            return showItem;
        try {
            final DecodedShowItem decoded = decodeShowItem(showItem, registries);
            if (decoded == null) return showItem;

            final ItemStack viewed = mapOutbound(decoded.itemStack, Function.identity());
            return encodeShowItem(showItem, viewed, decoded.passthrough, registries);
        } finally {
            context.activeShowItems.remove(showItem);
        }
    }

    /** Converts Adventure's value abstraction to the NBT representation used on the wire. */
    public static Map<Key, NbtDataComponentValue> showItemComponentsAsNbt(HoverEvent.ShowItem showItem) {
        return showItemComponentsAsNbt(showItem, null);
    }

    /** Converts Adventure's value abstraction using the packet's registry context when available. */
    public static Map<Key, NbtDataComponentValue> showItemComponentsAsNbt(
            HoverEvent.ShowItem showItem, @Nullable Registries registries) {
        return showItemComponentsAsNbt(showItem, registries, false);
    }

    public static Map<Key, NbtDataComponentValue> showItemComponentsAsNbt(
            HoverEvent.ShowItem showItem, @Nullable Registries registries, boolean forClient) {
        final Map<Key, DataComponentValue> values = showItem.dataComponents();
        if (values.isEmpty()) return Map.of();

        final Map<Key, NbtDataComponentValue> result = new HashMap<>(values.size());
        for (final Map.Entry<Key, DataComponentValue> entry : values.entrySet()) {
            final DataComponentValue value = entry.getValue();
            if (value instanceof DataComponentValue.Removed) {
                result.put(entry.getKey(), NbtDataComponentValue.removed());
            } else if (value instanceof DataComponentValue.TagSerializable tagSerializable) {
                result.put(entry.getKey(), NbtDataComponentValue.nbtDataComponentValue(
                        MinestomAdventure.unwrapNbt(tagSerializable.asBinaryTag())));
            } else if (value instanceof GsonDataComponentValue gsonValue) {
                result.put(entry.getKey(), NbtDataComponentValue.nbtDataComponentValue(
                        Transcoder.JSON.convertTo(Transcoder.NBT, gsonValue.element()).orElseThrow()));
            } else if (value instanceof MinestomDataComponentValue minestomValue) {
                final DataComponent<?> component = DataComponent.fromKey(entry.getKey());
                if (component != null && component.codec() != null) {
                    try {
                        result.put(entry.getKey(), NbtDataComponentValue.nbtDataComponentValue(
                                encodeComponent(component, minestomValue.value(), componentTranscoder(registries, forClient))));
                        continue;
                    } catch (RuntimeException ignored) {
                        // Fall through to Adventure's registered conversion so
                        // extension providers still get a chance to handle it.
                    }
                }
                result.put(entry.getKey(), DataComponentValueConverterRegistry.convert(
                        NbtDataComponentValue.class, entry.getKey(), value));
            } else {
                result.put(entry.getKey(), DataComponentValueConverterRegistry.convert(
                        NbtDataComponentValue.class, entry.getKey(), value));
            }
        }
        return Map.copyOf(result);
    }

    public static <T> T mapOutbound(ItemStack itemStack, Function<ItemStack, T> operation) {
        Objects.requireNonNull(itemStack, "itemStack");
        Objects.requireNonNull(operation, "operation");
        // Empty stacks have no serializable presentation and cannot be changed safely.
        if (itemStack.isAir()) return operation.apply(itemStack);
        if (!CONTEXT.isBound() || CONTEXT.get().direction != Direction.OUTBOUND)
            return operation.apply(itemStack);

        final Context context = CONTEXT.get();
        if (context.callbackDepth != 0) return operation.apply(itemStack);
        // Keep the input active for the whole nested serialization, not just the callback.
        // This makes codec/hash calls made by the callback safe and stops self-nesting views.
        if (!context.active.add(itemStack)) return operation.apply(itemStack);
        try {
            final ItemStack mappedByView;
            context.callbackDepth++;
            try {
                mappedByView = Objects.requireNonNull(context.view.view(itemStack, context.player),
                        "ItemStackView#view returned null");
            } finally {
                context.callbackDepth--;
            }
            ItemStack mapped = mappedByView;
            validate(itemStack, mapped, "view");
            if (context.componentOperator != null)
                mapped = ItemStack.copyWithOperator(mapped, context.componentOperator);
            context.touched = true;
            return operation.apply(mapped);
        } finally {
            context.active.remove(itemStack);
        }
    }

    private static ItemStack mapInbound(ItemStack itemStack) {
        Objects.requireNonNull(itemStack, "itemStack");
        if (itemStack.isAir()) return itemStack;
        if (!CONTEXT.isBound() || CONTEXT.get().direction != Direction.INBOUND) return itemStack;

        final Context context = CONTEXT.get();
        if (context.callbackDepth != 0) return itemStack;
        if (!context.active.add(itemStack)) return itemStack;
        try {
            final ItemStack mapped;
            context.callbackDepth++;
            try {
                mapped = Objects.requireNonNull(context.importer.importItem(itemStack, context.player),
                        "ItemStackImporter#importItem returned null");
            } finally {
                context.callbackDepth--;
            }
            context.touched = true;
            return mapped;
        } finally {
            context.active.remove(itemStack);
        }
    }

    public static boolean hasMappedItemStack() {
        return CONTEXT.isBound() && CONTEXT.get().touched;
    }

    public static boolean isItemTranslationSuppressed() {
        return SUPPRESS_ITEM_TRANSLATION.isBound();
    }

    public static @Nullable UnaryOperator<Component> componentOperator(Player player) {
        if (!ServerFlag.AUTOMATIC_COMPONENT_TRANSLATION) return null;
        final var locale = Objects.requireNonNullElseGet(
                player.getLocale(), MinestomAdventure::getDefaultLocale);
        return component -> MinestomAdventure.COMPONENT_TRANSLATOR.apply(component, locale);
    }

    public static <T, X extends Throwable> T suppressItemTranslation(
            ScopedValue.CallableOp<? extends T, X> operation) throws X {
        Objects.requireNonNull(operation, "operation");
        return ScopedValue.where(SUPPRESS_ITEM_TRANSLATION, true).call(operation);
    }

    public static <T, X extends Throwable> T withOutbound(ItemStackView view, Player player,
                                                          @Nullable UnaryOperator<Component> componentOperator,
                                                          ScopedValue.CallableOp<? extends T, X> operation) throws X {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(operation, "operation");
        if (view == ItemStackView.PASSTHROUGH && componentOperator == null) return operation.call();
        return ScopedValue.where(CONTEXT, new Context(Direction.OUTBOUND, view, ItemStackImporter.PASSTHROUGH, player, componentOperator)).call(operation);
    }

    public static void withOutbound(ItemStackView view, Player player,
                                    @Nullable UnaryOperator<Component> componentOperator, Runnable operation) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(operation, "operation");
        if (view == ItemStackView.PASSTHROUGH && componentOperator == null) {
            operation.run();
            return;
        }
        ScopedValue.where(CONTEXT, new Context(Direction.OUTBOUND, view, ItemStackImporter.PASSTHROUGH, player, componentOperator)).run(operation);
    }

    private static void validate(ItemStack input, ItemStack output, String method) {
        if (input.isAir() != output.isAir() || input.amount() != output.amount()) {
            throw new IllegalArgumentException("ItemStackView#" + method +
                    " must preserve item amount and emptiness");
        }
        // Components are deliberately unrestricted: no fixed list can cover every visual
        // component or future component. Views which need interaction restrictions can
        // enforce those policy-specific invariants in their own callback.
    }

    private static @Nullable DecodedShowItem decodeShowItem(HoverEvent.ShowItem showItem,
                                                              @Nullable Registries registries) {
        final Material material = Material.fromKey(showItem.item());
        // An obsolete/foreign item id cannot be represented by an ItemStack. It
        // is safer to retain the original hover event than to emit a partial one.
        if (material == null || showItem.count() <= 0) return null;

        final Map<Key, NbtDataComponentValue> rawComponents;
        try {
            rawComponents = showItemComponentsAsNbt(showItem, registries);
        } catch (RuntimeException ignored) {
            // A third-party Adventure value may have no NBT conversion. The
            // existing serializer will report that unsupported value as usual.
            return null;
        }

        final DataComponentMap.PatchBuilder patch = DataComponentMap.patchBuilder();
        final Map<Key, NbtDataComponentValue> passthrough = new HashMap<>();
        final Transcoder<BinaryTag> coder = componentTranscoder(registries, false);
        for (final Map.Entry<Key, NbtDataComponentValue> entry : rawComponents.entrySet()) {
            final DataComponent<?> component = DataComponent.fromKey(entry.getKey());
            final NbtDataComponentValue value = entry.getValue();
            if (component == null || component.codec() == null) {
                passthrough.put(entry.getKey(), value);
                continue;
            }

            try {
                if (value.value() == null) {
                    patch.remove(component);
                } else {
                    setDecodedComponent(patch, component, value.value(), coder);
                }
            } catch (RuntimeException ignored) {
                // Keep malformed or version-specific values byte-for-byte
                // representable instead of dropping them during a view.
                passthrough.put(entry.getKey(), value);
            }
        }
        return new DecodedShowItem(ItemStack.of(material, showItem.count(), patch.build()), passthrough);
    }

    @SuppressWarnings("unchecked")
    private static void setDecodedComponent(DataComponentMap.PatchBuilder patch,
                                            DataComponent<?> component,
                                            BinaryTag value,
                                            Transcoder<BinaryTag> coder) {
        final DataComponent<Object> typedComponent = (DataComponent<Object>) component;
        final Object decoded = typedComponent.decode(coder, value).orElseThrow();
        patch.set(typedComponent, decoded);
    }

    private static HoverEvent.ShowItem encodeShowItem(HoverEvent.ShowItem original, ItemStack viewed,
                                                       Map<Key, NbtDataComponentValue> passthrough,
                                                       @Nullable Registries registries) {
        final Map<Key, NbtDataComponentValue> dataComponents = new HashMap<>(passthrough);
        final Transcoder<BinaryTag> coder = componentTranscoder(registries, true);
        for (final DataComponent.Value entry : viewed.componentPatch().entrySet()) {
            final DataComponent<?> component = entry.component();
            final Object value = entry.value();
            if (value == null) {
                dataComponents.put(component.key(), NbtDataComponentValue.removed());
                continue;
            }

            final BinaryTag encoded;
            try {
                encoded = encodeComponent(component, value, coder);
            } catch (RuntimeException ignored) {
                // A component without an NBT codec cannot be represented in a
                // ShowItem payload. Preserve the complete original event.
                return original;
            }
            dataComponents.put(component.key(), NbtDataComponentValue.nbtDataComponentValue(encoded));
        }
        return HoverEvent.ShowItem.showItem(viewed.material().key(), viewed.amount(), dataComponents);
    }

    @SuppressWarnings("unchecked")
    private static BinaryTag encodeComponent(DataComponent<?> component, Object value,
                                             Transcoder<BinaryTag> coder) {
        final DataComponent<Object> typedComponent = (DataComponent<Object>) component;
        final Codec<Object> codec = typedComponent.codec();
        if (codec == null) throw new IllegalArgumentException("Component has no NBT codec: " + component.key());
        return typedComponent.encode(coder, value).orElseThrow();
    }

    private static Transcoder<BinaryTag> componentTranscoder(@Nullable Registries registries, boolean forClient) {
        // A context-free NetworkBuffer may not carry registries. Basic item
        // components can still be transformed; registry-backed ones are kept
        // as raw values by the decode/encode failure paths above.
        return forClient ? clientTranscoder(Transcoder.NBT, registries)
                : registries == null ? Transcoder.NBT : new RegistryTranscoder<>(Transcoder.NBT, registries);
    }

    private enum Direction {
        OUTBOUND,
        INBOUND
    }

    private static final class Context {
        private final Direction direction;
        private final ItemStackView view;
        private final ItemStackImporter importer;
        private final Player player;
        private final @Nullable UnaryOperator<Component> componentOperator;
        private final Set<ItemStack> active = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<HoverEvent.ShowItem> activeShowItems = Collections.newSetFromMap(new IdentityHashMap<>());
        private int callbackDepth;
        private boolean touched;

        private Context(Direction direction, ItemStackView view, ItemStackImporter importer, Player player,
                        @Nullable UnaryOperator<Component> componentOperator) {
            this.direction = direction;
            this.view = view;
            this.importer = importer;
            this.player = player;
            this.componentOperator = componentOperator;
        }
    }

    private record DecodedShowItem(ItemStack itemStack, Map<Key, NbtDataComponentValue> passthrough) {
    }

    private record ClientTranscoder<D>(Transcoder<D> delegate) implements TranscoderProxy<D> {
    }
}
