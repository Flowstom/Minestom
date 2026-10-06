package net.minestom.server.item;

import net.minestom.server.component.DataComponentMap;
import net.minestom.server.component.DataComponents;
import net.minestom.testing.RegistriesTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RegistriesTest
public class ItemStackRegistriesTest {

    @Test
    void resetRevertsToMaterialDefault() {
        ItemStack apple = ItemStack.of(Material.APPLE).without(DataComponents.FOOD);

        assertFalse(apple.has(DataComponents.FOOD));
        assertSame(apple, apple.reset(DataComponents.REPAIR_COST));
        assertTrue(apple.reset(DataComponents.FOOD).has(DataComponents.FOOD));
    }

    @Test
    void componentsReturnsResolvedView() {
        ItemStack item = ItemStack.of(Material.APPLE)
                .without(DataComponents.FOOD)
                .with(DataComponents.REPAIR_COST, 5);

        assertFalse(item.components().has(DataComponents.FOOD));
        assertEquals(5, item.components().get(DataComponents.REPAIR_COST));
        assertNull(item.componentPatch().get(DataComponents.FOOD));
    }

    @Test
    void materialReplacementCanPreserveEffectiveComponents() {
        ItemStack source = ItemStack.of(Material.STONE, 3).with(DataComponents.REPAIR_COST, 5);
        ItemStack target = source.withMaterial(Material.DIAMOND_SWORD, source.components());

        assertEquals(Material.DIAMOND_SWORD, target.material());
        assertEquals(source.amount(), target.amount());
        assertEquals(source.components(), target.components());
        assertNull(target.get(DataComponents.MAX_DAMAGE));
        assertTrue(source.withMaterial(Material.DIAMOND_SWORD).has(DataComponents.MAX_DAMAGE));
        assertEquals(source, target.withMaterial(Material.STONE, target.components()));
    }

    @Test
    void materialReplacementRetainsAirSemantics() {
        assertSame(ItemStack.AIR, ItemStack.of(Material.STONE).withMaterial(Material.AIR, DataComponentMap.EMPTY));
        assertEquals(1, ItemStack.AIR.withMaterial(Material.STONE, Material.STONE.prototype()).amount());
    }
}
