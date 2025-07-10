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
 * DEBUG: GridSpace-aware networking component with comprehensive debug logging.
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
    private long lastPhysicsUpdateTime = 0; // Now stores server tick instead of milliseconds
    private int physicsUpdatesCount = 0;

    // NEW: GridSpace network state
    private boolean gridSpaceInfoSent = false;
    private boolean initialBlockDataSent = false;

    // DEBUG: Call tracking
    private int handleNetworkUpdatesCallCount = 0;

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
        SLogger.log("GridNetworkingComponent", "Created networking component for grid " + grid.getGridId());
    }

    // ----------------------------------------------
    // PUBLIC METHODS (DEBUG ENHANCED)
    // ----------------------------------------------

    /**
     * DEBUG: Enhanced network update handling with comprehensive logging.
     */
    public void handleNetworkUpdates() {
        handleNetworkUpdatesCallCount++;

        if (grid.isDestroyed()) {
            if (verbose && handleNetworkUpdatesCallCount % 60 == 0) {
                SLogger.log("GridNetworkingComponent", "Grid " + grid.getGridId() + " is destroyed, skipping network updates");
            }
            return;
        }

        // DEBUG: Log call frequency
        if (verbose && handleNetworkUpdatesCallCount % 60 == 0) {
            SLogger.log("GridNetworkingComponent", "handleNetworkUpdates() call #" + handleNetworkUpdatesCallCount +
                    " for grid " + grid.getGridId());
            SLogger.log("GridNetworkingComponent", "  State: gridSpaceInfoSent=" + gridSpaceInfoSent +
                    ", initialBlockDataSent=" + initialBlockDataSent +
                    ", pendingNetworkUpdate=" + pendingNetworkUpdate +
                    ", rebuildComplete=" + rebuildComplete);
        }

        // NEW: Send GridSpace info on first update
        if (!gridSpaceInfoSent) {
            SLogger.log("GridNetworkingComponent", "ATTEMPTING to send GridSpace info for grid " + grid.getGridId());
            sendGridSpaceInfo();
        } else if (verbose && handleNetworkUpdatesCallCount % 60 == 0) {
            SLogger.log("GridNetworkingComponent", "GridSpace info already sent for grid " + grid.getGridId());
        }

        // Send physics state updates
        sendPhysicsStateUpdate();

        // NEW: Send initial block data
        if (!initialBlockDataSent && gridSpaceInfoSent) {
            SLogger.log("GridNetworkingComponent", "ATTEMPTING to send initial block data for grid " + grid.getGridId());
            sendInitialBlockData();
        } else if (!initialBlockDataSent) {
            if (verbose && handleNetworkUpdatesCallCount % 60 == 0) {
                SLogger.log("GridNetworkingComponent", "Waiting for GridSpace info before sending block data for grid " + grid.getGridId());
            }
        }

        // Handle block updates if dirty
        if (pendingNetworkUpdate && rebuildComplete) {
            SLogger.log("GridNetworkingComponent", "ATTEMPTING to send block updates for grid " + grid.getGridId());
            sendBlockUpdates();
        }
    }

    /**
     * DEBUG: Enhanced GridSpace info sending with detailed logging.
     */
    private void sendGridSpaceInfo() {
        try {
            SLogger.log("GridNetworkingComponent", "sendGridSpaceInfo() called for grid " + grid.getGridId());

            // Check if we have a valid GridSpace region
            if (grid.getGridSpaceRegion() == null) {
                SLogger.log("GridNetworkingComponent", "ERROR: No GridSpace region for grid " + grid.getGridId());
                return;
            }

            if (grid.getGridSpaceRegion().isCleanedUp()) {
                SLogger.log("GridNetworkingComponent", "ERROR: GridSpace region is cleaned up for grid " + grid.getGridId());
                return;
            }

            SLogger.log("GridNetworkingComponent", "GridSpace region is valid, calling GridNetwork.sendGridSpaceInfo()");
            GridNetwork.sendGridSpaceInfo(grid);
            gridSpaceInfoSent = true;

            SLogger.log("GridNetworkingComponent", "SUCCESS: Sent GridSpace info for grid " + grid.getGridId());
        } catch (Exception e) {
            SLogger.log("GridNetworkingComponent", "ERROR sending GridSpace info for grid " + grid.getGridId() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * DEBUG: Enhanced initial block data sending.
     */
    private void sendInitialBlockData() {
        try {
            SLogger.log("GridNetworkingComponent", "sendInitialBlockData() called for grid " + grid.getGridId());

            GridNetwork.sendGridBlocks(grid);
            initialBlockDataSent = true;

            SLogger.log("GridNetworkingComponent", "SUCCESS: Sent initial GridSpace block data for grid " + grid.getGridId());
        } catch (Exception e) {
            SLogger.log("GridNetworkingComponent", "ERROR sending initial block data for grid " + grid.getGridId() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * DEBUG: Enhanced block updates sending.
     */
    private void sendBlockUpdates() {
        try {
            SLogger.log("GridNetworkingComponent", "sendBlockUpdates() called for grid " + grid.getGridId());

            // Send GridSpace block data instead of local grid blocks
            GridNetwork.sendGridBlocks(grid);

            // Clear pending update flag
            pendingNetworkUpdate = false;

            SLogger.log("GridNetworkingComponent", "SUCCESS: Sent GridSpace block updates for grid " + grid.getGridId());
        } catch (Exception e) {
            SLogger.log("GridNetworkingComponent", "ERROR sending block updates for grid " + grid.getGridId() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * ULTRA-AGGRESSIVE FIX: Strictly one physics update per server tick, no exceptions.
     */
    private void sendPhysicsStateUpdate() {
        try {
            // ULTRA-AGGRESSIVE FIX: Strictly one update per server tick
            long currentServerTick = grid.getWorld().getTime();

            // If we've already sent an update this tick, absolutely refuse to send another
            if (lastPhysicsUpdateTime == currentServerTick) {
                if (verbose && handleNetworkUpdatesCallCount % 60 == 0) {
                    SLogger.log("GridNetworkingComponent", "BLOCKED duplicate physics update for grid " + grid.getGridId() +
                            " - already sent this tick (" + currentServerTick + ")");
                }
                return;
            }

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

            // SIMPLIFIED: Send update if it's a new tick OR we haven't sent anything yet
            boolean shouldSendUpdate = !hasLastSentTransform || (currentServerTick > lastPhysicsUpdateTime);

            if (shouldSendUpdate) {
                GridNetwork.sendGridState(grid);

                // Update last sent state
                lastSentPhysicsPosition.set(currentPosition);
                lastSentPhysicsRotation.set(currentRotation);
                lastSentPhysicsCentroid.set(currentCentroid);
                lastPhysicsUpdateTime = currentServerTick; // Mark this tick as sent
                hasLastSentTransform = true;
                physicsUpdatesCount++;

                if (verbose) {
                    SLogger.log("GridNetworkingComponent", "SENT physics update #" + physicsUpdatesCount +
                            " for grid " + grid.getGridId() + " at tick " + currentServerTick +
                            ", pos=" + currentPosition);
                }
            } else {
                if (verbose && handleNetworkUpdatesCallCount % 60 == 0) {
                    SLogger.log("GridNetworkingComponent", "SKIPPED physics update for grid " + grid.getGridId() +
                            " - no significant change or same tick");
                }
            }
        } catch (Exception e) {
            SLogger.log("GridNetworkingComponent", "Error sending physics state for grid " + grid.getGridId() + ": " + e.getMessage());
            e.printStackTrace();
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
    // STATE MANAGEMENT (DEBUG ENHANCED)
    // ----------------------------------------------

    /**
     * Marks that a network update is pending.
     */
    public void setPendingNetworkUpdate(boolean pending) {
        this.pendingNetworkUpdate = pending;
        if (verbose) {
            SLogger.log("GridNetworkingComponent", "Set pending network update to " + pending + " for grid " + grid.getGridId());
        }
    }

    /**
     * Sets the rebuild completion state.
     */
    public void setRebuildInProgress(boolean inProgress) {
        this.rebuildComplete = !inProgress;

        if (verbose) {
            SLogger.log("GridNetworkingComponent", "Set rebuild in progress to " + inProgress + " for grid " + grid.getGridId());
        }

        // If rebuild is complete and we have pending updates, trigger immediate update
        if (rebuildComplete && pendingNetworkUpdate) {
            SLogger.log("GridNetworkingComponent", "Rebuild complete with pending updates, triggering block updates for grid " + grid.getGridId());
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

        SLogger.log("GridNetworkingComponent", "Reset GridSpace state for grid " + grid.getGridId());
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

    /**
     * DEBUG: Gets call count for handleNetworkUpdates.
     */
    public int getHandleNetworkUpdatesCallCount() {
        return handleNetworkUpdatesCallCount;
    }
}