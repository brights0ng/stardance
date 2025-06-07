package net.starlight.stardance.physics;

import com.bulletphysics.collision.broadphase.AxisSweep3;
import com.bulletphysics.collision.broadphase.BroadphaseInterface;
import com.bulletphysics.collision.dispatch.CollisionDispatcher;
import com.bulletphysics.collision.dispatch.CollisionObject;
import com.bulletphysics.collision.dispatch.CollisionConfiguration;
import com.bulletphysics.collision.dispatch.DefaultCollisionConfiguration;
import com.bulletphysics.collision.narrowphase.ManifoldPoint;
import com.bulletphysics.collision.narrowphase.PersistentManifold;
import com.bulletphysics.dynamics.DiscreteDynamicsWorld;
import com.bulletphysics.dynamics.DynamicsWorld;
import com.bulletphysics.dynamics.RigidBody;
import com.bulletphysics.dynamics.constraintsolver.ConstraintSolver;
import com.bulletphysics.dynamics.constraintsolver.SequentialImpulseConstraintSolver;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.physics.entity.EntityPhysicsManager;
import net.starlight.stardance.utils.BlockEventHandler;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;

import javax.vecmath.Vector3f;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static net.starlight.stardance.physics.EngineManager.COLLISION_GROUP_GRID;
import static net.starlight.stardance.physics.EngineManager.COLLISION_MASK_GRID;

/**
 * Core physics simulation controller for a ServerWorld.
 * Manages bullet physics integration, contact detection, and entity interactions.
 */
public class PhysicsEngine implements ILoggingControl {

    // -------------------------------------------
    // CONSTANTS
    // -------------------------------------------

    private static final float TICK_DELTA = 1f / 20f;  // 20 ticks per second
    private static final int MAX_SUB_STEPS = 5;        // Max physics substeps per tick (reduced from 120)
    private static final Vector3f WORLD_AABB_MIN = new Vector3f(-10000, -1000, -10000); // Expanded X/Z limits
    private static final Vector3f WORLD_AABB_MAX = new Vector3f(10000, 1000, 10000);    // Expanded X/Z limits
    private static final Vector3f GRAVITY = new Vector3f(0, -36.2f, 0);  // Minecraft gravity

    // -------------------------------------------
    // PHYSICS COMPONENTS
    // -------------------------------------------

    private final Object physicsLock = new Object();
    private final ServerWorld serverWorld;

    // Bullet physics core components
    private final BroadphaseInterface broadphase;
    private final CollisionConfiguration collisionConfiguration;
    private final CollisionDispatcher dispatcher;
    private final ConstraintSolver solver;
    private final DynamicsWorld dynamicsWorld;

    // -------------------------------------------
    // SUBSYSTEMS
    // -------------------------------------------

    private final SubchunkManager subchunkManager;
    private final Set<LocalGrid> localGrids = ConcurrentHashMap.newKeySet();
    private final EntityPhysicsManager entityPhysicsManager;

    // -------------------------------------------
    // CONSTRUCTOR
    // -------------------------------------------

    /**
     * Creates a new physics engine for the given ServerWorld.
     */
    public PhysicsEngine(ServerWorld serverWorld) {
        this.serverWorld = serverWorld;

        // Initialize Bullet Physics components
        this.broadphase = new AxisSweep3(WORLD_AABB_MIN, WORLD_AABB_MAX);
        this.collisionConfiguration = new DefaultCollisionConfiguration();
        this.dispatcher = new CollisionDispatcher(collisionConfiguration);
        this.solver = new SequentialImpulseConstraintSolver();

        // Create dynamics world with custom dispatcher
        this.dynamicsWorld = new DiscreteDynamicsWorld(dispatcher, broadphase, solver, collisionConfiguration);
        this.dynamicsWorld.setGravity(GRAVITY);

        // Initialize an empty world with one step
        this.dynamicsWorld.stepSimulation(TICK_DELTA, 1);

        // Initialize subsystems
        this.subchunkManager = new SubchunkManager(dynamicsWorld, serverWorld);
        this.entityPhysicsManager = new EntityPhysicsManager(this,serverWorld);
        // Connect block events to subchunk manager
        new BlockEventHandler(subchunkManager);
    }

    // -------------------------------------------
    // PUBLIC METHODS
    // -------------------------------------------

    /**
     * Advances the physics simulation by one game tick.
     * Called once per server tick for the world.
     */
    public void tick(ServerWorld world) {
        // Update chunk-based data
        subchunkManager.updateDirtySubchunks();

        // In PhysicsEngine.tick method, before stepSimulation
        // Add this debugging code to identify problematic collisions
//        Set<String> shapeTypeCombinations = new HashSet<>();
//        int numManifolds = dispatcher.getNumManifolds();
//        for (int i = 0; i < numManifolds; i++) {
//            PersistentManifold manifold = dispatcher.getManifoldByIndexInternal(i);
//            CollisionObject bodyA = (CollisionObject) manifold.getBody0();
//            CollisionObject bodyB = (CollisionObject) manifold.getBody1();
//
//            String typeA = bodyA.getCollisionShape().getClass().getSimpleName();
//            String typeB = bodyB.getCollisionShape().getClass().getSimpleName();
//            String combo = typeA + " vs " + typeB;
//
//            shapeTypeCombinations.add(combo);
//        }
//
//        for (String combo : shapeTypeCombinations) {
//            SLogger.log(this, "Shape combination: " + combo);
//        }


        // Step the simulation
        stepSimulation(TICK_DELTA, MAX_SUB_STEPS);

        // Perform entity physics
        entityPhysicsManager.updateEntitiesInSubchunks(world);

        // Adjust collision normals for better behavior
        adjustContactNormals();

        // Update each LocalGrid
        for (LocalGrid grid : localGrids) {
            grid.tickUpdate();
        }

        for (CollisionObject object : dynamicsWorld.getCollisionObjectArray()){
            SLogger.log(this, "Object: " + object.getCollisionShape().getShapeType().toString() + "; Group: " + object.getBroadphaseHandle().collisionFilterGroup + "; Mask: " + object.getBroadphaseHandle().collisionFilterMask);
        }
    }

    /**
     * Steps the physics simulation by the given time step.
     * Thread-safe via locking.
     *
     * @param deltaTime Time step in seconds
     * @param maxSubSteps Maximum number of substeps
     */
    public void stepSimulation(float deltaTime, int maxSubSteps) {
        synchronized (physicsLock) {
            dynamicsWorld.stepSimulation(deltaTime, maxSubSteps);
        }
    }

    /**
     * Called when a block in the Minecraft world changes.
     * Updates physics objects that might be affected.
     *
     * @param pos Position of the changed block
     */
    public void onBlockUpdate(BlockPos pos) {
        synchronized (physicsLock) {
            // Create a region around the updated block
            float x = pos.getX();
            float y = pos.getY();
            float z = pos.getZ();
            float range = 1f;

            Vector3f aabbMin = new Vector3f(x - range, y - range, z - range);
            Vector3f aabbMax = new Vector3f(x + range, y + range, z + range);

            // Check each LocalGrid for overlap
            for (LocalGrid grid : localGrids) {
                Vector3f gridMin = new Vector3f();
                Vector3f gridMax = new Vector3f();
                grid.getAABB(gridMin, gridMax);

                if (aabbOverlap(aabbMin, aabbMax, gridMin, gridMax)) {
                    // Activate the grid for physics processing
                    grid.getRigidBody().activate();
                }
            }
        }
    }

    /**
     * Registers a LocalGrid with this physics engine.
     *
     * @param localGrid The grid to add
     */
    public void addGrid(LocalGrid localGrid) {
        synchronized (physicsLock) {
            localGrids.add(localGrid);

            RigidBody rigidBody = localGrid.getRigidBody();

            // Add rigid body to physics world if available
            if (rigidBody != null) {
                dynamicsWorld.addRigidBody(localGrid.getRigidBody());

                if (rigidBody.getBroadphaseHandle() != null) {
                    rigidBody.getBroadphaseHandle().collisionFilterGroup = COLLISION_GROUP_GRID;
                    rigidBody.getBroadphaseHandle().collisionFilterMask = COLLISION_MASK_GRID;
                }
            }
        }
    }

    // -------------------------------------------
    // PRIVATE METHODS
    // -------------------------------------------

    /**
     * Adjusts contact normals to improve collision response.
     * Makes block surface collisions more predictable.
     */
    private void adjustContactNormals() {
        int numManifolds = dispatcher.getNumManifolds();
        for (int i = 0; i < numManifolds; i++) {
            PersistentManifold manifold = dispatcher.getManifoldByIndexInternal(i);

            if (manifold == null){
                continue;
            }

            CollisionObject colObj0 = (CollisionObject) manifold.getBody0();
            CollisionObject colObj1 = (CollisionObject) manifold.getBody1();

            // Process each contact point
            for (int j = 0; j < manifold.getNumContacts(); j++) {
                ManifoldPoint cp = manifold.getContactPoint(j);

                // Adjust normals for world blocks
                if (isWorldBlock(colObj1)) {
                    float penetration = cp.getDistance(); // negative if penetrating

                    Vector3f contactPoint = new Vector3f();
                    cp.getPositionWorldOnB(contactPoint);

                    Vector3f blockCenter = getBlockCenter(colObj1);
                    Vector3f newNormal = new Vector3f();

                    // For deeper penetrations, use axis-aligned normals
                    if (penetration < -0.1f) {
                        // Calculate minimal translation vector
                        Vector3f diff = new Vector3f();
                        diff.sub(contactPoint, blockCenter);

                        float halfExtent = 0.5f;
                        float dx = halfExtent - Math.abs(diff.x);
                        float dy = halfExtent - Math.abs(diff.y);
                        float dz = halfExtent - Math.abs(diff.z);

                        // Choose direction with minimum penetration
                        if (dx <= dy && dx <= dz) {
                            newNormal.set(diff.x >= 0 ? 1 : -1, 0, 0);
                        } else if (dy <= dx && dy <= dz) {
                            newNormal.set(0, diff.y >= 0 ? 1 : -1, 0);
                        } else {
                            newNormal.set(0, 0, diff.z >= 0 ? 1 : -1);
                        }
                    } else {
                        // For light contacts, use vector from block center
                        newNormal.sub(contactPoint, blockCenter);
                        if (newNormal.lengthSquared() > 1e-4f) {
                            newNormal.normalize();
                        }
                    }

                    // Apply adjusted normal
                    cp.normalWorldOnB.set(newNormal);
                }
            }
        }
    }

    /**
     * Checks if a collision object represents a world block.
     */
    private boolean isWorldBlock(CollisionObject colObj) {
        return (colObj != null && colObj.getUserPointer() instanceof WorldBlockMarker);
    }

    /**
     * Gets the center position of a world block.
     */
    private Vector3f getBlockCenter(CollisionObject colObj) {
        WorldBlockMarker marker = (WorldBlockMarker) colObj.getUserPointer();
        return marker.getCenter();
    }

    /**
     * Tests if two AABBs overlap.
     */
    private boolean aabbOverlap(Vector3f minA, Vector3f maxA, Vector3f minB, Vector3f maxB) {
        return (minA.x <= maxB.x && maxA.x >= minB.x) &&
                (minA.y <= maxB.y && maxA.y >= minB.y) &&
                (minA.z <= maxB.z && maxA.z >= minB.z);
    }

    // -------------------------------------------
    // GETTERS
    // -------------------------------------------

    /**
     * Gets the Bullet dynamics world.
     */
    public DynamicsWorld getDynamicsWorld() {
        return dynamicsWorld;
    }

    /**
     * Gets all registered LocalGrids.
     */
    public Set<LocalGrid> getGrids() {
        return localGrids;
    }


    /**
     * Gets the subchunk manager.
     */
    public SubchunkManager getSubchunkManager() {
        return subchunkManager;
    }

    /**
     * Gets the entity physics manager
     */
    public EntityPhysicsManager getEntityPhysicsManager(){
        return entityPhysicsManager;
    }



    /**
     * Gets the physics lock for synchronization.
     */
    public Object getPhysicsLock() {
        return physicsLock;
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