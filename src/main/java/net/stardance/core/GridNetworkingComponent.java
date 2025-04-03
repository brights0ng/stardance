package net.stardance.core;

import com.bulletphysics.linearmath.Transform;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.stardance.core.LocalBlock;
import net.stardance.core.LocalGrid;
import net.stardance.utils.SLogger;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static net.stardance.Stardance.TRANSFORM_UPDATE_PACKET_ID;
import static net.stardance.Stardance.PHYSICS_STATE_UPDATE_PACKET_ID;
import static net.stardance.Stardance.serverInstance;

/**
 * Handles network communication for a LocalGrid.
 * Manages transform updates and block synchronization across the network.
 * This class is package-private - external code should use LocalGrid instead.
 */
class GridNetworkingComponent {
    // ----------------------------------------------
    // CONSTANTS
    // ----------------------------------------------
    private static final float POSITION_CHANGE_THRESHOLD = 0.001f;
    private static final AtomicInteger packetCounter = new AtomicInteger(0);
    private static final boolean verbose = false; // Enable for detailed logging

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
    // PUBLIC METHODS
    // ----------------------------------------------
    /**
     * Handles network updates based on current state.
     *
     * @param blocksDirty Whether blocks have changed
     */
    public void handleNetworkUpdates(boolean blocksDirty) {
        // Get rebuild status from physics component
        boolean rebuildInProgress = grid.getPhysicsComponent().isRebuildInProgress();

        // Send packet to clients once rebuild is complete
        if (!rebuildInProgress && pendingNetworkUpdate) {
            sendTransformUpdate(blocksDirty);
            pendingNetworkUpdate = false;
            rebuildComplete = true;
        } else if (!rebuildInProgress && blocksDirty) {
            // Normal case - blocks changed but not rebuilding
            sendTransformUpdate(true);
            rebuildComplete = true;
        } else if (!rebuildInProgress) {
            // Just send transform update
            sendTransformUpdate(false);
            rebuildComplete = true;
        } else {
            rebuildComplete = false;
        }

        // Check if we need to send a physics state update
        if (!rebuildInProgress) {
            sendPhysicsStateUpdate();
        }
    }

    /**
     * Sends a transform update packet to clients.
     *
     * @param sendBlocks Whether to include block data
     */
    private void sendTransformUpdate(boolean sendBlocks) {
        // Get current transform
        Transform currentTransform = new Transform();
        grid.getCurrentTransform(currentTransform);

        // Get current position and rotation
        Vector3f currentPosition = new Vector3f();
        currentTransform.origin.get(currentPosition);

        Quat4f currentRotation = new Quat4f();
        currentTransform.getRotation(currentRotation);

        // Skip if position hasn't changed much
        if (hasLastSentTransform) {
            float positionDiff = new org.joml.Vector3f(
                    lastSentPosition.x,
                    lastSentPosition.y,
                    lastSentPosition.z
            ).distance(currentPosition.x, currentPosition.y, currentPosition.z);

            float rotationDiff = quaternionDifference(lastSentRotation, currentRotation);

            if (positionDiff < POSITION_CHANGE_THRESHOLD && rotationDiff < 0.01f && !sendBlocks) {
                return;
            }
        }

        // Update last sent transform
        lastSentPosition.set(currentPosition);
        lastSentRotation.set(currentRotation);
        hasLastSentTransform = true;

        // Create packet
        PacketByteBuf buf = PacketByteBufs.create();

        // Write grid ID
        buf.writeUuid(grid.getGridId());

        // Write transform
        buf.writeFloat(currentPosition.x);
        buf.writeFloat(currentPosition.y);
        buf.writeFloat(currentPosition.z);

        // Write centroid
        Vector3f centroid = grid.getCentroid();
        buf.writeFloat(centroid.x);
        buf.writeFloat(centroid.y);
        buf.writeFloat(centroid.z);

        // Write rotation
        buf.writeFloat(currentRotation.x);
        buf.writeFloat(currentRotation.y);
        buf.writeFloat(currentRotation.z);
        buf.writeFloat(currentRotation.w);

        // Write whether blocks are included
        buf.writeBoolean(sendBlocks);

        // Write blocks if needed
        if (sendBlocks) {
            Map<BlockPos, LocalBlock> blocks = grid.getBlocks();
            buf.writeInt(blocks.size());

            for (Map.Entry<BlockPos, LocalBlock> entry : blocks.entrySet()) {
                BlockPos pos = entry.getKey();
                BlockState state = entry.getValue().getState();

                // Write position
                buf.writeInt(pos.getX());
                buf.writeInt(pos.getY());
                buf.writeInt(pos.getZ());

                // Write block state ID
                int rawId = Block.getRawIdFromState(state);
                buf.writeInt(rawId);
            }
        }

        // Send to clients
        int packetNum = packetCounter.incrementAndGet();
        boolean sent = broadcastPacket(TRANSFORM_UPDATE_PACKET_ID, buf);

        if (verbose) {
            SLogger.log(grid, "Transform packet #" + packetNum + (sent ? " sent" : " FAILED") +
                    " for grid " + grid.getGridId() +
                    ", position=" + currentPosition +
                    ", rotation=" + currentRotation +
                    ", blocks=" + (sendBlocks ? "included" : "not included"));
        }
    }

    /**
     * Sends a physics state update packet to clients.
     * This is separate from transform updates and is specifically for rendering.
     */
    private void sendPhysicsStateUpdate() {
        if (grid.getRigidBody() == null) return;

        // Get current physics state
        Vector3f currentPosition = new Vector3f();
        Vector3f centroid = grid.getCentroid();
        Quat4f currentRotation = new Quat4f();

        grid.getRigidBody().getCenterOfMassPosition(currentPosition);
        grid.getRigidBody().getOrientation(currentRotation);

        // Get current tick count to track timing
        long currentTickTime = grid.getWorld().getTime();

        // CRITICAL FIX: Always send an update at least once every few ticks
        boolean forceUpdate = (currentTickTime - lastPhysicsUpdateTime) > 3;

        boolean positionChanged = hasPositionChangedSignificantly(currentPosition, lastSentPhysicsPosition);
        boolean centroidChanged = hasPositionChangedSignificantly(centroid, lastSentPhysicsCentroid);
        boolean rotationChanged = hasRotationChangedSignificantly(currentRotation, lastSentPhysicsRotation);

        // Send if something changed or if we need to force an update
        if (positionChanged || centroidChanged || rotationChanged || forceUpdate) {
            // Update last sent physics state
            lastSentPhysicsPosition.set(currentPosition);
            lastSentPhysicsCentroid.set(centroid);
            lastSentPhysicsRotation.set(currentRotation);
            lastPhysicsUpdateTime = currentTickTime;
            physicsUpdatesCount++;

            // Create packet
            PacketByteBuf buf = PacketByteBufs.create();

            // Write grid ID
            buf.writeUuid(grid.getGridId());

            // Write tick time for sequencing
            buf.writeLong(currentTickTime);

            // Write current position
            buf.writeFloat(currentPosition.x);
            buf.writeFloat(currentPosition.y);
            buf.writeFloat(currentPosition.z);

            // Write centroid
            buf.writeFloat(centroid.x);
            buf.writeFloat(centroid.y);
            buf.writeFloat(centroid.z);

            // Write rotation
            buf.writeFloat(currentRotation.x);
            buf.writeFloat(currentRotation.y);
            buf.writeFloat(currentRotation.z);
            buf.writeFloat(currentRotation.w);

            // Send to clients
            int packetNum = packetCounter.incrementAndGet();
            boolean sent = broadcastPacket(PHYSICS_STATE_UPDATE_PACKET_ID, buf);

            if (verbose) {
                SLogger.log(grid, "Physics packet #" + packetNum + (sent ? " sent" : " FAILED") +
                        " for grid " + grid.getGridId() +
                        ", tick=" + currentTickTime +
                        ", pos=" + currentPosition +
                        ", update #" + physicsUpdatesCount +
                        (forceUpdate ? " (forced)" : ""));
            }
        }
    }

    /**
     * Broadcasts a packet to all players.
     *
     * @return True if the packet was sent to at least one player
     */
    private boolean broadcastPacket(net.minecraft.util.Identifier packetId, PacketByteBuf buf) {
        if (serverInstance == null) {
            SLogger.log(grid, "ERROR: serverInstance is null - cannot send packet!");
            return false;
        }

        boolean sentToAnyPlayer = false;

        for (ServerPlayerEntity player : serverInstance.getPlayerManager().getPlayerList()) {
            try {
                ServerPlayNetworking.send(player, packetId, buf);
                sentToAnyPlayer = true;
            } catch (Exception e) {
                SLogger.log(grid, "Failed to send packet to player " + player.getName() + ": " + e.getMessage());
            }
        }

        return sentToAnyPlayer;
    }

    /**
     * Calculates the angle between two quaternions.
     */
    private float quaternionDifference(Quat4f q1, Quat4f q2) {
        float dot = q1.x * q2.x + q1.y * q2.y + q1.z * q2.z + q1.w * q2.w;
        return (float) Math.acos(Math.min(Math.abs(dot), 1.0f)) * 2.0f;
    }

    /**
     * Checks if position has changed significantly.
     */
    private boolean hasPositionChangedSignificantly(Vector3f current, Vector3f previous) {
        float epsilon = 0.001f;
        return Math.abs(current.x - previous.x) > epsilon ||
                Math.abs(current.y - previous.y) > epsilon ||
                Math.abs(current.z - previous.z) > epsilon;
    }

    /**
     * Checks if rotation has changed significantly.
     */
    private boolean hasRotationChangedSignificantly(Quat4f current, Quat4f previous) {
        float epsilon = 0.001f;
        return Math.abs(current.x - previous.x) > epsilon ||
                Math.abs(current.y - previous.y) > epsilon ||
                Math.abs(current.z - previous.z) > epsilon ||
                Math.abs(current.w - previous.w) > epsilon;
    }

    // ----------------------------------------------
    // GETTERS / SETTERS
    // ----------------------------------------------
    /**
     * Sets whether a network update is pending.
     */
    public void setPendingNetworkUpdate(boolean pending) {
        this.pendingNetworkUpdate = pending;
    }

    /**
     * Gets whether a network update is pending.
     */
    public boolean isPendingNetworkUpdate() {
        return pendingNetworkUpdate;
    }

    /**
     * Checks if the network update is complete.
     */
    public boolean isUpdateComplete() {
        return rebuildComplete;
    }
}