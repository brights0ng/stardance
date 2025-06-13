package net.starlight.stardance.interaction;

import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.SidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.starlight.stardance.core.GridBlockEntity;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.utils.SLogger;
import org.jetbrains.annotations.Nullable;

/**
 * Block entity for chests on grids. Handles inventory storage.
 */
public class GridChestBlockEntity implements SidedInventory, GridBlockEntity {
    public static final int CHEST_SIZE = 27; // Standard chest size (9x3)
    private static final int[] AVAILABLE_SLOTS;

    static {
        // All slots are available from all sides for chests
        AVAILABLE_SLOTS = new int[CHEST_SIZE];
        for (int i = 0; i < CHEST_SIZE; i++) {
            AVAILABLE_SLOTS[i] = i;
        }
    }

    private final LocalGrid grid;
    private final BlockPos gridPos;
    private final DefaultedList<ItemStack> inventory;
    
    // Chest state for animation/sounds
    private int viewerCount = 0;
    private boolean wasOpen = false;

    public GridChestBlockEntity(LocalGrid grid, BlockPos gridPos) {
        this.grid = grid;
        this.gridPos = gridPos;
        this.inventory = DefaultedList.ofSize(CHEST_SIZE, ItemStack.EMPTY);
        
        SLogger.log("GridChestBlockEntity", "Created chest block entity at " + gridPos + " on grid " + grid.getGridId());
    }

    @Override
    public void tick(World world, BlockPos pos, BlockState state) {
        // Handle chest opening/closing sounds and animations
        if (!world.isClient) {
            boolean isOpen = viewerCount > 0;
            
            if (isOpen != wasOpen) {
                // Play chest open/close sound
                if (isOpen) {
                    world.playSound(null, pos, SoundEvents.BLOCK_CHEST_OPEN, SoundCategory.BLOCKS, 0.5f, 
                        world.random.nextFloat() * 0.1f + 0.9f);
                    SLogger.log("GridChestBlockEntity", "Chest opened at " + gridPos);
                } else {
                    world.playSound(null, pos, SoundEvents.BLOCK_CHEST_CLOSE, SoundCategory.BLOCKS, 0.5f, 
                        world.random.nextFloat() * 0.1f + 0.9f);
                    SLogger.log("GridChestBlockEntity", "Chest closed at " + gridPos);
                }
                wasOpen = isOpen;
            }
        }
    }

    /**
     * Called when a player opens the chest.
     */
    public void onOpen(PlayerEntity player) {
        viewerCount++;
        SLogger.log("GridChestBlockEntity", "Player " + player.getName().getString() + 
            " opened chest. Viewer count: " + viewerCount);
    }

    /**
     * Called when a player closes the chest.
     */
    public void onClose(PlayerEntity player) {
        viewerCount = Math.max(0, viewerCount - 1);
        SLogger.log("GridChestBlockEntity", "Player " + player.getName().getString() + 
            " closed chest. Viewer count: " + viewerCount);
    }

    // SidedInventory implementation
    @Override
    public int[] getAvailableSlots(Direction side) {
        return AVAILABLE_SLOTS; // All slots available from all sides
    }

    @Override
    public boolean canInsert(int slot, ItemStack stack, @Nullable Direction dir) {
        return true; // Chests accept any item in any slot
    }

    @Override
    public boolean canExtract(int slot, ItemStack stack, Direction dir) {
        return true; // Chests allow extraction from any slot
    }

    @Override
    public int size() {
        return CHEST_SIZE;
    }

    @Override
    public boolean isEmpty() {
        return inventory.stream().allMatch(ItemStack::isEmpty);
    }

    @Override
    public ItemStack getStack(int slot) {
        return inventory.get(slot);
    }

    @Override
    public ItemStack removeStack(int slot, int amount) {
        ItemStack result = Inventories.splitStack(inventory, slot, amount);
        if (!result.isEmpty()) {
            markDirty();
        }
        return result;
    }

    @Override
    public ItemStack removeStack(int slot) {
        ItemStack result = Inventories.removeStack(inventory, slot);
        if (!result.isEmpty()) {
            markDirty();
        }
        return result;
    }

    @Override
    public void setStack(int slot, ItemStack stack) {
        inventory.set(slot, stack);
        if (stack.getCount() > getMaxCountPerStack()) {
            stack.setCount(getMaxCountPerStack());
        }
        markDirty();
    }

    @Override
    public boolean canPlayerUse(PlayerEntity player) {
        return true; // Handled by screen handler
    }

    @Override
    public void clear() {
        inventory.clear();
        markDirty();
    }

    public void markDirty() {
        // In a full implementation, you might want to mark the grid as needing saving
        SLogger.log("GridChestBlockEntity", "Chest inventory changed at " + gridPos);
    }

    // NBT serialization for persistence
    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        Inventories.writeNbt(nbt, inventory);
        nbt.putInt("ViewerCount", viewerCount);
        nbt.putBoolean("WasOpen", wasOpen);
        return nbt;
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        inventory.clear();
        Inventories.readNbt(nbt, inventory);
        viewerCount = nbt.getInt("ViewerCount");
        wasOpen = nbt.getBoolean("WasOpen");
    }

    // Getters
    public DefaultedList<ItemStack> getInventory() {
        return inventory;
    }

    public int getViewerCount() {
        return viewerCount;
    }
}