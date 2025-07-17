package net.starlight.stardance.mixin.feature.core_raycast;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.gridspace.GridSpaceManager;
import net.starlight.stardance.gridspace.utils.GridSpaceRaycastUtils;
import net.starlight.stardance.utils.SLogger;
import org.spongepowered.asm.mixin.Mixin;

import static net.starlight.stardance.gridspace.utils.GridSpaceRaycastUtils.selectCloserHit;

/**
 * Level-wide raycast integration - matches VS2's exact pattern.
 * Delegates everything to GridSpaceRaycastUtils to handle recursion prevention.
 */
@Mixin(Level.class)
public abstract class MixinLevel implements BlockGetter {

    @Override
    public BlockHitResult clip(final ClipContext clipContext) {
        try {
            // Safety check for cross-GridSpace boundaries (VS2's exact approach)
            if (!isSafeToRaycastAcrossGridSpaces(clipContext)) {
                // VS2's exact warning message pattern
                SLogger.log("MixinLevel", "Trying to clip from " +
                        formatVec3(clipContext.getFrom()) + " to " + formatVec3(clipContext.getTo()) +
                        " which one of them is in a gridspace which is ... sus!!");

                // VS2's exact miss result calculation
                final Vec3 vec3 = clipContext.getFrom().subtract(clipContext.getTo());
                return BlockHitResult.miss(
                        clipContext.getTo(),
                        Direction.getNearest(vec3.x, vec3.y, vec3.z),
                        BlockPos.containing(clipContext.getTo())
                );
            }

            // Perform vanilla raycast using BlockGetter (avoids recursion)
            BlockHitResult vanillaResult = BlockGetter.super.clip(clipContext);

            // Perform grid raycast - CORRECTED: use proper method name and pass Level
            BlockHitResult gridResult = GridSpaceRaycastUtils.performGridSpaceRaycastOnly(
                    Level.class.cast(this), clipContext);

            // Select the closer hit
            BlockHitResult finalResult = selectCloserHit(vanillaResult, gridResult, clipContext.getFrom());

            return finalResult;

        } catch (Exception e) {
            SLogger.log("MixinLevel", "Enhanced level raycast failed: " + e.getMessage());
            e.printStackTrace();

            // Fallback to vanilla (using BlockGetter to avoid recursion)
            return BlockGetter.super.clip(clipContext);
        }
    }

    /**
     * Safety check to prevent raycasts that cross GridSpace boundaries.
     * Matches VS2's exact approach for cross-shipyard boundary detection.
     */
    private boolean isSafeToRaycastAcrossGridSpaces(ClipContext clipContext) {
        try {
            Vec3 fromPos = clipContext.getFrom();
            Vec3 toPos = clipContext.getTo();

            // Get the grids managing each position (null = world space)
            LocalGrid fromGrid = GridSpaceManager.getGridAtPosition(BlockPos.containing(fromPos));
            LocalGrid toGrid = GridSpaceManager.getGridAtPosition(BlockPos.containing(toPos));

            // VS2's exact logic: if grids are different, it's potentially unsafe
            if (fromGrid != toGrid) {
                // Calculate raycast distance
                double rayDistance = fromPos.distanceTo(toPos);

                // Allow short raycasts across boundaries (VS2 allows this implicitly)
                if (rayDistance < 2.0) {
                    SLogger.log("MixinLevel", String.format(
                            "Allowing short cross-boundary raycast: %.2f blocks", rayDistance));
                    return true;
                }

                // Log the boundary crossing attempt (matches VS2's warning approach)
                String fromGridName = fromGrid != null ? "Grid-" + fromGrid.getGridId() : "World";
                String toGridName = toGrid != null ? "Grid-" + toGrid.getGridId() : "World";

                SLogger.log("MixinLevel", String.format(
                        "Blocking cross-boundary raycast: %s → %s, distance: %.2f blocks",
                        fromGridName, toGridName, rayDistance));

                return false;
            }

            // Same grid (or both world) - always safe
            return true;

        } catch (Exception e) {
            // If we can't determine safely, VS2's approach is to allow the raycast
            SLogger.log("MixinLevel", "Safety check failed, allowing raycast: " + e.getMessage());
            return true;
        }
    }

    /**
     * Helper method to format Vec3 for logging (matches VS2's format).
     */
    private String formatVec3(Vec3 vec) {
        return String.format("(%.2f, %.2f, %.2f)", vec.x, vec.y, vec.z);
    }
}