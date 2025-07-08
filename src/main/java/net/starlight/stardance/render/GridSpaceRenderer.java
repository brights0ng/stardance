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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VS2-style renderer that projects GridSpace blocks into world space using rigid body transforms.
 * This replaces the old approach of rendering from LocalGrid's block map.
 *
 * Key principles from VS2:
 * 1. Blocks stay in GridSpace (never moved)
 * 2. Rendering applies real-time coordinate transformation
 * 3. Matrix transformation uses rigid body's current transform
 * 4. Client-side projection for smooth interpolation
 */
public class GridSpaceRenderer implements ILoggingControl {

    // ----------------------------------------------
    // CONSTANTS
    // ----------------------------------------------

    /** Maximum render distance for grid blocks */
    private static final double MAX_RENDER_DISTANCE = 256.0;

    /** Cache time for transform matrices (in milliseconds) */
    private static final long TRANSFORM_CACHE_TIME = 16; // ~60 FPS

    // ----------------------------------------------
    // CACHE MANAGEMENT
    // ----------------------------------------------

    /** Cached transform matrices per grid */
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
    // MAIN RENDERING METHOD
    // ----------------------------------------------

    /**
     * Renders all active grids by projecting their GridSpace blocks into world space.
     * This is the main entry point called during world rendering.
     *
     * @param matrixStack Matrix stack for transformations
     * @param vertexConsumers Vertex consumers for rendering
     * @param playerPos Current player position for culling
     * @param grids Set of active grids to render
     */
    public void renderGrids(MatrixStack matrixStack, VertexConsumerProvider vertexConsumers,
                            Vec3d playerPos, Set<LocalGrid> grids) {

        SLogger.log(this, "=== GridSpaceRenderer.renderGrids() called ===");
        SLogger.log(this, "Player position: " + playerPos);
        SLogger.log(this, "Number of grids to render: " + (grids != null ? grids.size() : "null"));

        if (grids == null || grids.isEmpty()) {
            SLogger.log(this, "No grids to render - exiting early");
            return;
        }

        long currentTime = System.currentTimeMillis();

        // Update render time for cache invalidation
        if (currentTime - lastRenderTime > TRANSFORM_CACHE_TIME) {
            invalidateTransformCache();
            lastRenderTime = currentTime;
        }

        int renderedCount = 0;
        int skippedCount = 0;

        for (LocalGrid grid : grids) {
            SLogger.log(this, "Processing grid: " + grid.getGridId());

            if (shouldRenderGrid(grid, playerPos)) {
                SLogger.log(this, "Rendering grid: " + grid.getGridId());
                renderGrid(grid, matrixStack, vertexConsumers, playerPos);
                renderedCount++;
            } else {
                SLogger.log(this, "Skipping grid: " + grid.getGridId() + " (out of range or destroyed)");
                skippedCount++;
            }
        }

        SLogger.log(this, "Rendering complete - rendered: " + renderedCount + ", skipped: " + skippedCount);
    }

    /**
     * Renders a single grid using VS2-style projection.
     *
     * @param grid The grid to render
     * @param matrixStack Matrix stack for transformations
     * @param vertexConsumers Vertex consumers for rendering
     * @param playerPos Player position for optimization
     */
    public void renderGrid(LocalGrid grid, MatrixStack matrixStack, VertexConsumerProvider vertexConsumers, Vec3d playerPos) {
        SLogger.log(this, "=== Rendering individual grid: " + grid.getGridId() + " ===");

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

            // Get GridSpace blocks to render
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

            // Render each block in GridSpace with transformation applied
            renderGridSpaceBlocks(grid, gridSpaceRegion, cachedTransform, matrixStack, vertexConsumers, playerPos);

            SLogger.log(this, "Grid rendering completed successfully");

        } catch (Exception e) {
            SLogger.log(this, "Error rendering grid " + grid.getGridId() + ": " + e.getMessage());
            e.printStackTrace(); // Print full stack trace for debugging
        }
    }

    // ----------------------------------------------
    // TRANSFORM COMPUTATION (VS2-STYLE)
    // ----------------------------------------------

    /**
     * Gets or computes the transform for a grid.
     * Now stores the JBullet Transform directly for proper rotation/translation.
     */
    private CachedTransform getOrComputeTransform(LocalGrid grid) {
        SLogger.log(this, "=== Computing transform for grid: " + grid.getGridId() + " ===");

        // Check cache first
        CachedTransform cached = transformCache.get(grid);
        long currentTime = System.currentTimeMillis();

        if (cached != null && (currentTime - cached.computeTime) < TRANSFORM_CACHE_TIME) {
            SLogger.log(this, "Using cached transform");
            return cached;
        }

        SLogger.log(this, "Computing new transform");

        // Get the rigid body transform
        Transform rigidBodyTransform = new Transform();
        grid.getCurrentTransform(rigidBodyTransform);

        SLogger.log(this, "Rigid body transform: " + rigidBodyTransform.origin + ", " + rigidBodyTransform.basis);

        // Store the JBullet transform directly
        CachedTransform newTransform = new CachedTransform(rigidBodyTransform, currentTime);
        transformCache.put(grid, newTransform);

        SLogger.log(this, "Transform computed and cached successfully");
        return newTransform;
    }

    // ----------------------------------------------
    // BLOCK RENDERING (THE CORE PROJECTION)
    // ----------------------------------------------

    /**
     * Renders blocks from GridSpace with transformation applied.
     * This is where the "projection" magic happens, just like VS2.
     *
     * Flow: GridSpace coords → Centered coords (0,0,0) → Rotate → Translate → World coords
     */
    private void renderGridSpaceBlocks(LocalGrid grid, GridSpaceRegion gridSpaceRegion,
                                       CachedTransform transform, MatrixStack matrixStack,
                                       VertexConsumerProvider vertexConsumers, Vec3d playerPos) {

        SLogger.log(this, "=== Rendering GridSpace blocks ===");

        // Get the GridSpace region origin and calculate center
        BlockPos gridSpaceOrigin = gridSpaceRegion.getRegionOrigin();
        SLogger.log(this, "GridSpace region origin: " + gridSpaceOrigin);

        // FIXED: Calculate the center of the GridSpace region (where grid-local 0,0,0 maps to)
        BlockPos gridSpaceCenter = new BlockPos(
                gridSpaceOrigin.getX() + 512,  // Half of 1024 region size
                gridSpaceOrigin.getY() + 512,
                gridSpaceOrigin.getZ() + 512
        );
        SLogger.log(this, "GridSpace region center: " + gridSpaceCenter);

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

            // Step 2: FIXED - Convert GridSpace position to centered position (subtract region CENTER)
            Vector3f centeredPos = new Vector3f(
                    gridSpacePos.getX() - gridSpaceCenter.getX(),
                    gridSpacePos.getY() - gridSpaceCenter.getY(),
                    gridSpacePos.getZ() - gridSpaceCenter.getZ()
            );
            SLogger.log(this, "Centered position (GridSpace - center): " + centeredPos.x + ", " + centeredPos.y + ", " + centeredPos.z);

            // Step 3 & 4: Apply rigid body transform (rotation + translation)
            Vector3f worldPos = applyRigidBodyTransform(centeredPos, transform);
            SLogger.log(this, "Final world position: " + worldPos.x + ", " + worldPos.y + ", " + worldPos.z);

            // Cull blocks that are too far away
            double distance = playerPos.distanceTo(new Vec3d(worldPos.x, worldPos.y, worldPos.z));
            SLogger.log(this, "Distance to player: " + distance);

            if (distance > MAX_RENDER_DISTANCE) {
                SLogger.log(this, "Block culled due to distance (" + distance + " > " + MAX_RENDER_DISTANCE + ")");
                culledBlocks++;
                continue;
            }

            // Render the block at its transformed world position
            SLogger.log(this, "Rendering block at world position: " + worldPos.x + ", " + worldPos.y + ", " + worldPos.z);
            renderBlockAtWorldPosition(blockState, worldPos, transform, matrixStack, vertexConsumers);
            renderedBlocks++;
        }

        SLogger.log(this, "Block rendering complete - rendered: " + renderedBlocks + ", culled: " + culledBlocks);
    }

    /**
     * Applies the rigid body transform to a centered position.
     * This handles both rotation around origin and translation to final position.
     */
    private Vector3f applyRigidBodyTransform(Vector3f centeredPos, CachedTransform transform) {
        // Get the rigid body transform components
        javax.vecmath.Vector3f rigidBodyPos = new javax.vecmath.Vector3f();
        Transform rigidBodyTransform = transform.bulletTransform;
        rigidBodyTransform.origin.get(rigidBodyPos);

        // Apply rotation to the centered position
        javax.vecmath.Vector3f rotatedPos = new javax.vecmath.Vector3f(centeredPos.x, centeredPos.y, centeredPos.z);
        rigidBodyTransform.basis.transform(rotatedPos);

        // FIXED: Subtract 0.5 to account for Minecraft block centering
        Vector3f finalPos = new Vector3f(
                rotatedPos.x + rigidBodyPos.x - 0.5f,
                rotatedPos.y + rigidBodyPos.y - 0.5f,
                rotatedPos.z + rigidBodyPos.z - 0.5f
        );

        return finalPos;
    }

    /**
     * Renders a single block at the specified world position with rotation applied.
     * This is where we actually draw the block to the screen.
     */
    private void renderBlockAtWorldPosition(BlockState blockState, Vector3f worldPos, CachedTransform transform,
                                            MatrixStack matrixStack, VertexConsumerProvider vertexConsumers) {

        matrixStack.push();

        try {
            // Translate to world position
            matrixStack.translate(worldPos.x, worldPos.y, worldPos.z);

            // FIXED: Apply rotation from rigid body transform
            applyRotationToMatrixStack(matrixStack, transform.bulletTransform);

            // FIXED: Calculate correct BlockPos for lighting (round to nearest integer)
            BlockPos lightingPos = new BlockPos(
                    (int) Math.round(worldPos.x),
                    (int) Math.round(worldPos.y),
                    (int) Math.round(worldPos.z)
            );

            // Render the block using Minecraft's block renderer with correct lighting position
            blockRenderer.renderBlock(
                    blockState,
                    lightingPos, // FIXED: Use actual world position for lighting instead of BlockPos.ORIGIN
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
     * Applies the rotation from a JBullet Transform to a Minecraft MatrixStack.
     * This makes the block rotate with the rigid body.
     */
    private void applyRotationToMatrixStack(MatrixStack matrixStack, Transform bulletTransform) {
        // Extract rotation matrix from JBullet transform
        javax.vecmath.Matrix3f rotationMatrix = bulletTransform.basis;

        // Convert to Minecraft's matrix format and apply
        // JBullet uses row-major, Minecraft uses column-major, so we need to transpose
        org.joml.Matrix4f minecraftMatrix = new org.joml.Matrix4f(
                rotationMatrix.m00, rotationMatrix.m10, rotationMatrix.m20, 0,
                rotationMatrix.m01, rotationMatrix.m11, rotationMatrix.m21, 0,
                rotationMatrix.m02, rotationMatrix.m12, rotationMatrix.m22, 0,
                0, 0, 0, 1
        );

        // Apply the rotation to the matrix stack
        matrixStack.multiplyPositionMatrix(minecraftMatrix);
    }

    // ----------------------------------------------
    // OPTIMIZATION AND CULLING
    // ----------------------------------------------

    /**
     * Determines if a grid should be rendered based on distance and other factors.
     */
    private boolean shouldRenderGrid(LocalGrid grid, Vec3d playerPos) {
        SLogger.log(this, "=== Checking if grid should render: " + grid.getGridId() + " ===");

        if (grid.isDestroyed()) {
            SLogger.log(this, "Grid is destroyed, should not render");
            return false;
        }

        // Get grid's world position
        javax.vecmath.Vector3f gridPos = new javax.vecmath.Vector3f();
        Transform gridTransform = new Transform();
        grid.getCurrentTransform(gridTransform);
        gridTransform.origin.get(gridPos);

        SLogger.log(this, "Grid world position: " + gridPos.x + ", " + gridPos.y + ", " + gridPos.z);
        SLogger.log(this, "Player position: " + playerPos.x + ", " + playerPos.y + ", " + playerPos.z);

        // Check distance
        double distance = playerPos.distanceTo(new Vec3d(gridPos.x, gridPos.y, gridPos.z));
        SLogger.log(this, "Distance to grid: " + distance + ", max distance: " + MAX_RENDER_DISTANCE);

        boolean shouldRender = distance <= MAX_RENDER_DISTANCE;
        SLogger.log(this, "Should render: " + shouldRender);

        return shouldRender;
    }

    /**
     * Invalidates cached transforms to ensure fresh data.
     */
    private void invalidateTransformCache() {
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
    public boolean stardance$isChatLoggingEnabled() { return false; }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() { return true; } // Enable logging for debugging

    // ----------------------------------------------
    // HELPER CLASSES
    // ----------------------------------------------

    /**
     * Cached transform data to avoid recomputing transforms every frame.
     * Now stores the JBullet Transform directly for proper rotation/translation handling.
     */
    private static class CachedTransform {
        final Transform bulletTransform;
        final long computeTime;

        CachedTransform(Transform bulletTransform, long computeTime) {
            this.bulletTransform = new Transform(bulletTransform); // Copy to avoid mutation
            this.computeTime = computeTime;
        }
    }
}