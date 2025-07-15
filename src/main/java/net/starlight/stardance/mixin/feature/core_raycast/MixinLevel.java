package net.starlight.stardance.mixin.feature.core_raycast;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.starlight.stardance.gridspace.utils.GridSpaceRaycastUtils;
import net.starlight.stardance.utils.SLogger;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Level-wide raycast integration - ensures ALL raycasts include GridSpace blocks.
 *
 * This mixin overrides Level.clip() directly to provide comprehensive coverage
 * for any code that performs raycasts without going through Entity.pick().
 *
 * Based on VS2's MixinLevel pattern but adapted for GridSpace architecture.
 */
@Mixin(Level.class)
public abstract class MixinLevel implements BlockGetter {

    /**
     * Override Level.clip() to include GridSpace blocks in all raycasts.
     *
     * IMPORTANT: We must avoid recursion by calling vanilla clip directly,
     * not through GridSpaceRaycastUtils.clipIncludeGrids().
     */
    @Override
    public BlockHitResult clip(final ClipContext clipContext) {
        try {
//            SLogger.log("MixinLevel", "Level.clip() intercepted - from " +
//                    formatVec3(clipContext.getFrom()) + " to " + formatVec3(clipContext.getTo()));

            // Safety check for cross-GridSpace boundaries (VS2 pattern)
            if (!isSafeToRaycastAcrossGridSpaces(clipContext)) {
//                SLogger.log("MixinLevel", "Unsafe cross-GridSpace raycast - returning miss");

                final Vec3 vec3 = clipContext.getFrom().subtract(clipContext.getTo());
                return BlockHitResult.miss(
                        clipContext.getTo(),
                        Direction.getNearest(vec3.x, vec3.y, vec3.z),
                        BlockPos.containing(clipContext.getTo())
                );
            }

            // Perform vanilla raycast using BlockGetter (avoids recursion)
            BlockHitResult vanillaResult = BlockGetter.super.clip(clipContext);
//            SLogger.log("MixinLevel", "Vanilla result: " + vanillaResult.getType());

            // Perform grid raycast (but only the grid part, not the full clipIncludeGrids)
            BlockHitResult gridResult = performGridRaycastOnly(clipContext);
//            SLogger.log("MixinLevel", "Grid result: " + gridResult.getType());

            // Select the closer hit
            BlockHitResult finalResult = selectCloserHit(vanillaResult, gridResult, clipContext.getFrom());

//            SLogger.log("MixinLevel", "Final result: " + finalResult.getType() + " at " + finalResult.getBlockPos());
            return finalResult;

        } catch (Exception e) {
            SLogger.log("MixinLevel", "Enhanced level raycast failed: " + e.getMessage());
            e.printStackTrace();

            // Fallback to vanilla (using BlockGetter to avoid recursion)
            return BlockGetter.super.clip(clipContext);
        }
    }

    /**
     * Performs ONLY the grid raycast portion, without calling level.clip().
     * This avoids recursion while still checking grids.
     */
    private BlockHitResult performGridRaycastOnly(ClipContext clipContext) {
        try {
            // Call the grid raycast part directly, bypassing the vanilla raycast
            return GridSpaceRaycastUtils.performGridSpaceRaycastOnly(Level.class.cast(this), clipContext);

        } catch (Exception e) {
            SLogger.log("MixinLevel", "Grid-only raycast failed: " + e.getMessage());
            return BlockHitResult.miss(clipContext.getTo(), null, BlockPos.containing(clipContext.getTo()));
        }
    }

    /**
     * Selects the closer hit between vanilla and grid results.
     */
    private BlockHitResult selectCloserHit(BlockHitResult worldHit, BlockHitResult gridHit, Vec3 rayOrigin) {
        // If only one hit, return it
        if (worldHit.getType() == HitResult.Type.MISS) return gridHit;
        if (gridHit.getType() == HitResult.Type.MISS) return worldHit;

        // Both hit - return closer one
        double worldDistance = worldHit.getLocation().distanceToSqr(rayOrigin);
        double gridDistance = gridHit.getLocation().distanceToSqr(rayOrigin);

        return gridDistance < worldDistance ? gridHit : worldHit;
    }

    /**
     * Safety check to prevent raycasts that cross GridSpace boundaries.
     */
    private boolean isSafeToRaycastAcrossGridSpaces(ClipContext clipContext) {
        // For now, allow all raycasts - implement boundary checking later
        return true;
    }

    /**
     * Helper method to format Vec3 for logging.
     */
    private String formatVec3(Vec3 vec) {
        return String.format("(%.2f, %.2f, %.2f)", vec.x, vec.y, vec.z);
    }
}