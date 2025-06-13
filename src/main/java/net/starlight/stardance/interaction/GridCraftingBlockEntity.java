package net.starlight.stardance.interaction;

import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.starlight.stardance.core.GridBlockEntity;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.utils.SLogger;

/**
 * Block entity for crafting tables on grids. Stores the crafting grid state.
 */
public class GridCraftingBlockEntity implements GridBlockEntity {
    private final LocalGrid grid;
    private final BlockPos gridPos;
    private GridCraftingInventory craftingInventory;

    public GridCraftingBlockEntity(LocalGrid grid, BlockPos gridPos) {
        this.grid = grid;
        this.gridPos = gridPos;
        
        SLogger.log("GridCraftingBlockEntity", "Created crafting block entity at " + gridPos + " on grid " + grid.getGridId());
    }

    @Override
    public void tick(World world, BlockPos pos, BlockState state) {
        // Crafting tables don't need ticking, but we implement this for the interface
    }

    /**
     * Gets or creates the crafting inventory for this crafting table.
     */
    public GridCraftingInventory getCraftingInventory(net.minecraft.screen.ScreenHandler screenHandler) {
        if (craftingInventory == null) {
            craftingInventory = new GridCraftingInventory(screenHandler, grid, gridPos);
        }
        return craftingInventory;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        if (craftingInventory != null) {
            craftingInventory.writeNbt(nbt);
        }
        return nbt;
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        if (craftingInventory != null) {
            craftingInventory.readNbt(nbt);
        }
    }
}