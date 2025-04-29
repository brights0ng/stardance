package net.stardance.physics;

import com.bulletphysics.collision.broadphase.CollisionFilterGroups;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.stardance.utils.ILoggingControl;

import java.util.concurrent.ConcurrentHashMap;

import static net.stardance.Stardance.serverInstance;

/**
 * Manages physics engines for multiple worlds.
 * Provides a centralized way to access the physics engine
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
        return false;
    }

    // ----------------------------------------------
    // PUBLIC METHODS
    // ----------------------------------------------

    /**
     * Loads or creates a PhysicsEngine for the specified world.
     * Only creates a new engine if one doesn't already exist.
     *
     * @param world The server world to load an engine for
     */
    public void load(ServerWorld world) {
        if (!engines.containsKey(world)) {
            PhysicsEngine engine = new PhysicsEngine(world);
            engines.put(world, engine);
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
        }
    }
}