package net.minestom.server.item;

import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Metadata;
import net.minestom.server.entity.Player;
import net.minestom.server.item.component.CustomModelData;
import net.minestom.server.listener.CreativeInventoryActionListener;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.client.play.ClientCreativeInventoryActionPacket;
import net.minestom.server.network.packet.server.CachedPacket;
import net.minestom.server.network.packet.server.play.DeclareRecipesPacket;
import net.minestom.server.network.packet.server.play.DestroyEntitiesPacket;
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket;
import net.minestom.server.network.packet.server.play.ParticlePacket;
import net.minestom.server.network.packet.server.play.SetPlayerInventorySlotPacket;
import net.minestom.server.network.packet.server.play.SetSlotPacket;
import net.minestom.server.network.packet.server.play.TradeListPacket;
import net.minestom.server.particle.Particle;
import net.minestom.server.recipe.Ingredient;
import net.minestom.server.recipe.display.SlotDisplay;
import net.minestom.server.utils.PacketSendingUtils;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@EnvTest
class ItemStackViewIntegrationTest {
    private static final CustomModelData VIEW_MODEL = new CustomModelData(
            List.of(1f), List.of(), List.of(), List.of());

    @Test
    void viewsArePerPlayerAndBypassGroupedPacketCache(Env env) {
        final var instance = env.createFlatInstance();
        final var firstConnection = env.createConnection();
        final var secondConnection = env.createConnection();
        final var first = firstConnection.connect(instance, new Pos(0, 42, 0));
        final var second = secondConnection.connect(instance, new Pos(0, 42, 0));
        final CustomModelData firstModel = viewModel(2f);
        final CustomModelData secondModel = viewModel(3f);
        first.setItemStackView((itemStack, _) -> itemStack.with(DataComponents.CUSTOM_MODEL_DATA, firstModel));
        second.setItemStackView((itemStack, _) -> itemStack.with(DataComponents.CUSTOM_MODEL_DATA, secondModel));

        final var firstPackets = firstConnection.trackIncoming(SetSlotPacket.class);
        final var secondPackets = secondConnection.trackIncoming(SetSlotPacket.class);
        final var itemStack = ItemStack.of(Material.DIAMOND);
        PacketSendingUtils.sendGroupedPacket(List.of(first, second),
                new SetSlotPacket(1, 0, (short) 0, itemStack));

        firstPackets.assertSingle(packet ->
                assertEquals(firstModel, packet.itemStack().get(DataComponents.CUSTOM_MODEL_DATA)));
        secondPackets.assertSingle(packet ->
                assertEquals(secondModel, packet.itemStack().get(DataComponents.CUSTOM_MODEL_DATA)));

        final var inventoryPackets = firstConnection.trackIncoming(SetPlayerInventorySlotPacket.class);
        first.getInventory().setItemStack(0, itemStack);
        inventoryPackets.assertSingle(packet ->
                assertEquals(firstModel, packet.itemStack().get(DataComponents.CUSTOM_MODEL_DATA)));
        assertEquals(itemStack, first.getInventory().getItemStack(0));
    }

    @Test
    void nestedItemStacksAreViewedWithoutChangingServerState(Env env) {
        final var connection = env.createConnection();
        final var player = connection.connect(env.createFlatInstance(), new Pos(0, 42, 0));
        player.setItemStackView((itemStack, _) -> withViewModel(itemStack));
        final ItemStack inner = ItemStack.of(Material.DIAMOND);
        final ItemStack outer = ItemStack.of(Material.BUNDLE)
                .with(DataComponents.BUNDLE_CONTENTS, List.of(inner));
        final var packets = connection.trackIncoming(SetSlotPacket.class);

        player.sendPacket(new SetSlotPacket(1, 0, (short) 0, outer));

        packets.assertSingle(packet -> {
            assertEquals(VIEW_MODEL, packet.itemStack().get(DataComponents.CUSTOM_MODEL_DATA));
            final List<ItemStack> contents = packet.itemStack().get(DataComponents.BUNDLE_CONTENTS);
            assertNotNull(contents);
            assertEquals(VIEW_MODEL, contents.getFirst().get(DataComponents.CUSTOM_MODEL_DATA));
        });
        assertNull(outer.get(DataComponents.CUSTOM_MODEL_DATA));
        assertNull(inner.get(DataComponents.CUSTOM_MODEL_DATA));
    }

    @Test
    void templateItemsInRecipesAndParticlesAreViewed(Env env) {
        final var connection = env.createConnection();
        final var player = connection.connect(env.createFlatInstance(), new Pos(0, 42, 0));
        player.setItemStackView((itemStack, _) -> withViewModel(itemStack));
        final ItemStack itemStack = ItemStack.of(Material.DIAMOND);

        final var recipes = connection.trackIncoming(DeclareRecipesPacket.class);
        player.sendPacket(new DeclareRecipesPacket(Map.of(), List.of(
                new DeclareRecipesPacket.StonecutterRecipe(
                        new Ingredient(Material.STONE), new SlotDisplay.ItemStack(itemStack)))));
        recipes.assertSingle(packet -> {
            final SlotDisplay display = packet.stonecutterRecipes().getFirst().optionDisplay();
            final ItemStack displayed = ((SlotDisplay.ItemStack) display).itemStack();
            assertEquals(VIEW_MODEL, displayed.get(DataComponents.CUSTOM_MODEL_DATA));
        });

        final var particles = connection.trackIncoming(ParticlePacket.class);
        player.sendPacket(new ParticlePacket(Particle.ITEM.withItem(itemStack), Pos.ZERO, Pos.ZERO, 0, 1));
        particles.assertSingle(packet -> {
            final ItemStack displayed = ((Particle.Item) packet.particle()).item();
            assertEquals(VIEW_MODEL, displayed.get(DataComponents.CUSTOM_MODEL_DATA));
        });
    }

    @Test
    void itemStackMetadataIsViewed(Env env) {
        final var connection = env.createConnection();
        final var player = connection.connect(env.createFlatInstance(), new Pos(0, 42, 0));
        player.setItemStackView((itemStack, _) -> withViewModel(itemStack));
        final var packets = connection.trackIncoming(EntityMetaDataPacket.class);

        player.sendPacket(new EntityMetaDataPacket(10, Map.of(8, Metadata.ItemStack(ItemStack.of(Material.STONE)))));

        packets.assertSingle(packet -> {
            final var itemStack = (ItemStack) packet.entries().get(8).value();
            assertEquals(VIEW_MODEL, itemStack.get(DataComponents.CUSTOM_MODEL_DATA));
        });
    }

    @Test
    void creativeItemsAndNestedContentsAreRestored(Env env) {
        final var connection = env.createConnection();
        final var player = connection.connect(env.createFlatInstance(), new Pos(0, 42, 0));
        player.setGameMode(GameMode.CREATIVE);
        player.setItemStackView(reversibleView());

        final ItemStack inner = ItemStack.of(Material.DIAMOND);
        final ItemStack canonical = ItemStack.of(Material.BUNDLE)
                .with(DataComponents.BUNDLE_CONTENTS, List.of(inner));
        final ItemStack viewed = withViewModel(canonical)
                .with(DataComponents.BUNDLE_CONTENTS, List.of(withViewModel(inner)));
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(MinecraftServer.getRegistries());
        buffer.write(ClientCreativeInventoryActionPacket.SERIALIZER,
                new ClientCreativeInventoryActionPacket((short) 36, viewed));
        buffer.readIndex(0);
        final ClientCreativeInventoryActionPacket packet = ItemStackViewContext.withInbound(
                player.getItemStackView(), player,
                () -> buffer.read(ClientCreativeInventoryActionPacket.SERIALIZER));

        CreativeInventoryActionListener.listener(packet, player);

        assertEquals(canonical, player.getInventory().getItemStack(0));
    }

    @Test
    void hashesUseTheSameRecursiveViewAsPackets(Env env) {
        final var connection = env.createConnection();
        final Player player = connection.connect(env.createFlatInstance(), new Pos(0, 42, 0));
        player.setItemStackView((itemStack, _) -> withViewModel(itemStack));
        final ItemStack inner = ItemStack.of(Material.DIAMOND);
        final ItemStack canonical = ItemStack.of(Material.BUNDLE)
                .with(DataComponents.BUNDLE_CONTENTS, List.of(inner));
        final ItemStack viewed = withViewModel(canonical)
                .with(DataComponents.BUNDLE_CONTENTS, List.of(withViewModel(inner)));

        final ItemStack.Hash contextualHash = ItemStackViewContext.withOutbound(player.getItemStackView(), player, null,
                () -> ItemStack.Hash.of(canonical, MinecraftServer.getRegistries()));

        assertEquals(ItemStack.Hash.of(viewed, MinecraftServer.getRegistries()), contextualHash);
    }

    @Test
    void serializationContextDoesNotLeakAfterFailure(Env env) {
        final var connection = env.createConnection();
        final Player player = connection.connect(env.createFlatInstance(), new Pos(0, 42, 0));
        final ItemStack itemStack = ItemStack.of(Material.DIAMOND);
        final ItemStackView view = (value, _) -> withViewModel(value);

        assertThrows(IllegalStateException.class, () ->
                ItemStackViewContext.withOutbound(view, player, null, () -> {
                    assertEquals(VIEW_MODEL,
                            ItemStackViewContext.outbound(itemStack).get(DataComponents.CUSTOM_MODEL_DATA));
                    throw new IllegalStateException();
                }));
        assertSame(itemStack, ItemStackViewContext.outbound(itemStack));
    }

    @Test
    void templateNetworkTypePreservesComponentRemovals(Env env) {
        final ItemStack withoutDefault = ItemStack.of(Material.STONE).without(DataComponents.MAX_STACK_SIZE);
        assertNull(withoutDefault.get(DataComponents.MAX_STACK_SIZE));

        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(env.process().registries());
        buffer.write(ItemStackTemplate.NETWORK_TYPE, withoutDefault);
        buffer.readIndex(0);

        final ItemStack decoded = buffer.read(ItemStackTemplate.NETWORK_TYPE);
        assertNull(decoded.get(DataComponents.MAX_STACK_SIZE));
        assertEquals(withoutDefault.componentPatch(), decoded.componentPatch());
    }

    @Test
    void rejectsPredictionRelevantChanges(Env env) {
        final var connection = env.createConnection();
        final Player player = connection.connect(env.createFlatInstance(), new Pos(0, 42, 0));
        final ItemStack itemStack = ItemStack.of(Material.STONE);
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(env.process().registries());

        player.setItemStackView((value, _) -> value.withAmount(2));
        assertThrows(IllegalArgumentException.class, () -> ItemStackViewContext.withOutbound(
                player.getItemStackView(), player, null,
                () -> buffer.write(ItemStack.NETWORK_TYPE, itemStack)));

        player.setItemStackView((value, _) -> value.with(DataComponents.MAX_STACK_SIZE, 2));
        assertThrows(IllegalArgumentException.class, () -> ItemStackViewContext.withOutbound(
                player.getItemStackView(), player, null,
                () -> buffer.write(ItemStack.NETWORK_TYPE, itemStack)));
    }

    @Test
    void callbackCanEncodeItsInputWithoutReentering(Env env) {
        final var connection = env.createConnection();
        final Player player = connection.connect(env.createFlatInstance(), new Pos(0, 42, 0));
        final AtomicInteger calls = new AtomicInteger();
        player.setItemStackView((value, _) -> {
            calls.incrementAndGet();
            value.toItemNBT(env.process().registries());
            return withViewModel(value);
        });

        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(env.process().registries());
        ItemStackViewContext.withOutbound(player.getItemStackView(), player, null,
                () -> buffer.write(ItemStack.NETWORK_TYPE, ItemStack.of(Material.DIAMOND)));

        assertEquals(1, calls.get());
    }

    @Test
    void viewRunsBeforeItemComponentTranslation(Env env) {
        final var connection = env.createConnection();
        final Player player = connection.connect(env.createFlatInstance(), new Pos(0, 42, 0));
        final Component canonicalName = Component.translatable("test.canonical");
        final Component viewedName = Component.translatable("test.viewed");
        final ItemStack itemStack = ItemStack.of(Material.DIAMOND)
                .with(DataComponents.CUSTOM_NAME, canonicalName);
        player.setItemStackView((value, _) -> {
            assertEquals(canonicalName, value.get(DataComponents.CUSTOM_NAME));
            return value.with(DataComponents.CUSTOM_NAME, viewedName);
        });

        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(env.process().registries());
        ItemStackViewContext.withOutbound(player.getItemStackView(), player, _ -> Component.text("translated"),
                () -> buffer.write(ItemStack.NETWORK_TYPE, itemStack));
        buffer.readIndex(0);

        assertEquals(Component.text("translated"),
                buffer.read(ItemStack.NETWORK_TYPE).get(DataComponents.CUSTOM_NAME));
    }

    @Test
    void tradeInputsAndResultsAreViewed(Env env) {
        final var connection = env.createConnection();
        final Player player = connection.connect(env.createFlatInstance(), new Pos(0, 42, 0));
        player.setItemStackView((value, _) -> withViewModel(value));
        final ItemStack input = ItemStack.of(Material.EMERALD);
        final ItemStack result = ItemStack.of(Material.DIAMOND);
        final TradeListPacket packet = new TradeListPacket(1, List.of(new TradeListPacket.Trade(
                input, result, null, false, 0, 12, 1, 0, 0, 0)), 1, 0, true, false);
        final var packets = connection.trackIncoming(TradeListPacket.class);

        player.sendPacket(new CachedPacket(packet));

        packets.assertSingle(viewed -> {
            final TradeListPacket.Trade trade = viewed.trades().getFirst();
            assertEquals(VIEW_MODEL, trade.inputItem1().components().get(DataComponents.CUSTOM_MODEL_DATA));
            assertEquals(VIEW_MODEL, trade.result().get(DataComponents.CUSTOM_MODEL_DATA));
        });
    }

    @Test
    void explicitlyContextFreeCacheDoesNotInvokeView(Env env) {
        final var connection = env.createConnection();
        final Player player = connection.connect(env.createFlatInstance(), new Pos(0, 42, 0));
        final AtomicInteger calls = new AtomicInteger();
        player.setItemStackView((value, _) -> {
            calls.incrementAndGet();
            return withViewModel(value);
        });

        player.sendPacket(new CachedPacket(new DestroyEntitiesPacket(10), false));

        assertEquals(0, calls.get());
    }

    private static ItemStack withViewModel(ItemStack itemStack) {
        return itemStack.with(DataComponents.CUSTOM_MODEL_DATA, VIEW_MODEL);
    }

    private static CustomModelData viewModel(float value) {
        return new CustomModelData(List.of(value), List.of(), List.of(), List.of());
    }

    private static ItemStackView reversibleView() {
        return new ItemStackView() {
            @Override
            public ItemStack view(ItemStack itemStack, Player player) {
                return withViewModel(itemStack);
            }

            @Override
            public ItemStack unview(ItemStack itemStack, Player player) {
                return itemStack.without(DataComponents.CUSTOM_MODEL_DATA);
            }
        };
    }
}
