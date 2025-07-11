package net.starlight.stardance.interaction;

import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.utils.TransformationAPI;

/**
 * GridSpace-aware EntityHitResult that wraps vanilla EntityHitResult.
 * Handles entities that are on or associated with grids.
 */
public class GridSpaceEntityHitResult extends EntityHitResult implements GridSpaceHitResult {
    private final LocalGrid grid;
    private final EntityHitResult originalHitResult;
    private final Vec3d worldPos;
    private final Vec3d gridSpacePos;
    private final BlockPos gridSpaceBlockPos;
    
    /**
     * Creates a new GridSpace entity hit result.
     * 
     * @param originalHitResult The original vanilla EntityHitResult
     * @param transformResult The transformation result from world to GridSpace
     */
    public GridSpaceEntityHitResult(EntityHitResult originalHitResult,
                                    TransformationAPI.GridSpaceTransformResult transformResult) {
        // Call super constructor with original entity but GridSpace position
        super(
            originalHitResult.getEntity(),          // Keep original entity
            transformResult.gridSpaceVec           // Use GridSpace position
        );
        
        this.originalHitResult = originalHitResult;
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
        return grid != null && !grid.isDestroyed() && getEntity() != null;
    }
    
    /**
     * Creates a new EntityHitResult with world coordinates.
     */
    public EntityHitResult toWorldHitResult() {
        return new EntityHitResult(
            originalHitResult.getEntity(),
            worldPos
        );
    }
    
    /**
     * Creates a new EntityHitResult with GridSpace coordinates.
     */
    public EntityHitResult toGridSpaceHitResult() {
        return new EntityHitResult(
            originalHitResult.getEntity(),
            gridSpacePos
        );
    }
    
    @Override
    public String toString() {
        return String.format("GridSpaceEntityHitResult{grid=%s, entity=%s, world=%s, gridSpace=%s}", 
            grid.getGridId(), getEntity().getEntityName(), worldPos, gridSpacePos);
    }
}