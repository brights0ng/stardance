package net.starlight.stardance.interaction;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.CraftingInventory;
import net.minecraft.inventory.Inventories;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.RecipeType;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.utils.SLogger;

import java.util.Optional;

/**
 * Crafting inventory for grid crafting tables.
 */
public class GridCraftingInventory extends CraftingInventory {
    private final LocalGrid grid;
    private final BlockPos gridPos;
    private final DefaultedList<ItemStack> craftingGrid;
    private final ScreenHandler screenHandler;

    public GridCraftingInventory(ScreenHandler screenHandler, LocalGrid grid, BlockPos gridPos) {
        super(screenHandler, 3, 3); // 3x3 crafting grid
        this.screenHandler = screenHandler;
        this.grid = grid;
        this.gridPos = gridPos;
        this.craftingGrid = DefaultedList.ofSize(9, ItemStack.EMPTY);
        
        SLogger.log("GridCraftingInventory", "Created crafting inventory for grid " + grid.getGridId() + " at " + gridPos);
    }

    @Override
    public int size() {
        return 9; // 3x3 crafting grid
    }

    @Override
    public boolean isEmpty() {
        return craftingGrid.stream().allMatch(ItemStack::isEmpty);
    }

    @Override
    public ItemStack getStack(int slot) {
        return slot >= 0 && slot < craftingGrid.size() ? craftingGrid.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeStack(int slot, int amount) {
        ItemStack result = Inventories.splitStack(craftingGrid, slot, amount);
        if (!result.isEmpty()) {
            this.markDirty();
            // DON'T call onContentChanged here - it causes loops
        }
        return result;
    }

    @Override
    public ItemStack removeStack(int slot) {
        ItemStack result = Inventories.removeStack(craftingGrid, slot);
        if (!result.isEmpty()) {
            this.markDirty();
            // DON'T call onContentChanged here - it causes loops
        }
        return result;
    }

    @Override
    public void setStack(int slot, ItemStack stack) {
        if (slot >= 0 && slot < craftingGrid.size()) {
            craftingGrid.set(slot, stack);
            this.markDirty();
            screenHandler.onContentChanged(this);
        }
    }

    @Override
    public void markDirty() {
        // Called when inventory changes
        SLogger.log("GridCraftingInventory", "Crafting inventory marked dirty");
    }

    @Override
    public boolean canPlayerUse(PlayerEntity player) {
        return true; // Handled by screen handler
    }

    @Override
    public void clear() {
        craftingGrid.clear();
        this.markDirty();
    }

    /**
     * Gets the current crafting recipe based on the items in the crafting grid.
     */
    public Optional<CraftingRecipe> getCurrentRecipe(World world) {
        return world.getRecipeManager().getFirstMatch(RecipeType.CRAFTING, this, world);
    }

    /**
     * Gets the crafting result for the current arrangement.
     */
    public ItemStack getCraftingResult(World world) {
        Optional<CraftingRecipe> recipe = getCurrentRecipe(world);
        if (recipe.isPresent()) {
            return recipe.get().craft(this, world.getRegistryManager());
        }
        return ItemStack.EMPTY;
    }

    // NBT serialization for persistence
    public NbtCompound writeNbt(NbtCompound nbt) {
        Inventories.writeNbt(nbt, craftingGrid);
        return nbt;
    }

    public void readNbt(NbtCompound nbt) {
        craftingGrid.clear();
        Inventories.readNbt(nbt, craftingGrid);
    }

    @Override
    public int getWidth() {
        return 3;
    }

    @Override
    public int getHeight() {
        return 3;
    }
}