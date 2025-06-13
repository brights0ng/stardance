package net.starlight.stardance.interaction;

import com.bulletphysics.linearmath.Transform;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.FurnaceScreenHandler;
import net.minecraft.util.math.BlockPos;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.utils.SLogger;

import javax.vecmath.Vector3f;

/**
 * Custom furnace screen handler for grid blocks.
 */
public class GridFurnaceScreenHandler extends FurnaceScreenHandler {
    private final LocalGrid grid;
    private final BlockPos gridLocalPos;
    private final GridFurnaceBlockEntity furnaceBlockEntity;

    public GridFurnaceScreenHandler(int syncId, PlayerInventory playerInventory, LocalGrid grid, BlockPos gridLocalPos, GridFurnaceBlockEntity furnaceEntity) {
        super(syncId, playerInventory, furnaceEntity, furnaceEntity.getPropertyDelegate());

        // Call parent constructor with the block entity's inventory and property delegate

        this.grid = grid;
        this.gridLocalPos = gridLocalPos;
        this.furnaceBlockEntity = furnaceEntity;

        SLogger.log("GridFurnaceScreenHandler", "Created GridFurnaceScreenHandler with syncId=" + syncId
                + ", grid=" + grid.getGridId()
                + ", gridLocalPos=" + gridLocalPos);
    }

    static GridFurnaceBlockEntity getOrCreateFurnaceBlockEntity(LocalGrid grid, BlockPos gridLocalPos) {
        var localBlock = grid.getBlocks().get(gridLocalPos);
        if (localBlock == null) {
            throw new IllegalStateException("No block found at position " + gridLocalPos);
        }

        // Check if we already have a furnace block entity
        if (localBlock.getBlockEntity() instanceof GridFurnaceBlockEntity furnaceEntity) {
            return furnaceEntity;
        }

        // Create new furnace block entity
        GridFurnaceBlockEntity newFurnaceEntity = new GridFurnaceBlockEntity(grid, gridLocalPos);
        localBlock.setBlockEntity(newFurnaceEntity);

        SLogger.log("GridFurnaceScreenHandler", "Created new GridFurnaceBlockEntity at " + gridLocalPos);
        return newFurnaceEntity;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        SLogger.log("GridFurnaceScreenHandler", "canUse() called for player " + player.getName().getString());

        if (grid.getBlock(gridLocalPos) == null) {
            SLogger.log("GridFurnaceScreenHandler", "canUse() - Block no longer exists at " + gridLocalPos);
            return false;
        }

        try {
            Vector3f gridWorldPos = new Vector3f();
            Transform gridTransform = new Transform();
            grid.getRigidBody().getWorldTransform(gridTransform);
            gridTransform.origin.get(gridWorldPos);

            double distanceToGrid = player.squaredDistanceTo(gridWorldPos.x, gridWorldPos.y, gridWorldPos.z);
            boolean withinRange = distanceToGrid <= 64.0;

            SLogger.log("GridFurnaceScreenHandler", "canUse() - Distance: " + Math.sqrt(distanceToGrid)
                    + ", within range: " + withinRange);

            return withinRange;
        } catch (Exception e) {
            SLogger.log("GridFurnaceScreenHandler", "canUse() - Exception: " + e.getMessage());
            return false;
        }
    }

    public GridFurnaceBlockEntity getFurnaceBlockEntity() {
        return furnaceBlockEntity;
    }
}