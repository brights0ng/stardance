package net.starlight.stardance.network;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.starlight.stardance.core.LocalBlock;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.gridspace.GridSpaceRegion;
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
 * Updated GridSpace-aware networking system.
 * Now sends GridSpace block data instead of LocalGrid block data for proper coordinate handling.
 */
public class GridNetwork implements ILoggingControl {
    // Packet types
    private static final ResourceLocation GRID_STATE_PACKET_ID = new ResourceLocation(MOD_ID, "grid_state");
    private static final ResourceLocation GRID_BLOCKS_PACKET_ID = new ResourceLocation(MOD_ID, "grid_blocks");
    private static final ResourceLocation GRID_REMOVE_PACKET_ID = new ResourceLocation(MOD_ID, "grid_remove");

    // NEW: GridSpace info packet for client-side region setup
    private static final ResourceLocation GRID_SPACE_INFO_PACKET_ID = new ResourceLocation(MOD_ID, "gridspace_info");

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
     * Registers client-side packet receivers with GridSpace support.
     */
    private static synchronized void registerClientReceivers() {
        if (clientReceiversRegistered) {
            return;
        }

        GridNetwork loggingInstance = new GridNetwork();

        try {
            // Register grid state packet receiver (unchanged)
            ClientPlayNetworking.registerGlobalReceiver(GRID_STATE_PACKET_ID,
                    (client, handler, buf, responseSender) -> {
                        try {
                            // Read packet data
                            UUID gridId = buf.readUUID();
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

            // NEW: Register GridSpace info packet receiver
            ClientPlayNetworking.registerGlobalReceiver(GRID_SPACE_INFO_PACKET_ID,
                    (client, handler, buf, responseSender) -> {
                        try {
                            UUID gridId = buf.readUUID();
                            int regionId = buf.readInt();

                            // Read GridSpace region origin
                            int originX = buf.readInt();
                            int originY = buf.readInt();
                            int originZ = buf.readInt();
                            BlockPos regionOrigin = new BlockPos(originX, originY, originZ);

                            int packetNum = packetCounter.incrementAndGet();
                            if (verbose) {
                                SLogger.log(loggingInstance, "Received GridSpace info packet #" + packetNum +
                                        " for grid " + gridId +
                                        ", regionId=" + regionId +
                                        ", origin=" + regionOrigin);
                            }

                            // Queue the update on the main thread
                            client.execute(() -> {
                                try {
                                    ClientGridManager registry = ClientGridManager.getInstance();
                                    registry.updateGridSpaceInfo(gridId, regionId, regionOrigin);
                                } catch (Exception e) {
                                    SLogger.log(loggingInstance, "Error processing GridSpace info: " + e.getMessage());
                                    e.printStackTrace();
                                }
                            });
                        } catch (Exception e) {
                            SLogger.log(loggingInstance, "Error reading GridSpace info packet: " + e.getMessage());
                            e.printStackTrace();
                        }
                    }
            );

            // UPDATED: Register grid blocks packet receiver for GridSpace blocks
            ClientPlayNetworking.registerGlobalReceiver(GRID_BLOCKS_PACKET_ID,
                    (client, handler, buf, responseSender) -> {
                        try {
                            // Read grid ID
                            UUID gridId = buf.readUUID();

                            // Read block count
                            int blockCount = buf.readInt();

                            // NEW: Read coordinate system flag
                            boolean useGridSpaceCoords = buf.readBoolean();

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
                                BlockState state = Block.stateById(blockStateId);

                                // Add to map
                                blocks.put(pos, state);
                            }

                            int packetNum = packetCounter.incrementAndGet();
                            if (verbose) {
                                SLogger.log(loggingInstance, "Received grid blocks packet #" + packetNum +
                                        " for grid " + gridId +
                                        " with " + blockCount + " blocks" +
                                        " (GridSpace coords: " + useGridSpaceCoords + ")");
                            }

                            // Queue the update on the main thread
                            client.execute(() -> {
                                try {
                                    // Update the client grid registry
                                    ClientGridManager registry = ClientGridManager.getInstance();

                                    if (useGridSpaceCoords) {
                                        // NEW: Handle GridSpace coordinates
                                        registry.updateGridSpaceBlocks(gridId, blocks);
                                    } else {
                                        // OLD: Handle grid-local coordinates (fallback)
                                        registry.updateGridBlocks(gridId, blocks);
                                    }
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

            // Register grid remove packet receiver (unchanged)
            ClientPlayNetworking.registerGlobalReceiver(GRID_REMOVE_PACKET_ID,
                    (client, handler, buf, responseSender) -> {
                        try {
                            // Read grid ID
                            UUID gridId = buf.readUUID();

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

            SLogger.log(loggingInstance, "GridSpace-aware network packet handlers registered");
            clientReceiversRegistered = true;

        } catch (Exception e) {
            SLogger.log(loggingInstance, "ERROR registering grid network receivers: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ----------------------------------------------
    // SERVER-SIDE PACKET SENDING (UPDATED FOR GRIDSPACE)
    // ----------------------------------------------

    /**
     * NEW: Sends GridSpace info to clients when a grid is created.
     */
    public static void sendGridSpaceInfo(LocalGrid grid) {
        if (serverInstance == null) return;

        try {
            GridSpaceRegion region = grid.getGridSpaceRegion();
            if (region == null) {
                SLogger.log(getInstance(), "Cannot send GridSpace info - region is null for grid " + grid.getGridId());
                return;
            }

            FriendlyByteBuf buf = PacketByteBufs.create();

            // Write grid ID
            buf.writeUUID(grid.getGridId());

            // Write region info
            buf.writeInt(region.getRegionId());

            // Write region origin
            BlockPos origin = region.getRegionOrigin();
            buf.writeInt(origin.getX());
            buf.writeInt(origin.getY());
            buf.writeInt(origin.getZ());

            // Send to all players
            for (ServerPlayer player : serverInstance.getPlayerList().getPlayers()) {
                ServerPlayNetworking.send(player, GRID_SPACE_INFO_PACKET_ID, buf);
            }

            if (verbose) {
                SLogger.log(getInstance(), "Sent GridSpace info for grid " + grid.getGridId() +
                        ", regionId=" + region.getRegionId() + ", origin=" + origin);
            }

        } catch (Exception e) {
            SLogger.log(getInstance(), "Error sending GridSpace info: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * UPDATED: Sends GridSpace block data to clients instead of LocalGrid blocks.
     */
    public static void sendGridBlocks(LocalGrid grid) {
        if (serverInstance == null) return;

        try {
            // Get blocks from GridSpace instead of LocalGrid
            Map<BlockPos, BlockState> gridSpaceBlocks = getGridSpaceBlocks(grid);

            if (gridSpaceBlocks.isEmpty()) {
                if (verbose) {
                    SLogger.log(getInstance(), "No GridSpace blocks to send for grid " + grid.getGridId());
                }
                return;
            }

            FriendlyByteBuf buf = PacketByteBufs.create();

            // Write grid ID
            buf.writeUUID(grid.getGridId());

            // Write block count
            buf.writeInt(gridSpaceBlocks.size());

            // NEW: Write coordinate system flag (true = GridSpace coordinates)
            buf.writeBoolean(true);

            // Write each block with GridSpace coordinates
            for (Map.Entry<BlockPos, BlockState> entry : gridSpaceBlocks.entrySet()) {
                BlockPos gridSpacePos = entry.getKey();
                BlockState state = entry.getValue();

                // Write GridSpace position
                buf.writeInt(gridSpacePos.getX());
                buf.writeInt(gridSpacePos.getY());
                buf.writeInt(gridSpacePos.getZ());

                // Write block state
                buf.writeInt(Block.getId(state));
            }

            // Send to all players
            for (ServerPlayer player : serverInstance.getPlayerList().getPlayers()) {
                ServerPlayNetworking.send(player, GRID_BLOCKS_PACKET_ID, buf);
            }

            if (verbose) {
                SLogger.log(getInstance(), "Sent GridSpace blocks for grid " + grid.getGridId() +
                        ", count=" + gridSpaceBlocks.size());
            }

        } catch (Exception e) {
            SLogger.log(getInstance(), "Error sending grid blocks: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * NEW: Gets blocks from GridSpace instead of LocalGrid's block map.
     * This reads the actual blocks stored in GridSpace coordinates.
     */
    private static Map<BlockPos, BlockState> getGridSpaceBlocks(LocalGrid grid) {
        Map<BlockPos, BlockState> gridSpaceBlocks = new HashMap<>();

        try {
            GridSpaceRegion region = grid.getGridSpaceRegion();
            if (region == null || region.isCleanedUp()) {
                return gridSpaceBlocks;
            }

            // Convert LocalGrid blocks to GridSpace coordinates and states
            for (Map.Entry<BlockPos, LocalBlock> entry : grid.getBlocks().entrySet()) {
                BlockPos gridLocalPos = entry.getKey();
                LocalBlock localBlock = entry.getValue();

                // Convert grid-local position to GridSpace position
                BlockPos gridSpacePos = grid.gridLocalToGridSpace(gridLocalPos);

                // Use the block state from the local block
                BlockState blockState = localBlock.getState();

                gridSpaceBlocks.put(gridSpacePos, blockState);
            }

            if (verbose) {
                SLogger.log(getInstance(), "Converted " + grid.getBlocks().size() +
                        " local blocks to " + gridSpaceBlocks.size() + " GridSpace blocks");
            }

        } catch (Exception e) {
            SLogger.log(getInstance(), "Error getting GridSpace blocks: " + e.getMessage());
            e.printStackTrace();
        }

        return gridSpaceBlocks;
    }

    /**
     * Sends a grid state update to all clients (unchanged).
     */
    public static void sendGridState(LocalGrid grid) {
        if (serverInstance == null) return;

        try {
            // Get current physics state
            Vector3f position = new Vector3f();
            Vector3f centroid = grid.getCentroid();
            Quat4f rotation = new Quat4f();

            // Get transform
            com.bulletphysics.linearmath.Transform transform = new com.bulletphysics.linearmath.Transform();
            grid.getCurrentTransform(transform);

            // Extract position and rotation
            transform.origin.get(position);
            transform.getRotation(rotation);

            FriendlyByteBuf buf = PacketByteBufs.create();

            // Write grid ID and server tick
            buf.writeUUID(grid.getGridId());
            buf.writeLong(grid.getWorld().getGameTime());

            // Write position
            buf.writeFloat(position.x);
            buf.writeFloat(position.y);
            buf.writeFloat(position.z);

            // Write centroid
            buf.writeFloat(centroid.x);
            buf.writeFloat(centroid.y);
            buf.writeFloat(centroid.z);

            // Write rotation
            buf.writeFloat(rotation.x);
            buf.writeFloat(rotation.y);
            buf.writeFloat(rotation.z);
            buf.writeFloat(rotation.w);

            // Send to all players
            for (ServerPlayer player : serverInstance.getPlayerList().getPlayers()) {
                ServerPlayNetworking.send(player, GRID_STATE_PACKET_ID, buf);
            }

        } catch (Exception e) {
            SLogger.log(getInstance(), "Error sending grid state: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Sends a grid removal notification to all clients (unchanged).
     */
    public static void sendGridRemove(UUID gridId) {
        if (serverInstance == null) return;

        try {
            FriendlyByteBuf buf = PacketByteBufs.create();
            buf.writeUUID(gridId);

            // Send to all players
            for (ServerPlayer player : serverInstance.getPlayerList().getPlayers()) {
                ServerPlayNetworking.send(player, GRID_REMOVE_PACKET_ID, buf);
            }

            if (verbose) {
                SLogger.log(getInstance(), "Sent grid remove for " + gridId);
            }

        } catch (Exception e) {
            SLogger.log(getInstance(), "Error sending grid remove: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ----------------------------------------------
    // LOGGING CONTROL
    // ----------------------------------------------

    @Override
    public boolean stardance$isChatLoggingEnabled() { return false; }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() { return false; }
}