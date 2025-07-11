package net.starlight.stardance.interaction;

import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
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
    private final Vec3d worldPos;
    private final Vec3d gridSpacePos;
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
            originalHitResult.getSide(),            // Keep original face direction
            transformResult.gridSpacePos,           // Use GridSpace block position
            originalHitResult.isInsideBlock()       // Keep original inside block flag
        );
        
        this.originalHitResult = originalHitResult;
        this.transformResult = transformResult;
        this.grid = transformResult.grid;
        this.worldPos = originalHitResult.getPos();
        this.gridSpacePos = transformResult.gridSpaceVec;
        this.gridSpaceBlockPos = transformResult.gridSpacePos;
    }
    
    @Override
    public LocalGrid getGrid() {
        return grid;
    }
    
    @Override
    public Vec3d getWorldPos() {
        return worldPos;
    }
    
    @Override
    public Vec3d getGridSpacePos() {
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
            originalHitResult.getSide(),
            originalHitResult.getBlockPos(),
            originalHitResult.isInsideBlock()
        );
    }
    
    /**
     * Creates a new BlockHitResult with GridSpace coordinates.
     * Useful for interactions that should happen in GridSpace.
     */
    public BlockHitResult toGridSpaceHitResult() {
        return new BlockHitResult(
            gridSpacePos,
            originalHitResult.getSide(),
            gridSpaceBlockPos,
            originalHitResult.isInsideBlock()
        );
    }
    
    @Override
    public String toString() {
        return String.format("GridSpaceBlockHitResult{grid=%s, world=%s, gridSpace=%s, face=%s}", 
            grid.getGridId(), worldPos, gridSpaceBlockPos, getSide());
    }
}