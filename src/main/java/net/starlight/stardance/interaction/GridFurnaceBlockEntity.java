package net.starlight.stardance.interaction;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.SidedInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.SmeltingRecipe;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.starlight.stardance.core.GridBlockEntity;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.utils.SLogger;
import org.jetbrains.annotations.Nullable;

/**
 * Block entity for furnaces on grids. Handles all the smelting logic.
 */
public class GridFurnaceBlockEntity implements SidedInventory, GridBlockEntity {
    private static final int INPUT_SLOT = 0;
    private static final int FUEL_SLOT = 1;
    private static final int OUTPUT_SLOT = 2;
    private static final int[] TOP_SLOTS = new int[]{INPUT_SLOT};
    private static final int[] BOTTOM_SLOTS = new int[]{OUTPUT_SLOT, FUEL_SLOT};
    private static final int[] SIDE_SLOTS = new int[]{FUEL_SLOT};

    private final LocalGrid grid;
    private final BlockPos gridPos;
    private final DefaultedList<ItemStack> inventory;

    // Furnace state
    private int burnTime = 0;
    private int fuelTime = 0;
    private int cookTime = 0;
    private int cookTimeTotal = 200; // 10 seconds default

    public GridFurnaceBlockEntity(LocalGrid grid, BlockPos gridPos) {
        this.grid = grid;
        this.gridPos = gridPos;
        this.inventory = DefaultedList.ofSize(3, ItemStack.EMPTY);

        SLogger.log("GridFurnaceBlockEntity", "Created furnace block entity at " + gridPos + " on grid " + grid.getGridId());
    }

    /**
     * Ticks the furnace logic. Should be called every server tick.
     */
    @Override
    public void tick(World world, BlockPos pos, BlockState state) {
        boolean wasLit = this.isBurning();
        boolean dirty = false;

        if (this.isBurning()) {
            --this.burnTime;
        }

        if (!world.isClient) {
            ItemStack inputStack = this.inventory.get(INPUT_SLOT);
            ItemStack fuelStack = this.inventory.get(FUEL_SLOT);
            boolean hasInput = !inputStack.isEmpty();
            boolean hasFuel = !fuelStack.isEmpty();

            if (this.isBurning() || hasFuel && hasInput) {
                Recipe<?> recipe = hasInput ? world.getRecipeManager()
                        .getFirstMatch(RecipeType.SMELTING, this, world).orElse(null) : null;

                int maxStackSize = this.getMaxCountPerStack();

                if (!this.isBurning() && this.canAcceptRecipeOutput(world, recipe, this.inventory, maxStackSize)) {
                    this.burnTime = this.getFuelTime(fuelStack);
                    this.fuelTime = this.burnTime;

                    if (this.isBurning()) {
                        dirty = true;
                        if (!fuelStack.isEmpty()) {
                            // FIX: Handle null recipe remainder properly
                            Item fuelItem = fuelStack.getItem();
                            ItemStack recipeRemainder = fuelItem.getRecipeRemainder().getDefaultStack();

                            fuelStack.decrement(1);
                            if (fuelStack.isEmpty()) {
                                // Only set remainder if it's not null
                                if (recipeRemainder != null) {
                                    this.inventory.set(FUEL_SLOT, recipeRemainder.copy());
                                } else {
                                    this.inventory.set(FUEL_SLOT, ItemStack.EMPTY);
                                }
                            }
                        }
                    }
                }

                if (this.isBurning() && this.canAcceptRecipeOutput(world, recipe, this.inventory, maxStackSize)) {
                    ++this.cookTime;
                    if (this.cookTime == this.cookTimeTotal) {
                        this.cookTime = 0;
                        this.cookTimeTotal = this.getCookTime(world, this);
                        if (this.craftRecipe(world, recipe, this.inventory, maxStackSize)) {
                            dirty = true;
                        }
                    }
                } else {
                    this.cookTime = 0;
                }
            } else if (!this.isBurning() && this.cookTime > 0) {
                this.cookTime = Math.max(0, this.cookTime - 2);
            }

            if (wasLit != this.isBurning()) {
                dirty = true;
            }
        }

        if (dirty) {
            SLogger.log("GridFurnaceBlockEntity", "Furnace state changed - burnTime: " + burnTime +
                    ", cookTime: " + cookTime + ", isBurning: " + isBurning());
        }
    }

    private boolean isBurning() {
        return this.burnTime > 0;
    }

    private int getFuelTime(ItemStack fuel) {
        if (fuel.isEmpty()) {
            return 0;
        }
        return AbstractFurnaceBlockEntity.createFuelTimeMap().getOrDefault(fuel.getItem(), 0);
    }

    private int getCookTime(World world, SidedInventory inventory) {
        return world.getRecipeManager().getFirstMatch(RecipeType.SMELTING, inventory, world)
                .map(SmeltingRecipe::getCookTime).orElse(200);
    }

    private boolean canAcceptRecipeOutput(World world, @Nullable Recipe<?> recipe,
                                          DefaultedList<ItemStack> slots, int count) {
        if (!slots.get(INPUT_SLOT).isEmpty() && recipe != null) {
            ItemStack recipeOutput = recipe.getOutput(world.getRegistryManager());
            if (recipeOutput.isEmpty()) {
                return false;
            }

            ItemStack outputStack = slots.get(OUTPUT_SLOT);
            if (outputStack.isEmpty()) {
                return true;
            }

            if (!ItemStack.canCombine(outputStack, recipeOutput)) {
                return false;
            }

            int combinedCount = outputStack.getCount() + recipeOutput.getCount();
            return combinedCount <= count && combinedCount <= outputStack.getMaxCount();
        }

        return false;
    }

    private boolean craftRecipe(World world, @Nullable Recipe<?> recipe,
                                DefaultedList<ItemStack> slots, int count) {
        if (recipe != null && this.canAcceptRecipeOutput(world, recipe, slots, count)) {
            ItemStack inputStack = slots.get(INPUT_SLOT);
            ItemStack recipeOutput = recipe.getOutput(world.getRegistryManager());
            ItemStack outputStack = slots.get(OUTPUT_SLOT);

            if (outputStack.isEmpty()) {
                slots.set(OUTPUT_SLOT, recipeOutput.copy());
            } else if (ItemStack.canCombine(outputStack, recipeOutput)) {
                outputStack.increment(recipeOutput.getCount());
            }

            inputStack.decrement(1);
            return true;
        }

        return false;
    }

    // Property delegate for screen handler
    public PropertyDelegate getPropertyDelegate() {
        return new PropertyDelegate() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case 0 -> GridFurnaceBlockEntity.this.burnTime;
                    case 1 -> GridFurnaceBlockEntity.this.fuelTime;
                    case 2 -> GridFurnaceBlockEntity.this.cookTime;
                    case 3 -> GridFurnaceBlockEntity.this.cookTimeTotal;
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
                switch (index) {
                    case 0 -> GridFurnaceBlockEntity.this.burnTime = value;
                    case 1 -> GridFurnaceBlockEntity.this.fuelTime = value;
                    case 2 -> GridFurnaceBlockEntity.this.cookTime = value;
                    case 3 -> GridFurnaceBlockEntity.this.cookTimeTotal = value;
                }
            }

            @Override
            public int size() {
                return 4;
            }
        };
    }

    // Rest of the SidedInventory implementation remains the same...
    @Override
    public int[] getAvailableSlots(Direction side) {
        if (side == Direction.DOWN) {
            return BOTTOM_SLOTS;
        } else if (side == Direction.UP) {
            return TOP_SLOTS;
        } else {
            return SIDE_SLOTS;
        }
    }

    @Override
    public boolean canInsert(int slot, ItemStack stack, @Nullable Direction dir) {
        return this.isValid(slot, stack);
    }

    @Override
    public boolean canExtract(int slot, ItemStack stack, Direction dir) {
        if (dir == Direction.DOWN && slot == FUEL_SLOT) {
            return stack.isOf(Items.BUCKET);
        }
        return true;
    }

    @Override
    public int size() {
        return this.inventory.size();
    }

    @Override
    public boolean isEmpty() {
        return this.inventory.stream().allMatch(ItemStack::isEmpty);
    }

    @Override
    public ItemStack getStack(int slot) {
        return this.inventory.get(slot);
    }

    @Override
    public ItemStack removeStack(int slot, int amount) {
        return Inventories.splitStack(this.inventory, slot, amount);
    }

    @Override
    public ItemStack removeStack(int slot) {
        return Inventories.removeStack(this.inventory, slot);
    }

    @Override
    public void setStack(int slot, ItemStack stack) {
        ItemStack existingStack = this.inventory.get(slot);
        this.inventory.set(slot, stack);

        if (stack.getCount() > this.getMaxCountPerStack()) {
            stack.setCount(this.getMaxCountPerStack());
        }
    }

    @Override
    public void markDirty() {

    }

    @Override
    public boolean canPlayerUse(PlayerEntity player) {
        return true;
    }

    @Override
    public void clear() {
        this.inventory.clear();
    }

    @Override
    public boolean isValid(int slot, ItemStack stack) {
        if (slot == OUTPUT_SLOT) {
            return false; // Can't insert into output slot
        } else if (slot != FUEL_SLOT) {
            return true; // Input slot accepts anything
        } else {
            return AbstractFurnaceBlockEntity.canUseAsFuel(stack); // Fuel slot only accepts fuel
        }
    }

    // NBT serialization for persistence
    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        Inventories.writeNbt(nbt, this.inventory);
        nbt.putShort("BurnTime", (short)this.burnTime);
        nbt.putShort("CookTime", (short)this.cookTime);
        nbt.putShort("CookTimeTotal", (short)this.cookTimeTotal);
        nbt.putShort("FuelTime", (short)this.fuelTime);
        return nbt;
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        this.inventory.clear();
        Inventories.readNbt(nbt, this.inventory);
        this.burnTime = nbt.getShort("BurnTime");
        this.cookTime = nbt.getShort("CookTime");
        this.cookTimeTotal = nbt.getShort("CookTimeTotal");
        this.fuelTime = nbt.getShort("FuelTime");
    }
}