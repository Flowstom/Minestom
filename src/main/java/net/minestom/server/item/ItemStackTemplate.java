package net.minestom.server.item;

import net.minestom.server.codec.Codec;
import net.minestom.server.component.DataComponent;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.NetworkBufferTemplate;

public interface ItemStackTemplate {
    NetworkBuffer.Type<ItemStack> NETWORK_TYPE = ItemStackViewContext.networkType(NetworkBufferTemplate.template(
            Material.NETWORK_TYPE, ItemStack::material,
            NetworkBuffer.VAR_INT, ItemStack::amount,
            // Vanilla sends a patch here; resolved components would lose explicit removals.
            DataComponent.PATCH_NETWORK_TYPE, ItemStack::componentPatch,
            ItemStack::of));
    Codec<ItemStack> CODEC = ItemStack.CODEC
            .orElse(Material.CODEC.transform(ItemStack::of, ItemStack::material));
}
