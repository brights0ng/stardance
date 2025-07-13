package net.starlight.stardance.debug;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.render.DebugRenderer;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;
import net.starlight.stardance.utils.TransformationAPI;

import java.util.Optional;

/**
 * Visual debugging for raycast transformation using DebugRenderer.
 * Shows rays, hit points, and coordinate transformations in 3D space.
 */
public class VisualRaycastDebugger implements ILoggingControl {

    // Debug colors (ARGB format)
    private static final int COLOR_RAY_ORIGINAL = 0xFF00FF00;    // Green - Original ray
    private static final int COLOR_RAY_TRANSFORMED = 0xFF0088FF; // Blue - Transformed ray  
    private static final int COLOR_HIT_WORLD = 0xFFFFFF00;       // Yellow - World hit point
    private static final int COLOR_HIT_GRIDSPACE = 0xFFFF8800;   // Orange - GridSpace hit point
    private static final int COLOR_GRID_BOUNDS = 0xFF8800FF;     // Purple - Grid bounds
    private static final int COLOR_ERROR_LINE = 0xFFFF0000;      // Red - Transformation error
    
    // Debug settings
    private static final double RAY_LENGTH = 64.0;
    private static final int VISUAL_DURATION = 200; // 10 seconds at 20 TPS
    private static final float LINE_WIDTH = 0.03f;
    private static final float MARKER_SIZE = 0.2f;

    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return true;
    }

    /**
     * Visualizes the player's current raycast with full transformation pipeline.
     */
    public static void visualizePlayerRaycast(Player player) {
        if (player == null || player.level().isClientSide) {
            return;
        }

        try {
            SLogger.log("VisualRaycastDebugger", "=== VISUALIZING RAYCAST TRANSFORMATION ===");
            
            // Clear previous debug visuals
            DebugRenderer.clear();
            
            // Get player's eye position and look direction
            Vec3 eyePos = player.getEyePosition();
            Vec3 lookDir = player.getLookAngle().normalize();
            Vec3 rayEnd = eyePos.add(lookDir.scale(RAY_LENGTH));
            
            // 1. Draw the original ray
            DebugRenderer.addRay(eyePos, lookDir, RAY_LENGTH, COLOR_RAY_ORIGINAL, LINE_WIDTH, VISUAL_DURATION);
            DebugRenderer.addPoint(eyePos, COLOR_RAY_ORIGINAL, MARKER_SIZE, VISUAL_DURATION);
            
            SLogger.log("VisualRaycastDebugger", "Original ray: " + eyePos + " → " + rayEnd);
            
            // 2. Perform raycast and get hit result
            HitResult hitResult = player.pick(RAY_LENGTH, 0.0f, false);
            
            if (hitResult.getType() == HitResult.Type.MISS) {
                SLogger.log("VisualRaycastDebugger", "Ray missed - no hit within " + RAY_LENGTH + " blocks");
                return;
            }
            
            Vec3 worldHitPos = hitResult.getLocation();
            
            // 3. Draw world hit point
            DebugRenderer.addPoint(worldHitPos, COLOR_HIT_WORLD, MARKER_SIZE * 1.5f, VISUAL_DURATION);
            DebugRenderer.addCrosshair(worldHitPos, COLOR_HIT_WORLD, MARKER_SIZE, LINE_WIDTH, VISUAL_DURATION);
            
            SLogger.log("VisualRaycastDebugger", "World hit: " + worldHitPos);
            
            // 4. Check for grid transformation
            Optional<TransformationAPI.GridSpaceTransformResult> gridTransform = 
                TransformationAPI.getInstance().worldToGridSpace(worldHitPos, player.level());
                
            if (gridTransform.isPresent()) {
                TransformationAPI.GridSpaceTransformResult transform = gridTransform.get();
                LocalGrid grid = transform.grid;
                
                SLogger.log("VisualRaycastDebugger", "Grid hit detected: " + grid.getGridId());
                
                // 5. Visualize the grid bounds
                visualizeGridBounds(grid);
                
                // 6. Show GridSpace coordinates (transform back to world for visualization)
                Vec3 gridSpaceWorldPos = TransformationAPI.getInstance().gridSpaceToWorld(transform.gridSpaceVec, grid);
                
                DebugRenderer.addPoint(gridSpaceWorldPos, COLOR_HIT_GRIDSPACE, MARKER_SIZE * 2.0f, VISUAL_DURATION);
                DebugRenderer.addCrosshair(gridSpaceWorldPos, COLOR_HIT_GRIDSPACE, MARKER_SIZE * 1.2f, LINE_WIDTH, VISUAL_DURATION);
                
                SLogger.log("VisualRaycastDebugger", "GridSpace hit (back to world): " + gridSpaceWorldPos);
                
                // 7. Draw transformation error line if there's a difference
                double error = worldHitPos.distanceTo(gridSpaceWorldPos);
                if (error > 0.01) { // Only show if error > 1cm
                    DebugRenderer.addLine(worldHitPos, gridSpaceWorldPos, COLOR_ERROR_LINE, LINE_WIDTH * 2, VISUAL_DURATION);
                    SLogger.log("VisualRaycastDebugger", "Transformation error: " + String.format("%.4f blocks", error));
                }
                
                // 8. Draw transformed ray (from eye to GridSpace position)
                DebugRenderer.addLine(eyePos, gridSpaceWorldPos, COLOR_RAY_TRANSFORMED, LINE_WIDTH, VISUAL_DURATION);
                
                // 9. Add text information at hit point
                addHitPointInfo(worldHitPos, transform, error);
                
            } else {
                SLogger.log("VisualRaycastDebugger", "No grid intersection found");
            }
            
            SLogger.log("VisualRaycastDebugger", "=== VISUALIZATION COMPLETE ===");
            
        } catch (Exception e) {
            SLogger.log("VisualRaycastDebugger", "Error in raycast visualization: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Visualizes the bounding box of the grid for context.
     */
    private static void visualizeGridBounds(LocalGrid grid) {
        try {
            javax.vecmath.Vector3f minAabb = new javax.vecmath.Vector3f();
            javax.vecmath.Vector3f maxAabb = new javax.vecmath.Vector3f();
            grid.getAABB(minAabb, maxAabb);
            
            net.minecraft.world.phys.AABB gridBox = new net.minecraft.world.phys.AABB(
                minAabb.x, minAabb.y, minAabb.z,
                maxAabb.x, maxAabb.y, maxAabb.z
            );
            
            DebugRenderer.addBox(gridBox, COLOR_GRID_BOUNDS, LINE_WIDTH * 0.5f, VISUAL_DURATION);
            
            SLogger.log("VisualRaycastDebugger", "Grid bounds: " + 
                String.format("(%.1f,%.1f,%.1f) to (%.1f,%.1f,%.1f)", 
                    minAabb.x, minAabb.y, minAabb.z, maxAabb.x, maxAabb.y, maxAabb.z));
                    
        } catch (Exception e) {
            SLogger.log("VisualRaycastDebugger", "Could not visualize grid bounds: " + e.getMessage());
        }
    }

    /**
     * Adds detailed information markers at the hit point.
     */
    private static void addHitPointInfo(Vec3 worldHitPos, TransformationAPI.GridSpaceTransformResult transform, double error) {
        // Create a small "info cluster" around the hit point
        Vec3 offset1 = worldHitPos.add(0.5, 0.5, 0);
        Vec3 offset2 = worldHitPos.add(-0.5, 0.5, 0);
        Vec3 offset3 = worldHitPos.add(0, 0.5, 0.5);
        
        // Different colored points for different info
        DebugRenderer.addPoint(offset1, 0xFF88FF88, MARKER_SIZE * 0.8f, VISUAL_DURATION); // Grid ID
        DebugRenderer.addPoint(offset2, 0xFFFF8888, MARKER_SIZE * 0.8f, VISUAL_DURATION); // GridSpace pos
        DebugRenderer.addPoint(offset3, 0xFF8888FF, MARKER_SIZE * 0.8f, VISUAL_DURATION); // Error indicator
        
        // Connect info points to main hit point
        DebugRenderer.addLine(worldHitPos, offset1, 0xFF888888, LINE_WIDTH * 0.3f, VISUAL_DURATION);
        DebugRenderer.addLine(worldHitPos, offset2, 0xFF888888, LINE_WIDTH * 0.3f, VISUAL_DURATION);
        DebugRenderer.addLine(worldHitPos, offset3, 0xFF888888, LINE_WIDTH * 0.3f, VISUAL_DURATION);
    }

    /**
     * Quick visualization for debugging specific coordinates.
     */
    public static void visualizeCoordinateTransformation(Vec3 worldPos, Entity context) {
        if (context == null || context.level().isClientSide) {
            return;
        }

        try {
            SLogger.log("VisualRaycastDebugger", "Visualizing coordinate transformation for: " + worldPos);
            
            // Clear previous visuals
            DebugRenderer.clear();
            
            // Mark the input position
            DebugRenderer.addPoint(worldPos, COLOR_HIT_WORLD, MARKER_SIZE * 2, VISUAL_DURATION);
            DebugRenderer.addCrosshair(worldPos, COLOR_HIT_WORLD, MARKER_SIZE * 1.5f, LINE_WIDTH, VISUAL_DURATION);
            
            // Test transformation
            Optional<TransformationAPI.GridSpaceTransformResult> result = 
                TransformationAPI.getInstance().worldToGridSpace(worldPos, context.level());
                
            if (result.isPresent()) {
                TransformationAPI.GridSpaceTransformResult transform = result.get();
                
                // Show GridSpace position (transformed back to world)
                Vec3 gridSpaceWorldPos = TransformationAPI.getInstance().gridSpaceToWorld(transform.gridSpaceVec, transform.grid);
                
                DebugRenderer.addPoint(gridSpaceWorldPos, COLOR_HIT_GRIDSPACE, MARKER_SIZE * 2, VISUAL_DURATION);
                DebugRenderer.addCrosshair(gridSpaceWorldPos, COLOR_HIT_GRIDSPACE, MARKER_SIZE * 1.5f, LINE_WIDTH, VISUAL_DURATION);
                
                // Show error if significant
                double error = worldPos.distanceTo(gridSpaceWorldPos);
                if (error > 0.01) {
                    DebugRenderer.addLine(worldPos, gridSpaceWorldPos, COLOR_ERROR_LINE, LINE_WIDTH * 2, VISUAL_DURATION);
                }
                
                // Visualize grid bounds
                visualizeGridBounds(transform.grid);
                
                SLogger.log("VisualRaycastDebugger", "Transformation error: " + String.format("%.6f blocks", error));
            } else {
                SLogger.log("VisualRaycastDebugger", "No grid found at position: " + worldPos);
            }
            
        } catch (Exception e) {
            SLogger.log("VisualRaycastDebugger", "Error in coordinate visualization: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Creates a visual trajectory showing the complete transformation pipeline.
     */
    public static void visualizeTransformationPipeline(Player player) {
        if (player == null) return;
        
        try {
            // Clear previous visuals
            DebugRenderer.clear();
            
            Vec3 eyePos = player.getEyePosition();
            Vec3 lookDir = player.getLookAngle().normalize();
            
            // Create a series of points along the ray to show transformation at each point
            int numPoints = 20;
            for (int i = 0; i < numPoints; i++) {
                double distance = (i + 1) * (RAY_LENGTH / numPoints);
                Vec3 rayPoint = eyePos.add(lookDir.scale(distance));
                
                // Test transformation at this point
                Optional<TransformationAPI.GridSpaceTransformResult> result = 
                    TransformationAPI.getInstance().worldToGridSpace(rayPoint, player.level());
                    
                if (result.isPresent()) {
                    // This point hits a grid - mark it specially
                    DebugRenderer.addPoint(rayPoint, COLOR_HIT_GRIDSPACE, MARKER_SIZE * 1.2f, VISUAL_DURATION);
                } else {
                    // Regular world point
                    DebugRenderer.addPoint(rayPoint, COLOR_RAY_ORIGINAL, MARKER_SIZE * 0.5f, VISUAL_DURATION);
                }
            }
            
            SLogger.log("VisualRaycastDebugger", "Transformation pipeline visualized with " + numPoints + " test points");
            
        } catch (Exception e) {
            SLogger.log("VisualRaycastDebugger", "Error in pipeline visualization: " + e.getMessage());
            e.printStackTrace();
        }
    }
}