package net.stardance.network;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.stardance.utils.ILoggingControl;
import net.stardance.utils.SLogger;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Client-side representation of a LocalGrid.
 * Handles rendering and interpolation based on server updates.
 */
public class ClientLocalGrid implements ILoggingControl {
    // Core identification
    private final UUID gridId;

    // Physics state - current and previous for interpolation
    private Vector3f previousPosition = new Vector3f();
    private Vector3f currentPosition = new Vector3f();
    private Quat4f previousRotation = new Quat4f(0, 0, 0, 1);
    private Quat4f currentRotation = new Quat4f(0, 0, 0, 1);
    private Vector3f centroid = new Vector3f();

    // Server time tracking
    private long previousServerTick;
    private long currentServerTick;

    // CRITICAL FIX: Independent timing for interpolation
    private long lastUpdateTimeMs;
    private static final long INTERPOLATION_PERIOD_MS = 50; // 50ms (20 updates/second)

    // Queue to store updates in the order they should be applied
    private final ConcurrentLinkedQueue<StateUpdate> pendingUpdates = new ConcurrentLinkedQueue<>();

    // Block data
    private final Map<BlockPos, BlockState> blocks = new HashMap<>();

    // State flags
    private boolean isInitialized = false;
    private boolean needsBlockUpdate = false;

    // Debug
    private boolean verbose = false;

    /**
     * Creates a new ClientLocalGrid with the given ID.
     */
    public ClientLocalGrid(UUID gridId) {
        this.gridId = gridId;
        this.lastUpdateTimeMs = System.currentTimeMillis();
    }

    /**
     * Updates the grid state based on server data.
     * This now queues the update and processes all pending updates
     * to ensure they're applied in the correct order.
     */
    public void updateState(Vector3f newPosition, Quat4f newRotation, Vector3f newCentroid, long serverTick) {
        // Add this update to the pending queue
        StateUpdate update = new StateUpdate(
                new Vector3f(newPosition),
                new Quat4f(newRotation),
                new Vector3f(newCentroid),
                serverTick
        );

        pendingUpdates.add(update);

        // Process all pending updates in order
        processUpdates();

        // CRITICAL FIX: Reset our interpolation timer when we get an update
        lastUpdateTimeMs = System.currentTimeMillis();
    }

    /**
     * Process all pending updates in sequence order.
     */
    private void processUpdates() {
        // If no updates or not initialized yet, just apply the first update
        if (!isInitialized && !pendingUpdates.isEmpty()) {
            StateUpdate update = pendingUpdates.poll();

            // Apply as both previous and current
            previousPosition.set(update.position);
            currentPosition.set(update.position);
            previousRotation.set(update.rotation);
            currentRotation.set(update.rotation);
            centroid.set(update.centroid);

            previousServerTick = update.serverTick;
            currentServerTick = update.serverTick;

            isInitialized = true;

            if (verbose) {
                SLogger.log(this, "Initialized grid state: tick=" + update.serverTick +
                        ", pos=" + update.position +
                        ", remaining updates=" + pendingUpdates.size());
            }

            // Continue processing if there are more updates
            if (!pendingUpdates.isEmpty()) {
                processUpdates();
            }

            return;
        }

        // Sort pending updates by server tick (just in case)
        StateUpdate[] sortedUpdates = pendingUpdates.toArray(new StateUpdate[0]);
        Arrays.sort(sortedUpdates, Comparator.comparingLong(update -> update.serverTick));
        pendingUpdates.clear();
        for (StateUpdate update : sortedUpdates) {
            pendingUpdates.add(update);
        }

        // Process all valid updates in the correct order
        while (!pendingUpdates.isEmpty()) {
            StateUpdate update = pendingUpdates.peek();

            // Skip if this is an old update
            if (update.serverTick <= currentServerTick) {
                if (verbose) {
                    SLogger.log(this, "Skipping old update: tick=" + update.serverTick +
                            ", current tick=" + currentServerTick);
                }
                pendingUpdates.poll(); // Remove and discard
                continue;
            }

            // Handle sequence gaps
            if (update.serverTick > currentServerTick + 1 && update.serverTick <= currentServerTick + 5) {
                if (verbose) {
                    SLogger.log(this, "Applying non-sequential update: tick=" + update.serverTick +
                            ", expected=" + (currentServerTick + 1) +
                            ", gap=" + (update.serverTick - currentServerTick));
                }

                // Just apply it and continue
            } else if (update.serverTick > currentServerTick + 5) {
                // This update is too far ahead, wait for intermediate updates
                if (verbose) {
                    SLogger.log(this, "Waiting for intermediate update: tick=" + update.serverTick +
                            ", expected=" + (currentServerTick + 1) +
                            ", gap too large=" + (update.serverTick - currentServerTick));
                }

                // If we've been waiting too long (more than 30 pending updates), just apply it
                if (pendingUpdates.size() > 30) {
                    if (verbose) {
                        SLogger.log(this, "Too many pending updates, applying out-of-sequence update");
                    }
                } else {
                    return; // Wait for more updates to arrive
                }
            }

            // This is the next update to apply
            pendingUpdates.poll(); // Remove from queue

            // Shift current to previous
            previousPosition.set(currentPosition);
            previousRotation.set(currentRotation);
            previousServerTick = currentServerTick;

            // Update current
            currentPosition.set(update.position);
            currentRotation.set(update.rotation);
            centroid.set(update.centroid);
            currentServerTick = update.serverTick;

            // CRITICAL FIX: Reset our interpolation timer when applying a state update
            lastUpdateTimeMs = System.currentTimeMillis();

            if (verbose) {
                SLogger.log(this, "Applied update: previousTick=" + previousServerTick +
                        ", currentTick=" + currentServerTick +
                        ", pos=" + update.position);
            }
        }
    }

    /**
     * Updates the grid's block data.
     */
    public void updateBlocks(Map<BlockPos, BlockState> newBlocks) {
        blocks.clear();
        blocks.putAll(newBlocks);
        needsBlockUpdate = false;

        if (verbose) {
            SLogger.log(this, "Updated blocks: count=" + blocks.size());
        }
    }

    /**
     * Updates a single block in the grid.
     */
    public void updateBlock(BlockPos pos, BlockState state) {
        blocks.put(pos, state);
    }

    /**
     * Removes a block from the grid.
     */
    public void removeBlock(BlockPos pos) {
        blocks.remove(pos);
    }

    /**
     * Renders the grid with interpolation based on server tick delta.
     */
    public void render(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                       float partialServerTick, long currentWorldTick) {
        if (!isInitialized || blocks.isEmpty()) {
            return;
        }

        BlockRenderManager blockRenderer = MinecraftClient.getInstance().getBlockRenderManager();

        // CRITICAL FIX: Calculate our own interpolation factor based on time since last update
        float tickDelta = calculateInterpolationFactor();

        // Save original matrix state
        matrices.push();

        // Calculate interpolated position
        Vector3f interpolatedPos = new Vector3f();
        interpolatedPos.x = previousPosition.x + (currentPosition.x - previousPosition.x) * tickDelta;
        interpolatedPos.y = previousPosition.y + (currentPosition.y - previousPosition.y) * tickDelta;
        interpolatedPos.z = previousPosition.z + (currentPosition.z - previousPosition.z) * tickDelta;

        // Calculate interpolated rotation
        Quat4f interpolatedRot = new Quat4f();
        interpolatedRot.interpolate(previousRotation, currentRotation, tickDelta);

        // Debug logging
        if (verbose) {
            SLogger.log(this, "Rendering grid with tickDelta: " + tickDelta +
                    ", prevPos: " + previousPosition +
                    ", currPos: " + currentPosition +
                    ", interpolatedPos: " + interpolatedPos);
        }

        // Apply position - world position of the rigid body
        matrices.translate(interpolatedPos.x, interpolatedPos.y, interpolatedPos.z);

        // Apply rotation
        org.joml.Quaternionf mcQuat = new org.joml.Quaternionf(
                interpolatedRot.x,
                interpolatedRot.y,
                interpolatedRot.z,
                interpolatedRot.w
        );
        matrices.multiply(mcQuat);

        // Render each block
        for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue();

            // Save matrix state for this block
            matrices.push();

            // Position the block relative to the grid origin
            // With centroid offset adjustment
            matrices.translate(
                    pos.getX() - centroid.x,
                    pos.getY() - centroid.y,
                    pos.getZ() - centroid.z
            );

            // Get the right render layer
            RenderLayer renderLayer = RenderLayers.getBlockLayer(state);
            VertexConsumer buffer = vertexConsumers.getBuffer(renderLayer);

            // Use Minecraft's block renderer
            blockRenderer.renderBlock(
                    state,
                    new BlockPos(0, 0, 0),
                    MinecraftClient.getInstance().world,
                    matrices,
                    buffer,
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
     * Calculates the interpolation factor based on time since last update.
     * CRITICAL FIX: This is now based on real time rather than server ticks.
     */
    private float calculateInterpolationFactor() {
        long currentTimeMs = System.currentTimeMillis();
        long timeSinceUpdateMs = currentTimeMs - lastUpdateTimeMs;

        // Calculate interpolation factor (0.0 to 1.0) based on time elapsed
        float factor = Math.min(1.0f, (float)timeSinceUpdateMs / INTERPOLATION_PERIOD_MS);

        return factor;
    }

    /**
     * Gets the grid's UUID.
     */
    public UUID getGridId() {
        return gridId;
    }

    /**
     * Sets the verbosity of logging.
     */
    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    /**
     * Gets the current position of the grid.
     */
    public Vector3f getCurrentPosition() {
        return new Vector3f(currentPosition);
    }

    /**
     * Gets the current rotation of the grid.
     */
    public Quat4f getCurrentRotation() {
        Quat4f copy = new Quat4f();
        copy.set(currentRotation);
        return copy;
    }

    /**
     * Checks if this grid is initialized.
     */
    public boolean isInitialized() {
        return isInitialized;
    }

    /**
     * Checks if this grid needs a block update.
     */
    public boolean needsBlockUpdate() {
        return needsBlockUpdate;
    }

    /**
     * Marks this grid as needing a block update.
     */
    public void markNeedsBlockUpdate() {
        this.needsBlockUpdate = true;
    }

    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return false;
    }

    /**
     * Helper class to store state updates with their server tick time.
     */
    private static class StateUpdate {
        final Vector3f position;
        final Quat4f rotation;
        final Vector3f centroid;
        final long serverTick;

        StateUpdate(Vector3f position, Quat4f rotation, Vector3f centroid, long serverTick) {
            this.position = position;
            this.rotation = rotation;
            this.centroid = centroid;
            this.serverTick = serverTick;
        }
    }
}