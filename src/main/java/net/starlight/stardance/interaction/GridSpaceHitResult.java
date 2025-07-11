package net.starlight.stardance.interaction;

import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.starlight.stardance.core.LocalGrid;

/**
 * PUBLIC INTERFACE: Base interface for GridSpace-aware hit results.
 *
 * This interface allows mixins and other systems to work with hit results
 * that contain both world coordinates (visual) and GridSpace coordinates (actual).
 *
 * Recreates VS2's ShipBlockHitResult concept for Stardance.
 */
public interface GridSpaceHitResult {

    /**
     * Gets the grid this hit result is associated with.
     * @return The LocalGrid containing the interacted block/entity
     */
    LocalGrid getGrid();

    /**
     * Gets the original world coordinates where the player interacted.
     * This is where the player sees the block/entity visually.
     * @return World coordinates of the interaction
     */
    Vec3d getWorldPos();

    /**
     * Gets the GridSpace coordinates where the block/entity actually exists.
     * This is where the interaction should be executed for it to take effect.
     * @return GridSpace coordinates of the actual block/entity
     */
    Vec3d getGridSpacePos();

    /**
     * Gets the GridSpace block position (discrete coordinates).
     * Useful for block-based operations that need integer coordinates.
     * @return GridSpace block position
     */
    BlockPos getGridSpaceBlockPos();

    /**
     * Gets the original vanilla hit result that this wraps.
     * Useful when you need to pass the original hit result to other systems.
     * @return The original HitResult
     */
    HitResult getOriginalHitResult();

    /**
     * Checks if this hit result represents a valid GridSpace interaction.
     * @return true if the hit result is valid and can be used for interactions
     */
    boolean isValid();
}