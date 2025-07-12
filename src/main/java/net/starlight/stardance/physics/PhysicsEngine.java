package net.starlight.stardance.physics;

import com.bulletphysics.collision.broadphase.AxisSweep3;
import com.bulletphysics.collision.broadphase.BroadphaseInterface;
import com.bulletphysics.collision.dispatch.*;
import com.bulletphysics.collision.narrowphase.ManifoldPoint;
import com.bulletphysics.collision.narrowphase.PersistentManifold;
import com.bulletphysics.dynamics.DiscreteDynamicsWorld;
import com.bulletphysics.dynamics.DynamicsWorld;
import com.bulletphysics.dynamics.RigidBody;
import com.bulletphysics.dynamics.constraintsolver.ConstraintSolver;
import com.bulletphysics.dynamics.constraintsolver.SequentialImpulseConstraintSolver;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.physics.entity.EntityPhysicsManager;
import net.starlight.stardance.utils.BlockEventHandler;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;

import javax.vecmath.Vector3d;
import javax.vecmath.Vector3f;
import java.util.Optional;
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

    /**
     * VS2-style physics raycast against all grids using JBullet collision detection.
     */
    public Optional<PhysicsRaycastResult> raycastGrids(Vec3d rayStart, Vec3d rayEnd) {
        try {
            // Convert to JBullet vectors
            Vector3f bulletStart = new Vector3f((float) rayStart.x, (float) rayStart.y, (float) rayStart.z);
            Vector3f bulletEnd = new Vector3f((float) rayEnd.x, (float) rayEnd.y, (float) rayEnd.z);

            // Create raycast callback that only hits grids
            GridRayResultCallback callback = new GridRayResultCallback(bulletStart, bulletEnd);

            // Perform raycast against dynamics world
            synchronized (getPhysicsLock()) {
                getDynamicsWorld().rayTest(bulletStart, bulletEnd, callback);
            }

            if (callback.hasHit()) {
                return Optional.of(callback.createResult());
            }

            return Optional.empty();

        } catch (Exception e) {
            SLogger.log("PhysicsEngine", "Error in grid raycast: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Custom raycast callback that only hits LocalGrid rigid bodies.
     */
    private static class GridRayResultCallback extends CollisionWorld.ClosestRayResultCallback {

        public GridRayResultCallback(Vector3f rayFromWorld, Vector3f rayToWorld) {
            super(rayFromWorld, rayToWorld);
        }

        @Override
        public float addSingleResult(CollisionWorld.LocalRayResult rayResult, boolean normalInWorldSpace) {
            // Only process hits against LocalGrid rigid bodies
            Object userPointer = rayResult.collisionObject.getUserPointer();
            if (!(userPointer instanceof LocalGrid)) {
                return 1.0f; // Skip non-grid objects
            }

            return super.addSingleResult(rayResult, normalInWorldSpace);
        }

        public PhysicsRaycastResult createResult() {
            if (!hasHit()) return null;

            Vector3f hitPoint = new Vector3f();
            hitPointWorld.get(hitPoint);

            Vector3f hitNormal = new Vector3f();
            hitNormalWorld.get(hitNormal);

            LocalGrid hitGrid = (LocalGrid) collisionObject.getUserPointer();

            // Convert world hit point to GridSpace coordinates
            Vec3d worldHitPos = new Vec3d(hitPoint.x, hitPoint.y, hitPoint.z);
            BlockPos gridSpacePos = worldToGridSpace(worldHitPos, hitGrid);

            return new PhysicsRaycastResult(
                    worldHitPos,
                    gridSpacePos,
                    hitGrid,
                    closestHitFraction
            );
        }

        private BlockPos worldToGridSpace(Vec3d worldPos, LocalGrid grid) {
            // Transform world → grid-local → GridSpace
            Vector3d worldPoint = new Vector3d(worldPos.x, worldPos.y, worldPos.z);
            Vector3d gridLocalPoint = grid.worldToGridLocal(worldPoint);
            BlockPos gridLocalPos = new BlockPos(
                    (int) Math.floor(gridLocalPoint.x),
                    (int) Math.floor(gridLocalPoint.y),
                    (int) Math.floor(gridLocalPoint.z)
            );
            return grid.gridLocalToGridSpace(gridLocalPos);
        }
    }

    /**
     * Result of a physics raycast against grids.
     */
    public static class PhysicsRaycastResult {
        public final Vec3d worldHitPos;
        public final BlockPos gridSpacePos;
        public final LocalGrid grid;
        public final float hitFraction;

        public PhysicsRaycastResult(Vec3d worldHitPos, BlockPos gridSpacePos, LocalGrid grid, float hitFraction) {
            this.worldHitPos = worldHitPos;
            this.gridSpacePos = gridSpacePos;
            this.grid = grid;
            this.hitFraction = hitFraction;
        }

        /**
         * Creates a BlockHitResult for vanilla compatibility.
         */
        public BlockHitResult createBlockHitResult() {
            // Create a BlockHitResult using GridSpace coordinates
            // This makes the hit appear to vanilla code as if it hit a block at GridSpace position
            Direction hitSide = Direction.UP; // You may want to calculate this from hit normal

            return new BlockHitResult(
                    worldHitPos,           // Visual hit position (where player sees the hit)
                    hitSide,
                    gridSpacePos,          // GridSpace coordinates (where the block actually is)
                    false
            );
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
        return false;
    }

    public void removeGrid(LocalGrid localGrid) {
        dynamicsWorld.removeRigidBody(localGrid.getRigidBody());
    }
}