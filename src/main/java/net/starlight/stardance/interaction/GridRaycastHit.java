package net.starlight.stardance.interaction;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.starlight.stardance.core.LocalGrid;

/**
     * Result of a grid raycast operation.
     */
    public class GridRaycastHit {
        public final BlockPos gridSpacePos;           // GridSpace coordinates (for interaction)
        public final Vec3 visualHitPos;              // World coordinates (for distance calculation)
        public final BlockHitResult transformedResult; // Result with GridSpace coordinates
        final LocalGrid grid;

        public GridRaycastHit(BlockPos gridSpacePos, Vec3 visualHitPos, BlockHitResult transformedResult, LocalGrid grid) {
            this.gridSpacePos = gridSpacePos;
            this.visualHitPos = visualHitPos;
            this.transformedResult = transformedResult;
            this.grid = grid;
        }
    }