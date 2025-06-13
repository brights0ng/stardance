package net.starlight.stardance.interaction;

import com.bulletphysics.linearmath.Transform;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.CraftingResultInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.RecipeType;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.utils.SLogger;

import javax.vecmath.Vector3f;
import java.util.Optional;

/**
 * Custom crafting screen handler for grid blocks with full crafting functionality.
 */
public class GridCraftingScreenHandler extends ScreenHandler {
    private final LocalGrid grid;
    private final BlockPos gridLocalPos;
    private final GridCraftingBlockEntity craftingBlockEntity;
    private final GridCraftingInventory craftingInventory;
    private final CraftingResultInventory resultInventory;
    private final PlayerEntity player;

    public GridCraftingScreenHandler(int syncId, PlayerInventory playerInventory, LocalGrid grid, BlockPos gridLocalPos) {
        super(ScreenHandlerType.CRAFTING, syncId);
        
        this.grid = grid;
        this.gridLocalPos = gridLocalPos;
        this.player = playerInventory.player;
        this.craftingBlockEntity = getOrCreateCraftingBlockEntity(grid, gridLocalPos);
        this.craftingInventory = craftingBlockEntity.getCraftingInventory(this);
        this.resultInventory = new CraftingResultInventory();
        
        // Add crafting result slot (top-left in GUI)
        this.addSlot(new GridCraftingResultSlot(playerInventory.player, craftingInventory, resultInventory, 0, 124, 35, this));
        
        // Add crafting grid slots (3x3)
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 3; ++col) {
                this.addSlot(new Slot(craftingInventory, col + row * 3, 30 + col * 18, 17 + row * 18));
            }
        }
        
        // Add player inventory slots
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        
        // Add player hotbar slots
        for (int col = 0; col < 9; ++col) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
        }
        
        // Update crafting result initially
        this.updateCraftingResult();
        
        SLogger.log("GridCraftingScreenHandler", "Created GridCraftingScreenHandler with syncId=" + syncId 
            + ", grid=" + grid.getGridId() 
            + ", gridLocalPos=" + gridLocalPos);
    }

    private static GridCraftingBlockEntity getOrCreateCraftingBlockEntity(LocalGrid grid, BlockPos gridLocalPos) {
        var localBlock = grid.getBlocks().get(gridLocalPos);
        if (localBlock == null) {
            throw new IllegalStateException("No block found at position " + gridLocalPos);
        }

        // Check if we already have a crafting block entity
        if (localBlock.getBlockEntity() instanceof GridCraftingBlockEntity craftingEntity) {
            return craftingEntity;
        }

        // Create new crafting block entity
        GridCraftingBlockEntity newCraftingEntity = new GridCraftingBlockEntity(grid, gridLocalPos);
        localBlock.setBlockEntity(newCraftingEntity);
        
        SLogger.log("GridCraftingScreenHandler", "Created new GridCraftingBlockEntity at " + gridLocalPos);
        return newCraftingEntity;
    }

    /**
     * Handles single crafting (normal click on result).
     */
    public void handleCrafting(int amountCrafted) {
        SLogger.log("GridCraftingScreenHandler", "handleCrafting called for amount: " + amountCrafted);
        
        if (!player.getWorld().isClient) {
            this.performCrafting(1); // Always craft just 1 for normal click
            this.updateCraftingResult();
        }
    }

    /**
     * Handles maximum crafting (shift-click on result).
     */
    public void handleMaxCrafting() {
        SLogger.log("GridCraftingScreenHandler", "handleMaxCrafting called");
        
        if (!player.getWorld().isClient) {
            // Calculate maximum possible crafts
            int maxCrafts = calculateMaxPossibleCrafts();
            SLogger.log("GridCraftingScreenHandler", "Max possible crafts: " + maxCrafts);
            
            if (maxCrafts > 0) {
                this.performCrafting(maxCrafts);
                this.updateCraftingResult();
            }
        }
    }

    /**
     * Calculates the maximum number of items that can be crafted.
     */
    private int calculateMaxPossibleCrafts() {
        World world = player.getWorld();
        Optional<CraftingRecipe> optional = world.getRecipeManager().getFirstMatch(RecipeType.CRAFTING, craftingInventory, world);
        
        if (optional.isEmpty()) {
            return 0;
        }

        CraftingRecipe recipe = optional.get();
        ItemStack result = recipe.getOutput(world.getRegistryManager());
        
        if (result.isEmpty()) {
            return 0;
        }

        // Find the minimum stack size in the crafting grid
        int minStackSize = Integer.MAX_VALUE;
        for (int i = 0; i < craftingInventory.size(); i++) {
            ItemStack stack = craftingInventory.getStack(i);
            if (!stack.isEmpty()) {
                minStackSize = Math.min(minStackSize, stack.getCount());
            }
        }

        if (minStackSize == Integer.MAX_VALUE) {
            return 0;
        }

        // Also consider how much space is available in player inventory
        int spaceAvailable = calculateInventorySpace(result);
        int maxBySpace = spaceAvailable / result.getCount();

        return Math.min(minStackSize, maxBySpace);
    }

    /**
     * Calculates available space in player inventory for the given item.
     */
    private int calculateInventorySpace(ItemStack item) {
        int space = 0;
        PlayerInventory inv = player.getInventory();
        
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) {
                space += item.getMaxCount();
            } else if (ItemStack.canCombine(stack, item)) {
                space += item.getMaxCount() - stack.getCount();
            }
        }
        
        return space;
    }

    /**
     * Performs the actual crafting logic.
     */
    private void performCrafting(int times) {
        World world = player.getWorld();
        Optional<CraftingRecipe> optional = world.getRecipeManager().getFirstMatch(RecipeType.CRAFTING, craftingInventory, world);
        
        if (optional.isEmpty()) {
            SLogger.log("GridCraftingScreenHandler", "No recipe found for crafting");
            return;
        }

        CraftingRecipe recipe = optional.get();
        SLogger.log("GridCraftingScreenHandler", "Performing crafting " + times + " times for recipe: " + recipe.getId());

        for (int craft = 0; craft < times; craft++) {
            // Check if we still have enough ingredients
            if (!canCraft(recipe)) {
                SLogger.log("GridCraftingScreenHandler", "Cannot craft anymore after " + craft + " iterations");
                break;
            }

            // Craft the item
            ItemStack result = recipe.craft(craftingInventory, world.getRegistryManager());
            
            // Give the result to the player
            if (!player.getInventory().insertStack(result.copy())) {
                // Drop if inventory is full
                player.dropItem(result, false);
            }

            // Get recipe remainders (like empty buckets)
            DefaultedList<ItemStack> remainders = recipe.getRemainder(craftingInventory);
            
            // Consume ingredients and handle remainders
            for (int i = 0; i < craftingInventory.size(); ++i) {
                ItemStack currentStack = craftingInventory.getStack(i);
                ItemStack remainder = i < remainders.size() ? remainders.get(i) : ItemStack.EMPTY;
                
                if (!currentStack.isEmpty()) {
                    // Consume one item
                    currentStack.decrement(1);
                    if (currentStack.isEmpty()) {
                        craftingInventory.setStack(i, ItemStack.EMPTY);
                    }
                    
                    SLogger.log("GridCraftingScreenHandler", "Consumed 1 item from slot " + i + 
                        ", remaining: " + currentStack.getCount());
                }
                
                if (!remainder.isEmpty()) {
                    // Handle remainder
                    ItemStack slotStack = craftingInventory.getStack(i);
                    if (slotStack.isEmpty()) {
                        craftingInventory.setStack(i, remainder.copy());
                    } else if (ItemStack.canCombine(slotStack, remainder)) {
                        slotStack.increment(remainder.getCount());
                    } else {
                        // Drop remainder if it can't fit
                        if (!player.getInventory().insertStack(remainder.copy())) {
                            player.dropItem(remainder.copy(), false);
                        }
                    }
                }
            }
            
            SLogger.log("GridCraftingScreenHandler", "Crafted iteration " + (craft + 1) + ": " + result);
        }
    }

    /**
     * Checks if we can still craft with the current ingredients.
     */
    private boolean canCraft(CraftingRecipe recipe) {
        // Simple check - make sure we have at least one of each required ingredient
        for (int i = 0; i < craftingInventory.size(); i++) {
            ItemStack stack = craftingInventory.getStack(i);
            if (!stack.isEmpty() && stack.getCount() <= 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onContentChanged(net.minecraft.inventory.Inventory inventory) {
        if (inventory == craftingInventory) {
            this.updateCraftingResult();
        }
    }

    private void updateCraftingResult() {
        if (!player.getWorld().isClient) {
            World world = player.getWorld();
            
            Optional<CraftingRecipe> optional = world.getRecipeManager().getFirstMatch(RecipeType.CRAFTING, craftingInventory, world);
            
            if (optional.isPresent()) {
                CraftingRecipe recipe = optional.get();
                ItemStack result = recipe.craft(craftingInventory, world.getRegistryManager());
                resultInventory.setStack(0, result);
                SLogger.log("GridCraftingScreenHandler", "Updated result: " + result);
            } else {
                resultInventory.setStack(0, ItemStack.EMPTY);
                SLogger.log("GridCraftingScreenHandler", "Cleared result - no valid recipe");
            }
            
            sendContentUpdates();
        }
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slot) {
        ItemStack itemStack = ItemStack.EMPTY;
        Slot clickedSlot = this.slots.get(slot);
        
        if (clickedSlot.hasStack()) {
            ItemStack clickedStack = clickedSlot.getStack();
            itemStack = clickedStack.copy();
            
            if (slot == 0) {
                // Crafting result slot - handled by GridCraftingResultSlot.onQuickTransfer
                if (!this.insertItem(clickedStack, 10, 46, true)) {
                    return ItemStack.EMPTY;
                }
                
                clickedSlot.onQuickTransfer(clickedStack, itemStack);
                
            } else if (slot >= 1 && slot <= 9) {
                // FROM crafting grid TO player inventory/hotbar
                if (!this.insertItem(clickedStack, 10, 46, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (slot >= 10 && slot < 37) {
                // FROM player inventory - try crafting grid first, then hotbar
                if (!this.insertItem(clickedStack, 1, 10, false)) {
                    if (!this.insertItem(clickedStack, 37, 46, false)) {
                        return ItemStack.EMPTY;
                    }
                }
            } else if (slot >= 37 && slot < 46) {
                // FROM hotbar - try crafting grid first, then player inventory
                if (!this.insertItem(clickedStack, 1, 10, false)) {
                    if (!this.insertItem(clickedStack, 10, 37, false)) {
                        return ItemStack.EMPTY;
                    }
                }
            }
            
            if (clickedStack.isEmpty()) {
                clickedSlot.setStack(ItemStack.EMPTY);
            } else {
                clickedSlot.markDirty();
            }
            
            if (clickedStack.getCount() == itemStack.getCount()) {
                return ItemStack.EMPTY;
            }
            
            clickedSlot.onTakeItem(player, clickedStack);
            
            // Update crafting result after inventory changes
            if (slot >= 1 && slot <= 9 || slot >= 10) {
                this.updateCraftingResult();
            }
        }
        
        return itemStack;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        if (grid.getBlock(gridLocalPos) == null) {
            return false;
        }

        try {
            Vector3f gridWorldPos = new Vector3f();
            Transform gridTransform = new Transform();
            grid.getRigidBody().getWorldTransform(gridTransform);
            gridTransform.origin.get(gridWorldPos);

            double distanceToGrid = player.squaredDistanceTo(gridWorldPos.x, gridWorldPos.y, gridWorldPos.z);
            return distanceToGrid <= 64.0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onClosed(PlayerEntity player) {
        super.onClosed(player);
        
        // Drop crafting grid items when closed (like vanilla)
        if (!player.getWorld().isClient) {
            for (int i = 0; i < 9; ++i) {
                ItemStack itemStack = craftingInventory.removeStack(i);
                if (!itemStack.isEmpty()) {
                    player.dropItem(itemStack, false);
                }
            }
        }
    }
}