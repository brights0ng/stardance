package net.starlight.stardance.interaction;

import com.bulletphysics.linearmath.Transform;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.util.math.BlockPos;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.utils.SLogger;

import javax.vecmath.Vector3f;

/**
 * Custom chest screen handler for grid blocks.
 */
public class GridChestScreenHandler extends GenericContainerScreenHandler {
    private final LocalGrid grid;
    private final BlockPos gridLocalPos;
    private final GridChestBlockEntity chestBlockEntity;

    public GridChestScreenHandler(int syncId, PlayerInventory playerInventory, LocalGrid grid, BlockPos gridLocalPos, GridChestBlockEntity chestEntity) {
        super(ScreenHandlerType.GENERIC_9X3, syncId, playerInventory, chestEntity, 3);

        // Call parent constructor with the block entity's inventory

        this.grid = grid;
        this.gridLocalPos = gridLocalPos;
        this.chestBlockEntity = chestEntity;

        // Notify chest that it's been opened
        chestEntity.onOpen(playerInventory.player);

        SLogger.log("GridChestScreenHandler", "Created GridChestScreenHandler with syncId=" + syncId
                + ", grid=" + grid.getGridId()
                + ", gridLocalPos=" + gridLocalPos);
    }

    static GridChestBlockEntity getOrCreateChestBlockEntity(LocalGrid grid, BlockPos gridLocalPos) {
        var localBlock = grid.getBlocks().get(gridLocalPos);
        if (localBlock == null) {
            throw new IllegalStateException("No block found at position " + gridLocalPos);
        }

        // Check if we already have a chest block entity
        if (localBlock.getBlockEntity() instanceof GridChestBlockEntity chestEntity) {
            return chestEntity;
        }

        // Create new chest block entity
        GridChestBlockEntity newChestEntity = new GridChestBlockEntity(grid, gridLocalPos);
        localBlock.setBlockEntity(newChestEntity);

        SLogger.log("GridChestScreenHandler", "Created new GridChestBlockEntity at " + gridLocalPos);
        return newChestEntity;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        SLogger.log("GridChestScreenHandler", "canUse() called for player " + player.getName().getString());

        if (grid.getBlock(gridLocalPos) == null) {
            SLogger.log("GridChestScreenHandler", "canUse() - Block no longer exists at " + gridLocalPos);
            return false;
        }

        try {
            Vector3f gridWorldPos = new Vector3f();
            Transform gridTransform = new Transform();
            grid.getRigidBody().getWorldTransform(gridTransform);
            gridTransform.origin.get(gridWorldPos);

            double distanceToGrid = player.squaredDistanceTo(gridWorldPos.x, gridWorldPos.y, gridWorldPos.z);
            boolean withinRange = distanceToGrid <= 64.0;

            SLogger.log("GridChestScreenHandler", "canUse() - Distance: " + Math.sqrt(distanceToGrid)
                    + ", within range: " + withinRange);

            return withinRange;
        } catch (Exception e) {
            SLogger.log("GridChestScreenHandler", "canUse() - Exception: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void onClosed(PlayerEntity player) {
        super.onClosed(player);

        // Notify chest that it's been closed
        chestBlockEntity.onClose(player);

        SLogger.log("GridChestScreenHandler", "Chest screen closed for player " + player.getName().getString());
    }

    public GridChestBlockEntity getChestBlockEntity() {
        return chestBlockEntity;
    }
}