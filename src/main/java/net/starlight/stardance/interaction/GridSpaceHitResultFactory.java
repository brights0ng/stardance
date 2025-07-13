package net.starlight.stardance.interaction;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.utils.TransformationAPI;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;

import java.util.Optional;

/**
 * PUBLIC API: Factory and utility class for creating and managing GridSpace hit results.
 * This is the main interface that mixins will use when intercepting interactions.
 *
 * Recreates VS2's missing ShipBlockHitResult functionality for 1.20.x.
 */
public class GridSpaceHitResultFactory implements ILoggingControl {

    private static final TransformationAPI transformAPI = TransformationAPI.getInstance();

    // ===============================================
    // MAIN CONVERSION METHODS FOR MIXINS
    // ===============================================

    /**
     * CORE METHOD: Converts a vanilla HitResult to a GridSpace-aware version.
     * This is the main method mixins will call to transform hit results.
     *
     * @param hitResult The original vanilla hit result
     * @param world The world context
     * @return GridSpace hit result if targeting a grid, otherwise original hit result
     */
    public static HitResult convertToGridSpaceHitResult(HitResult hitResult, Level world) {
        if (hitResult == null || world == null) {
            return hitResult;
        }

        // Skip if already a GridSpace hit result
        if (isGridSpaceHitResult(hitResult)) {
            return hitResult;
        }

        try {
            // Try to transform the hit position to GridSpace
            Optional<TransformationAPI.GridSpaceTransformResult> transformResult =
                    transformAPI.worldToGridSpace(hitResult.getLocation(), world);

            if (transformResult.isPresent()) {
                SLogger.log("GridSpaceHitResultFactory", "Converting hit result to GridSpace: " +
                        hitResult.getLocation() + " → " + transformResult.get().gridSpacePos);

                // Create appropriate GridSpace hit result based on type
                if (hitResult instanceof BlockHitResult blockHit) {
                    return new GridSpaceBlockHitResult(blockHit, transformResult.get());
                } else if (hitResult instanceof EntityHitResult entityHit) {
                    return new GridSpaceEntityHitResult(entityHit, transformResult.get());
                }
            }
        } catch (Exception e) {
            SLogger.log("GridSpaceHitResultFactory", "Failed to convert hit result to GridSpace: " + e.getMessage());
        }

        // Not targeting a grid, return original
        return hitResult;
    }

    /**
     * CORE METHOD: Converts a GridSpace hit result back to vanilla for specific use cases.
     * Some systems need GridSpace coordinates, others need world coordinates.
     *
     * @param hitResult The GridSpace hit result
     * @param useGridSpaceCoordinates Whether to use GridSpace (true) or world (false) coordinates
     * @return Vanilla hit result with appropriate coordinates
     */
    public static HitResult convertToVanillaHitResult(HitResult hitResult, boolean useGridSpaceCoordinates) {
        if (!(hitResult instanceof GridSpaceHitResult gridSpaceHit)) {
            return hitResult;
        }

        try {
            if (gridSpaceHit instanceof GridSpaceBlockHitResult blockHit) {
                return useGridSpaceCoordinates ? blockHit.toGridSpaceHitResult() : blockHit.toWorldHitResult();
            } else if (gridSpaceHit instanceof GridSpaceEntityHitResult entityHit) {
                return useGridSpaceCoordinates ? entityHit.toGridSpaceHitResult() : entityHit.toWorldHitResult();
            }
        } catch (Exception e) {
            SLogger.log("GridSpaceHitResultFactory", "Failed to convert GridSpace hit result to vanilla: " + e.getMessage());
        }

        return hitResult;
    }

    // ===============================================
    // DETECTION AND UTILITY METHODS
    // ===============================================

    /**
     * Checks if a hit result is targeting a grid.
     * Useful for mixins to determine if they need to apply special handling.
     */
    public static boolean isGridSpaceHitResult(HitResult hitResult) {
        return hitResult instanceof GridSpaceHitResult;
    }

    /**
     * Checks if a hit result is targeting a block on a grid.
     */
    public static boolean isGridSpaceBlockHitResult(HitResult hitResult) {
        return hitResult instanceof GridSpaceBlockHitResult;
    }

    /**
     * Checks if a hit result is targeting an entity associated with a grid.
     */
    public static boolean isGridSpaceEntityHitResult(HitResult hitResult) {
        return hitResult instanceof GridSpaceEntityHitResult;
    }

    /**
     * Extracts the grid from a hit result if it's a GridSpace hit result.
     * Returns null if not targeting a grid.
     */
    public static LocalGrid getGridFromHitResult(HitResult hitResult) {
        if (hitResult instanceof GridSpaceHitResult gridSpaceHit) {
            return gridSpaceHit.getGrid();
        }
        return null;
    }

    // ===============================================
    // COORDINATE EXTRACTION METHODS
    // ===============================================

    /**
     * Gets the GridSpace coordinates from a hit result, if available.
     * This is where the interaction should actually be executed.
     */
    public static Vec3 getGridSpaceCoordinates(HitResult hitResult) {
        if (hitResult instanceof GridSpaceHitResult gridSpaceHit) {
            return gridSpaceHit.getGridSpacePos();
        }
        return hitResult.getLocation(); // Fallback to original coordinates
    }

    /**
     * Gets the world coordinates from a hit result.
     * This is where the player visually sees the interaction.
     */
    public static Vec3 getWorldCoordinates(HitResult hitResult) {
        if (hitResult instanceof GridSpaceHitResult gridSpaceHit) {
            return gridSpaceHit.getWorldPos();
        }
        return hitResult.getLocation();
    }

    /**
     * Gets the original vanilla hit result from a GridSpace hit result.
     * Useful when you need to pass the original to other systems.
     */
    public static HitResult getOriginalHitResult(HitResult hitResult) {
        if (hitResult instanceof GridSpaceHitResult gridSpaceHit) {
            return gridSpaceHit.getOriginalHitResult();
        }
        return hitResult;
    }

    // ===============================================
    // CONVENIENCE METHODS FOR MIXINS
    // ===============================================

    /**
     * Convenience method: Gets GridSpace coordinates for block interactions.
     * Returns null if not targeting a grid block.
     */
    public static Vec3 getGridSpaceBlockCoordinates(HitResult hitResult) {
        if (hitResult instanceof GridSpaceBlockHitResult gridSpaceBlockHit) {
            return gridSpaceBlockHit.getGridSpacePos();
        }
        return null;
    }

    /**
     * Convenience method: Checks if interaction should be handled in GridSpace.
     * Mixins can use this to decide whether to apply coordinate transformation.
     */
    public static boolean shouldHandleInGridSpace(HitResult hitResult) {
        return isGridSpaceHitResult(hitResult) &&
                ((GridSpaceHitResult) hitResult).isValid();
    }

    /**
     * Debug method: Logs information about a hit result transformation.
     */
    public static void debugHitResult(HitResult hitResult, String context) {
        if (isGridSpaceHitResult(hitResult)) {
            GridSpaceHitResult gridSpaceHit = (GridSpaceHitResult) hitResult;
            SLogger.log("GridSpaceHitResultFactory", String.format(
                    "[%s] GridSpace hit result - Grid: %s, World: %s, GridSpace: %s",
                    context,
                    gridSpaceHit.getGrid().getGridId(),
                    gridSpaceHit.getWorldPos(),
                    gridSpaceHit.getGridSpacePos()
            ));
        } else if (hitResult != null) {
            SLogger.log("GridSpaceHitResultFactory", String.format(
                    "[%s] Vanilla hit result - Pos: %s, Type: %s",
                    context,
                    hitResult.getLocation(),
                    hitResult.getType()
            ));
        } else {
            SLogger.log("GridSpaceHitResultFactory", String.format("[%s] Null hit result", context));
        }
    }

    // ===============================================
    // LOGGING CONTROL
    // ===============================================

    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return true;
    }
}