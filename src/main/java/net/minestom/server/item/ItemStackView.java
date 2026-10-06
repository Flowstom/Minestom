package net.minestom.server.item;

import net.minestom.server.entity.Player;
import org.jetbrains.annotations.ApiStatus;

/**
 * Controls how item stacks are represented to a player during network serialization.
 * <p>
 * Views change only the serialized representation of an item. Inventories, entities,
 * and other server state continue to contain the original item stack. Amount and
 * emptiness must be preserved; this is checked when a view is applied. Material may
 * change. Use {@link ItemStack#withMaterial(Material, net.minestom.server.component.DataComponentMap)}
 * to express desired effective components against the destination material's defaults.
 * Any component may be changed because components can affect rendering, tooltips,
 * client prediction, or interaction. A view that changes interaction-sensitive
 * components is responsible for preserving the corresponding client/server
 * invariants itself.
 * The view is applied recursively to typed item stacks stored inside components and
 * other packet data.
 * Outbound traversal visits an outer stack before its nested stacks. Client-supplied
 * creative items are handled separately by {@link ItemStackImporter}.
 * <p>
 * Implementations must be thread-safe, fast, and deterministic for as long as the
 * client representation is expected to remain valid. They are invoked from network
 * and tick threads and may be invoked more than once for a packet. Returning
 * {@code null} is not permitted. A callback may encode or hash its input; item
 * transformations invoked from inside the callback are ignored. Empty stacks are
 * not passed to the callback because they have no wire-level presentation.
 * <p>
 * A view should not collapse two distinct stacks which may appear together into the
 * same client representation. Doing so can change client stacking prediction and
 * requires reconciliation of affected inventory slots.
 * <p>
 * Changing the view does not resend existing client state. The relevant inventory,
 * entity metadata, equipment, recipe, advancement, or other state must be updated
 * explicitly.
 * <p>
 * The transformation happens while typed item codecs are running. Material-only
 * recipe displays, ingredients, already encoded NBT, and raw buffered packets do
 * not contain a typed item stack and are therefore not transformed. Modern
 * Adventure {@code show_item} hover payloads are adapted at component
 * serialization; legacy opaque hover NBT is not interpreted by the modern
 * component serializer, and unencodable component values are not partially
 * rewritten.
 * Custom {@link net.minestom.server.network.player.PlayerConnection}
 * implementations are responsible for applying the item context around their own
 * packet encoding; the built-in socket connection does this automatically. Codec-based
 * packet fields must opt into client presentation with a client transcoder. Canonical
 * codecs and item NBT ignore ambient presentation scopes.
 */
@FunctionalInterface
@ApiStatus.Experimental
public interface ItemStackView {
    ItemStackView PASSTHROUGH = (itemStack, _) -> itemStack;

    /**
     * Returns the representation of {@code itemStack} which should be sent to
     * {@code player}.
     *
     * @param itemStack the item stack in server state
     * @param player the receiving player
     * @return the item stack to send to the player
     */
    ItemStack view(ItemStack itemStack, Player player);
}
