package net.starlight.stardance.render;

import com.bulletphysics.linearmath.Transform;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.gridspace.GridSpaceRegion;
import net.starlight.stardance.gridspace.GridSpaceBlockManager;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;
import org.joml.Vector3f;
import org.joml.Quaternionf;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VS2-style renderer that projects GridSpace blocks into world space using rigid body transforms.
 * Now includes smooth interpolation for 60+ FPS rendering instead of choppy 20 TPS movement.
 *
 * Key principles from VS2:
 * 1. Blocks stay in GridSpace (never moved)
 * 2. Rendering applies real-time coordinate transformation
 * 3. Matrix transformation uses rigid body's current transform
 * 4. Client-side projection for smooth interpolation
 * 5. Interpolation between physics ticks for smooth movement
 */
public class GridSpaceRenderer implements ILoggingControl {

    // ----------------------------------------------
    // CONSTANTS
    // ----------------------------------------------

    /** Maximum render distance for grid blocks */
    private static final double MAX_RENDER_DISTANCE = 256.0;

    /** Cache time for transform matrices (in milliseconds) - matches server tick rate */
    private static final long TRANSFORM_CACHE_TIME = 50; // 20 TPS = 50ms per tick

    // ----------------------------------------------
    // CACHE MANAGEMENT
    // ----------------------------------------------

    /** Cached transform matrices per grid with interpolation support */
    private final ConcurrentHashMap<LocalGrid, CachedTransform> transformCache = new ConcurrentHashMap<>();

    /** Last render frame time for cache invalidation */
    private long lastRenderTime = 0;

    // ----------------------------------------------
    // RENDERING COMPONENTS
    // ----------------------------------------------

    private final MinecraftClient client;
    private final BlockRenderManager blockRenderer;

    // ----------------------------------------------
    // CONSTRUCTOR
    // ----------------------------------------------

    public GridSpaceRenderer() {
        this.client = MinecraftClient.getInstance();
        this.blockRenderer = client.getBlockRenderManager();
    }

    // ----------------------------------------------
    // MAIN RENDERING METHODS
    // ----------------------------------------------

    /**
     * Renders all active grids by projecting their GridSpace blocks into world space.
     * This is the main entry point called during world rendering.
     */
    public void renderGrids(Set<LocalGrid> grids, MatrixStack matrixStack,
                               VertexConsumerProvider vertexConsumers, Vec3d playerPos, float tickDelta) {

        if (grids == null || grids.isEmpty()) {
            return;
        }

        SLogger.log(this, "=== Rendering " + grids.size() + " grids with interpolation (tickDelta: " + tickDelta + ") ===");

        long currentTime = System.currentTimeMillis();

        // Clean up old cache entries if this is a new frame
        if (currentTime - lastRenderTime > 100) { // Clean every 100ms
            cleanupTransformCache(grids);
        }
        lastRenderTime = currentTime;

        // Render each grid with interpolation
        for (LocalGrid grid : grids) {
            if (grid != null && !grid.isDestroyed()) {
                renderGrid(grid, matrixStack, vertexConsumers, playerPos, tickDelta);
            }
        }

        SLogger.log(this, "All grids rendered successfully");
    }

    /**
     * Renders a single grid with interpolated transforms.
     *
     * @param grid The grid to render
     * @param matrixStack Matrix stack for transformations
     * @param vertexConsumers Vertex consumers for rendering
     * @param playerPos Player position for optimization
     * @param tickDelta Interpolation factor between physics ticks (0.0-1.0)
     */
    public void renderGrid(LocalGrid grid, MatrixStack matrixStack, VertexConsumerProvider vertexConsumers,
                           Vec3d playerPos, float tickDelta) {
        SLogger.log(this, "=== Rendering individual grid with interpolation: " + grid.getGridId() + " ===");

        if (grid.isDestroyed()) {
            SLogger.log(this, "Grid is destroyed, skipping");
            return;
        }

        SLogger.log(this, "Grid block count: " + grid.getBlocks().size());

        try {
            // Get or compute transform matrix for this grid
            CachedTransform cachedTransform = getOrComputeTransform(grid);
            if (cachedTransform == null) {
                SLogger.log(this, "Failed to get transform for grid, skipping");
                return;
            }

            SLogger.log(this, "Transform computed successfully");

            // Get GridSpace components
            GridSpaceRegion gridSpaceRegion = grid.getGridSpaceRegion();
            GridSpaceBlockManager blockManager = grid.getGridSpaceBlockManager();

            if (gridSpaceRegion == null) {
                SLogger.log(this, "GridSpace region is null!");
                return;
            }

            if (blockManager == null) {
                SLogger.log(this, "GridSpace block manager is null!");
                return;
            }

            SLogger.log(this, "GridSpace components are valid, proceeding to render blocks");

            // Render each block in GridSpace with interpolated transformation applied
            renderGridSpaceBlocks(grid, gridSpaceRegion, cachedTransform, matrixStack, vertexConsumers, playerPos, tickDelta);

            SLogger.log(this, "Grid rendering completed successfully");

        } catch (Exception e) {
            SLogger.log(this, "Error rendering grid " + grid.getGridId() + ": " + e.getMessage());
            e.printStackTrace(); // Print full stack trace for debugging
        }
    }

    // ----------------------------------------------
    // TRANSFORM COMPUTATION WITH INTERPOLATION
    // ----------------------------------------------

    /**
     * Gets or computes the transform for a grid with interpolation support.
     * Stores both previous and current transforms for smooth interpolation.
     */
    private CachedTransform getOrComputeTransform(LocalGrid grid) {
        SLogger.log(this, "=== Computing transform for grid: " + grid.getGridId() + " ===");

        // Check cache first
        CachedTransform cached = transformCache.get(grid);
        long currentTime = System.currentTimeMillis();

        if (cached == null) {
            // Create new cached transform
            Transform rigidBodyTransform = new Transform();
            grid.getCurrentTransform(rigidBodyTransform);

            cached = new CachedTransform(rigidBodyTransform, currentTime);
            transformCache.put(grid, cached);

            SLogger.log(this, "Created new cached transform");
            return cached;
        }

        // Check if we need to update (new physics tick)
        if ((currentTime - cached.computeTime) >= TRANSFORM_CACHE_TIME) {
            // Get new transform
            Transform rigidBodyTransform = new Transform();
            grid.getCurrentTransform(rigidBodyTransform);

            // Update the cached transform (shifts current to previous)
            cached.updateTransform(rigidBodyTransform, currentTime);

            SLogger.log(this, "Updated cached transform with interpolation data");
        }

        return cached;
    }

    // ----------------------------------------------
    // BLOCK RENDERING WITH INTERPOLATION
    // ----------------------------------------------

    /**
     * Renders blocks from GridSpace with interpolated transformation applied.
     * This is where the "projection" magic happens, just like VS2.
     *
     * FLOW: GridSpace coords → Grid-local coords → Centroid-relative coords → Interpolated transform → World coords
     */
    private void renderGridSpaceBlocks(LocalGrid grid, GridSpaceRegion gridSpaceRegion,
                                       CachedTransform transform, MatrixStack matrixStack,
                                       VertexConsumerProvider vertexConsumers, Vec3d playerPos, float tickDelta) {

        SLogger.log(this, "=== Rendering GridSpace blocks with interpolation (tickDelta: " + tickDelta + ") ===");

        // Get the GridSpace region origin and calculate center
        BlockPos gridSpaceOrigin = gridSpaceRegion.getRegionOrigin();
        SLogger.log(this, "GridSpace region origin: " + gridSpaceOrigin);

        // Calculate the center of the GridSpace region (where grid-local 0,0,0 maps to)
        BlockPos gridSpaceCenter = new BlockPos(
                gridSpaceOrigin.getX() + 512,  // Half of 1024 region size
                gridSpaceOrigin.getY() + 512,
                gridSpaceOrigin.getZ() + 512
        );
        SLogger.log(this, "GridSpace region center: " + gridSpaceCenter);

        // Get the centroid from physics (center of mass)
        javax.vecmath.Vector3f centroid = grid.getCentroid();
        SLogger.log(this, "Grid centroid: " + centroid.x + ", " + centroid.y + ", " + centroid.z);

        // Get all blocks in the grid
        var blocks = grid.getBlocks();
        SLogger.log(this, "Processing " + blocks.size() + " blocks for rendering");

        int renderedBlocks = 0;
        int culledBlocks = 0;

        for (var entry : blocks.entrySet()) {
            BlockPos gridLocalPos = entry.getKey();
            var localBlock = entry.getValue();
            BlockState blockState = localBlock.getState();

            SLogger.log(this, "Processing block at grid-local pos: " + gridLocalPos +
                    ", block: " + blockState.getBlock().getName().getString());

            // Step 1: Convert to GridSpace position (where block actually exists)
            BlockPos gridSpacePos = grid.gridLocalToGridSpace(gridLocalPos);
            SLogger.log(this, "GridSpace position: " + gridSpacePos);

            // Step 2: Convert GridSpace position back to grid-local coordinates
            // This gives us the position relative to grid-local (0,0,0)
            Vector3f gridLocalPosition = new Vector3f(
                    gridSpacePos.getX() - gridSpaceCenter.getX(),
                    gridSpacePos.getY() - gridSpaceCenter.getY(),
                    gridSpacePos.getZ() - gridSpaceCenter.getZ()
            );
            SLogger.log(this, "Grid-local position: " + gridLocalPosition.x + ", " + gridLocalPosition.y + ", " + gridLocalPosition.z);

            // Step 3: Position relative to centroid (matches the old GridRenderer!)
            // This matches exactly what the old system did: pos - centroid
            Vector3f centroidRelativePos = new Vector3f(
                    gridLocalPosition.x - centroid.x,
                    gridLocalPosition.y - centroid.y,
                    gridLocalPosition.z - centroid.z
            );
            SLogger.log(this, "Centroid-relative position: " + centroidRelativePos.x + ", " + centroidRelativePos.y + ", " + centroidRelativePos.z);

            // Step 4: Apply interpolated rigid body transform
            Vector3f worldPos = applyRigidBodyTransformWithInterpolation(centroidRelativePos, transform, tickDelta);

            // Step 5: Center the block properly (Minecraft coordinate system)
            // Block at (1,1,1) should render at (1.5,1.5,1.5)
            worldPos.x += 0.5f;
            worldPos.y += 0.5f;
            worldPos.z += 0.5f;

            SLogger.log(this, "Final world position (centered): " + worldPos.x + ", " + worldPos.y + ", " + worldPos.z);

            // Cull blocks that are too far away
            double distance = playerPos.distanceTo(new Vec3d(worldPos.x, worldPos.y, worldPos.z));
            SLogger.log(this, "Distance to player: " + distance);

            if (distance > MAX_RENDER_DISTANCE) {
                SLogger.log(this, "Block culled due to distance (" + distance + " > " + MAX_RENDER_DISTANCE + ")");
                culledBlocks++;
                continue;
            }

            // Render the block at its interpolated world position
            SLogger.log(this, "Rendering block at world position: " + worldPos.x + ", " + worldPos.y + ", " + worldPos.z);
            renderBlockAtWorldPosition(blockState, worldPos, transform, matrixStack, vertexConsumers, tickDelta);
            renderedBlocks++;
        }

        SLogger.log(this, "Interpolated rendering complete - rendered: " + renderedBlocks + ", culled: " + culledBlocks);
    }

    /**
     * Applies the rigid body transform with smooth interpolation between ticks.
     * This creates smooth movement at high framerates instead of choppy 20 TPS movement.
     */
    private Vector3f applyRigidBodyTransformWithInterpolation(Vector3f centroidRelativePos, CachedTransform transform, float tickDelta) {

        // If this is the first frame, no interpolation needed
        if (transform.needsInitialization) {
            return applyRigidBodyTransformStatic(centroidRelativePos, transform.currentBulletTransform);
        }

        // Interpolate position
        javax.vecmath.Vector3f prevPos = new javax.vecmath.Vector3f();
        javax.vecmath.Vector3f currPos = new javax.vecmath.Vector3f();
        transform.previousBulletTransform.origin.get(prevPos);
        transform.currentBulletTransform.origin.get(currPos);

        Vector3f interpolatedPos = new Vector3f(
                prevPos.x + (currPos.x - prevPos.x) * tickDelta,
                prevPos.y + (currPos.y - prevPos.y) * tickDelta,
                prevPos.z + (currPos.z - prevPos.z) * tickDelta
        );

        // Interpolate rotation
        javax.vecmath.Quat4f prevRot = new javax.vecmath.Quat4f();
        javax.vecmath.Quat4f currRot = new javax.vecmath.Quat4f();
        transform.previousBulletTransform.getRotation(prevRot);
        transform.currentBulletTransform.getRotation(currRot);

        javax.vecmath.Quat4f interpolatedRot = new javax.vecmath.Quat4f();
        interpolatedRot.interpolate(prevRot, currRot, tickDelta);

        // Create interpolated transform
        Transform interpolatedTransform = new Transform();
        interpolatedTransform.origin.set(interpolatedPos.x, interpolatedPos.y, interpolatedPos.z);
        interpolatedTransform.setRotation(interpolatedRot);

        // Apply the interpolated transform
        return applyRigidBodyTransformStatic(centroidRelativePos, interpolatedTransform);
    }

    /**
     * Static version of transform application (no interpolation).
     * Handles both rotation around origin and translation to final position.
     */
    private Vector3f applyRigidBodyTransformStatic(Vector3f centroidRelativePos, Transform rigidBodyTransform) {
        // Get the rigid body transform components
        javax.vecmath.Vector3f rigidBodyPos = new javax.vecmath.Vector3f();
        rigidBodyTransform.origin.get(rigidBodyPos);

        // Apply rotation to the centroid-relative position
        javax.vecmath.Vector3f rotatedPos = new javax.vecmath.Vector3f(centroidRelativePos.x, centroidRelativePos.y, centroidRelativePos.z);
        rigidBodyTransform.basis.transform(rotatedPos);

        // Translate to final world position
        Vector3f finalPos = new Vector3f(
                rotatedPos.x + rigidBodyPos.x,
                rotatedPos.y + rigidBodyPos.y,
                rotatedPos.z + rigidBodyPos.z
        );

        return finalPos;
    }

    /**
     * Renders a single block at the specified world position with rotation applied.
     * This is where we actually draw the block to the screen.
     */
    private void renderBlockAtWorldPosition(BlockState blockState, Vector3f worldPos, CachedTransform transform,
                                            MatrixStack matrixStack, VertexConsumerProvider vertexConsumers, float tickDelta) {

        matrixStack.push();

        try {
            // Translate to world position (now properly centered)
            matrixStack.translate(worldPos.x, worldPos.y, worldPos.z);

            // Apply interpolated rotation from rigid body transform
            applyInterpolatedRotationToMatrixStack(matrixStack, transform, tickDelta);

            // Calculate correct BlockPos for lighting from block center
            // Since worldPos is now centered (1.5, 1.5, 1.5), we need to get the block position (1, 1, 1)
            BlockPos lightingPos = new BlockPos(
                    (int) Math.floor(worldPos.x),
                    (int) Math.floor(worldPos.y),
                    (int) Math.floor(worldPos.z)
            );

            SLogger.log(this, "Lighting calculated from block position: " + lightingPos);

            // Render the block using Minecraft's block renderer with correct lighting position
            blockRenderer.renderBlock(
                    blockState,
                    lightingPos, // Use the proper block position for lighting
                    client.world,
                    matrixStack,
                    vertexConsumers.getBuffer(net.minecraft.client.render.RenderLayer.getSolid()),
                    false, // No overlay
                    client.world.random
            );

        } catch (Exception e) {
            SLogger.log(this, "Error rendering block at " + worldPos + ": " + e.getMessage());
        } finally {
            matrixStack.pop();
        }
    }

    /**
     * Applies interpolated rotation from a JBullet Transform to a Minecraft MatrixStack.
     * This makes the block rotate smoothly with the rigid body.
     */
    private void applyInterpolatedRotationToMatrixStack(MatrixStack matrixStack, CachedTransform transform, float tickDelta) {
        if (transform.needsInitialization) {
            // No interpolation for first frame
            applyRotationToMatrixStack(matrixStack, transform.currentBulletTransform);
            return;
        }

        // Get interpolated rotation
        javax.vecmath.Quat4f prevRot = new javax.vecmath.Quat4f();
        javax.vecmath.Quat4f currRot = new javax.vecmath.Quat4f();
        transform.previousBulletTransform.getRotation(prevRot);
        transform.currentBulletTransform.getRotation(currRot);

        javax.vecmath.Quat4f interpolatedRot = new javax.vecmath.Quat4f();
        interpolatedRot.interpolate(prevRot, currRot, tickDelta);

        // Convert to JOML quaternion for Minecraft
        Quaternionf mcQuat = new Quaternionf(interpolatedRot.x, interpolatedRot.y, interpolatedRot.z, interpolatedRot.w);
        matrixStack.multiply(mcQuat);
    }

    /**
     * Applies rotation from a JBullet Transform to a Minecraft MatrixStack (non-interpolated).
     */
    private void applyRotationToMatrixStack(MatrixStack matrixStack, Transform bulletTransform) {
        javax.vecmath.Quat4f bulletRot = new javax.vecmath.Quat4f();
        bulletTransform.getRotation(bulletRot);

        Quaternionf mcQuat = new Quaternionf(bulletRot.x, bulletRot.y, bulletRot.z, bulletRot.w);
        matrixStack.multiply(mcQuat);
    }

    // ----------------------------------------------
    // CACHE MANAGEMENT
    // ----------------------------------------------

    /**
     * Cleans up transform cache entries for grids that no longer exist.
     */
    private void cleanupTransformCache(Set<LocalGrid> activeGrids) {
        transformCache.entrySet().removeIf(entry -> {
            LocalGrid grid = entry.getKey();
            return grid == null || grid.isDestroyed() || !activeGrids.contains(grid);
        });
    }

    /**
     * Invalidates the transform cache for all grids.
     * Call this when the world changes or when major physics updates occur.
     */
    public void invalidateTransformCache() {
        transformCache.clear();
    }

    // ----------------------------------------------
    // CLEANUP
    // ----------------------------------------------

    /**
     * Cleans up resources when shutting down.
     */
    public void cleanup() {
        transformCache.clear();
    }

    // ----------------------------------------------
    // LOGGING CONTROL
    // ----------------------------------------------

    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return true; // Enable logging for debugging
    }

    // ----------------------------------------------
    // HELPER CLASSES
    // ----------------------------------------------

    /**
     * Cached transform data with interpolation support.
     * Stores both previous and current transforms for smooth movement.
     */
    private static class CachedTransform {
        final Transform previousBulletTransform;
        final Transform currentBulletTransform;
        long computeTime;
        boolean needsInitialization;

        CachedTransform(Transform bulletTransform, long computeTime) {
            this.previousBulletTransform = new Transform(bulletTransform); // Initialize both to same transform
            this.currentBulletTransform = new Transform(bulletTransform);
            this.computeTime = computeTime;
            this.needsInitialization = true;
        }

        /**
         * Updates with a new transform, shifting current to previous.
         */
        void updateTransform(Transform newTransform, long newComputeTime) {
            // Shift current to previous
            this.previousBulletTransform.set(this.currentBulletTransform);
            // Update current
            this.currentBulletTransform.set(newTransform);
            this.computeTime = newComputeTime;
            this.needsInitialization = false;
        }
    }
}