package net.starlight.stardance.utils;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * VS2-Style distance calculation utilities that account for grids.
 * Similar to VS2's VSGameUtilsKt but for Stardance.
 */
public class GridAwareDistanceUtils {

    /**
     * Calculate squared distance between player and position, accounting for grids.
     * Similar to VS2's squaredDistanceToInclShips.
     */
    public static double squaredDistanceToInclGrids(Player player, double x, double y, double z) {
        Level level = player.level();
        
        // Check if target position is on a grid
        var transformResult = TransformationAPI.getInstance().worldToGridSpace(
            new Vec3(x, y, z), level);
            
        if (transformResult.isPresent()) {
            // Position is on a grid - use visual world coordinates for distance
            var result = transformResult.get();
            Vec3 gridWorldPos = new Vec3(result.gridSpaceVec.x, result.gridSpaceVec.y, result.gridSpaceVec.z);
            return player.distanceToSqr(gridWorldPos.x, gridWorldPos.y, gridWorldPos.z);
        } else {
            // Regular world position
            return player.distanceToSqr(x, y, z);
        }
    }

    /**
     * Get world coordinates for a block position, accounting for grids.
     * Returns visual position where player sees the block.
     */
    public static Vec3 getWorldCoordinates(Level level, BlockPos pos) {
        var transformResult = TransformationAPI.getInstance().worldToGridSpace(
            new Vec3(pos.getX(), pos.getY(), pos.getZ()), level);
            
        if (transformResult.isPresent()) {
            var result = transformResult.get();
            return new Vec3(result.gridSpaceVec.x, result.gridSpaceVec.y, result.gridSpaceVec.z);
        } else {
            return Vec3.atCenterOf(pos);
        }
    }
}