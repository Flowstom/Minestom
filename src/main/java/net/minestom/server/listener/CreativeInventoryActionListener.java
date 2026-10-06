package net.minestom.server.listener;

import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.inventory.CreativeInventoryActionEvent;
import net.minestom.server.inventory.PlayerInventory;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.ItemStackImporter;
import net.minestom.server.item.ItemStackView;
import net.minestom.server.item.ItemStackViewContext;
import net.minestom.server.network.packet.client.play.ClientCreativeInventoryActionPacket;
import net.minestom.server.utils.inventory.PlayerInventoryUtils;

import java.util.Objects;

public final class CreativeInventoryActionListener {
    public static void listener(ClientCreativeInventoryActionPacket packet, Player player) {
        if (player.getGameMode() != GameMode.CREATIVE) return;
        short slot = packet.slot();
        // Check permissions and bounds before application code imports client-supplied data.
        if (slot != -1 && (slot < 1 || slot > PlayerInventoryUtils.OFFHAND_SLOT)) return;
        final ItemStack rawItem = packet.item();
        final ItemStackImporter importer = player.getCreativeItemImporter();
        final ItemStack sentItem = ItemStackViewContext.importItemStack(
                rawItem, importer, player, MinecraftServer.getRegistries());
        if (!sentItem.isAir() && sentItem.amount() > sentItem.maxStackSize()) {
            if (slot != -1) {
                final int inventorySlot = PlayerInventoryUtils.convertWindow0SlotToMinestomSlot(slot);
                player.getInventory().sendSlotRefresh(inventorySlot, player.getInventory().getItemStack(inventorySlot));
            }
            return;
        }
        if (slot == -1) {
            // Drop item
            CreativeInventoryActionEvent event = new CreativeInventoryActionEvent(player, slot, sentItem);
            EventDispatcher.call(event);
            if (event.isCancelled()) return;
            player.dropItem(event.getClickedItem());
            return;
        }
        // Set item
        slot = (short) PlayerInventoryUtils.convertWindow0SlotToMinestomSlot(slot);
        PlayerInventory inventory = player.getInventory();

        CreativeInventoryActionEvent event = new CreativeInventoryActionEvent(player, slot, sentItem);
        EventDispatcher.call(event);
        final ItemStack setItem = event.getClickedItem();
        final ItemStack previousItem = inventory.getItemStack(slot);

        if (event.isCancelled()) {
            // Event is cancelled, keep the old item
            player.getInventory().sendSlotRefresh(slot, previousItem);
            return;
        }

        if (Objects.equals(previousItem, setItem)) {
            // Inventory insertion skips equal values. A custom import or view can still require
            // correcting the client's supplied representation, even when server state is unchanged.
            if (importer != ItemStackImporter.PASSTHROUGH || player.getItemStackView() != ItemStackView.PASSTHROUGH ||
                    !Objects.equals(setItem, rawItem)) {
                inventory.sendSlotRefresh(slot, previousItem);
            }
            return;
        }

        inventory.setItemStack(slot, setItem);
    }
}
