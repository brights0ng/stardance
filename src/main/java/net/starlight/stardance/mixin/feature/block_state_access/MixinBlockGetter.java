package net.starlight.stardance.mixin.feature.block_state_access;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.gridspace.GridSpaceManager;
import net.starlight.stardance.utils.SLogger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Intercepts getBlockState calls to include GridSpace blocks.
 * Matches VS2's exact @WrapOperation pattern for block state access.
 */
@Mixin(BlockGetter.class)
public class MixinBlockGetter {

    /**
     * Intercepts getBlockState calls to check both world and grid positions.
     * This matches VS2's exact approach using @WrapOperation.
     */
    @WrapOperation(
            method = "*",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/BlockGetter;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;")
    )
    private BlockState getBlockStateIncludeGrids(BlockGetter instance, BlockPos blockPos, Operation<BlockState> original) {
        try {
            // Get the original world block state first
            BlockState worldBlockState = original.call(instance, blockPos);

            // If we found a non-air block in the world, return it
            if (!worldBlockState.isAir()) {
                return worldBlockState;
            }

            // If world block is air, check grids using Stardance's equivalent of transformToNearbyShipsAndWorld
            if (instance instanceof Level level) {
                List<Vec3> candidatePositions = transformToNearbyGridsAndWorld(level,
                        blockPos.getX(), blockPos.getY(), blockPos.getZ(), 1.5);

                for (Vec3 candidate : candidatePositions) {
                    BlockPos candidatePos = BlockPos.containing(candidate.x, candidate.y, candidate.z);

                    // Skip the original position since we already checked it
                    if (candidatePos.equals(blockPos)) {
                        continue;
                    }

                    // Check if this position has a grid block
                    LocalGrid grid = GridSpaceManager.getGridAtPosition(candidatePos);
                    if (grid != null) {
                        // Convert world position to grid space
                        Vec3 gridSpacePos = grid.worldToGridSpace(candidate);
                        BlockPos gridSpaceBlockPos = BlockPos.containing(gridSpacePos);

                        // Get block state from grid using the correct method
                        BlockState gridBlockState = grid.getBlockState(gridSpaceBlockPos);
                        if (gridBlockState != null && !gridBlockState.isAir()) {
                            SLogger.log("MixinBlockGetter",
                                    String.format("Found grid block at world=%s → grid=%s: %s",
                                            candidatePos.toShortString(),
                                            gridSpaceBlockPos.toShortString(),
                                            gridBlockState.getBlock().getDescriptionId()));

                            return gridBlockState;
                        }
                    }
                }
            }

            // Return original world block state if nothing found in grids
            return worldBlockState;

        } catch (Exception e) {
            SLogger.log("MixinBlockGetter", "Error in getBlockState: " + e.getMessage());
            // Fallback to original behavior
            return original.call(instance, blockPos);
        }
    }

    /**
     * Stardance equivalent of VS2's transformToNearbyShipsAndWorld.
     * Returns candidate positions to check for blocks (world and grid spaces).
     */
    private List<Vec3> transformToNearbyGridsAndWorld(Level level, double x, double y, double z, double radius) {
        List<Vec3> candidates = new java.util.ArrayList<>();

        // Always include the original world position
        candidates.add(new Vec3(x, y, z));

        // Find nearby grids and add their transformed positions
        List<LocalGrid> nearbyGrids = GridSpaceManager.getGridsNear(new Vec3(x, y, z), radius);

        for (LocalGrid grid : nearbyGrids) {
            try {
                // Transform world position to grid space
                Vec3 worldPos = new Vec3(x, y, z);
                Vec3 gridSpacePos = grid.worldToGridSpace(worldPos);

                // Transform back to visual world coordinates for the candidate list
                Vec3 visualWorldPos = grid.gridSpaceToWorldSpace(gridSpacePos);
                candidates.add(visualWorldPos);

            } catch (Exception e) {
                // Skip this grid if transformation fails
                SLogger.log("MixinBlockGetter", "Grid transformation failed for grid " + grid.getGridId());
            }
        }

        return candidates;
    }
}