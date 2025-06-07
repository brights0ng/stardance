package net.starlight.stardance.network;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.starlight.stardance.core.LocalBlock;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static net.starlight.stardance.Stardance.MOD_ID;
import static net.starlight.stardance.Stardance.serverInstance;

/**
 * Handles networking communication between server and client for grid synchronization.
 */
public class GridNetwork implements ILoggingControl {
    // Packet types
    private static final Identifier GRID_STATE_PACKET_ID = new Identifier(MOD_ID, "grid_state");
    private static final Identifier GRID_BLOCKS_PACKET_ID = new Identifier(MOD_ID, "grid_blocks");
    private static final Identifier GRID_REMOVE_PACKET_ID = new Identifier(MOD_ID, "grid_remove");

    // Debug
    private static final AtomicInteger packetCounter = new AtomicInteger(0);
    private static boolean verbose = true;

    // Singleton instance for client side
    private static GridNetwork INSTANCE;

    // Track if we've already registered client receivers
    private static boolean clientReceiversRegistered = false;

    /**
     * Gets the singleton instance.
     */
    public static GridNetwork getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new GridNetwork();
        }
        return INSTANCE;
    }

    /**
     * Initializes the network handlers.
     * Should be called early in mod initialization.
     */
    public static void init() {
        // Register client-side receivers
        registerClientReceivers();
    }

    /**
     * Registers client-side packet receivers.
     */
    private static synchronized void registerClientReceivers() {
        if (clientReceiversRegistered) {
            return;
        }

        GridNetwork loggingInstance = new GridNetwork();

        try {
            // Register grid state packet receiver
            ClientPlayNetworking.registerGlobalReceiver(GRID_STATE_PACKET_ID,
                    (client, handler, buf, responseSender) -> {
                        try {
                            // Read packet data
                            UUID gridId = buf.readUuid();
                            long serverTick = buf.readLong();

                            // Read position
                            float posX = buf.readFloat();
                            float posY = buf.readFloat();
                            float posZ = buf.readFloat();

                            // Read centroid
                            float centroidX = buf.readFloat();
                            float centroidY = buf.readFloat();
                            float centroidZ = buf.readFloat();

                            // Read rotation
                            float rotX = buf.readFloat();
                            float rotY = buf.readFloat();
                            float rotZ = buf.readFloat();
                            float rotW = buf.readFloat();

                            // Create vectors
                            Vector3f position = new Vector3f(posX, posY, posZ);
                            Vector3f centroid = new Vector3f(centroidX, centroidY, centroidZ);
                            Quat4f rotation = new Quat4f(rotX, rotY, rotZ, rotW);

                            int packetNum = packetCounter.incrementAndGet();
                            if (verbose) {
                                SLogger.log(loggingInstance, "Received grid state packet #" + packetNum +
                                        " for grid " + gridId +
                                        " at tick " + serverTick +
                                        " pos=" + position);
                            }

                            // Queue the update on the main thread
                            client.execute(() -> {
                                try {
                                    // Update the client grid registry
                                    ClientGridManager registry = ClientGridManager.getInstance();
                                    registry.updateGridState(gridId, position, rotation, centroid, serverTick);
                                } catch (Exception e) {
                                    SLogger.log(loggingInstance, "Error processing grid state: " + e.getMessage());
                                    e.printStackTrace();
                                }
                            });
                        } catch (Exception e) {
                            SLogger.log(loggingInstance, "Error reading grid state packet: " + e.getMessage());
                            e.printStackTrace();
                        }
                    }
            );

            // Register grid blocks packet receiver
            ClientPlayNetworking.registerGlobalReceiver(GRID_BLOCKS_PACKET_ID,
                    (client, handler, buf, responseSender) -> {
                        try {
                            // Read grid ID
                            UUID gridId = buf.readUuid();

                            // Read block count
                            int blockCount = buf.readInt();

                            Map<BlockPos, BlockState> blocks = new HashMap<>();

                            // Read each block
                            for (int i = 0; i < blockCount; i++) {
                                // Read position
                                int x = buf.readInt();
                                int y = buf.readInt();
                                int z = buf.readInt();
                                BlockPos pos = new BlockPos(x, y, z);

                                // Read block state ID
                                int blockStateId = buf.readInt();
                                BlockState state = Block.getStateFromRawId(blockStateId);

                                // Add to map
                                blocks.put(pos, state);
                            }

                            int packetNum = packetCounter.incrementAndGet();
                            if (verbose) {
                                SLogger.log(loggingInstance, "Received grid blocks packet #" + packetNum +
                                        " for grid " + gridId +
                                        " with " + blockCount + " blocks");
                            }

                            // Queue the update on the main thread
                            client.execute(() -> {
                                try {
                                    // Update the client grid registry
                                    ClientGridManager registry = ClientGridManager.getInstance();
                                    registry.updateGridBlocks(gridId, blocks);
                                } catch (Exception e) {
                                    SLogger.log(loggingInstance, "Error processing grid blocks: " + e.getMessage());
                                    e.printStackTrace();
                                }
                            });
                        } catch (Exception e) {
                            SLogger.log(loggingInstance, "Error reading grid blocks packet: " + e.getMessage());
                            e.printStackTrace();
                        }
                    }
            );

            // Register grid remove packet receiver
            ClientPlayNetworking.registerGlobalReceiver(GRID_REMOVE_PACKET_ID,
                    (client, handler, buf, responseSender) -> {
                        try {
                            // Read grid ID
                            UUID gridId = buf.readUuid();

                            int packetNum = packetCounter.incrementAndGet();
                            if (verbose) {
                                SLogger.log(loggingInstance, "Received grid remove packet #" + packetNum +
                                        " for grid " + gridId);
                            }

                            // Queue the update on the main thread
                            client.execute(() -> {
                                try {
                                    // Update the client grid registry
                                    ClientGridManager registry = ClientGridManager.getInstance();
                                    registry.removeGrid(gridId);
                                } catch (Exception e) {
                                    SLogger.log(loggingInstance, "Error processing grid remove: " + e.getMessage());
                                    e.printStackTrace();
                                }
                            });
                        } catch (Exception e) {
                            SLogger.log(loggingInstance, "Error reading grid remove packet: " + e.getMessage());
                            e.printStackTrace();
                        }
                    }
            );

            SLogger.log(loggingInstance, "Grid network packet handlers registered");
            clientReceiversRegistered = true;

        } catch (Exception e) {
            SLogger.log(loggingInstance, "ERROR registering grid network receivers: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Sends a grid state update to all clients.
     */
    public static void sendGridState(LocalGrid grid) {
        if (grid == null || grid.getRigidBody() == null || serverInstance == null) {
            return;
        }

        try {
            // Create packet
            PacketByteBuf buf = PacketByteBufs.create();

            // Write grid ID
            buf.writeUuid(grid.getGridId());

            // Get current server tick - CRUCIAL for ordering updates
            long serverTick = grid.getWorld().getTime();

            // Write server tick
            buf.writeLong(serverTick);

            // Get current position
            Vector3f currentPosition = new Vector3f();
            grid.getRigidBody().getCenterOfMassPosition(currentPosition);

            // Write position
            buf.writeFloat(currentPosition.x);
            buf.writeFloat(currentPosition.y);
            buf.writeFloat(currentPosition.z);

            // Write centroid
            Vector3f centroid = grid.getCentroid();
            buf.writeFloat(centroid.x);
            buf.writeFloat(centroid.y);
            buf.writeFloat(centroid.z);

            // Get current rotation
            Quat4f currentRotation = new Quat4f();
            grid.getRigidBody().getOrientation(currentRotation);

            // Write rotation
            buf.writeFloat(currentRotation.x);
            buf.writeFloat(currentRotation.y);
            buf.writeFloat(currentRotation.z);
            buf.writeFloat(currentRotation.w);

            // Send to all players
            int packetNum = packetCounter.incrementAndGet();
            broadcastToAllPlayers(GRID_STATE_PACKET_ID, buf);

            GridNetwork loggingInstance = new GridNetwork();
            if (verbose) {
                SLogger.log(loggingInstance, "Sent grid state packet #" + packetNum +
                        " for grid " + grid.getGridId() +
                        " at tick " + serverTick +
                        " pos=" + currentPosition);
            }
        } catch (Exception e) {
            GridNetwork loggingInstance = new GridNetwork();
            SLogger.log(loggingInstance, "Error sending grid state: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Sends grid block data to all clients.
     */
    public static void sendGridBlocks(LocalGrid grid) {
        if (grid == null || serverInstance == null) {
            return;
        }

        try {
            // Create packet
            PacketByteBuf buf = PacketByteBufs.create();

            // Write grid ID
            buf.writeUuid(grid.getGridId());

            // Get blocks
            Map<BlockPos, LocalBlock> blocks = grid.getBlocks();

            // Write block count
            buf.writeInt(blocks.size());

            // Write each block
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

            // Send to all players
            int packetNum = packetCounter.incrementAndGet();
            broadcastToAllPlayers(GRID_BLOCKS_PACKET_ID, buf);

            GridNetwork loggingInstance = new GridNetwork();
            if (verbose) {
                SLogger.log(loggingInstance, "Sent grid blocks packet #" + packetNum +
                        " for grid " + grid.getGridId() +
                        " with " + blocks.size() + " blocks");
            }
        } catch (Exception e) {
            GridNetwork loggingInstance = new GridNetwork();
            SLogger.log(loggingInstance, "Error sending grid blocks: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Sends a grid remove notification to all clients.
     */
    public static void sendGridRemove(UUID gridId) {
        if (serverInstance == null) {
            return;
        }

        try {
            // Create packet
            PacketByteBuf buf = PacketByteBufs.create();

            // Write grid ID
            buf.writeUuid(gridId);

            // Send to all players
            int packetNum = packetCounter.incrementAndGet();
            broadcastToAllPlayers(GRID_REMOVE_PACKET_ID, buf);

            GridNetwork loggingInstance = new GridNetwork();
            if (verbose) {
                SLogger.log(loggingInstance, "Sent grid remove packet #" + packetNum +
                        " for grid " + gridId);
            }
        } catch (Exception e) {
            GridNetwork loggingInstance = new GridNetwork();
            SLogger.log(loggingInstance, "Error sending grid remove: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Broadcasts a packet to all players.
     */
    private static void broadcastToAllPlayers(Identifier packetId, PacketByteBuf buf) {
        if (serverInstance == null) {
            return;
        }

        for (ServerPlayerEntity player : serverInstance.getPlayerManager().getPlayerList()) {
            try {
                ServerPlayNetworking.send(player, packetId, buf);
            } catch (Exception e) {
                GridNetwork loggingInstance = new GridNetwork();
                SLogger.log(loggingInstance, "Error sending packet to player " + player.getName().getString() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Sets the verbosity of logging.
     */
    public static void setVerbose(boolean isVerbose) {
        verbose = isVerbose;
    }

    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return false;
    }
}