package net.minestom.server.network.packet.server.play;

import net.minestom.server.component.DataComponent;
import net.minestom.server.component.DataComponentMap;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.ItemStackViewContext;
import net.minestom.server.item.Material;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.NetworkBufferTemplate;
import net.minestom.server.network.packet.server.ServerPacket;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static net.minestom.server.network.NetworkBuffer.*;

public record TradeListPacket(int windowId, List<Trade> trades,
                              int villagerLevel, int experience,
                              boolean regularVillager, boolean canRestock) implements ServerPacket.Play {
    public static final int MAX_TRADES = Short.MAX_VALUE;

    public static final NetworkBuffer.Type<TradeListPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, TradeListPacket::windowId,
            Trade.SERIALIZER.list(MAX_TRADES), TradeListPacket::trades,
            VAR_INT, TradeListPacket::villagerLevel,
            VAR_INT, TradeListPacket::experience,
            BOOLEAN, TradeListPacket::regularVillager,
            BOOLEAN, TradeListPacket::canRestock,
            TradeListPacket::new);

    public TradeListPacket {
        trades = List.copyOf(trades);
    }

    public record Trade(
            ItemCost inputItem1,
            ItemStack result,
            @Nullable ItemCost inputItem2,
            boolean tradeDisabled,
            int tradeUsesNumber,
            int maxTradeUsesNumber,
            int exp,
            int specialPrice,
            float priceMultiplier,
            int demand
    ) {

        public static final NetworkBuffer.Type<Trade> SERIALIZER = NetworkBufferTemplate.template(
                ItemCost.NETWORK_TYPE, Trade::inputItem1,
                ItemStack.NETWORK_TYPE, Trade::result,
                ItemCost.NETWORK_TYPE.optional(), Trade::inputItem2,
                BOOLEAN, Trade::tradeDisabled,
                INT, Trade::tradeUsesNumber,
                INT, Trade::maxTradeUsesNumber,
                INT, Trade::exp,
                INT, Trade::specialPrice,
                FLOAT, Trade::priceMultiplier,
                INT, Trade::demand,
                Trade::new);

        public Trade(
                ItemStack inputItem1,
                ItemStack result,
                @Nullable ItemStack inputItem2,
                boolean tradeDisabled,
                int tradeUsesNumber,
                int maxTradeUsesNumber,
                int exp,
                int specialPrice,
                float priceMultiplier,
                int demand
        ) {
            this(
                    new ItemCost(inputItem1),
                    result,
                    inputItem2 == null ? null : new ItemCost(inputItem2),
                    tradeDisabled,
                    tradeUsesNumber,
                    maxTradeUsesNumber,
                    exp,
                    specialPrice,
                    priceMultiplier,
                    demand
            );
        }
    }

    public record ItemCost(Material material, int amount, DataComponentMap components) {
        private static final NetworkBuffer.Type<ItemCost> DELEGATE = NetworkBufferTemplate.template(
                Material.NETWORK_TYPE, ItemCost::material,
                VAR_INT, ItemCost::amount,
                DataComponent.MAP_NETWORK_TYPE, ItemCost::components,
                ItemCost::new);
        private static final NetworkBuffer.Type<ItemCost> NETWORK_TYPE = new NetworkBuffer.Type<>() {
            @Override
            public void write(NetworkBuffer buffer, ItemCost value) {
                final ItemStack stack = ItemStack.of(value.material, value.amount, value.components);
                ItemStackViewContext.mapOutbound(stack, viewed -> {
                    DELEGATE.write(buffer, withViewedComponents(value, stack, viewed));
                    return null;
                });
            }

            @Override
            public ItemCost read(NetworkBuffer buffer) {
                return DELEGATE.read(buffer);
            }
        };

        public ItemCost(ItemStack itemStack) {
            this(itemStack.material(), itemStack.amount(), itemStack.componentPatch());
        }

        private static ItemCost withViewedComponents(ItemCost cost, ItemStack original, ItemStack viewed) {
            // ItemCost is an exact predicate, not an ItemStack patch; preserve every unchanged requirement.
            final Set<DataComponent<?>> changed = new HashSet<>();
            // Check both patches so changes to inherited components and explicit removals are included.
            collectChangedComponents(original, viewed, original.componentPatch(), changed);
            collectChangedComponents(original, viewed, viewed.componentPatch(), changed);
            final DataComponentMap.Builder builder = DataComponentMap.builder();
            for (DataComponent.Value entry : cost.components.entrySet()) {
                if (entry.value() != null && !changed.contains(entry.component())) {
                    set(builder, entry.component(), entry.value());
                }
            }
            for (DataComponent<?> component : changed) {
                final Object value = viewed.get(component);
                if (value != null) set(builder, component, value);
            }
            return new ItemCost(cost.material, cost.amount, builder.build());
        }

        private static void collectChangedComponents(ItemStack original, ItemStack viewed,
                                                     DataComponentMap patch,
                                                     Set<DataComponent<?>> changed) {
            for (DataComponent.Value entry : patch.entrySet()) {
                final DataComponent<?> component = entry.component();
                if (!Objects.equals(original.get(component), viewed.get(component))) {
                    changed.add(component);
                }
            }
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private static void set(DataComponentMap.Builder builder, DataComponent component, Object value) {
            builder.set(component, value);
        }
    }
}
