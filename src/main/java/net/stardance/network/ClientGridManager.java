package net.stardance.network;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.stardance.network.ClientLocalGrid;
import net.stardance.utils.ILoggingControl;
import net.stardance.utils.SLogger;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for client-side grid instances.
 * Manages creation, updates, and rendering of ClientLocalGrids.
 */
public class ClientGridManager implements ILoggingControl {
    // Singleton instance
    private static ClientGridManager INSTANCE;

    // Map of grid ID to client grid
    private final Map<UUID, ClientLocalGrid> grids = new ConcurrentHashMap<>();

    // Debug
    private boolean verbose = false;

    /**
     * Gets the singleton instance of the registry.
     */
    public static ClientGridManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ClientGridManager();
        }
        return INSTANCE;
    }

    /**
     * Private constructor for singleton.
     */
    private ClientGridManager() {
        // Private constructor for singleton
    }

    /**
     * Gets or creates a client grid with the given ID.
     */
    public ClientLocalGrid getOrCreateGrid(UUID gridId) {
        return grids.computeIfAbsent(gridId, id -> {
            ClientLocalGrid grid = new ClientLocalGrid(id);
            SLogger.log(this, "Created new client grid: " + id);
            return grid;
        });
    }

    /**
     * Updates a grid's state.
     */
    public void updateGridState(UUID gridId, Vector3f position, Quat4f rotation, Vector3f centroid, long serverTick) {
        ClientLocalGrid grid = getOrCreateGrid(gridId);
        grid.updateState(position, rotation, centroid, serverTick);

        if (verbose) {
            SLogger.log(this, "Updated grid state: " + gridId + ", serverTick=" + serverTick + ", pos=" + position);
        }
    }

    /**
     * Updates a grid's blocks.
     */
    public void updateGridBlocks(UUID gridId, Map<BlockPos, BlockState> blocks) {
        ClientLocalGrid grid = getOrCreateGrid(gridId);
        grid.updateBlocks(blocks);

        if (verbose) {
            SLogger.log(this, "Updated grid blocks: " + gridId + ", count=" + blocks.size());
        }
    }

    /**
     * Updates a single block in a grid.
     */
    public void updateGridBlock(UUID gridId, BlockPos pos, BlockState state) {
        ClientLocalGrid grid = getOrCreateGrid(gridId);
        grid.updateBlock(pos, state);
    }

    /**
     * Removes a grid from the registry.
     */
    public void removeGrid(UUID gridId) {
        grids.remove(gridId);

        if (verbose) {
            SLogger.log(this, "Removed grid: " + gridId);
        }
    }

    /**
     * Renders all registered grids.
     */
    public void renderGrids(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                            float partialServerTick, long currentWorldTick) {
        for (ClientLocalGrid grid : grids.values()) {
            grid.render(matrices, vertexConsumers, partialServerTick, currentWorldTick);
        }
    }

    /**
     * Gets a grid by ID.
     */
    public ClientLocalGrid getGrid(UUID gridId) {
        return grids.get(gridId);
    }

    /**
     * Gets all registered grids.
     */
    public Map<UUID, ClientLocalGrid> getGrids() {
        return grids;
    }





    /**
     * Clears all registered grids.
     */
    public void clear() {
        grids.clear();

        if (verbose) {
            SLogger.log(this, "Cleared all client grids");
        }
    }

    /**
     * Removes grids that haven't been updated for a while.
     */
    public void cleanup(long currentTick, long maxTickAge) {
        // Implementation would track last update time for each grid and remove stale ones
    }

    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return true;
    }
}