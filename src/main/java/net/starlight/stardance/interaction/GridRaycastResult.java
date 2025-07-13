package net.starlight.stardance.interaction;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.starlight.stardance.core.LocalGrid;

/**
 * Result of a grid raycast operation.
 */
public class GridRaycastResult {
    public final double distance;
    public final BlockPos gridSpacePos;
    public final BlockHitResult blockHitResult;
    public final LocalGrid grid;

    public GridRaycastResult(double distance, BlockPos gridSpacePos, BlockHitResult blockHitResult, LocalGrid grid) {
        this.distance = distance;
        this.gridSpacePos = gridSpacePos;
        this.blockHitResult = blockHitResult;
        this.grid = grid;
    }
}