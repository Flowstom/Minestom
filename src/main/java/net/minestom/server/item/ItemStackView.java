package net.minestom.server.item;

import net.minestom.server.entity.Player;
import org.jetbrains.annotations.ApiStatus;

/**
 * Controls how item stacks are represented to a player during network serialization.
 * <p>
 * Views change only the serialized representation of an item. Inventories, entities,
 * and other server state continue to contain the original item stack. Material,
 * amount, and emptiness must be preserved; this is checked when a view is applied.
 * Any component may be changed because components can affect rendering, tooltips,
 * client prediction, or interaction. A view that changes interaction-sensitive
 * components is responsible for preserving the corresponding client/server
 * invariants itself.
 * The view is applied recursively to typed item stacks stored inside components and
 * other packet data.
 * Outbound traversal visits an outer stack before its nested stacks; inbound
 * traversal necessarily restores nested stacks before their containing stack.
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
 * makes the inverse transformation ambiguous.
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
 * packet encoding and decoding; the built-in socket connection does this automatically.
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

    /**
     * Converts an item stack received from the client back to its server
     * representation.
     * <p>
     * This is used for item stacks supplied by the client, currently creative
     * inventory actions. Implementations which change outbound components must
     * override this method with the inverse transformation when the client can
     * send those components back. The default leaves the received representation
     * unchanged. Because this operation cannot recover information discarded by a
     * lossy view, implementations should avoid transformations that make the
     * canonical server value ambiguous.
     *
     * @param itemStack the item stack received from the player
     * @param player the sending player
     * @return the item stack to use in server state
     */
    default ItemStack unview(ItemStack itemStack, Player player) {
        return itemStack;
    }
}
