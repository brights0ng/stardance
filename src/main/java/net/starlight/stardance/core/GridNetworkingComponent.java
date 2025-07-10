package net.starlight.stardance.core;

import com.bulletphysics.linearmath.Transform;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.starlight.stardance.network.GridNetwork;
import net.starlight.stardance.utils.SLogger;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static net.starlight.stardance.Stardance.TRANSFORM_UPDATE_PACKET_ID;
import static net.starlight.stardance.Stardance.PHYSICS_STATE_UPDATE_PACKET_ID;
import static net.starlight.stardance.Stardance.serverInstance;

/**
 * UPDATED: GridSpace-aware networking component for LocalGrid.
 * Now sends GridSpace block data and region information to clients.
 */
class GridNetworkingComponent {
    // ----------------------------------------------
    // CONSTANTS
    // ----------------------------------------------
    private static final float POSITION_CHANGE_THRESHOLD = 0.001f;
    private static final AtomicInteger packetCounter = new AtomicInteger(0);
    private static final boolean verbose = true; // Enable for GridSpace debugging

    // ----------------------------------------------
    // PARENT REFERENCE
    // ----------------------------------------------
    private final LocalGrid grid;

    // ----------------------------------------------
    // NETWORK STATE
    // ----------------------------------------------
    private Vector3f lastSentPosition = new Vector3f();
    private Quat4f lastSentRotation = new Quat4f(0, 0, 0, 1);
    private boolean hasLastSentTransform = false;
    private boolean pendingNetworkUpdate = false;
    private boolean rebuildComplete = true;

    // Physics state tracking
    private Vector3f lastSentPhysicsPosition = new Vector3f();
    private Vector3f lastSentPhysicsCentroid = new Vector3f();
    private Quat4f lastSentPhysicsRotation = new Quat4f(0, 0, 0, 1);
    private long lastPhysicsUpdateTime = 0;
    private int physicsUpdatesCount = 0;

    // NEW: GridSpace network state
    private boolean gridSpaceInfoSent = false;
    private boolean initialBlockDataSent = false;

    // ----------------------------------------------
    // CONSTRUCTOR
    // ----------------------------------------------
    /**
     * Creates a new GridNetworkingComponent for the given LocalGrid.
     *
     * @param grid The parent LocalGrid
     */
    GridNetworkingComponent(LocalGrid grid) {
        this.grid = grid;
    }

    // ----------------------------------------------
    // PUBLIC METHODS (UPDATED FOR GRIDSPACE)
    // ----------------------------------------------

    /**
     * UPDATED: Handles network updates with GridSpace support.
     * Now sends GridSpace info and blocks instead of local grid data.
     */
    public void handleNetworkUpdates() {
        if (grid.isDestroyed()) {
            return;
        }

        // NEW: Send GridSpace info on first update
        if (!gridSpaceInfoSent) {
            sendGridSpaceInfo();
        }

        // Send physics state updates
        sendPhysicsStateUpdate();

        // NEW: Send initial block data
        if (!initialBlockDataSent && gridSpaceInfoSent) {
            sendInitialBlockData();
        }

        // Handle block updates if dirty
        if (pendingNetworkUpdate && rebuildComplete) {
            sendBlockUpdates();
        }
    }

    /**
     * NEW: Sends GridSpace region information to clients.
     * This tells clients about the GridSpace region allocated to this grid.
     */
    private void sendGridSpaceInfo() {
        try {
            GridNetwork.sendGridSpaceInfo(grid);
            gridSpaceInfoSent = true;

            if (verbose) {
                SLogger.log("GridNetworkingComponent", "Sent GridSpace info for grid " + grid.getGridId());
            }
        } catch (Exception e) {
            SLogger.log("GridNetworkingComponent", "Error sending GridSpace info: " + e.getMessage());
        }
    }

    /**
     * NEW: Sends initial block data using GridSpace coordinates.
     */
    private void sendInitialBlockData() {
        try {
            GridNetwork.sendGridBlocks(grid);
            initialBlockDataSent = true;

            if (verbose) {
                SLogger.log("GridNetworkingComponent", "Sent initial GridSpace block data for grid " + grid.getGridId());
            }
        } catch (Exception e) {
            SLogger.log("GridNetworkingComponent", "Error sending initial block data: " + e.getMessage());
        }
    }

    /**
     * UPDATED: Sends block updates using GridSpace coordinates.
     */
    private void sendBlockUpdates() {
        try {
            // Send GridSpace block data instead of local grid blocks
            GridNetwork.sendGridBlocks(grid);

            // Clear pending update flag
            pendingNetworkUpdate = false;

            if (verbose) {
                SLogger.log("GridNetworkingComponent", "Sent GridSpace block updates for grid " + grid.getGridId());
            }
        } catch (Exception e) {
            SLogger.log("GridNetworkingComponent", "Error sending block updates: " + e.getMessage());
        }
    }

    /**
     * Sends physics state updates (unchanged).
     */
    private void sendPhysicsStateUpdate() {
        try {
            // Get current physics state
            Vector3f currentPosition = new Vector3f();
            Vector3f currentCentroid = grid.getCentroid();
            Quat4f currentRotation = new Quat4f();

            // Get transform
            Transform transform = new Transform();
            grid.getCurrentTransform(transform);

            // Extract position and rotation
            transform.origin.get(currentPosition);
            transform.getRotation(currentRotation);

            // Check if we need to send an update
            boolean shouldSendUpdate = !hasLastSentTransform ||
                    hasSignificantPositionChange(currentPosition) ||
                    hasSignificantRotationChange(currentRotation) ||
                    hasSignificantCentroidChange(currentCentroid) ||
                    (System.currentTimeMillis() - lastPhysicsUpdateTime > 100); // Force update every 100ms

            if (shouldSendUpdate) {
                GridNetwork.sendGridState(grid);

                // Update last sent state
                lastSentPhysicsPosition.set(currentPosition);
                lastSentPhysicsRotation.set(currentRotation);
                lastSentPhysicsCentroid.set(currentCentroid);
                lastPhysicsUpdateTime = System.currentTimeMillis();
                hasLastSentTransform = true;
                physicsUpdatesCount++;

                if (verbose && physicsUpdatesCount % 60 == 0) { // Log every 60 updates (3 seconds at 20 TPS)
                    SLogger.log("GridNetworkingComponent", "Sent physics update #" + physicsUpdatesCount +
                            " for grid " + grid.getGridId() + " at " + currentPosition);
                }
            }
        } catch (Exception e) {
            SLogger.log("GridNetworkingComponent", "Error sending physics state: " + e.getMessage());
        }
    }

    /**
     * Check if position has changed significantly.
     */
    private boolean hasSignificantPositionChange(Vector3f currentPosition) {
        float dx = Math.abs(currentPosition.x - lastSentPhysicsPosition.x);
        float dy = Math.abs(currentPosition.y - lastSentPhysicsPosition.y);
        float dz = Math.abs(currentPosition.z - lastSentPhysicsPosition.z);

        return dx > POSITION_CHANGE_THRESHOLD || dy > POSITION_CHANGE_THRESHOLD || dz > POSITION_CHANGE_THRESHOLD;
    }

    /**
     * Check if rotation has changed significantly.
     */
    private boolean hasSignificantRotationChange(Quat4f currentRotation) {
        float threshold = 0.001f;

        float dx = Math.abs(currentRotation.x - lastSentPhysicsRotation.x);
        float dy = Math.abs(currentRotation.y - lastSentPhysicsRotation.y);
        float dz = Math.abs(currentRotation.z - lastSentPhysicsRotation.z);
        float dw = Math.abs(currentRotation.w - lastSentPhysicsRotation.w);

        return dx > threshold || dy > threshold || dz > threshold || dw > threshold;
    }

    /**
     * Check if centroid has changed significantly.
     */
    private boolean hasSignificantCentroidChange(Vector3f currentCentroid) {
        float threshold = 0.001f;

        float dx = Math.abs(currentCentroid.x - lastSentPhysicsCentroid.x);
        float dy = Math.abs(currentCentroid.y - lastSentPhysicsCentroid.y);
        float dz = Math.abs(currentCentroid.z - lastSentPhysicsCentroid.z);

        return dx > threshold || dy > threshold || dz > threshold;
    }

    // ----------------------------------------------
    // STATE MANAGEMENT (UPDATED)
    // ----------------------------------------------

    /**
     * Marks that a network update is pending.
     */
    public void setPendingNetworkUpdate(boolean pending) {
        this.pendingNetworkUpdate = pending;
    }

    /**
     * Sets the rebuild completion state.
     */
    public void setRebuildInProgress(boolean inProgress) {
        this.rebuildComplete = !inProgress;

        // If rebuild is complete and we have pending updates, trigger immediate update
        if (rebuildComplete && pendingNetworkUpdate) {
            sendBlockUpdates();
        }
    }

    /**
     * NEW: Resets GridSpace network state for complete re-sync.
     */
    public void resetGridSpaceState() {
        gridSpaceInfoSent = false;
        initialBlockDataSent = false;
        pendingNetworkUpdate = true;
    }

    /**
     * Gets the number of physics updates sent.
     */
    public int getPhysicsUpdatesCount() {
        return physicsUpdatesCount;
    }

    /**
     * Gets whether GridSpace info has been sent.
     */
    public boolean isGridSpaceInfoSent() {
        return gridSpaceInfoSent;
    }

    /**
     * Gets whether initial block data has been sent.
     */
    public boolean isInitialBlockDataSent() {
        return initialBlockDataSent;
    }
}