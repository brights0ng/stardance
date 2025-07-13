package net.starlight.stardance.utils;

import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.starlight.stardance.physics.PhysicsEngine;

import java.util.Optional;

import static net.starlight.stardance.Stardance.engineManager;

public class GridSpaceRaycastUtils implements ILoggingControl {

    @Override
    public boolean stardance$isChatLoggingEnabled() { return false; }
    @Override
    public boolean stardance$isConsoleLoggingEnabled() { return false; }

    /**
     * Enhanced raycast that includes grids with physics precision fix.
     */
    public static BlockHitResult raycastIncludeGrids(Level world, ClipContext context) {
        try {
            // 1. Perform physics raycast first (grids)
            PhysicsEngine engine = engineManager.getEngine(world);
            Optional<PhysicsEngine.PhysicsRaycastResult> physicsHit = Optional.empty();

            if (engine != null) {
                physicsHit = engine.raycastGrids(context.getFrom(), context.getTo());
            }

            // 2. Perform vanilla world raycast
            BlockHitResult vanillaHit = world.clip(context);

            // 3. Return whichever is closer with PRECISION FIX
            if (physicsHit.isPresent()) {
                PhysicsEngine.PhysicsRaycastResult gridHit = physicsHit.get();

                double vanillaDistance = context.getFrom().distanceToSqr(vanillaHit.getLocation());
                double gridDistance = context.getFrom().distanceToSqr(gridHit.worldHitPos);

                if (gridDistance < vanillaDistance) {
//                    SLogger.log("GridSpaceRaycastUtils", "Grid hit is closer - using grid result with precision fix");

                    // PRECISION FIX: Find the actual block near the physics coordinates
                    Optional<TransformationAPI.GridSpaceTransformResult> actualBlock =
                            TransformationAPI.getInstance().findNearestGridSpaceBlock(gridHit.gridSpacePos, world, 2.0);

                    if (actualBlock.isPresent()) {
                        // Use the corrected coordinates
                        TransformationAPI.GridSpaceTransformResult corrected = actualBlock.get();
//                        SLogger.log("GridSpaceRaycastUtils", String.format(
//                                "Physics precision fix: %s → %s", gridHit.gridSpacePos, corrected.gridSpacePos
//                        ));

                        return new BlockHitResult(
                                gridHit.worldHitPos,          // Keep original world hit position for rendering
                                Direction.UP,                 // Hit direction
                                corrected.gridSpacePos,       // Corrected GridSpace coordinates
                                false
                        );
                    } else {
//                        SLogger.log("GridSpaceRaycastUtils", "Physics precision fix failed - no nearby blocks found");
                    }
                }
            }

//            SLogger.log("GridSpaceRaycastUtils", "Using vanilla raycast result");
            return vanillaHit;

        } catch (Exception e) {
//            SLogger.log("GridSpaceRaycastUtils", "Error in enhanced raycast: " + e.getMessage());
            return world.clip(context);
        }
    }
}