package net.minestom.server.item;

import net.minestom.server.entity.Player;
import org.jetbrains.annotations.ApiStatus;

/**
 * Imports decoded client-supplied creative items into authoritative server items.
 * <p>
 * This operation is independent of {@link ItemStackView}: a creative player may create
 * an item which the server has never sent. Implementations may change material, amount,
 * and components according to application rules. They must return a non-null item.
 * Typed nested stacks are imported before their containing stack; empty stacks are skipped.
 * Opaque item data, such as items embedded in custom NBT, is not traversed.
 * <p>
 * The creative listener checks game mode and slot bounds before importing, then validates
 * the result and calls the creative inventory event. Ordinary inventory clicks do not import
 * items. Importers must be thread-safe and must validate any application-specific schema.
 */
@FunctionalInterface
@ApiStatus.Experimental
public interface ItemStackImporter {
    ItemStackImporter PASSTHROUGH = (itemStack, _) -> itemStack;

    /**
     * Returns the authoritative item to use in server state.
     *
     * @param itemStack the supplied item, with typed nested stacks already imported
     * @param player the creative player supplying the item
     * @return the item to use in server state
     */
    ItemStack importItem(ItemStack itemStack, Player player);
}
