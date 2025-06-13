package net.starlight.stardance.interaction;

import com.bulletphysics.linearmath.Transform;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.util.math.BlockPos;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.utils.SLogger;

import javax.vecmath.Vector3f;

/**
 * Custom anvil screen handler for grid blocks.
 */
public class GridAnvilScreenHandler extends AnvilScreenHandler {
    private final LocalGrid grid;
    private final BlockPos gridLocalPos;

    public GridAnvilScreenHandler(int syncId, PlayerInventory playerInventory, LocalGrid grid, BlockPos gridLocalPos) {
        super(syncId, playerInventory, ScreenHandlerContext.EMPTY);
        this.grid = grid;
        this.gridLocalPos = gridLocalPos;
        
        SLogger.log("GridAnvilScreenHandler", "Created GridAnvilScreenHandler with syncId=" + syncId 
            + ", grid=" + grid.getGridId() 
            + ", gridLocalPos=" + gridLocalPos);
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        SLogger.log("GridAnvilScreenHandler", "canUse() called for player " + player.getName().getString());
        
        if (grid.getBlock(gridLocalPos) == null) {
            SLogger.log("GridAnvilScreenHandler", "canUse() - Block no longer exists at " + gridLocalPos);
            return false;
        }

        try {
            Vector3f gridWorldPos = new Vector3f();
            Transform gridTransform = new Transform();
            grid.getRigidBody().getWorldTransform(gridTransform);
            gridTransform.origin.get(gridWorldPos);

            double distanceToGrid = player.squaredDistanceTo(gridWorldPos.x, gridWorldPos.y, gridWorldPos.z);
            boolean withinRange = distanceToGrid <= 64.0;
            
            SLogger.log("GridAnvilScreenHandler", "canUse() - Distance: " + Math.sqrt(distanceToGrid) 
                + ", within range: " + withinRange);
            
            return withinRange;
        } catch (Exception e) {
            SLogger.log("GridAnvilScreenHandler", "canUse() - Exception: " + e.getMessage());
            return false;
        }
    }
}