package net.starlight.stardance.item;

import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.starlight.stardance.Stardance;
import net.minecraft.registry.Registry;

public class ModItems {
    public static final Item GRID_CREATOR = new GridCreatorItem(new Item.Settings().maxCount(1).maxDamage(100));
    public static final Item IMPULSE_TOOL = new ImpulseToolItem(new Item.Settings().maxCount(1).maxDamage(200));

    public static final ItemGroup STARDANCE_UTILS = new ItemGroup.Builder(ItemGroup.Row.TOP, 1)
            .displayName(Text.literal("Stardance Utils"))
            .icon(() -> new ItemStack(GRID_CREATOR))
            .entries((displayContext, entries) -> {
                entries.add(GRID_CREATOR);
                entries.add(IMPULSE_TOOL);
            })
            .build();

    public static void registerItems() {
        Registry.register(Registries.ITEM, new Identifier(Stardance.MOD_ID, "grid_creator"), GRID_CREATOR);
        Registry.register(Registries.ITEM, new Identifier(Stardance.MOD_ID, "impulse_tool"), IMPULSE_TOOL);
        Registry.register(Registries.ITEM_GROUP, new Identifier(Stardance.MOD_ID, "stardance_utils"), STARDANCE_UTILS);
    }
}