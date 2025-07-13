package net.starlight.stardance.interaction;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.utils.TransformationAPI;

/**
 * GridSpace-aware BlockHitResult that wraps vanilla BlockHitResult.
 * Stores both world coordinates (visual) and GridSpace coordinates (actual).
 * 
 * This recreates VS2's missing ShipBlockHitResult functionality for 1.20.x.
 */
public class GridSpaceBlockHitResult extends BlockHitResult implements GridSpaceHitResult {
    private final LocalGrid grid;
    private final BlockHitResult originalHitResult;
    private final Vec3 worldPos;
    private final Vec3 gridSpacePos;
    private final BlockPos gridSpaceBlockPos;
    private final TransformationAPI.GridSpaceTransformResult transformResult;
    
    /**
     * Creates a new GridSpace block hit result.
     * 
     * @param originalHitResult The original vanilla BlockHitResult
     * @param transformResult The transformation result from world to GridSpace
     */
    public GridSpaceBlockHitResult(BlockHitResult originalHitResult, 
                                   TransformationAPI.GridSpaceTransformResult transformResult) {
        // Call super constructor with GridSpace coordinates
        super(
            transformResult.gridSpaceVec,           // Use GridSpace position for the hit
            originalHitResult.getDirection(),            // Keep original face direction
            transformResult.gridSpacePos,           // Use GridSpace block position
            originalHitResult.isInside()       // Keep original inside block flag
        );
        
        this.originalHitResult = originalHitResult;
        this.transformResult = transformResult;
        this.grid = transformResult.grid;
        this.worldPos = originalHitResult.getLocation();
        this.gridSpacePos = transformResult.gridSpaceVec;
        this.gridSpaceBlockPos = transformResult.gridSpacePos;
    }
    
    @Override
    public LocalGrid getGrid() {
        return grid;
    }
    
    @Override
    public Vec3 getWorldPos() {
        return worldPos;
    }
    
    @Override
    public Vec3 getGridSpacePos() {
        return gridSpacePos;
    }
    
    @Override
    public BlockPos getGridSpaceBlockPos() {
        return gridSpaceBlockPos;
    }
    
    @Override
    public HitResult getOriginalHitResult() {
        return originalHitResult;
    }
    
    @Override
    public boolean isValid() {
        return grid != null && !grid.isDestroyed() && gridSpaceBlockPos != null;
    }
    
    /**
     * Gets the grid-local block position for reference.
     */
    public BlockPos getGridLocalBlockPos() {
        return transformResult.gridLocalPos;
    }
    
    /**
     * Creates a new BlockHitResult with world coordinates.
     * Useful when you need to pass a vanilla hit result to other systems.
     */
    public BlockHitResult toWorldHitResult() {
        return new BlockHitResult(
            worldPos,
            originalHitResult.getDirection(),
            originalHitResult.getBlockPos(),
            originalHitResult.isInside()
        );
    }
    
    /**
     * Creates a new BlockHitResult with GridSpace coordinates.
     * Useful for interactions that should happen in GridSpace.
     */
    public BlockHitResult toGridSpaceHitResult() {
        return new BlockHitResult(
            gridSpacePos,
            originalHitResult.getDirection(),
            gridSpaceBlockPos,
            originalHitResult.isInside()
        );
    }
    
    @Override
    public String toString() {
        return String.format("GridSpaceBlockHitResult{grid=%s, world=%s, gridSpace=%s, face=%s}", 
            grid.getGridId(), worldPos, gridSpaceBlockPos, getDirection());
    }
}