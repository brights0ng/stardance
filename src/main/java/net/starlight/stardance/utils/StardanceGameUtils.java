package net.starlight.stardance.utils;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.starlight.stardance.utils.TransformationAPI.GridSpaceTransformResult;

import java.util.Optional;

/**
 * VS2-Style comprehensive distance utilities for Stardance.
 * Replaces all distance calculations to account for moving grids.
 * 
 * This is Stardance's equivalent of VS2's VSGameUtilsKt.
 */
public class StardanceGameUtils implements ILoggingControl {

    @Override
    public boolean stardance$isChatLoggingEnabled() { return false; }
    @Override
    public boolean stardance$isConsoleLoggingEnabled() { return false; }

    // ===============================================
    // CORE DISTANCE FUNCTIONS (VS2-Style)
    // ===============================================

    /**
     * VS2-Style: Calculate squared distance from entity to coordinates, including grids.
     * Replaces Entity.distanceToSqr(double, double, double).
     */
    public static double squaredDistanceToInclGrids(Entity entity, double x, double y, double z) {
        Level level = entity.level();
        
        // Check if target coordinates are on a grid
        Optional<GridSpaceTransformResult> targetTransform = TransformationAPI.getInstance()
            .worldToGridSpace(new Vec3 (x, y, z), level);
            
        // Check if entity is on a grid
        Optional<GridSpaceTransformResult> entityTransform = TransformationAPI.getInstance()
            .worldToGridSpace(new Vec3(entity.getX(), entity.getY(), entity.getZ()), level);

        Vec3 entityPos = entity.position();
        Vec3 targetPos = new Vec3(x, y, z);
        
        // If entity is on a grid, use its visual world position
        if (entityTransform.isPresent()) {
            GridSpaceTransformResult result = entityTransform.get();
            entityPos = new Vec3(result.gridSpaceVec.x, result.gridSpaceVec.y, result.gridSpaceVec.z);
        }
        
        // If target is on a grid, use its visual world position  
        if (targetTransform.isPresent()) {
            GridSpaceTransformResult result = targetTransform.get();
            targetPos = new Vec3(result.gridSpaceVec.x, result.gridSpaceVec.y, result.gridSpaceVec.z);
        }
        
        return entityPos.distanceToSqr(targetPos);
    }

    /**
     * VS2-Style: Calculate squared distance from entity to Vec3, including grids.
     * Replaces Entity.distanceToSqr(Vec3).
     */
    public static double squaredDistanceToInclGrids(Entity entity, Vec3 targetVec) {
        return squaredDistanceToInclGrids(entity, targetVec.x, targetVec.y, targetVec.z);
    }

    /**
     * VS2-Style: Calculate squared distance between two coordinate points, including grids.
     * Used for general distance calculations in the world.
     */
    public static double squaredDistanceBetweenInclGrids(Level level, double x1, double y1, double z1, 
                                                        double x2, double y2, double z2) {
        // Check if either position is on a grid
        Optional<GridSpaceTransformResult> pos1Transform = TransformationAPI.getInstance()
            .worldToGridSpace(new Vec3 (x1, y1, z1), level);
            
        Optional<GridSpaceTransformResult> pos2Transform = TransformationAPI.getInstance()
            .worldToGridSpace(new Vec3 (x2, y2, z2), level);

        Vec3 finalPos1 = new Vec3(x1, y1, z1);
        Vec3 finalPos2 = new Vec3(x2, y2, z2);
        
        // Use visual world positions if on grids
        if (pos1Transform.isPresent()) {
            GridSpaceTransformResult result = pos1Transform.get();
            finalPos1 = new Vec3(result.gridSpaceVec.x, result.gridSpaceVec.y, result.gridSpaceVec.z);
        }
        
        if (pos2Transform.isPresent()) {
            GridSpaceTransformResult result = pos2Transform.get();
            finalPos2 = new Vec3(result.gridSpaceVec.x, result.gridSpaceVec.y, result.gridSpaceVec.z);
        }
        
        return finalPos1.distanceToSqr(finalPos2);
    }

    /**
     * VS2-Style: Calculate squared distance between Vec3 positions, including grids.
     */
    public static double squaredDistanceBetweenInclGrids(Level level, Vec3 pos1, Vec3 pos2) {
        return squaredDistanceBetweenInclGrids(level, pos1.x, pos1.y, pos1.z, pos2.x, pos2.y, pos2.z);
    }

    /**
     * VS2-Style: Get world coordinates for a block position, accounting for grids.
     * Returns the visual position where players see the block.
     */
    public static Vec3 getWorldCoordinates(Level level, BlockPos pos, Vec3 blockCenter) {
        Optional<GridSpaceTransformResult> transform = TransformationAPI.getInstance()
            .worldToGridSpace(new Vec3 (pos.getX(), pos.getY(), pos.getZ()), level);
            
        if (transform.isPresent()) {
            GridSpaceTransformResult result = transform.get();
            // Return visual world position of the grid block
            return new Vec3(result.gridSpaceVec.x + 0.5, result.gridSpaceVec.y + 0.5, result.gridSpaceVec.z + 0.5);
        } else {
            // Regular world block
            return Vec3.atCenterOf(pos);
        }
    }

    // ===============================================
    // PLAYER-SPECIFIC UTILITIES
    // ===============================================

    /**
     * Get the server-side eye position of a player, accounting for grids.
     * Similar to VS2's EntityDragger.serversideEyePosition().
     */
    public static Vec3 getServerSideEyePosition(Player player) {
        Vec3 eyePos = player.getEyePosition();
        
        // Check if player is on a grid
        Optional<GridSpaceTransformResult> transform = TransformationAPI.getInstance()
            .worldToGridSpace(new Vec3 (eyePos.x, eyePos.y, eyePos.z), player.level());
            
        if (transform.isPresent()) {
            GridSpaceTransformResult result = transform.get();
            return new Vec3(result.gridSpaceVec.x, result.gridSpaceVec.y, result.gridSpaceVec.z);
        }
        
        return eyePos;
    }

    /**
     * Check if a position is manageable by any grid (for optimization).
     */
    public static boolean isPositionOnAnyGrid(Level level, double x, double y, double z) {
        return TransformationAPI.getInstance()
            .worldToGridSpace(new Vec3(x, y, z), level)
            .isPresent();
    }
}