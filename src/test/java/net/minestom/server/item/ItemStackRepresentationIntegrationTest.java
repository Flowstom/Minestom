package net.minestom.server.item;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.minestom.server.adventure.MinestomDataComponentValue;
import net.minestom.server.adventure.serializer.nbt.NbtDataComponentValue;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.dialog.Dialog;
import net.minestom.server.dialog.DialogAfterAction;
import net.minestom.server.dialog.DialogBody;
import net.minestom.server.dialog.DialogMetadata;
import net.minestom.server.item.component.CustomModelData;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.server.CachedPacket;
import net.minestom.server.network.packet.server.common.ShowDialogPacket;
import net.minestom.server.network.packet.server.play.SetSlotPacket;
import net.minestom.server.network.packet.server.play.TradeListPacket;
import net.minestom.server.registry.RegistryTranscoder;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@EnvTest
class ItemStackRepresentationIntegrationTest {
    @Test
    void materialSubstitutionProjectsNestedStacksAndRemovesDestinationDefaults(Env env) {
        final var player = env.createConnection().connect(env.createFlatInstance(), Pos.ZERO);
        player.setItemStackView((item, _) -> item.withMaterial(Material.DIAMOND_SWORD, item.components()));
        final ItemStack child = ItemStack.of(Material.STONE, 3).without(DataComponents.REPAIR_COST);
        final ItemStack source = ItemStack.of(Material.BUNDLE)
                .with(DataComponents.BUNDLE_CONTENTS, List.of(child));

        for (final var type : List.of(ItemStack.NETWORK_TYPE, ItemStackTemplate.NETWORK_TYPE,
                NetworkBuffer.TypedNBT(ItemStack.CODEC))) {
            final var buffer = NetworkBuffer.resizableBuffer(env.process().registries());
            ItemStackViewContext.withOutbound(player.getItemStackView(), player, null, () -> buffer.write(type, source));
            buffer.readIndex(0);
            final ItemStack viewed = buffer.read(type);

            assertEquals(Material.DIAMOND_SWORD, viewed.material());
            assertNull(viewed.get(DataComponents.MAX_DAMAGE));
            final ItemStack viewedChild = viewed.get(DataComponents.BUNDLE_CONTENTS).getFirst();
            assertEquals(Material.DIAMOND_SWORD, viewedChild.material());
            assertEquals(child.amount(), viewedChild.amount());
            assertEquals(child.components(), viewedChild.components());
            assertNull(viewedChild.get(DataComponents.REPAIR_COST));
        }
        assertEquals(Material.BUNDLE, source.material());
        assertEquals(child, source.get(DataComponents.BUNDLE_CONTENTS).getFirst());
    }

    @Test
    void canonicalItemAndHoverCodecsIgnoreAmbientPresentation(Env env) {
        final var player = env.createConnection().connect(env.createFlatInstance(), Pos.ZERO);
        final AtomicInteger calls = new AtomicInteger();
        player.setItemStackView((item, _) -> {
            calls.incrementAndGet();
            return item.withMaterial(Material.DIAMOND_SWORD, item.components());
        });
        final ItemStack child = ItemStack.of(Material.STONE);
        final Component hover = Component.text("hover").hoverEvent(child.asHoverEvent());
        final ItemStack source = ItemStack.of(Material.BUNDLE)
                .with(DataComponents.BUNDLE_CONTENTS, List.of(child))
                .with(DataComponents.CUSTOM_NAME, hover);
        final var registries = env.process().registries();
        final var expectedNbt = source.toItemNBT(registries);
        final var coder = new RegistryTranscoder<>(Transcoder.NBT, registries);
        final BinaryTag expectedHover = Codec.COMPONENT.encode(coder, hover).orElseThrow();

        ItemStackViewContext.withOutbound(player.getItemStackView(), player, _ -> Component.text("translated"), () -> {
            assertEquals(expectedNbt, source.toItemNBT(registries));
            assertEquals(source, ItemStack.fromItemNBT(expectedNbt, registries));
            assertEquals(expectedHover, Codec.COMPONENT.encode(coder, hover).orElseThrow());
            assertCanonicalRoundTrip(Transcoder.NBT, source, env);
            assertCanonicalRoundTrip(Transcoder.JSON, source, env);
            assertCanonicalRoundTrip(Transcoder.JAVA, source, env);
        });
        assertEquals(0, calls.get());
    }

    @Test
    void explicitlyClientCodecProjectsWithoutChangingCanonicalEncoding(Env env) {
        final var player = env.createConnection().connect(env.createFlatInstance(), Pos.ZERO);
        player.setItemStackView((item, _) -> item.withMaterial(Material.DIAMOND_SWORD, item.components()));
        final ItemStack source = ItemStack.of(Material.STONE);
        final var clientCoder = ItemStackViewContext.clientTranscoder(Transcoder.NBT, env.process().registries());

        final BinaryTag viewed = ItemStackViewContext.withOutbound(player.getItemStackView(), player, null,
                () -> ItemStack.CODEC.encode(clientCoder, source).orElseThrow());

        final ItemStack decoded = ItemStack.CODEC.decode(clientCoder, viewed).orElseThrow();
        assertEquals(Material.DIAMOND_SWORD, decoded.material());
        assertEquals(source.components(), decoded.components());
        assertEquals("minecraft:stone", source.toItemNBT(env.process().registries()).getString("id"));
    }

    @Test
    void dialogPacketsProjectItemsWithoutFilteringDialogSerializers(Env env) {
        final var player = env.createConnection().connect(env.createFlatInstance(), Pos.ZERO);
        player.setItemStackView((item, _) -> item.withMaterial(Material.DIAMOND_SWORD, item.components()));
        final ItemStack source = ItemStack.of(Material.STONE);
        final Dialog dialog = new Dialog.Notice(new DialogMetadata(Component.text("Items"), null, true, false,
                DialogAfterAction.CLOSE, List.of(new DialogBody.Item(source, null, true, true, 16, 16)), List.of()),
                Dialog.Notice.DEFAULT_ACTION);
        final var buffer = NetworkBuffer.resizableBuffer(env.process().registries());

        ItemStackViewContext.withOutbound(player.getItemStackView(), player, null,
                () -> buffer.write(ShowDialogPacket.INLINE_SERIALIZER, new ShowDialogPacket(dialog)));
        buffer.readIndex(0);
        final var viewed = (Dialog.Notice) buffer.read(ShowDialogPacket.INLINE_SERIALIZER).dialog().asValue();
        final var viewedItem = ((DialogBody.Item) viewed.metadata().body().getFirst()).itemStack();

        assertEquals(Material.DIAMOND_SWORD, viewedItem.material());
        assertEquals(source.components(), viewedItem.components());
        final var canonicalCoder = new RegistryTranscoder<>(Transcoder.NBT, env.process().registries());
        final var canonical = Dialog.REGISTRY_CODEC.encode(canonicalCoder, dialog).orElseThrow();
        assertEquals(dialog, Dialog.REGISTRY_CODEC.decode(canonicalCoder, canonical).orElseThrow());
    }

    @Test
    void hoverIdentifiersDefaultsAndNestedStacksFollowMaterialSubstitution(Env env) {
        final var player = env.createConnection().connect(env.createFlatInstance(), Pos.ZERO);
        player.setItemStackView((item, _) -> item.withMaterial(Material.DIAMOND_SWORD, item.components()));
        final ItemStack source = ItemStack.of(Material.BUNDLE, 2)
                .with(DataComponents.BUNDLE_CONTENTS, List.of(ItemStack.of(Material.STONE)));
        final Component hover = Component.text("hover").hoverEvent(source.asHoverEvent());

        for (final var type : List.of(NetworkBuffer.COMPONENT, NetworkBuffer.JSON_COMPONENT)) {
            final var buffer = NetworkBuffer.resizableBuffer(env.process().registries());
            ItemStackViewContext.withOutbound(player.getItemStackView(), player, null, () -> buffer.write(type, hover));
            buffer.readIndex(0);
            final var viewed = (HoverEvent.ShowItem) buffer.read(type).hoverEvent().value();

            assertEquals(Material.DIAMOND_SWORD.key(), viewed.item());
            assertEquals(source.amount(), viewed.count());
            final var raw = viewed.dataComponentsAs(NbtDataComponentValue.class);
            assertTrue(raw.containsKey(DataComponents.MAX_DAMAGE.key()));
            assertNull(raw.get(DataComponents.MAX_DAMAGE.key()).value());
            final var components = viewed.dataComponentsAs(MinestomDataComponentValue.class);
            final var nested = (List<?>) components.get(DataComponents.BUNDLE_CONTENTS.key()).value();
            assertEquals(Material.DIAMOND_SWORD, ((ItemStack) nested.getFirst()).material());
        }
    }

    @Test
    void contextFreeHoverEncodingStillUsesClientPresentation(Env env) {
        final var player = env.createConnection().connect(env.createFlatInstance(), Pos.ZERO);
        player.setItemStackView((item, _) -> item.withMaterial(Material.DIAMOND_SWORD, item.components()));
        final Component hover = Component.text("hover").hoverEvent(ItemStack.of(Material.STONE).asHoverEvent());
        for (final var type : List.of(NetworkBuffer.COMPONENT, NetworkBuffer.JSON_COMPONENT)) {
            final var buffer = NetworkBuffer.resizableBuffer();
            ItemStackViewContext.withOutbound(player.getItemStackView(), player, null, () -> buffer.write(type, hover));
            buffer.readIndex(0);
            assertEquals(Material.DIAMOND_SWORD.key(), ((HoverEvent.ShowItem) buffer.read(type).hoverEvent().value()).item());
        }
    }

    @Test
    void substitutedHashesMatchClientStacksIncludingNestedRemovals(Env env) {
        final var player = env.createConnection().connect(env.createFlatInstance(), Pos.ZERO);
        player.setItemStackView((item, _) -> item.withMaterial(Material.DIAMOND_SWORD, item.components()));
        final ItemStack child = ItemStack.of(Material.STONE).without(DataComponents.REPAIR_COST);
        final ItemStack source = ItemStack.of(Material.BUNDLE).with(DataComponents.BUNDLE_CONTENTS, List.of(child));
        final ItemStack expected = source.withMaterial(Material.DIAMOND_SWORD, source.components())
                .with(DataComponents.BUNDLE_CONTENTS, List.of(child.withMaterial(Material.DIAMOND_SWORD, child.components())));

        final var hash = ItemStackViewContext.withOutbound(player.getItemStackView(), player, null,
                () -> ItemStack.Hash.of(source, env.process().registries()));

        assertEquals(ItemStack.Hash.of(expected, env.process().registries()), hash);
        assertNotEquals(ItemStack.Hash.of(source, env.process().registries()), hash);
    }

    @Test
    void sharedPacketsRemainIndependentForMaterialAndComponentViews(Env env) {
        final var instance = env.createFlatInstance();
        final var vanillaConnection = env.createConnection();
        final var substitutedConnection = env.createConnection();
        final var componentConnection = env.createConnection();
        final var vanilla = vanillaConnection.connect(instance, Pos.ZERO);
        final var substituted = substitutedConnection.connect(instance, Pos.ZERO);
        final var component = componentConnection.connect(instance, Pos.ZERO);
        substituted.setItemStackView((item, _) -> item.withMaterial(Material.DIAMOND_SWORD, item.components()));
        final var model = new CustomModelData(List.of(1f), List.of(), List.of(), List.of());
        component.setItemStackView((item, _) -> item.with(DataComponents.CUSTOM_MODEL_DATA, model));
        final ItemStack source = ItemStack.of(Material.STONE);
        final CachedPacket packet = new CachedPacket(new SetSlotPacket(1, 0, (short) 0, source));
        final var vanillaPackets = vanillaConnection.trackIncoming(SetSlotPacket.class);
        final var substitutedPackets = substitutedConnection.trackIncoming(SetSlotPacket.class);
        final var componentPackets = componentConnection.trackIncoming(SetSlotPacket.class);

        vanilla.sendPacket(packet);
        substituted.sendPacket(packet);
        component.sendPacket(packet);

        vanillaPackets.assertSingle(value -> assertEquals(source, value.itemStack()));
        substitutedPackets.assertSingle(value -> {
            assertEquals(Material.DIAMOND_SWORD, value.itemStack().material());
            assertEquals(source.components(), value.itemStack().components());
        });
        componentPackets.assertSingle(value -> {
            assertEquals(source.material(), value.itemStack().material());
            assertEquals(model, value.itemStack().get(DataComponents.CUSTOM_MODEL_DATA));
        });
    }

    @Test
    void tradeInputsAndResultsUseTheViewedMaterial(Env env) {
        final var player = env.createConnection().connect(env.createFlatInstance(), Pos.ZERO);
        player.setItemStackView((item, _) -> item.withMaterial(Material.PAPER, item.components()));
        final ItemStack source = ItemStack.of(Material.EMERALD, 3);
        final var trade = new TradeListPacket.Trade(source, source, source, false, 0, 10, 1, 0, 0, 0);
        final var packet = new TradeListPacket(1, List.of(trade), 1, 0, true, true);
        final var buffer = NetworkBuffer.resizableBuffer(env.process().registries());

        ItemStackViewContext.withOutbound(player.getItemStackView(), player, null,
                () -> buffer.write(TradeListPacket.SERIALIZER, packet));
        buffer.readIndex(0);
        final var viewed = buffer.read(TradeListPacket.SERIALIZER).trades().getFirst();

        assertEquals(Material.PAPER, viewed.inputItem1().material());
        assertEquals(source.amount(), viewed.inputItem1().amount());
        assertEquals(Material.PAPER, viewed.inputItem2().material());
        assertEquals(Material.PAPER, viewed.result().material());
        assertEquals(Material.EMERALD, trade.inputItem1().material());
        assertEquals(source, trade.result());
    }

    @Test
    void viewsStillPreserveAmountsAndEmptiness(Env env) {
        final var player = env.createConnection().connect(env.createFlatInstance(), Pos.ZERO);
        final ItemStack source = ItemStack.of(Material.STONE);
        for (final ItemStack invalid : List.of(ItemStack.of(Material.DIAMOND, 2), ItemStack.AIR)) {
            assertThrows(IllegalArgumentException.class, () ->
                    ItemStackViewContext.withOutbound((_, _) -> invalid, player, null,
                            () -> NetworkBuffer.makeArray(ItemStack.NETWORK_TYPE, source)));
        }
        final AtomicInteger calls = new AtomicInteger();
        ItemStackViewContext.withOutbound((item, _) -> {
            calls.incrementAndGet();
            return item;
        }, player, null, () -> NetworkBuffer.makeArray(ItemStack.NETWORK_TYPE, ItemStack.AIR));
        assertEquals(0, calls.get());
    }

    private static <D> void assertCanonicalRoundTrip(Transcoder<D> transcoder, ItemStack source, Env env) {
        final var coder = new RegistryTranscoder<>(transcoder, env.process().registries());
        assertEquals(source, ItemStack.CODEC.decode(coder, ItemStack.CODEC.encode(coder, source).orElseThrow()).orElseThrow());
    }
}
