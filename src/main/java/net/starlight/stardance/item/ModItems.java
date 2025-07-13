package net.starlight.stardance.item;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.starlight.stardance.Stardance;

public class ModItems {
    public static final Item GRID_CREATOR = new GridCreatorItem(new Item.Properties().stacksTo(1).durability(100));
    public static final Item IMPULSE_TOOL = new ImpulseToolItem(new Item.Properties().stacksTo(1).durability(200));

    public static final CreativeModeTab STARDANCE_UTILS = new CreativeModeTab.Builder(CreativeModeTab.Row.TOP, 1)
            .title(Component.literal("Stardance Utils"))
            .icon(() -> new ItemStack(GRID_CREATOR))
            .displayItems((displayContext, entries) -> {
                entries.accept(GRID_CREATOR);
                entries.accept(IMPULSE_TOOL);
            })
            .build();

    public static void registerItems() {
        Registry.register(BuiltInRegistries.ITEM, new ResourceLocation(Stardance.MOD_ID, "grid_creator"), GRID_CREATOR);
        Registry.register(BuiltInRegistries.ITEM, new ResourceLocation(Stardance.MOD_ID, "impulse_tool"), IMPULSE_TOOL);
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, new ResourceLocation(Stardance.MOD_ID, "stardance_utils"), STARDANCE_UTILS);
    }
}