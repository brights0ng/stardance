package net.stardance.physics.entity;

import com.bulletphysics.collision.broadphase.BroadphaseInterface;
import com.bulletphysics.collision.broadphase.BroadphaseNativeType;
import com.bulletphysics.collision.broadphase.DbvtBroadphase;
import com.bulletphysics.collision.dispatch.*;
import com.bulletphysics.collision.dispatch.CollisionWorld.ClosestConvexResultCallback;
import com.bulletphysics.collision.dispatch.CollisionWorld.ConvexResultCallback;
import com.bulletphysics.collision.narrowphase.ManifoldPoint;
import com.bulletphysics.collision.narrowphase.PersistentManifold;
import com.bulletphysics.collision.shapes.BoxShape;
import com.bulletphysics.collision.shapes.CollisionShape;
import com.bulletphysics.collision.shapes.ConvexShape;
import com.bulletphysics.dynamics.DynamicsWorld;
import com.bulletphysics.dynamics.RigidBody;
import com.bulletphysics.linearmath.Transform;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.stardance.core.LocalGrid;
import net.stardance.physics.PhysicsEngine;
import net.stardance.utils.ILoggingControl;
import net.stardance.utils.SLogger;

import javax.vecmath.Matrix3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles collision detection between entities and physics objects.
 * Provides methods for sweep tests and contact detection.
 */
public class ContactDetector implements ILoggingControl {
    // Constants
    private static final float CONTACT_PROCESSING_THRESHOLD = 0.0f;
    private static final float SWEEP_TEST_MARGIN = 0.04f; // Margin for sweep tests
    private static final float MIN_PENETRATION_FOR_CONTACT = 0.005f; // Minimum penetration to consider a valid contact
    private static final float GROUND_NORMAL_Y_THRESHOLD = 0.7071f; // cos(45 degrees)

    // Parent references
    private final EntityPhysicsManager entityPhysicsManager;
    private final PhysicsEngine physicsEngine;

    // Cache of current entity contacts
    private final Map<Entity, List<Contact>> entityContacts = new ConcurrentHashMap<>();

    // Dedicated collision world for ghost object tests
    private final CollisionWorld ghostCollisionWorld;
    private final BroadphaseInterface ghostBroadphase;

    /**
     * Creates a new ContactDetector.
     *
     * @param entityPhysicsManager The parent EntityPhysicsManager
     * @param physicsEngine The PhysicsEngine to use
     */
    public ContactDetector(EntityPhysicsManager entityPhysicsManager, PhysicsEngine physicsEngine) {
        this.entityPhysicsManager = entityPhysicsManager;
        this.physicsEngine = physicsEngine;

        // Initialize the ghost collision world for non-persistent queries
        this.ghostBroadphase = new DbvtBroadphase();
        DefaultCollisionConfiguration collisionConfig = new DefaultCollisionConfiguration();
        this.ghostCollisionWorld = new CollisionWorld(
                new com.bulletphysics.collision.dispatch.CollisionDispatcher(collisionConfig),
                ghostBroadphase,
                collisionConfig
        );

        SLogger.log(this, "ContactDetector initialized");
    }

    /**
     * Performs a sweep test to detect collisions along a movement path.
     * Uses Bullet's convex sweep test for accurate collision detection.
     *
     * @param entity The entity to check
     * @param movement The proposed movement vector
     * @return A SweepResult if a collision is detected, null otherwise
     */
    public SweepResult sweepTest(Entity entity, Vec3d movement) {
        // Skip if movement is too small
        if (movement.lengthSquared() < 1e-6) {
            return null;
        }

        synchronized (physicsEngine.getPhysicsLock()) {
            // Get the entity's collision object
            CollisionObject entityCollisionObject = getEntityCollisionObject(entity);
            if (entityCollisionObject == null) {
                return null;
            }

            // Get the current transform
            Transform startTransform = new Transform();
            entityCollisionObject.getWorldTransform(startTransform);

            // Calculate end transform
            Transform endTransform = new Transform(startTransform);
            endTransform.origin.x += (float) movement.x;
            endTransform.origin.y += (float) movement.y;
            endTransform.origin.z += (float) movement.z;

            // Create callback
            CustomConvexResultCallback callback = new CustomConvexResultCallback(entityCollisionObject);

            // Set up filtering to ignore the entity itself
            callback.collisionFilterGroup = entityCollisionObject.getBroadphaseHandle().collisionFilterGroup;
            callback.collisionFilterMask = entityCollisionObject.getBroadphaseHandle().collisionFilterMask;

            // Perform the sweep test
            DynamicsWorld dynamicsWorld = physicsEngine.getDynamicsWorld();
            ConvexShape convexShape = getEntityConvexShape(entity);

            if (convexShape != null) {
                dynamicsWorld.convexSweepTest(convexShape, startTransform, endTransform, callback);

                // Check if we hit something
                if (callback.hasHit()) {
                    // Get hit info - accessing fields directly from ClosestConvexResultCallback
                    CollisionObject hitObject = callback.hitCollisionObject;

                    // Create a sweep result
                    return createSweepResult(
                            callback.closestHitFraction,
                            callback.hitNormalWorld,
                            callback.hitPointWorld,
                            hitObject,
                            entity
                    );
                }
            }
        }

        // No collision detected
        return null;
    }

    /**
     * Performs a more comprehensive convex sweep test that can handle
     * multiple collision objects and provides more detailed information.
     *
     * @param entity The entity to check
     * @param movement The proposed movement vector
     * @param entityProxies Map of entity proxies for additional checks
     * @return A SweepResult if a collision is detected, null otherwise
     */
    public SweepResult convexSweepTest(Entity entity, Vec3d movement, Map<Entity, EntityProxy> entityProxies) {
        // Skip if movement is too small
        if (movement.lengthSquared() < 1e-6) {
            return null;
        }

        // Create a ghost object for the sweep test
        GhostObject ghostObject = createGhostObjectForEntity(entity);
        if (ghostObject == null) {
            // Fall back to basic sweep test
            return sweepTest(entity, movement);
        }

        synchronized (physicsEngine.getPhysicsLock()) {
            try {
                // Add ghost object to our dedicated collision world
                ghostCollisionWorld.addCollisionObject(ghostObject);

                // Create current and target transforms
                Transform currentTransform = new Transform();
                ghostObject.getWorldTransform(currentTransform);

                Transform targetTransform = new Transform(currentTransform);
                targetTransform.origin.x += (float) movement.x;
                targetTransform.origin.y += (float) movement.y;
                targetTransform.origin.z += (float) movement.z;

                // Temporarily add all collision objects from dynamics world to our ghost world
                DynamicsWorld dynamicsWorld = physicsEngine.getDynamicsWorld();
                List<CollisionObject> tempObjects = new ArrayList<>();

                int numCollisionObjects = dynamicsWorld.getNumCollisionObjects();
                for (int i = 0; i < numCollisionObjects; i++) {
                    CollisionObject obj = dynamicsWorld.getCollisionObjectArray().getQuick(i);

                    // Skip the entity we're testing
                    if (obj.getUserPointer() != entity && shouldConsiderForCollision(obj, entity)) {
                        // Create a copy of the collision object
                        CollisionObject copy = new CollisionObject();
                        copy.setCollisionShape(obj.getCollisionShape());
                        Transform objTransform = new Transform();
                        obj.getWorldTransform(objTransform);
                        copy.setWorldTransform(objTransform);
                        copy.setUserPointer(obj.getUserPointer());

                        ghostCollisionWorld.addCollisionObject(copy);
                        tempObjects.add(copy);
                    }
                }

                // Perform the sweep test using our own implementation
                MultiSweepCallback callback = new MultiSweepCallback(ghostObject);
                convexSweepTestInternal(ghostObject, currentTransform, targetTransform, callback);

                // If we hit something, create a sweep result
                if (callback.hasHit()) {
                    // Create a sweep result from the closest hit
                    SweepResult result = createSweepResult(
                            callback.getClosestHitFraction(),
                            callback.getClosestHitNormal(),
                            callback.getClosestHitPoint(),
                            callback.getClosestHitObject(),
                            entity
                    );

                    // Store all hits in the result for multi-contact resolution
                    result.setAllHits(callback.getAllHits());

                    return result;
                }

                // Clean up temporary objects
                for (CollisionObject obj : tempObjects) {
                    ghostCollisionWorld.removeCollisionObject(obj);
                }

                return null;
            } finally {
                // Always remove ghost object from collision world
                ghostCollisionWorld.removeCollisionObject(ghostObject);
            }
        }
    }

    /**
     * Internal implementation of convex sweep test using a ghost object.
     */
    private void convexSweepTestInternal(GhostObject ghostObject, Transform from,
                                         Transform to, MultiSweepCallback callback) {
        // Implementation would use custom collision detection logic
        // to step through the movement and collect all contacts

        // For now, we'll use a simplified approach with bullet's existing methods

        // Calculate movement vector
        Vector3f movementVec = new Vector3f();
        movementVec.sub(to.origin, from.origin);

        // Normalize for step calculation
        float movementLength = movementVec.length();
        if (movementLength < 1e-6f) {
            return;
        }

        Vector3f movementDir = new Vector3f(movementVec);
        movementDir.normalize();

        // Perform a series of small discrete position tests
        final int STEPS = 10;
        float stepSize = movementLength / STEPS;

        for (int i = 0; i <= STEPS; i++) {
            // Calculate position for this step
            float t = i / (float) STEPS;
            Transform stepTransform = new Transform(from);
            Vector3f stepMove = new Vector3f(movementVec);
            stepMove.scale(t);
            stepTransform.origin.add(stepMove);

            // Update ghost object position
            ghostObject.setWorldTransform(stepTransform);

            // Check for collisions at this position
            ghostCollisionWorld.performDiscreteCollisionDetection();

            // Process contacts
            int numObjects = ghostObject.getNumOverlappingObjects();
            for (int j = 0; j < numObjects; j++) {
                CollisionObject overlappingObject = ghostObject.getOverlappingObject(j);

                // Skip invalid objects
                if (overlappingObject == null || overlappingObject == ghostObject) {
                    continue;
                }

                // Create a contact point for this overlap
                Vector3f normal = estimateContactNormal(ghostObject, overlappingObject);
                Vector3f hitPoint = estimateContactPoint(ghostObject, overlappingObject);

                // Register the hit
                callback.addHit(overlappingObject, t, hitPoint, normal);

                // If this is a penetrating contact, we might want to break early
                // and report the first significant contact
                if (t < 0.1f) {
                    // This means we're already intersecting at the start
                    // We should handle this as a special case
                    callback.setPenetratingContact(true);
                }
            }

            // If we found a hit and it's a penetrating contact, break
            if (callback.hasHit() && callback.isPenetratingContact()) {
                break;
            }
        }
    }

    /**
     * Estimates the contact normal between two collision objects.
     */
    private Vector3f estimateContactNormal(CollisionObject objA, CollisionObject objB) {
        // Get centers of both objects
        Vector3f centerA = new Vector3f();
        Vector3f centerB = new Vector3f();

        Transform transA = new Transform();
        Transform transB = new Transform();
        objA.getWorldTransform(transA);
        objB.getWorldTransform(transB);

        centerA.set(transA.origin);
        centerB.set(transB.origin);

        // Direction from B to A
        Vector3f normal = new Vector3f();
        normal.sub(centerA, centerB);

        // If centers are too close, use y-axis as default
        if (normal.lengthSquared() < 1e-6f) {
            normal.set(0.0f, 1.0f, 0.0f);
        } else {
            normal.normalize();
        }

        return normal;
    }

    /**
     * Estimates the contact point between two collision objects.
     */
    private Vector3f estimateContactPoint(CollisionObject objA, CollisionObject objB) {
        // Get centers of both objects
        Vector3f centerA = new Vector3f();
        Vector3f centerB = new Vector3f();

        Transform transA = new Transform();
        Transform transB = new Transform();
        objA.getWorldTransform(transA);
        objB.getWorldTransform(transB);

        centerA.set(transA.origin);
        centerB.set(transB.origin);

        // Use midpoint as contact point
        Vector3f contactPoint = new Vector3f();
        contactPoint.add(centerA, centerB);
        contactPoint.scale(0.5f);

        return contactPoint;
    }

    /**
     * Determines if an object should be considered for collision with an entity.
     */
    private boolean shouldConsiderForCollision(CollisionObject obj, Entity entity) {
        // Skip non-collidable objects
        if ((obj.getCollisionFlags() & CollisionFlags.NO_CONTACT_RESPONSE) != 0) {
            return false;
        }

        // Check if it's another entity
        if (obj.getUserPointer() instanceof EntityProxy) {
            EntityProxy proxy = (EntityProxy) obj.getUserPointer();
            if (proxy.getEntity() == entity) {
                return false;
            }

            // Check if both entities should interact
            // For now, we'll consider all entities
            return true;
        }

        // Check if it's a grid
        if (obj.getUserPointer() instanceof LocalGrid) {
            // Grids should always collide with entities
            return true;
        }

        // Consider collision by default
        return true;
    }

    /**
     * Creates a ghost object for an entity, used in sweep tests.
     */
    private GhostObject createGhostObjectForEntity(Entity entity) {
        // Get the entity's collision shape
        ConvexShape convexShape = getEntityConvexShape(entity);
        if (convexShape == null) {
            return null;
        }

        // Create ghost object
        GhostObject ghostObject = new GhostObject();
        ghostObject.setCollisionShape(convexShape);

        // Set transform
        Box box = entity.getBoundingBox();
        Transform transform = new Transform();
        transform.setIdentity();

        // Center at entity's position
        float x = (float) ((box.minX + box.maxX) * 0.5);
        float y = (float) ((box.minY + box.maxY) * 0.5);
        float z = (float) ((box.minZ + box.maxZ) * 0.5);
        transform.origin.set(x, y, z);

        ghostObject.setWorldTransform(transform);
        ghostObject.setUserPointer(entity);

        return ghostObject;
    }

    /**
     * Gets a convex collision shape for an entity.
     */
    private ConvexShape getEntityConvexShape(Entity entity) {
        // Get the entity's proxy
        EntityProxy proxy = entityPhysicsManager.getEntityProxies().get(entity);
        if (proxy == null) {
            // Create a box shape based on entity bounds
            Box box = entity.getBoundingBox();
            float halfWidth = (float) ((box.maxX - box.minX) * 0.5f);
            float halfHeight = (float) ((box.maxY - box.minY) * 0.5f);
            float halfDepth = (float) ((box.maxZ - box.minZ) * 0.5f);

            BoxShape boxShape = new BoxShape(new Vector3f(halfWidth, halfHeight, halfDepth));
            boxShape.setMargin(SWEEP_TEST_MARGIN);
            return boxShape;
        }

        // Get the collision shape from the proxy
        CollisionShape shape = proxy.getCollisionShape();

        // Ensure it's a convex shape
        if (shape.getShapeType() != BroadphaseNativeType.CONVEX_SHAPE_PROXYTYPE) {
            // Fall back to a box shape
            Vector3f halfExtents = proxy.getHalfExtents();
            BoxShape boxShape = new BoxShape(halfExtents);
            boxShape.setMargin(SWEEP_TEST_MARGIN);
            return boxShape;
        }

        return (ConvexShape) shape;
    }

    /**
     * Gets the collision object for an entity.
     */
    private CollisionObject getEntityCollisionObject(Entity entity) {
        // Get the entity's proxy
        EntityProxy proxy = entityPhysicsManager.getEntityProxies().get(entity);
        if (proxy == null) {
            return null;
        }

        return proxy.getCollisionObject();
    }

    /**
     * Creates a SweepResult from hit information.
     */
    private SweepResult createSweepResult(float hitFraction, Vector3f hitNormal,
                                          Vector3f hitPoint, CollisionObject hitObject,
                                          Entity entity) {
        // Determine what we hit
        Object hitUserData = hitObject.getUserPointer();

        LocalGrid hitGrid = null;
        Entity hitEntity = null;

        if (hitUserData instanceof LocalGrid) {
            hitGrid = (LocalGrid) hitUserData;
        } else if (hitUserData instanceof EntityProxy) {
            EntityProxy hitProxy = (EntityProxy) hitUserData;
            hitEntity = hitProxy.getEntity();
        }

        // Create the sweep result
        return new SweepResult(
                hitFraction,
                new Vector3f(hitNormal),
                new Vector3f(hitPoint),
                hitGrid,
                hitEntity,
                entity
        );
    }

    /**
     * Detects contacts between an entity and other objects.
     * Called after movement to identify and handle penetrations.
     *
     * @param entity The entity to check
     * @return List of contacts for the entity
     */
    public List<Contact> detectContacts(Entity entity) {
        List<Contact> contacts = new ArrayList<>();

        synchronized (physicsEngine.getPhysicsLock()) {
            // Get the entity's collision object
            CollisionObject entityCollisionObject = getEntityCollisionObject(entity);
            if (entityCollisionObject == null) {
                return contacts;
            }

            // Check for collisions with all other objects
            DynamicsWorld dynamicsWorld = physicsEngine.getDynamicsWorld();
            int numManifolds = dynamicsWorld.getDispatcher().getNumManifolds();

            for (int i = 0; i < numManifolds; i++) {
                PersistentManifold manifold = dynamicsWorld.getDispatcher().getManifoldByIndexInternal(i);

                // Get the two collision objects
                CollisionObject objA = (CollisionObject) manifold.getBody0();
                CollisionObject objB = (CollisionObject) manifold.getBody1();

                // Check if either object is our entity
                boolean isEntityA = isEntityObject(objA, entity);
                boolean isEntityB = isEntityObject(objB, entity);

                if (!isEntityA && !isEntityB) {
                    // Neither object is our entity
                    continue;
                }

                // Get the other object
                CollisionObject otherObject = isEntityA ? objB : objA;
                boolean swapped = isEntityB; // If entity is B, we need to swap normal

                // Process each contact point in the manifold
                int numContacts = manifold.getNumContacts();
                for (int j = 0; j < numContacts; j++) {
                    ManifoldPoint contactPoint = manifold.getContactPoint(j);

                    // Get penetration depth
                    float penetration = -contactPoint.getDistance();

                    // Skip if penetration is too small
                    if (penetration < MIN_PENETRATION_FOR_CONTACT) {
                        continue;
                    }

                    // Get contact normal and point
                    Vector3f normal = new Vector3f(contactPoint.normalWorldOnB);

                    // Swap normal if needed
                    if (swapped) {
                        normal.negate();
                    }

                    // Get the contact point in world space
                    Vector3f contactPointWorld = new Vector3f();
                    if (swapped) {
                        contactPoint.getPositionWorldOnA(contactPointWorld);
                    } else {
                        contactPoint.getPositionWorldOnB(contactPointWorld);
                    }

                    // Create a contact
                    Contact contact = createContact(entity, otherObject, normal, contactPointWorld, penetration);
                    if (contact != null) {
                        contacts.add(contact);
                    }
                }
            }
        }

        // Update the entity's contacts
        entityContacts.put(entity, contacts);

        return contacts;
    }

    /**
     * Collects all current contacts for all tracked entities.
     *
     * @return Map of entities to their contacts
     */
    public Map<Entity, List<Contact>> collectContacts() {
        // Clear previous contacts
        entityContacts.clear();

        // Get all tracked entities from the manager
        Set<Entity> trackedEntities = entityPhysicsManager.getTrackedEntities();

        // Detect contacts for each entity
        for (Entity entity : trackedEntities) {
            List<Contact> contacts = detectContacts(entity);
            if (!contacts.isEmpty()) {
                entityContacts.put(entity, contacts);
            }
        }

        return Collections.unmodifiableMap(entityContacts);
    }

    /**
     * Gets current contacts for a specific entity.
     *
     * @param entity The entity to get contacts for
     * @return List of contacts for the entity
     */
    public List<Contact> getContactsForEntity(Entity entity) {
        List<Contact> contacts = entityContacts.get(entity);
        if (contacts != null) {
            return Collections.unmodifiableList(contacts);
        }
        return Collections.emptyList();
    }

    /**
     * Gets all entities that currently have contacts.
     *
     * @return Set of entities with contacts
     */
    public Set<Entity> getEntitiesWithContacts() {
        return Collections.unmodifiableSet(entityContacts.keySet());
    }

    /**
     * Creates a Contact from collision information.
     */
    private Contact createContact(Entity entity, CollisionObject otherObject,
                                  Vector3f normal, Vector3f contactPoint, float penetration) {
        // Determine what we're in contact with
        Object userData = otherObject.getUserPointer();

        LocalGrid contactGrid = null;
        Entity contactEntity = null;

        if (userData instanceof LocalGrid) {
            contactGrid = (LocalGrid) userData;
        } else if (userData instanceof EntityProxy) {
            EntityProxy proxy = (EntityProxy) userData;
            contactEntity = proxy.getEntity();
        } else {
            // Unknown object type
            return null;
        }

        // Create the contact
        Contact contact = new Contact(entity, contactGrid, contactEntity, normal, contactPoint, penetration);

        // If it's a grid contact, calculate grid velocity at contact point
        if (contactGrid != null) {
            Vector3f gridVelocity = contactGrid.getVelocityAtPoint(contactPoint);
            contact.setGridVelocityAtContactPoint(gridVelocity);
        }

        return contact;
    }

    /**
     * Checks if a collision object represents the specified entity.
     */
    private boolean isEntityObject(CollisionObject obj, Entity entity) {
        Object userData = obj.getUserPointer();

        if (userData == entity) {
            return true;
        }

        if (userData instanceof EntityProxy) {
            EntityProxy proxy = (EntityProxy) userData;
            return proxy.getEntity() == entity;
        }

        return false;
    }

    /**
     * Checks if an entity is on a grid.
     *
     * @param entity The entity to check
     * @param grid The grid to check
     * @return true if the entity is on the grid
     */
    public boolean isEntityOnGrid(Entity entity, LocalGrid grid) {
        // Get contacts for this entity
        List<Contact> contacts = getContactsForEntity(entity);

        for (Contact contact : contacts) {
            // Check if it's a contact with this grid
            if (contact.getGrid() == grid) {
                // Check if the normal points mostly upward
                Vector3f normal = contact.getContactNormal();
                if (normal.y > GROUND_NORMAL_Y_THRESHOLD) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Checks if an entity is in contact with a grid.
     *
     * @param entity The entity to check
     * @param grid The grid to check
     * @return true if there's any contact
     */
    public boolean checkEntityGridContact(Entity entity, LocalGrid grid) {
        // Get the entity's proxy
        EntityProxy proxy = entityPhysicsManager.getEntityProxies().get(entity);
        if (proxy == null) {
            return false;
        }

        // Get the grid's rigid body
        RigidBody gridBody = grid.getRigidBody();
        if (gridBody == null) {
            return false;
        }

        // Get the collision objects
        CollisionObject entityObj = proxy.getCollisionObject();

        // Simple AABB overlap check
        Transform entityTransform = new Transform();
        Transform gridTransform = new Transform();

        entityObj.getWorldTransform(entityTransform);
        gridBody.getWorldTransform(gridTransform);

        Vector3f entityMin = new Vector3f();
        Vector3f entityMax = new Vector3f();
        Vector3f gridMin = new Vector3f();
        Vector3f gridMax = new Vector3f();

        entityObj.getCollisionShape().getAabb(entityTransform, entityMin, entityMax);
        gridBody.getCollisionShape().getAabb(gridTransform, gridMin, gridMax);

        // Check AABB overlap
        if (entityMax.x < gridMin.x || entityMin.x > gridMax.x ||
                entityMax.y < gridMin.y || entityMin.y > gridMax.y ||
                entityMax.z < gridMin.z || entityMin.z > gridMax.z) {
            return false;
        }

        // More detailed contact check
        // Use bullet's collision detection
        synchronized (physicsEngine.getPhysicsLock()) {
            DynamicsWorld dynamicsWorld = physicsEngine.getDynamicsWorld();
            int numManifolds = dynamicsWorld.getDispatcher().getNumManifolds();

            for (int i = 0; i < numManifolds; i++) {
                PersistentManifold manifold = dynamicsWorld.getDispatcher().getManifoldByIndexInternal(i);

                // Get the two collision objects
                CollisionObject objA = (CollisionObject) manifold.getBody0();
                CollisionObject objB = (CollisionObject) manifold.getBody1();

                // Check if this manifold involves our entity and grid
                boolean entityInvolved = (objA == entityObj || objB == entityObj);
                boolean gridInvolved = (objA == gridBody || objB == gridBody);

                if (entityInvolved && gridInvolved && manifold.getNumContacts() > 0) {
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return true;
    }

    /**
     * Result of a sweep test, containing collision information.
     */
    public static class SweepResult {
        private final float timeOfImpact;
        private final Vector3f hitNormal;
        private final Vector3f hitPoint;
        private final LocalGrid grid;
        private final Entity collidedEntity;
        private final Entity sourceEntity;
        private List<SweepHit> allHits;

        /**
         * Creates a new SweepResult.
         */
        public SweepResult(float timeOfImpact, Vector3f hitNormal, Vector3f hitPoint,
                           LocalGrid grid, Entity collidedEntity, Entity sourceEntity) {
            this.timeOfImpact = timeOfImpact;
            this.hitNormal = hitNormal;
            this.hitPoint = hitPoint;
            this.grid = grid;
            this.collidedEntity = collidedEntity;
            this.sourceEntity = sourceEntity;
            this.allHits = new ArrayList<>();
        }

        /**
         * Gets the time of impact (0-1).
         */
        public float getTimeOfImpact() {
            return timeOfImpact;
        }

        /**
         * Gets the hit normal.
         */
        public Vector3f getHitNormal() {
            return hitNormal;
        }

        /**
         * Gets the hit point.
         */
        public Vector3f getHitPoint() {
            return hitPoint;
        }

        /**
         * Gets the grid involved in the collision, if any.
         */
        public LocalGrid getGrid() {
            return grid;
        }

        /**
         * Gets the entity involved in the collision, if any.
         */
        public Entity getCollidedEntity() {
            return collidedEntity;
        }

        /**
         * Gets the entity that performed the sweep test.
         */
        public Entity getSourceEntity() {
            return sourceEntity;
        }

        /**
         * Sets all hits from the sweep test.
         */
        public void setAllHits(List<SweepHit> hits) {
            this.allHits = new ArrayList<>(hits);
        }

        /**
         * Gets all hits from the sweep test.
         */
        public List<SweepHit> getAllHits() {
            return Collections.unmodifiableList(allHits);
        }

        /**
         * Calculates the safe position up to the collision point.
         *
         * @param startPos Starting position
         * @param movement Full movement vector
         * @param safetyMargin Small margin to prevent intersection
         * @return Safe position vector
         */
        public Vec3d getSafePosition(Vec3d startPos, Vec3d movement, double safetyMargin) {
            // Calculate adjusted time of impact with safety margin
            float adjustedTOI = Math.max(0.0f, timeOfImpact - (float) safetyMargin);

            // Calculate safe movement
            return new Vec3d(
                    startPos.x + movement.x * adjustedTOI,
                    startPos.y + movement.y * adjustedTOI,
                    startPos.z + movement.z * adjustedTOI
            );
        }

        /**
         * Calculates a deflected movement after the collision.
         *
         * @param originalMovement Original movement vector
         * @param remainingTime Fraction of movement remaining after collision
         * @return Deflected movement vector
         */
        public Vec3d getDeflectedMovement(Vec3d originalMovement, float remainingTime) {
            if (remainingTime <= 0.0f) {
                return Vec3d.ZERO;
            }

            // Convert Vec3d to Vector3f for bullet math
            Vector3f movement = new Vector3f(
                    (float) originalMovement.x,
                    (float) originalMovement.y,
                    (float) originalMovement.z
            );

            // Calculate remaining movement magnitude
            float remainingMagnitude = movement.length() * remainingTime;

            // Project remaining movement onto collision plane
            Vector3f normal = new Vector3f(hitNormal);
            normal.normalize();

            // Calculate dot product
            float dot = movement.dot(normal);

            // Only deflect if moving into the surface
            if (dot < 0) {
                // Remove normal component from movement
                Vector3f normalComponent = new Vector3f(normal);
                normalComponent.scale(dot);
                movement.sub(normalComponent);

                // Renormalize and scale by remaining magnitude
                if (movement.lengthSquared() > 1e-6f) {
                    movement.normalize();
                    movement.scale(remainingMagnitude);
                } else {
                    // Movement is directly into normal, cancel it
                    movement.set(0, 0, 0);
                }
            }

            // Convert back to Vec3d
            return new Vec3d(movement.x, movement.y, movement.z);
        }
    }

    /**
     * Represents a single hit from a sweep test.
     */
    public static class SweepHit {
        private final CollisionObject hitObject;
        private final float hitFraction;
        private final Vector3f hitPoint;
        private final Vector3f hitNormal;

        /**
         * Creates a new SweepHit.
         */
        public SweepHit(CollisionObject hitObject, float hitFraction,
                        Vector3f hitPoint, Vector3f hitNormal) {
            this.hitObject = hitObject;
            this.hitFraction = hitFraction;
            this.hitPoint = new Vector3f(hitPoint);
            this.hitNormal = new Vector3f(hitNormal);
        }

        /**
         * Gets the hit object.
         */
        public CollisionObject getHitObject() {
            return hitObject;
        }

        /**
         * Gets the hit fraction (0-1).
         */
        public float getHitFraction() {
            return hitFraction;
        }

        /**
         * Gets the hit point.
         */
        public Vector3f getHitPoint() {
            return hitPoint;
        }

        /**
         * Gets the hit normal.
         */
        public Vector3f getHitNormal() {
            return hitNormal;
        }
    }

    /**
     * Custom convex result callback that filters out certain objects.
     */
    private static class CustomConvexResultCallback extends ClosestConvexResultCallback {
        private final CollisionObject sourceObject;

        public CustomConvexResultCallback(CollisionObject sourceObject) {
            super(new Vector3f(), new Vector3f());
            this.sourceObject = sourceObject;
        }

        @Override
        public float addSingleResult(CollisionWorld.LocalConvexResult convexResult, boolean normalInWorldSpace) {
            // Filter out self collisions
            if (convexResult.hitCollisionObject == sourceObject) {
                return 1.0f;
            }

            // Handle the result
            return super.addSingleResult(convexResult, normalInWorldSpace);
        }
    }

    /**
     * Callback for collecting multiple sweep test hits.
     */
    private static class MultiSweepCallback {
        private final CollisionObject sourceObject;
        private final List<SweepHit> hits = new ArrayList<>();
        private boolean hasHit = false;
        private boolean penetratingContact = false;

        private SweepHit closestHit = null;

        public MultiSweepCallback(CollisionObject sourceObject) {
            this.sourceObject = sourceObject;
        }

        /**
         * Adds a hit to the collection.
         */
        public void addHit(CollisionObject hitObject, float hitFraction,
                           Vector3f hitPoint, Vector3f hitNormal) {
            // Filter out self collisions
            if (hitObject == sourceObject) {
                return;
            }

            // Create a sweep hit
            SweepHit hit = new SweepHit(hitObject, hitFraction, hitPoint, hitNormal);
            hits.add(hit);
            hasHit = true;

            // Update closest hit
            if (closestHit == null || hitFraction < closestHit.getHitFraction()) {
                closestHit = hit;
            }
        }

        /**
         * Checks if any hits were recorded.
         */
        public boolean hasHit() {
            return hasHit;
        }

        /**
         * Gets all recorded hits.
         */
        public List<SweepHit> getAllHits() {
            return hits;
        }

        /**
         * Gets the closest hit fraction.
         */
        public float getClosestHitFraction() {
            return closestHit != null ? closestHit.getHitFraction() : 1.0f;
        }

        /**
         * Gets the closest hit normal.
         */
        public Vector3f getClosestHitNormal() {
            return closestHit != null ? closestHit.getHitNormal() : new Vector3f(0, 1, 0);
        }

        /**
         * Gets the closest hit point.
         */
        public Vector3f getClosestHitPoint() {
            return closestHit != null ? closestHit.getHitPoint() : new Vector3f();
        }

        /**
         * Gets the closest hit object.
         */
        public CollisionObject getClosestHitObject() {
            return closestHit != null ? closestHit.getHitObject() : null;
        }

        /**
         * Sets whether a penetrating contact was detected.
         */
        public void setPenetratingContact(boolean penetrating) {
            this.penetratingContact = penetrating;
        }

        /**
         * Checks if a penetrating contact was detected.
         */
        public boolean isPenetratingContact() {
            return penetratingContact;
        }
    }
}