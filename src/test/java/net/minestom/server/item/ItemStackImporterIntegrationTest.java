package net.minestom.server.item;

import net.kyori.adventure.text.Component;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.event.inventory.CreativeInventoryActionEvent;
import net.minestom.server.event.item.ItemDropEvent;
import net.minestom.server.listener.CreativeInventoryActionListener;
import net.minestom.server.listener.WindowListener;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.client.play.ClientClickWindowPacket;
import net.minestom.server.network.packet.client.play.ClientCreativeInventoryActionPacket;
import net.minestom.server.network.packet.server.play.SetCursorItemPacket;
import net.minestom.server.network.packet.server.play.SetPlayerInventorySlotPacket;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@EnvTest
class ItemStackImporterIntegrationTest {
    @Test
    void newlyCreatedCreativeItemsImportIndependentlyOfTheirOutboundView(Env env) {
        final var connection = env.createConnection();
        final var player = connection.connect(env.createFlatInstance(), Pos.ZERO);
        player.setGameMode(GameMode.CREATIVE);
        player.setItemStackView((item, _) -> item.withMaterial(Material.PAPER, item.components()));
        player.setCreativeItemImporter((item, _) -> ItemStack.of(Material.DIAMOND, 4)
                .with(DataComponents.CUSTOM_NAME, item.get(DataComponents.CUSTOM_NAME)));
        final ItemStack raw = ItemStack.of(Material.STONE).with(DataComponents.CUSTOM_NAME, Component.text("New"));
        final var buffer = NetworkBuffer.resizableBuffer(env.process().registries());
        buffer.write(ClientCreativeInventoryActionPacket.SERIALIZER, new ClientCreativeInventoryActionPacket((short) 36, raw));
        buffer.readIndex(0);
        final var packet = buffer.read(ClientCreativeInventoryActionPacket.SERIALIZER);
        assertEquals(raw, packet.item());
        player.eventNode().addListener(CreativeInventoryActionEvent.class, event -> {
            assertEquals(Material.DIAMOND, event.getClickedItem().material());
            assertEquals(4, event.getClickedItem().amount());
        });
        final var updates = connection.trackIncoming(SetPlayerInventorySlotPacket.class);

        CreativeInventoryActionListener.listener(packet, player);

        final ItemStack canonical = player.getInventory().getItemStack(0);
        assertEquals(Material.DIAMOND, canonical.material());
        assertEquals(4, canonical.amount());
        assertEquals(raw.get(DataComponents.CUSTOM_NAME), canonical.get(DataComponents.CUSTOM_NAME));
        updates.assertSingle(update -> {
            assertEquals(Material.PAPER, update.itemStack().material());
            assertEquals(canonical.components(), update.itemStack().components());
        });
    }

    @Test
    void importerVisitsNestedStacksBeforeParentsAndLeavesCanonicalCodecsAlone(Env env) {
        final var player = env.createConnection().connect(env.createFlatInstance(), Pos.ZERO);
        player.setGameMode(GameMode.CREATIVE);
        final ItemStack child = ItemStack.of(Material.STONE);
        final ItemStack raw = ItemStack.of(Material.BUNDLE).with(DataComponents.BUNDLE_CONTENTS, List.of(child));
        final var registries = env.process().registries();
        final var rawNbt = raw.toItemNBT(registries);
        final List<Material> visited = new ArrayList<>();
        player.setCreativeItemImporter((item, _) -> {
            visited.add(item.material());
            assertEquals(raw, ItemStack.fromItemNBT(rawNbt, registries));
            assertEquals(rawNbt, raw.toItemNBT(registries));
            if (item.material() == Material.BUNDLE) {
                assertEquals(Material.DIAMOND, item.get(DataComponents.BUNDLE_CONTENTS).getFirst().material());
                return item;
            }
            return item.withMaterial(Material.DIAMOND, item.components());
        });

        CreativeInventoryActionListener.listener(new ClientCreativeInventoryActionPacket((short) 36, raw), player);

        assertEquals(List.of(Material.STONE, Material.BUNDLE), visited);
        assertEquals(Material.DIAMOND, player.getInventory().getItemStack(0).get(DataComponents.BUNDLE_CONTENTS).getFirst().material());
        assertEquals(child, raw.get(DataComponents.BUNDLE_CONTENTS).getFirst());
    }

    @Test
    void normalizationToExistingItemStillRefreshesClient(Env env) {
        final var connection = env.createConnection();
        final var player = connection.connect(env.createFlatInstance(), Pos.ZERO);
        player.setGameMode(GameMode.CREATIVE);
        final ItemStack canonical = ItemStack.of(Material.DIAMOND);
        player.getInventory().setItemStack(0, canonical);
        player.setItemStackView((item, _) -> item.withMaterial(Material.PAPER, item.components()));
        player.setCreativeItemImporter((_, _) -> canonical);
        final var updates = connection.trackIncoming(SetPlayerInventorySlotPacket.class);

        CreativeInventoryActionListener.listener(new ClientCreativeInventoryActionPacket((short) 36, ItemStack.of(Material.STONE)), player);

        assertEquals(canonical, player.getInventory().getItemStack(0));
        updates.assertSingle(update -> assertEquals(Material.PAPER, update.itemStack().material()));
    }

    @Test
    void customViewRefreshesEqualCreativeInputWithDefaultImporter(Env env) {
        final var connection = env.createConnection();
        final var player = connection.connect(env.createFlatInstance(), Pos.ZERO);
        player.setGameMode(GameMode.CREATIVE);
        final ItemStack canonical = ItemStack.of(Material.DIAMOND);
        player.getInventory().setItemStack(0, canonical);
        player.setItemStackView((item, _) -> item.withMaterial(Material.PAPER, item.components()));
        final var updates = connection.trackIncoming(SetPlayerInventorySlotPacket.class);

        CreativeInventoryActionListener.listener(new ClientCreativeInventoryActionPacket((short) 36, canonical), player);

        updates.assertSingle(update -> assertEquals(Material.PAPER, update.itemStack().material()));
    }

    @Test
    void unchangedPassthroughCreativeItemsDoNotResend(Env env) {
        final var connection = env.createConnection();
        final var player = connection.connect(env.createFlatInstance(), Pos.ZERO);
        player.setGameMode(GameMode.CREATIVE);
        final ItemStack item = ItemStack.of(Material.DIAMOND);
        player.getInventory().setItemStack(0, item);
        final var updates = connection.trackIncoming(SetPlayerInventorySlotPacket.class);

        CreativeInventoryActionListener.listener(new ClientCreativeInventoryActionPacket((short) 36, item), player);

        assertTrue(updates.collect().isEmpty());
    }

    @Test
    void gameModeAndSlotBoundsAreCheckedBeforeImport(Env env) {
        final var player = env.createConnection().connect(env.createFlatInstance(), Pos.ZERO);
        final AtomicInteger calls = new AtomicInteger();
        player.setCreativeItemImporter((item, _) -> {
            calls.incrementAndGet();
            return item;
        });
        CreativeInventoryActionListener.listener(new ClientCreativeInventoryActionPacket((short) 36, ItemStack.of(Material.STONE)), player);
        player.setGameMode(GameMode.CREATIVE);
        for (final short slot : new short[]{0, -2, 46, Short.MAX_VALUE}) {
            CreativeInventoryActionListener.listener(new ClientCreativeInventoryActionPacket(slot, ItemStack.of(Material.STONE)), player);
        }
        assertEquals(0, calls.get());
    }

    @Test
    void invalidImportedAmountIsRejectedAndPreviousSlotIsRefreshed(Env env) {
        final var connection = env.createConnection();
        final var player = connection.connect(env.createFlatInstance(), Pos.ZERO);
        player.setGameMode(GameMode.CREATIVE);
        final ItemStack previous = ItemStack.of(Material.DIAMOND);
        player.getInventory().setItemStack(0, previous);
        player.setCreativeItemImporter((_, _) -> ItemStack.of(Material.DIAMOND_SWORD, 2));
        final var updates = connection.trackIncoming(SetPlayerInventorySlotPacket.class);

        CreativeInventoryActionListener.listener(new ClientCreativeInventoryActionPacket((short) 36, ItemStack.of(Material.STONE)), player);

        assertEquals(previous, player.getInventory().getItemStack(0));
        updates.assertSingle(update -> assertEquals(previous, update.itemStack()));
    }

    @Test
    void creativeEventsCanCancelImportedItems(Env env) {
        final var connection = env.createConnection();
        final var player = connection.connect(env.createFlatInstance(), Pos.ZERO);
        player.setGameMode(GameMode.CREATIVE);
        final ItemStack previous = ItemStack.of(Material.STONE);
        player.getInventory().setItemStack(0, previous);
        player.setCreativeItemImporter((_, _) -> ItemStack.of(Material.DIAMOND));
        player.eventNode().addListener(CreativeInventoryActionEvent.class, event -> {
            assertEquals(Material.DIAMOND, event.getClickedItem().material());
            event.setCancelled(true);
        });
        final var updates = connection.trackIncoming(SetPlayerInventorySlotPacket.class);

        CreativeInventoryActionListener.listener(new ClientCreativeInventoryActionPacket((short) 36, ItemStack.of(Material.PAPER)), player);

        assertEquals(previous, player.getInventory().getItemStack(0));
        updates.assertSingle(update -> assertEquals(previous, update.itemStack()));
    }

    @Test
    void creativeDropsUseImportedItems(Env env) {
        final var player = env.createConnection().connect(env.createFlatInstance(), Pos.ZERO);
        player.setGameMode(GameMode.CREATIVE);
        final ItemStack imported = ItemStack.of(Material.DIAMOND, 3);
        final AtomicInteger drops = new AtomicInteger();
        player.setCreativeItemImporter((_, _) -> imported);
        player.eventNode().addListener(ItemDropEvent.class, event -> {
            assertEquals(imported, event.getItemStack());
            drops.incrementAndGet();
            event.setCancelled(true);
        });

        CreativeInventoryActionListener.listener(new ClientCreativeInventoryActionPacket((short) -1, ItemStack.of(Material.PAPER)), player);

        assertEquals(1, drops.get());
    }

    @Test
    void ordinaryClicksUseCanonicalStacksAndProjectedCursorHashes(Env env) {
        final var connection = env.createConnection();
        final var player = connection.connect(env.createFlatInstance(), Pos.ZERO);
        final ItemStack canonical = ItemStack.of(Material.STONE, 2);
        player.getInventory().setItemStack(0, canonical);
        player.setItemStackView((item, _) -> item.withMaterial(Material.PAPER, item.components()));
        player.setCreativeItemImporter((_, _) -> {
            fail("Ordinary clicks must not import items");
            return ItemStack.AIR;
        });
        final var expected = canonical.withMaterial(Material.PAPER, canonical.components());
        final var cursorPackets = connection.trackIncoming(SetCursorItemPacket.class);

        WindowListener.clickWindowListener(new ClientClickWindowPacket(0, 0, (short) 36, (byte) 0,
                ClientClickWindowPacket.ClickType.PICKUP, Map.of(), ItemStack.Hash.of(expected, env.process().registries())), player);

        assertEquals(canonical, player.getInventory().getCursorItem());
        assertTrue(player.getInventory().getItemStack(0).isAir());
        cursorPackets.assertSingle(update -> assertEquals(expected, update.itemStack()));
    }

    @Test
    void failedImportsLeaveInventoryAndPresentationContextIntact(Env env) {
        final var player = env.createConnection().connect(env.createFlatInstance(), Pos.ZERO);
        player.setGameMode(GameMode.CREATIVE);
        final ItemStack canonical = ItemStack.of(Material.STONE);
        player.getInventory().setItemStack(0, canonical);
        player.setCreativeItemImporter((_, _) -> null);

        assertThrows(NullPointerException.class, () -> CreativeInventoryActionListener.listener(
                new ClientCreativeInventoryActionPacket((short) 36, canonical), player));

        assertEquals(canonical, player.getInventory().getItemStack(0));
        assertSame(canonical, ItemStackViewContext.outbound(canonical));
        assertEquals(canonical, ItemStack.fromItemNBT(canonical.toItemNBT(env.process().registries()), env.process().registries()));
    }
}
