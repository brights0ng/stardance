package net.starlight.stardance.interaction;

import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.starlight.stardance.core.LocalGrid;

/**
     * Result of a grid raycast operation.
     */
    public class GridRaycastHit {
        public final BlockPos gridSpacePos;           // GridSpace coordinates (for interaction)
        public final Vec3d visualHitPos;              // World coordinates (for distance calculation)
        public final BlockHitResult transformedResult; // Result with GridSpace coordinates
        final LocalGrid grid;

        public GridRaycastHit(BlockPos gridSpacePos, Vec3d visualHitPos, BlockHitResult transformedResult, LocalGrid grid) {
            this.gridSpacePos = gridSpacePos;
            this.visualHitPos = visualHitPos;
            this.transformedResult = transformedResult;
            this.grid = grid;
        }
    }