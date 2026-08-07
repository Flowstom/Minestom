package net.minestom.server.item;

import net.kyori.adventure.text.Component;
import net.minestom.server.ServerFlag;
import net.minestom.server.adventure.MinestomAdventure;
import net.minestom.server.component.DataComponent;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.Player;
import net.minestom.server.network.NetworkBuffer;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.IdentityHashMap;
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
                return mapInbound(delegate.read(buffer));
            }
        };
    }

    public static ItemStack outbound(ItemStack itemStack) {
        return mapOutbound(itemStack, Function.identity());
    }

    public static ItemStack inbound(ItemStack itemStack) {
        return mapInbound(itemStack);
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
                mapped = Objects.requireNonNull(context.view.unview(itemStack, context.player),
                        "ItemStackView#unview returned null");
            } finally {
                context.callbackDepth--;
            }
            validate(itemStack, mapped, "unview");
            context.touched = true;
            return mapped;
        } finally {
            context.active.remove(itemStack);
        }
    }

    public static boolean hasMappedItemStack() {
        return CONTEXT.isBound() && CONTEXT.get().touched;
    }

    public static boolean isPresentationComponent(DataComponent<?> component) {
        // Avoid an eager collection here: this class is reached while DataComponents initializes.
        return component == DataComponents.CUSTOM_NAME ||
                component == DataComponents.ITEM_NAME ||
                component == DataComponents.ITEM_MODEL ||
                component == DataComponents.LORE ||
                component == DataComponents.RARITY ||
                component == DataComponents.CUSTOM_MODEL_DATA ||
                component == DataComponents.TOOLTIP_DISPLAY ||
                component == DataComponents.ENCHANTMENT_GLINT_OVERRIDE ||
                component == DataComponents.DYED_COLOR ||
                component == DataComponents.MAP_COLOR ||
                component == DataComponents.TOOLTIP_STYLE;
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
        return with(Direction.OUTBOUND, view, player, componentOperator, operation);
    }

    public static void withOutbound(ItemStackView view, Player player,
                                    @Nullable UnaryOperator<Component> componentOperator, Runnable operation) {
        with(Direction.OUTBOUND, view, player, componentOperator, operation);
    }

    public static <T, X extends Throwable> T withInbound(ItemStackView view, Player player,
                                                         ScopedValue.CallableOp<? extends T, X> operation) throws X {
        return with(Direction.INBOUND, view, player, null, operation);
    }

    public static void withInbound(ItemStackView view, Player player, Runnable operation) {
        with(Direction.INBOUND, view, player, null, operation);
    }

    private static <T, X extends Throwable> T with(Direction direction, ItemStackView view, Player player,
                                                   @Nullable UnaryOperator<Component> componentOperator,
                                                   ScopedValue.CallableOp<? extends T, X> operation) throws X {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(operation, "operation");
        if (view == ItemStackView.PASSTHROUGH && componentOperator == null) return operation.call();
        return ScopedValue.where(CONTEXT, new Context(direction, view, player, componentOperator)).call(operation);
    }

    private static void with(Direction direction, ItemStackView view, Player player,
                             @Nullable UnaryOperator<Component> componentOperator, Runnable operation) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(operation, "operation");
        if (view == ItemStackView.PASSTHROUGH && componentOperator == null) {
            operation.run();
            return;
        }
        ScopedValue.where(CONTEXT, new Context(direction, view, player, componentOperator)).run(operation);
    }

    private static void validate(ItemStack input, ItemStack output, String method) {
        if (input.isAir() != output.isAir() || input.material() != output.material() || input.amount() != output.amount()) {
            throw new IllegalArgumentException("ItemStackView#" + method +
                    " must preserve item material, amount, and emptiness");
        }
        validateComponents(input, output, input.componentPatch().entrySet(), method);
        validateComponents(input, output, output.componentPatch().entrySet(), method);
    }

    private static void validateComponents(ItemStack input, ItemStack output,
                                           Iterable<DataComponent.Value> entries, String method) {
        for (DataComponent.Value entry : entries) {
            final DataComponent<?> component = entry.component();
            if (!isPresentationComponent(component) &&
                    !Objects.equals(input.get(component), output.get(component))) {
                throw new IllegalArgumentException("ItemStackView#" + method +
                        " cannot change interaction component " + component.key());
            }
        }
    }

    private enum Direction {
        OUTBOUND,
        INBOUND
    }

    private static final class Context {
        private final Direction direction;
        private final ItemStackView view;
        private final Player player;
        private final @Nullable UnaryOperator<Component> componentOperator;
        private final Set<ItemStack> active = Collections.newSetFromMap(new IdentityHashMap<>());
        private int callbackDepth;
        private boolean touched;

        private Context(Direction direction, ItemStackView view, Player player,
                        @Nullable UnaryOperator<Component> componentOperator) {
            this.direction = direction;
            this.view = view;
            this.player = player;
            this.componentOperator = componentOperator;
        }
    }
}
