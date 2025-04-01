package net.stardance.render;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.stardance.core.LocalBlock;
import net.stardance.core.LocalGrid;
import net.stardance.physics.PhysicsEngine;
import net.stardance.utils.ILoggingControl;
import net.stardance.utils.SLogger;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static net.stardance.Stardance.engineManager;
import static net.stardance.Stardance.serverInstance;

/**
 * Handles rendering of LocalGrids on the client side.
 * Manages interpolation of grid transforms for smooth rendering.
 */
public class GridRenderer implements ILoggingControl {
    // Grid render data cache - using ConcurrentHashMap for thread safety
    private final Map<UUID, GridRenderData> gridRenderDataCache = new ConcurrentHashMap<>();

    // Flag to detect if we're in a new frame
    private long lastRenderFrameTime = 0;
    private boolean verbose = true; // Set to true for detailed logging

    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;  // Enable to see more debugging info
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return true;
    }

    /**
     * Renders all LocalGrids in the current world.
     */
    public void renderGrids(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                            float tickDelta, Vec3d cameraPos, ClientWorld clientWorld) {
        // Check prerequisites
        if (clientWorld == null) return;

        // Check if we're in a new frame
        long currentFrameTime = MinecraftClient.getInstance().world.getTime();
        boolean isNewFrame = currentFrameTime != lastRenderFrameTime;
        if (isNewFrame) {
            lastRenderFrameTime = currentFrameTime;
        }

        // Position the rendering to the camera reference frame
        matrices.push();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        // Get the server world and physics engine if needed
        ServerWorld serverWorld = null;
        PhysicsEngine engine = null;

        if (serverInstance != null) {
            serverWorld = serverInstance.getWorld(clientWorld.getRegistryKey());
            if (serverWorld != null) {
                engine = engineManager.getEngine(serverWorld);
            }
        }

        // If we have an engine, render each LocalGrid from it
        if (engine != null) {
            for (LocalGrid grid : engine.getGrids()) {
                if (grid.getRigidBody() != null) {
                    renderLocalGrid(grid, matrices, vertexConsumers, tickDelta, isNewFrame);
                }
            }
        }

        matrices.pop();
    }

    /**
     * Renders a LocalGrid that exists in the local engine.
     */
    private void renderLocalGrid(LocalGrid grid, MatrixStack matrices,
                                 VertexConsumerProvider vertexConsumers, float tickDelta, boolean isNewFrame) {
        BlockRenderManager blockRenderer = MinecraftClient.getInstance().getBlockRenderManager();

        // Get the cached render data
        GridRenderData renderData = getOrCreateRenderData(grid.getGridId());

        // If we don't have valid render data yet, try to initialize from the grid directly
        if (renderData.needsInitialization) {
            initializeRenderDataFromGrid(renderData, grid);
        }

        // Calculate interpolated position
        Vector3f interpolatedPos = new Vector3f();
        interpolatedPos.x = renderData.prevPosition.x + (renderData.currentPosition.x - renderData.prevPosition.x) * tickDelta;
        interpolatedPos.y = renderData.prevPosition.y + (renderData.currentPosition.y - renderData.prevPosition.y) * tickDelta;
        interpolatedPos.z = renderData.prevPosition.z + (renderData.currentPosition.z - renderData.prevPosition.z) * tickDelta;

        // Calculate interpolated rotation
        Quat4f interpolatedRot = new Quat4f();
        interpolatedRot.interpolate(renderData.prevRotation, renderData.currentRotation, tickDelta);

        // Debug logging
        if (isNewFrame && verbose) {
            SLogger.log(this, "Rendering grid with tickDelta: " + tickDelta +
                    ", prevPos: " + renderData.prevPosition +
                    ", currentPos: " + renderData.currentPosition +
                    ", interpolatedPos: " + interpolatedPos +
                    ", centroid: " + renderData.centroid);
        }

        // Save original matrix state to restore later
        matrices.push();

        // 1. Translate to the interpolated world position of the grid
        matrices.translate(interpolatedPos.x, interpolatedPos.y, interpolatedPos.z);

        // 2. Apply the interpolated rotation
        org.joml.Quaternionf mcQuat = new org.joml.Quaternionf(
                interpolatedRot.x,
                interpolatedRot.y,
                interpolatedRot.z,
                interpolatedRot.w
        );
        matrices.multiply(mcQuat);

        // Get the blocks from the grid
        Map<BlockPos, LocalBlock> blocks = grid.getBlocks();

        // Render each block
        for (Map.Entry<BlockPos, LocalBlock> entry : blocks.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue().getState();

            // Save matrix state for this block
            matrices.push();

            // 3. Position the block relative to the grid origin
            // When a block is at (0,0,0) in grid space, it should be at -centroid
            // to counteract the grid rigid body being centered on the centroid
            matrices.translate(
                    pos.getX() - renderData.centroid.x,
                    pos.getY() - renderData.centroid.y,
                    pos.getZ() - renderData.centroid.z
            );

            // Use Minecraft's block renderer
            blockRenderer.renderBlock(
                    state,
                    new BlockPos(0, 0, 0), // render at the current transform
                    MinecraftClient.getInstance().world,
                    matrices,
                    vertexConsumers.getBuffer(net.minecraft.client.render.RenderLayers.getBlockLayer(state)),
                    false,
                    MinecraftClient.getInstance().world.random
            );

            // Restore matrix state to grid transform
            matrices.pop();
        }

        // Restore original matrix state
        matrices.pop();
    }

    /**
     * Initializes render data from the grid if we don't have network updates yet.
     */
    private void initializeRenderDataFromGrid(GridRenderData renderData, LocalGrid grid) {
        // Get current position and rotation from the grid's rigid body
        Vector3f position = new Vector3f();
        Quat4f rotation = new Quat4f();

        grid.getRigidBody().getCenterOfMassPosition(position);
        grid.getRigidBody().getOrientation(rotation);

        // Get the centroid
        Vector3f centroid = grid.getCentroid();

        // Initialize both prev and current to the same values
        renderData.prevPosition.set(position);
        renderData.currentPosition.set(position);
        renderData.prevRotation.set(rotation);
        renderData.currentRotation.set(rotation);
        renderData.centroid.set(centroid);

        // Mark as initialized
        renderData.needsInitialization = false;

        SLogger.log(this, "Initialized render data from grid " + grid.getGridId() +
                ", position: " + position +
                ", rotation: " + rotation +
                ", centroid: " + centroid);
    }

    /**
     * Gets or creates render data for a grid by ID.
     */
    private GridRenderData getOrCreateRenderData(UUID gridId) {
        return gridRenderDataCache.computeIfAbsent(gridId, id -> new GridRenderData());
    }

    /**
     * Receives a physics update from the server via network packet.
     */
    public void receivePhysicsUpdate(UUID gridId, long tickTime, Vector3f position,
                                     Vector3f centroid, Quat4f rotation) {
        GridRenderData renderData = getOrCreateRenderData(gridId);

        // Skip older updates (packet ordering might not be guaranteed)
        if (tickTime < renderData.lastUpdateTick) {
            return;
        }

        // Check if this is a new update that should shift our positions
        if (tickTime > renderData.lastUpdateTick) {
            // Shift current position/rotation to previous
            renderData.prevPosition.set(renderData.currentPosition);
            renderData.prevRotation.set(renderData.currentRotation);

            // Update the current position/rotation with the new data
            renderData.currentPosition.set(position);
            renderData.currentRotation.set(rotation);
            renderData.centroid.set(centroid);
            renderData.lastUpdateTick = tickTime;
            renderData.needsInitialization = false;

            if (verbose) {
                SLogger.log(this, "Physics update received for grid " + gridId +
                        " at tick " + tickTime +
                        ", prevPos: " + renderData.prevPosition +
                        ", currentPos: " + renderData.currentPosition +
                        ", centroid: " + renderData.centroid);
            }
        }
    }

    /**
     * Cleans up render data for grids that no longer exist.
     */
    public void cleanupRenderData() {
        // Future implementation to clean up old render data
    }

    /**
     * Stores render data for a grid to enable smooth interpolation.
     */
    private static class GridRenderData {
        Vector3f prevPosition = new Vector3f();
        Vector3f currentPosition = new Vector3f();
        Vector3f centroid = new Vector3f();
        Quat4f prevRotation = new Quat4f(0, 0, 0, 1);
        Quat4f currentRotation = new Quat4f(0, 0, 0, 1);
        long lastUpdateTick = 0;
        boolean needsInitialization = true;
    }
}