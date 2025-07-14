package net.starlight.stardance.utils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.starlight.stardance.physics.PhysicsEngine;

import java.util.Optional;

import static net.starlight.stardance.Stardance.engineManager;

/**
 * VS2-Style Grid Raycast Utilities - Recursion Safe Implementation
 */
public class GridSpaceRaycastUtils implements ILoggingControl {

    @Override
    public boolean stardance$isChatLoggingEnabled() { return false; }
    @Override
    public boolean stardance$isConsoleLoggingEnabled() { return false; }

    // Thread-local flag to prevent recursion when we add MixinLevel
    private static final ThreadLocal<Boolean> PERFORMING_VANILLA_CLIP = ThreadLocal.withInitial(() -> false);

    /**
     * VS2-Style: Main raycast function that includes grids.
     * This is recursion-safe and matches VS2's pattern.
     */
    public static BlockHitResult clipIncludeGrids(Level level, ClipContext context) {
        try {
            // VS2-Style: Safety checks first
            if (isUnsafeRaycast(level, context)) {
                SLogger.log("GridSpaceRaycastUtils", "Unsafe raycast detected - using fallback");
                return createMissResult(context);
            }

            // 1. VS2-Style: Perform vanilla raycast (recursion-safe)
            BlockHitResult vanillaResult = performVanillaClipSafe(level, context);

            // 2. VS2-Style: Perform grid-based raycast using JBullet physics
            BlockHitResult gridResult = performGridPhysicsRaycast(level, context);

            // 3. VS2-Style: Return whichever hit is closer
            return selectCloserHit(vanillaResult, gridResult, context.getFrom());

        } catch (Exception e) {
            SLogger.log("GridSpaceRaycastUtils", "Error in clipIncludeGrids: " + e.getMessage());
            // Fallback to safe vanilla raycast
            return performVanillaClipSafe(level, context);
        }
    }

    /**
     * Recursion-safe vanilla clip using ThreadLocal flag.
     * This prevents infinite recursion when MixinLevel is active.
     */
    private static BlockHitResult performVanillaClipSafe(Level level, ClipContext context) {
        try {
            // Check if we're already performing a vanilla clip (prevent recursion)
            if (PERFORMING_VANILLA_CLIP.get()) {
                SLogger.log("GridSpaceRaycastUtils", "Recursion detected - creating miss result");
                return createMissResult(context);
            }

            // Set flag to indicate we're performing vanilla clip
            PERFORMING_VANILLA_CLIP.set(true);

            try {
                // This will call Level.clip, which may be overridden by our MixinLevel
                // But our MixinLevel will check the ThreadLocal flag and skip grid processing
                return level.clip(context);
            } finally {
                // Always clear the flag
                PERFORMING_VANILLA_CLIP.set(false);
            }

        } catch (Exception e) {
            PERFORMING_VANILLA_CLIP.set(false); // Ensure flag is cleared on error
            SLogger.log("GridSpaceRaycastUtils", "Vanilla clip failed: " + e.getMessage());
            return createMissResult(context);
        }
    }

    /**
     * Check if we're currently performing a vanilla clip (used by MixinLevel).
     */
    public static boolean isPerformingVanillaClip() {
        return PERFORMING_VANILLA_CLIP.get();
    }

    /**
     * VS2-Style: Perform grid raycast using JBullet physics engine.
     */
    private static BlockHitResult performGridPhysicsRaycast(Level level, ClipContext context) {
        try {
            PhysicsEngine engine = engineManager.getEngine(level);
            if (engine == null) {
                return createMissResult(context);
            }

            Optional<PhysicsEngine.PhysicsRaycastResult> physicsHit = engine.raycastGrids(
                    context.getFrom(),
                    context.getTo()
            );

            if (physicsHit.isPresent()) {
                PhysicsEngine.PhysicsRaycastResult hit = physicsHit.get();

                // Apply precision fix
                Optional<TransformationAPI.GridSpaceTransformResult> actualBlock =
                        TransformationAPI.getInstance().findNearestGridSpaceBlock(
                                hit.gridSpacePos, level, 2.0
                        );

                if (actualBlock.isPresent()) {
                    TransformationAPI.GridSpaceTransformResult corrected = actualBlock.get();

                    // Enhanced logging for testing
                    SLogger.log("GridSpaceRaycastUtils", String.format(
                            "Grid hit: World=%.2f,%.2f,%.2f GridSpace=%s",
                            hit.worldHitPos.x, hit.worldHitPos.y, hit.worldHitPos.z,
                            corrected.gridSpacePos.toString()
                    ));

                    return new BlockHitResult(
                            hit.worldHitPos,           // World position for rendering
                            calculateHitDirection(context, hit.worldHitPos), // Proper hit direction
                            corrected.gridSpacePos,    // GridSpace coordinates for interaction
                            false
                    );
                } else {
                    SLogger.log("GridSpaceRaycastUtils", "Physics hit but precision fix failed");
                }
            }

            return createMissResult(context);

        } catch (Exception e) {
            SLogger.log("GridSpaceRaycastUtils", "Grid physics raycast failed: " + e.getMessage());
            return createMissResult(context);
        }
    }

    /**
     * VS2-Style: Select the closer hit between vanilla and grid results.
     */
    private static BlockHitResult selectCloserHit(BlockHitResult vanillaHit, BlockHitResult gridHit, Vec3 rayStart) {
        // If grid raycast missed, use vanilla
        if (gridHit.getType() == HitResult.Type.MISS) {
            SLogger.log("GridSpaceRaycastUtils", "Grid missed, using vanilla result");
            return vanillaHit;
        }

        // If vanilla raycast missed, use grid
        if (vanillaHit.getType() == HitResult.Type.MISS) {
            SLogger.log("GridSpaceRaycastUtils", "Vanilla missed, using grid result");
            return gridHit;
        }

        // Both hit - compare distances (squared for performance)
        double vanillaDistance = rayStart.distanceToSqr(vanillaHit.getLocation());
        double gridDistance = rayStart.distanceToSqr(gridHit.getLocation());

        if (gridDistance < vanillaDistance) {
            SLogger.log("GridSpaceRaycastUtils", String.format(
                    "Grid hit closer: %.3f vs %.3f", Math.sqrt(gridDistance), Math.sqrt(vanillaDistance)
            ));
            return gridHit;
        } else {
            SLogger.log("GridSpaceRaycastUtils", String.format(
                    "Vanilla hit closer: %.3f vs %.3f", Math.sqrt(vanillaDistance), Math.sqrt(gridDistance)
            ));
            return vanillaHit;
        }
    }

    /**
     * VS2-Style: Safety checks to prevent problematic raycasts.
     */
    private static boolean isUnsafeRaycast(Level level, ClipContext context) {
        try {
            Vec3 from = context.getFrom();
            Vec3 to = context.getTo();

            // Check for extremely long raycasts
            if (from.distanceToSqr(to) > 10000.0) { // > 100 block distance
                SLogger.log("GridSpaceRaycastUtils", "Raycast too long: " + Math.sqrt(from.distanceToSqr(to)));
                return true;
            }

            // Check for NaN or infinite coordinates
            if (!isValidCoordinate(from) || !isValidCoordinate(to)) {
                SLogger.log("GridSpaceRaycastUtils", "Invalid coordinates detected");
                return true;
            }

            return false;

        } catch (Exception e) {
            SLogger.log("GridSpaceRaycastUtils", "Safety check failed: " + e.getMessage());
            return true;
        }
    }

    /**
     * Validate that coordinates are safe to use.
     */
    private static boolean isValidCoordinate(Vec3 vec) {
        return Double.isFinite(vec.x) && Double.isFinite(vec.y) && Double.isFinite(vec.z);
    }

    /**
     * Calculate proper hit direction based on raycast.
     */
    private static Direction calculateHitDirection(ClipContext context, Vec3 hitPos) {
        try {
            Vec3 rayDirection = context.getTo().subtract(context.getFrom()).normalize();
            return Direction.getNearest(rayDirection.x, rayDirection.y, rayDirection.z).getOpposite();
        } catch (Exception e) {
            return Direction.UP; // Safe fallback
        }
    }

    /**
     * Create a safe miss result when raycasts fail.
     */
    private static BlockHitResult createMissResult(ClipContext context) {
        Vec3 rayDirection = context.getTo().subtract(context.getFrom());
        return BlockHitResult.miss(
                context.getTo(),
                Direction.getNearest(rayDirection.x, rayDirection.y, rayDirection.z),
                BlockPos.containing(context.getTo())
        );
    }

    // ========== TESTING UTILITIES ==========

    /**
     * Test the precision fix to see how well it works.
     */
    public static void testPrecisionFix(Level level, ClipContext context) {
        try {
            PhysicsEngine engine = engineManager.getEngine(level);
            if (engine == null) {
                SLogger.log("GridSpaceRaycastUtils", "❌ No physics engine for precision test");
                return;
            }

            Optional<PhysicsEngine.PhysicsRaycastResult> physicsHit = engine.raycastGrids(
                    context.getFrom(), context.getTo()
            );

            if (physicsHit.isPresent()) {
                PhysicsEngine.PhysicsRaycastResult hit = physicsHit.get();

                SLogger.log("GridSpaceRaycastUtils", "=== PRECISION FIX TEST ===");
                SLogger.log("GridSpaceRaycastUtils", "Raw physics hit GridSpace: " + hit.gridSpacePos);

                Optional<TransformationAPI.GridSpaceTransformResult> corrected =
                        TransformationAPI.getInstance().findNearestGridSpaceBlock(
                                hit.gridSpacePos, level, 2.0
                        );

                if (corrected.isPresent()) {
                    SLogger.log("GridSpaceRaycastUtils", "✅ Corrected to GridSpace: " + corrected.get().gridSpacePos);
//                    double distance = hit.gridSpacePos.distance(corrected.get().gridSpacePos);
//                    SLogger.log("GridSpaceRaycastUtils", "Correction distance: " + distance);
                } else {
                    SLogger.log("GridSpaceRaycastUtils", "❌ Precision fix failed - no nearby blocks");
                }
            } else {
                SLogger.log("GridSpaceRaycastUtils", "No physics hit to test precision fix");
            }

        } catch (Exception e) {
            SLogger.log("GridSpaceRaycastUtils", "❌ Precision fix test failed: " + e.getMessage());
        }
    }

    // ========== COMPATIBILITY LAYER ==========

    /**
     * Your existing method name for backward compatibility.
     */
    public static BlockHitResult raycastIncludeGrids(Level world, ClipContext context) {
        return clipIncludeGrids(world, context);
    }
}