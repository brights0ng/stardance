package net.starlight.stardance.network;

import net.minecraft.block.BlockState;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UPDATED: Client-side grid representation with GridSpace support.
 * Now stores both grid-local blocks (legacy) and GridSpace blocks + region info.
 */
public class ClientLocalGrid implements ILoggingControl {

    // Grid identification
    private final UUID gridId;

    // Physics state
    private Vector3f position = new Vector3f();
    private Vector3f centroid = new Vector3f();
    private Quat4f rotation = new Quat4f(0, 0, 0, 1);
    private long lastServerTick = 0;

    // Block storage
    private final Map<BlockPos, BlockState> gridLocalBlocks = new ConcurrentHashMap<>(); // Legacy
    private final Map<BlockPos, BlockState> gridSpaceBlocks = new ConcurrentHashMap<>(); // NEW

    // NEW: GridSpace region information
    private int regionId = -1;
    private BlockPos regionOrigin = null;
    private boolean hasGridSpaceInfo = false;

    // Interpolation and rendering state
    private boolean hasValidState = false;
    private Vector3f lastPosition = new Vector3f();
    private Quat4f lastRotation = new Quat4f(0, 0, 0, 1);

    // Debug
    private boolean verbose = true;

    // ----------------------------------------------
    // CONSTRUCTOR
    // ----------------------------------------------

    public ClientLocalGrid(UUID gridId) {
        this.gridId = gridId;
        SLogger.log(this, "Created ClientLocalGrid: " + gridId);
    }

    // ----------------------------------------------
    // STATE UPDATES
    // ----------------------------------------------

    /**
     * Updates the grid's physics state (unchanged).
     */
    public void updateState(Vector3f position, Quat4f rotation, Vector3f centroid, long serverTick) {
        // Store previous state for interpolation
        if (hasValidState) {
            lastPosition.set(this.position);
            lastRotation.set(this.rotation);
        }

        // Update current state
        this.position.set(position);
        this.rotation.set(rotation);
        this.centroid.set(centroid);
        this.lastServerTick = serverTick;
        this.hasValidState = true;

        if (verbose) {
            SLogger.log(this, "Updated state for grid " + gridId + ": pos=" + position + ", rot=" + rotation);
        }
    }

    /**
     * NEW: Updates GridSpace region information.
     */
    public void updateGridSpaceInfo(int regionId, BlockPos regionOrigin) {
        this.regionId = regionId;
        this.regionOrigin = regionOrigin;
        this.hasGridSpaceInfo = true;

        if (verbose) {
            SLogger.log(this, "Updated GridSpace info for grid " + gridId +
                    ": regionId=" + regionId + ", origin=" + regionOrigin);
        }
    }

    /**
     * NEW: Updates blocks using GridSpace coordinates.
     * This is the new primary method for block updates.
     */
    public void updateGridSpaceBlocks(Map<BlockPos, BlockState> blocks) {
        gridSpaceBlocks.clear();
        gridSpaceBlocks.putAll(blocks);

        if (verbose) {
            SLogger.log(this, "Updated GridSpace blocks for grid " + gridId + ": " + blocks.size() + " blocks");
        }
    }

    /**
     * LEGACY: Updates blocks using grid-local coordinates.
     * Kept for backwards compatibility with old networking.
     */
    public void updateBlocks(Map<BlockPos, BlockState> blocks) {
        gridLocalBlocks.clear();
        gridLocalBlocks.putAll(blocks);

        if (verbose) {
            SLogger.log(this, "Updated grid-local blocks for grid " + gridId + ": " + blocks.size() + " blocks");
        }
    }

    /**
     * Updates a single block (assumes grid-local coordinates for compatibility).
     */
    public void updateBlock(BlockPos pos, BlockState state) {
        if (state != null) {
            gridLocalBlocks.put(pos, state);
        } else {
            gridLocalBlocks.remove(pos);
        }
    }

    // ----------------------------------------------
    // RENDERING (UPDATED)
    // ----------------------------------------------

    /**
     * UPDATED: Renders this client grid.
     * Now uses GridSpace blocks if available, falls back to grid-local blocks.
     */
    public void render(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                       float partialTick, long currentWorldTick) {

        if (!hasValidState) {
            return; // No state to render
        }

        // Choose which block set to render
        Map<BlockPos, BlockState> blocksToRender;
        boolean usingGridSpaceBlocks = false;

        if (!gridSpaceBlocks.isEmpty() && hasGridSpaceInfo) {
            // Use GridSpace blocks (preferred)
            blocksToRender = gridSpaceBlocks;
            usingGridSpaceBlocks = true;
        } else if (!gridLocalBlocks.isEmpty()) {
            // Fall back to grid-local blocks
            blocksToRender = gridLocalBlocks;
        } else {
            // No blocks to render
            return;
        }

        if (verbose && blocksToRender.size() > 0) {
            SLogger.log(this, "Rendering grid " + gridId + " with " + blocksToRender.size() +
                    " blocks (" + (usingGridSpaceBlocks ? "GridSpace" : "grid-local") + ")");
        }

        // Render blocks with appropriate coordinate transformation
        matrices.push();
        try {
            if (usingGridSpaceBlocks) {
                renderGridSpaceBlocks(matrices, vertexConsumers, blocksToRender, partialTick);
            } else {
                renderGridLocalBlocks(matrices, vertexConsumers, blocksToRender, partialTick);
            }
        } finally {
            matrices.pop();
        }
    }

    /**
     * NEW: Renders blocks stored in GridSpace coordinates.
     * This method should transform GridSpace coordinates to world coordinates for rendering.
     */
    private void renderGridSpaceBlocks(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                       Map<BlockPos, BlockState> gridSpaceBlocks, float partialTick) {

        // Apply grid transform
        applyGridTransform(matrices, partialTick);

        // TODO: For now, we'll render them as-is
        // In a complete implementation, you'd want to:
        // 1. Convert GridSpace coordinates to grid-local coordinates
        // 2. Apply the grid's transform matrix
        // 3. Render each block at the transformed position

        // Placeholder rendering - just render a few blocks for testing
        int rendered = 0;
        for (Map.Entry<BlockPos, BlockState> entry : gridSpaceBlocks.entrySet()) {
            if (rendered >= 10) break; // Limit for performance during testing

            BlockPos gridSpacePos = entry.getKey();
            BlockState state = entry.getValue();

            // Convert GridSpace to grid-local for rendering
            BlockPos gridLocalPos = convertGridSpaceToGridLocal(gridSpacePos);

            if (gridLocalPos != null) {
                renderBlockAt(matrices, vertexConsumers, gridLocalPos, state);
                rendered++;
            }
        }

        if (verbose && rendered > 0) {
            SLogger.log(this, "Rendered " + rendered + " GridSpace blocks for grid " + gridId);
        }
    }

    /**
     * LEGACY: Renders blocks stored in grid-local coordinates.
     */
    private void renderGridLocalBlocks(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                       Map<BlockPos, BlockState> gridLocalBlocks, float partialTick) {

        // Apply grid transform
        applyGridTransform(matrices, partialTick);

        // Render each block at its grid-local position
        for (Map.Entry<BlockPos, BlockState> entry : gridLocalBlocks.entrySet()) {
            BlockPos gridLocalPos = entry.getKey();
            BlockState state = entry.getValue();

            renderBlockAt(matrices, vertexConsumers, gridLocalPos, state);
        }
    }

    /**
     * NEW: Converts GridSpace coordinates to grid-local coordinates.
     */
    private BlockPos convertGridSpaceToGridLocal(BlockPos gridSpacePos) {
        if (!hasGridSpaceInfo || regionOrigin == null) {
            return null;
        }

        // GridSpace to region-local
        int regionLocalX = gridSpacePos.getX() - regionOrigin.getX();
        int regionLocalY = gridSpacePos.getY() - regionOrigin.getY();
        int regionLocalZ = gridSpacePos.getZ() - regionOrigin.getZ();

        // Region-local to grid-local (accounting for center offset)
        int gridLocalX = regionLocalX - 512; // GRIDSPACE_CENTER_OFFSET
        int gridLocalY = regionLocalY - 512;
        int gridLocalZ = regionLocalZ - 512;

        return new BlockPos(gridLocalX, gridLocalY, gridLocalZ);
    }

    /**
     * Applies the grid's transform to the matrix stack.
     */
    private void applyGridTransform(MatrixStack matrices, float partialTick) {
        // Apply position
        matrices.translate(position.x, position.y, position.z);

        // Apply rotation (simplified - you might want more sophisticated interpolation)
        // TODO: Apply rotation from quaternion
        // For now, we'll skip rotation to get basic positioning working
    }

    /**
     * Renders a single block at the specified grid-local position.
     */
    private void renderBlockAt(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                               BlockPos gridLocalPos, BlockState state) {
        matrices.push();
        matrices.translate(gridLocalPos.getX(), gridLocalPos.getY(), gridLocalPos.getZ());

        // TODO: Actual block rendering
        // For now, this is a placeholder
        // You'll need to use Minecraft's BlockRenderManager here

        matrices.pop();
    }

    // ----------------------------------------------
    // GETTERS
    // ----------------------------------------------

    public UUID getGridId() { return gridId; }
    public Vector3f getPosition() { return position; }
    public Vector3f getCentroid() { return centroid; }
    public Quat4f getRotation() { return rotation; }
    public long getLastServerTick() { return lastServerTick; }
    public boolean hasValidState() { return hasValidState; }

    // NEW getters
    public Map<BlockPos, BlockState> getGridSpaceBlocks() { return gridSpaceBlocks; }
    public Map<BlockPos, BlockState> getGridLocalBlocks() { return gridLocalBlocks; }
    public boolean hasGridSpaceInfo() { return hasGridSpaceInfo; }
    public int getRegionId() { return regionId; }
    public BlockPos getRegionOrigin() { return regionOrigin; }

    // ----------------------------------------------
    // LOGGING CONTROL
    // ----------------------------------------------

    @Override
    public boolean stardance$isChatLoggingEnabled() { return false; }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() { return true; }
}