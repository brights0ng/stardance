package net.starlight.stardance.interaction;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.CraftingResultInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.starlight.stardance.utils.SLogger;

/**
 * Custom crafting result slot for grid crafting tables.
 */
public class GridCraftingResultSlot extends Slot {
    private final GridCraftingInventory craftingInventory;
    private final PlayerEntity player;
    private final GridCraftingScreenHandler screenHandler;
    private int amountCrafted;

    public GridCraftingResultSlot(PlayerEntity player, GridCraftingInventory craftingInventory, 
                                 CraftingResultInventory resultInventory, int index, int x, int y,
                                 GridCraftingScreenHandler screenHandler) {
        super(resultInventory, index, x, y);
        this.player = player;
        this.craftingInventory = craftingInventory;
        this.screenHandler = screenHandler;
    }

    @Override
    public boolean canInsert(ItemStack stack) {
        return false; // Can't insert into result slot
    }

    @Override
    public ItemStack takeStack(int amount) {
        if (this.hasStack()) {
            this.amountCrafted += Math.min(amount, this.getStack().getCount());
        }
        return super.takeStack(amount);
    }

    @Override
    protected void onCrafted(ItemStack stack, int amount) {
        this.amountCrafted += amount;
        this.onCrafted(stack);
    }

    @Override
    protected void onTake(int amount) {
        this.amountCrafted += amount;
    }

    @Override
    protected void onCrafted(ItemStack stack) {
        if (this.amountCrafted > 0) {
            stack.onCraft(this.player.getWorld(), this.player, this.amountCrafted);
        }
        
        SLogger.log("GridCraftingResultSlot", "onCrafted called - delegating to screen handler for: " + stack);
        
        // Delegate to screen handler to handle the crafting logic
        screenHandler.handleCrafting(this.amountCrafted);
        
        // Reset crafted amount
        this.amountCrafted = 0;
    }

    @Override
    public void onQuickTransfer(ItemStack newItem, ItemStack original) {
        // Handle shift-click crafting - craft as many as possible
        SLogger.log("GridCraftingResultSlot", "onQuickTransfer called - delegating max crafting to screen handler");
        screenHandler.handleMaxCrafting();
    }

    @Override
    public boolean canTakeItems(PlayerEntity playerEntity) {
        return true;
    }
}