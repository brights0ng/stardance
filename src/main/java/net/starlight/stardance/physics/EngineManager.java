package net.starlight.stardance.physics;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.gridspace.GridSpaceManager;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;
import org.joml.Vector3f;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static net.starlight.stardance.Stardance.serverInstance;

/**
 * Manages physics engines and GridSpace managers for multiple worlds.
 * Provides a centralized way to access both the physics engine and GridSpace manager
 * for any given server world.
 */
public class EngineManager implements ILoggingControl {

    // ----------------------------------------------
    // FIELDS
    // ----------------------------------------------

    /**
     * Maps ServerWorlds to their respective PhysicsEngines.
     * Uses ConcurrentHashMap for thread safety.
     */
    private final ConcurrentHashMap<ServerWorld, PhysicsEngine> engines = new ConcurrentHashMap<>();

    /**
     * Maps ServerWorlds to their respective GridSpaceManagers.
     * Each dimension gets its own GridSpace manager to prevent interference.
     */
    private final ConcurrentHashMap<ServerWorld, GridSpaceManager> gridSpaceManagers = new ConcurrentHashMap<>();

    public static final short COLLISION_GROUP_ENTITY = 4;
    public static final short COLLISION_GROUP_GRID = 1;
    public static final short COLLISION_GROUP_MESH = 2;
    public static final short COLLISION_MASK_ENTITY = COLLISION_GROUP_GRID;
    public static final short COLLISION_MASK_GRID = (short)(COLLISION_GROUP_GRID | COLLISION_GROUP_ENTITY | COLLISION_GROUP_MESH);
    public static final short COLLISION_MASK_MESH = COLLISION_GROUP_GRID;

    // ----------------------------------------------
    // LOGGING CONTROL
    // ----------------------------------------------

    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return true; // Enable for GridSpace initialization logging
    }

    // ----------------------------------------------
    // PUBLIC METHODS
    // ----------------------------------------------

    /**
     * Loads or creates a PhysicsEngine and GridSpaceManager for the specified world.
     * Only creates new instances if they don't already exist.
     *
     * @param world The server world to load engines for
     */
    public void load(ServerWorld world) {
        // Load PhysicsEngine
        if (!engines.containsKey(world)) {
            PhysicsEngine engine = new PhysicsEngine(world);
            engines.put(world, engine);
            SLogger.log(this, "Created PhysicsEngine for dimension: " + world.getRegistryKey().getValue());
        }

        // Load GridSpaceManager
        if (!gridSpaceManagers.containsKey(world)) {
            GridSpaceManager gridSpaceManager = new GridSpaceManager(world);
            gridSpaceManagers.put(world, gridSpaceManager);
            SLogger.log(this, "Created GridSpaceManager for dimension: " + world.getRegistryKey().getValue());
        }
    }

    /**
     * Gets the PhysicsEngine for a specific ServerWorld.
     *
     * @param world The server world
     * @return The PhysicsEngine for the world, or null if not loaded
     */
    public PhysicsEngine getEngine(ServerWorld world) {
        return engines.get(world);
    }

    /**
     * Gets the PhysicsEngine for a generic World by mapping
     * it to the corresponding ServerWorld.
     *
     * @param world A World instance
     * @return The PhysicsEngine for the world, or null if not found
     */
    public PhysicsEngine getEngine(World world) {
        if (serverInstance == null) {
            return null;
        }

        ServerWorld serverWorld = serverInstance.getWorld(world.getRegistryKey());
        return serverWorld != null ? engines.get(serverWorld) : null;
    }

    /**
     * Gets the GridSpaceManager for a specific ServerWorld.
     *
     * @param world The server world
     * @return The GridSpaceManager for the world, or null if not loaded
     */
    public GridSpaceManager getGridSpaceManager(ServerWorld world) {
        return gridSpaceManagers.get(world);
    }

    /**
     * Gets the GridSpaceManager for a generic World by mapping
     * it to the corresponding ServerWorld.
     *
     * @param world A World instance
     * @return The GridSpaceManager for the world, or null if not found
     */
    public GridSpaceManager getGridSpaceManager(World world) {
        if (serverInstance == null) {
            return null;
        }

        ServerWorld serverWorld = serverInstance.getWorld(world.getRegistryKey());
        return serverWorld != null ? gridSpaceManagers.get(serverWorld) : null;
    }

    /**
     * Updates all physics engines.
     * Called once per server tick.
     *
     * @param server The Minecraft server instance
     */
    public void tick(MinecraftServer server) {
        for (ServerWorld world : server.getWorlds()) {
            PhysicsEngine engine = engines.get(world);
            if (engine != null) {
                engine.tick(world);
            }
            // Note: GridSpaceManagers don't need ticking - they're stateless managers
        }
    }

    /**
     * Shuts down all engines and managers for a specific world.
     * Called when a world is unloaded.
     *
     * @param world The server world being unloaded
     */
    public void unload(ServerWorld world) {
        // Shutdown and remove PhysicsEngine
        PhysicsEngine engine = engines.remove(world);
        if (engine != null) {
            SLogger.log(this, "Shutting down PhysicsEngine for dimension: " + world.getRegistryKey().getValue());
            // Add any necessary PhysicsEngine cleanup here
        }

        // Shutdown and remove GridSpaceManager
        GridSpaceManager gridSpaceManager = gridSpaceManagers.remove(world);
        if (gridSpaceManager != null) {
            SLogger.log(this, "Shutting down GridSpaceManager for dimension: " + world.getRegistryKey().getValue());
            gridSpaceManager.shutdown();
        }
    }

    /**
     * Shuts down all engines and managers.
     * Called during server shutdown.
     */
    public void shutdown() {
        SLogger.log(this, "Shutting down EngineManager - cleaning up " + engines.size() +
                " physics engines and " + gridSpaceManagers.size() + " GridSpace managers");

        // Shutdown all physics engines
        for (PhysicsEngine engine : engines.values()) {
            // Add any necessary PhysicsEngine shutdown logic here
        }
        engines.clear();

        // Shutdown all GridSpace managers
        for (GridSpaceManager manager : gridSpaceManagers.values()) {
            manager.shutdown();
        }
        gridSpaceManagers.clear();
    }

    /**
     * Utility method to find which grid a player is looking at.
     */
    public LocalGrid getGridPlayerIsLookingAt(PlayerEntity player) {
        // Reuse the detection logic
        PhysicsEngine engine = getEngine(player.getWorld());
        if (engine == null) {
            return null;
        }

        Set<LocalGrid> grids = engine.getGrids();
        float playerReachDistance = 4.5f;

        Vec3d eyePos = player.getEyePos();
        Vec3d lookVec = player.getRotationVec(1.0F);
        Vec3d reachPoint = eyePos.add(lookVec.multiply(playerReachDistance));

        for (LocalGrid grid : grids) {
            if (gridIntersectsRay(grid, eyePos, reachPoint)) {
                return grid;
            }
        }
        return null;
    }

    private boolean gridIntersectsRay(LocalGrid grid, Vec3d start, Vec3d end) {
        javax.vecmath.Vector3f minAabb = new javax.vecmath.Vector3f();
        javax.vecmath.Vector3f maxAabb = new javax.vecmath.Vector3f();
        grid.getAABB(minAabb, maxAabb);

        Box gridBox = new Box(minAabb.x, minAabb.y, minAabb.z,
                maxAabb.x, maxAabb.y, maxAabb.z);

        return gridBox.intersects(start, end);
    }

    /**
     * Utility method for block breaking from static contexts.
     */
    public boolean breakGridBlock(PlayerEntity player) {
        // You can create a static instance or delegate to the handler
        // For now, let's keep it simple and delegate to the existing handler
        return false; // Implement if needed
    }

    /**
     * Gets statistics for all GridSpace managers.
     * Useful for monitoring and debugging.
     */
    public String getGridSpaceStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("GridSpace Statistics:\n");

        for (GridSpaceManager manager : gridSpaceManagers.values()) {
            stats.append("  ").append(manager.getStats().toString()).append("\n");
        }

        return stats.toString();
    }
}