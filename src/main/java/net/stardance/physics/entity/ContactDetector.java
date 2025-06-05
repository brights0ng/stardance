package net.stardance.physics.entity;

import com.bulletphysics.collision.broadphase.*;
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
import net.minecraft.entity.player.PlayerEntity;
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

    // Debugging flag - set to true to enable verbose collision detection logging
    private static final boolean DEBUG_COLLISIONS = true;

    // Parent references
    private final EntityPhysicsManager entityPhysicsManager;
    private final PhysicsEngine physicsEngine;

    // Cache of current entity contacts
    private final Map<Entity, List<Contact>> entityContacts = new ConcurrentHashMap<>();

    // Internal counters for stats and debugging
    private int sweepTestCount = 0;
    private int hitDetectionCount = 0;

    /**
     * Creates a new ContactDetector.
     *
     * @param entityPhysicsManager The parent EntityPhysicsManager
     * @param physicsEngine The PhysicsEngine to use
     */
    public ContactDetector(EntityPhysicsManager entityPhysicsManager, PhysicsEngine physicsEngine) {
        this.entityPhysicsManager = entityPhysicsManager;
        this.physicsEngine = physicsEngine;
        SLogger.log(this, "ContactDetector initialized");
    }

    // Inside ContactDetector.java

    public SweepResult convexSweepTest(Entity entity, Vec3d movement, Map<Entity, EntityProxy> entityProxies) {
        // ... (skip movement check, get proxy, check shape, setup transforms - SAME AS BEFORE) ...
        EntityProxy entityProxy = entityPhysicsManager.getEntityProxy(entity);
        if (entityProxy == null || entityProxy.getCollisionObject() == null || entityProxy.getCollisionObject().getBroadphaseHandle() == null) {
            // ... handle null proxy ...
            return null;
        }
        CollisionObject entityCollisionObject = entityProxy.getCollisionObject();
        CollisionShape entityShape = entityCollisionObject.getCollisionShape();
        if (!(entityShape instanceof ConvexShape)) {
            // ... handle non-convex shape ...
            return null;
        }
        Transform startTransform = new Transform();
        entityCollisionObject.getWorldTransform(startTransform);
        Transform endTransform = new Transform(startTransform);
        endTransform.origin.x += (float) movement.x;
        endTransform.origin.y += (float) movement.y;
        endTransform.origin.z += (float) movement.z;


        SweepResult closestResult = null;

        synchronized (physicsEngine.getPhysicsLock()) {
            DynamicsWorld dynamicsWorld = physicsEngine.getDynamicsWorld();

            // Use the standard callback
            ClosestConvexResultCallback callback = new ClosestConvexResultCallback(startTransform.origin, endTransform.origin);
            // We don't need to set callback filters here since the sweep call doesn't use them

            // Perform sweep test against the *entire world* using the available overload
            dynamicsWorld.convexSweepTest(
                    (ConvexShape)entityShape,
                    startTransform,
                    endTransform,
                    callback
                    // No filter group/mask parameters available in this overload
            );

            // Check if the callback found *any* geometric hit
            if (callback.hasHit()) {
                CollisionObject hitObject = callback.hitCollisionObject;

                // Skip self-collision
                if (hitObject == entityCollisionObject) {
                    return null; // Or continue searching if sweep allowed multiple hits
                }

                // Determine if we hit a grid or another entity
                LocalGrid hitGrid = null;
                Entity hitEntity = null;
                Object userPointer = hitObject.getUserPointer();

                if (userPointer instanceof LocalGrid) {
                    hitGrid = (LocalGrid) userPointer;
                } else if (userPointer instanceof EntityProxy) { // Assuming you store EntityProxy
                    hitEntity = ((EntityProxy) userPointer).getEntity();
                } else if (userPointer instanceof Entity) { // Or maybe the Entity itself
                    hitEntity = (Entity) userPointer;
                }
                // NOTE: We intentionally DO NOT check for WorldBlockMarker here anymore.

                // Create the result, including the hitObject, regardless of type for now.
                // Filtering will happen in the Mixin.
                closestResult = new SweepResult(
                        callback.closestHitFraction,
                        new Vector3f(callback.hitNormalWorld),
                        new Vector3f(callback.hitPointWorld),
                        hitGrid,
                        hitEntity,
                        entity,
                        hitObject // <-- Pass the hit object
                );
                hitDetectionCount++;

                // Optional: Log the raw geometric hit here before filtering
                if (DEBUG_COLLISIONS && entity.isPlayer()) {
                    String targetType = "Unknown";
                    if(hitGrid != null) targetType = "Grid " + hitGrid.getGridId().toString().substring(0, 4);
                    else if(hitEntity != null) targetType = "Entity " + hitEntity.getEntityName();
                    else if (userPointer instanceof net.stardance.physics.WorldBlockMarker) targetType = "WorldBlockMarker/Mesh";
                    else if (hitObject != null) targetType = "Object: " + hitObject.getCollisionShape().getName();

                    SLogger.log(this, String.format(
                            "[%s %d] Raw sweep test hit %s (Group: %d, Mask: %d) at fraction %.4f",
                            entity.getType().getName().getString(), entity.getId(), targetType,
                            hitObject.getBroadphaseHandle().collisionFilterGroup,
                            hitObject.getBroadphaseHandle().collisionFilterMask,
                            callback.closestHitFraction));
                }
            } else if (DEBUG_COLLISIONS && entity.isPlayer()) {
                SLogger.log(this, String.format("[%s %d] Raw sweep test found no geometric collisions.",
                        entity.getType().getName().getString(), entity.getId()));
            }

        } // end synchronized block

        return closestResult; // Return the raw geometric result (or null)
    }

    /**
     * Checks if two collision objects are currently colliding.
     * This uses jBullet's collision detection to properly handle all shape types.
     */
    private boolean checkCollision(CollisionObject objA, CollisionObject objB) {
        // Get the collision dispatcher
        CollisionDispatcher dispatcher = (CollisionDispatcher) physicsEngine.getDynamicsWorld().getDispatcher();

        // Get the collision algorithm (with just 2 parameters as per jBullet's API)
        CollisionAlgorithm algorithm = dispatcher.findAlgorithm(objA, objB);

        if (algorithm == null) {
            return false;
        }

        // Instead of using processCollision directly, check existing manifolds
        // This is safer since the API might differ between jBullet versions
        DynamicsWorld dynamicsWorld = physicsEngine.getDynamicsWorld();

        for (int i = 0; i < dispatcher.getNumManifolds(); i++) {
            PersistentManifold manifold = dispatcher.getManifoldByIndexInternal(i);

            // Check if this manifold involves our objects
            boolean objectsMatch = (
                    (manifold.getBody0() == objA && manifold.getBody1() == objB) ||
                            (manifold.getBody0() == objB && manifold.getBody1() == objA)
            );

            if (objectsMatch && manifold.getNumContacts() > 0) {
                // Clean up
                dispatcher.freeCollisionAlgorithm(algorithm);
                return true;
            }
        }

        // If no existing manifold, fall back to AABB overlap
        Vector3f aabbMinA = new Vector3f();
        Vector3f aabbMaxA = new Vector3f();
        Vector3f aabbMinB = new Vector3f();
        Vector3f aabbMaxB = new Vector3f();

        Transform transA = new Transform();
        Transform transB = new Transform();
        objA.getWorldTransform(transA);
        objB.getWorldTransform(transB);

        objA.getCollisionShape().getAabb(transA, aabbMinA, aabbMaxA);
        objB.getCollisionShape().getAabb(transB, aabbMinB, aabbMaxB);

        // Clean up
        dispatcher.freeCollisionAlgorithm(algorithm);

        // Check for AABB overlap with a small margin
        float margin = 0.01f;
        return (
                aabbMinA.x - margin <= aabbMaxB.x && aabbMaxA.x + margin >= aabbMinB.x &&
                        aabbMinA.y - margin <= aabbMaxB.y && aabbMaxA.y + margin >= aabbMinB.y &&
                        aabbMinA.z - margin <= aabbMaxB.z && aabbMaxA.z + margin >= aabbMinB.z
        );
    }

    /**
     * Detects contacts between an entity and other objects.
     * This method properly uses jBullet's collision detection for all shape types.
     */
    /**
     * Detects contacts between an entity and other objects.
     * This method properly uses jBullet's collision detection for all shape types.
     */
    public List<Contact> detectContacts(Entity entity) {
        List<Contact> contacts = new ArrayList<>();
        boolean isPlayer = entity instanceof PlayerEntity;

        synchronized (physicsEngine.getPhysicsLock()) {
            // Get the entity's collision object
            CollisionObject entityCollisionObject = getEntityCollisionObject(entity);
            if (entityCollisionObject == null) {
                if (DEBUG_COLLISIONS && isPlayer) {
                    SLogger.log(this, "Player collision object not found in detectContacts");
                }
                return contacts;
            }

            // Create a collision dispatcher
            CollisionDispatcher dispatcher = (CollisionDispatcher) physicsEngine.getDynamicsWorld().getDispatcher();

            // Create and configure a DispatcherInfo
            DispatcherInfo dispatcherInfo = new DispatcherInfo();
            dispatcherInfo.timeStep = 1.0f / 20.0f; // Minecraft's tick rate
            dispatcherInfo.stepCount = 0;
            dispatcherInfo.debugDraw = null;
            dispatcherInfo.allowedCcdPenetration = 0.0f;
            dispatcherInfo.enableSatConvex = false;

            // 1. Check for collisions with grids
            for (LocalGrid grid : physicsEngine.getGrids()) {
                RigidBody gridBody = grid.getRigidBody();
                if (gridBody == null) continue;

                // Skip if this is the entity's own grid
                if (gridBody.getUserPointer() == entity) continue;

                // Get collision algorithm for this pair
                CollisionAlgorithm algorithm = dispatcher.findAlgorithm(
                        entityCollisionObject, gridBody,
                        null
                );

                if (algorithm == null) continue;

                // Create ManifoldResult to receive contact information
                ManifoldResult manifoldResult = new ManifoldResult(entityCollisionObject, gridBody);

                // Process collision with proper parameters
                algorithm.processCollision(entityCollisionObject, gridBody, dispatcherInfo, manifoldResult);

                // Get the manifold that was populated by the ManifoldResult
                PersistentManifold manifold = manifoldResult.getPersistentManifold();

                if (manifold != null) {
                    // Extract contact points
                    int numContacts = manifold.getNumContacts();
                    for (int i = 0; i < numContacts; i++) {
                        ManifoldPoint contactPoint = manifold.getContactPoint(i);

                        // Get penetration depth
                        float penetration = -contactPoint.getDistance();

                        // Skip if penetration is too small
                        if (penetration < MIN_PENETRATION_FOR_CONTACT) {
                            continue;
                        }

                        // Get contact normal (pointing from B to A)
                        Vector3f normal = new Vector3f(contactPoint.normalWorldOnB);

                        // The normal points from B to A, but we want it to point from grid to entity
                        // If the entity is A, we need to negate the normal
                        if (entityCollisionObject == manifold.getBody0()) {
                            normal.negate();
                        }

                        // Ensure the normal is normalized
                        if (normal.lengthSquared() > 0) {
                            normal.normalize();
                        } else {
                            normal.set(0, 1, 0); // Default to up if normal is zero
                        }

                        // Get contact point in world space
                        Vector3f contactPointWorld = new Vector3f();
                        if (entityCollisionObject == manifold.getBody0()) {
                            contactPoint.getPositionWorldOnA(contactPointWorld);
                        } else {
                            contactPoint.getPositionWorldOnB(contactPointWorld);
                        }

                        // Create a contact
                        Contact contact = new Contact(entity, grid, null, normal, contactPointWorld, penetration);

                        // Add grid velocity
                        Vector3f gridVelocity = grid.getVelocityAtPoint(contactPointWorld);
                        contact.setGridVelocityAtContactPoint(gridVelocity);

                        contacts.add(contact);
                    }
                }

                // Clean up
                dispatcher.freeCollisionAlgorithm(algorithm);
            }

            // 2. Check for collisions with other entities
//            for (Map.Entry<Entity, EntityProxy> entry : entityPhysicsManager.getEntityProxies().entrySet()) {
//                Entity otherEntity = entry.getKey();
//                EntityProxy otherProxy = entry.getValue();
//
//                // Skip self or invalid proxies
//                if (otherEntity == entity || !otherProxy.isActive()) continue;
//
//                CollisionObject otherCollisionObject = otherProxy.getCollisionObject();
//                if (otherCollisionObject == null) continue;
//
//                // Get collision algorithm for this pair
//                CollisionAlgorithm algorithm = dispatcher.findAlgorithm(
//                        entityCollisionObject, otherCollisionObject,
//                        null
//                );
//
//                if (algorithm == null) continue;
//
//                // Create ManifoldResult to receive contact information
//                ManifoldResult manifoldResult = new ManifoldResult(entityCollisionObject, otherCollisionObject);
//
//                // Process collision with proper parameters
//                algorithm.processCollision(entityCollisionObject, otherCollisionObject, dispatcherInfo, manifoldResult);
//
//                // Get the manifold that was populated by the ManifoldResult
//                PersistentManifold manifold = manifoldResult.getPersistentManifold();
//
//                if (manifold != null) {
//                    // Extract contact points
//                    int numContacts = manifold.getNumContacts();
//                    for (int i = 0; i < numContacts; i++) {
//                        ManifoldPoint contactPoint = manifold.getContactPoint(i);
//
//                        // Get penetration depth
//                        float penetration = -contactPoint.getDistance();
//
//                        // Skip if penetration is too small
//                        if (penetration < MIN_PENETRATION_FOR_CONTACT) {
//                            continue;
//                        }
//
//                        // Get contact normal (pointing from B to A)
//                        Vector3f normal = new Vector3f(contactPoint.normalWorldOnB);
//
//                        // The normal points from B to A, but we want it to point from other entity to this entity
//                        // If this entity is A, we need to negate the normal
//                        if (entityCollisionObject == manifold.getBody0()) {
//                            normal.negate();
//                        }
//
//                        // Ensure the normal is normalized
//                        if (normal.lengthSquared() > 0) {
//                            normal.normalize();
//                        } else {
//                            normal.set(0, 1, 0); // Default to up if normal is zero
//                        }
//
//                        // Get contact point in world space
//                        Vector3f contactPointWorld = new Vector3f();
//                        if (entityCollisionObject == manifold.getBody0()) {
//                            contactPoint.getPositionWorldOnA(contactPointWorld);
//                        } else {
//                            contactPoint.getPositionWorldOnB(contactPointWorld);
//                        }
//
//                        // Create a contact
//                        Contact contact = new Contact(entity, null, otherEntity, normal, contactPointWorld, penetration);
//                        contacts.add(contact);
//                    }
//                }

                // Clean up
//                dispatcher.freeCollisionAlgorithm(algorithm);
//            }
        }

        // Debug log the contacts
        if (DEBUG_COLLISIONS && isPlayer && !contacts.isEmpty()) {
            SLogger.log(this, String.format("Detected %d contacts for player", contacts.size()));
            for (int i = 0; i < contacts.size(); i++) {
                Contact contact = contacts.get(i);
                Vector3f normal = contact.getContactNormal();
                String contactType = contact.isGridContact() ? "grid" : "entity";

                SLogger.log(this, String.format(
                        "Contact %d: type=%s, normal=(%.2f, %.2f, %.2f), depth=%.4f, ground=%s",
                        i, contactType, normal.x, normal.y, normal.z,
                        contact.getPenetrationDepth(),
                        contact.isGroundContact() ? "true" : "false"));
            }
        }

        // Store contacts for later use
        entityContacts.put(entity, contacts);

        return contacts;
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

        // Simple AABB overlap check first
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

    /**
     * Estimates the collision normal between two collision objects.
     * This uses shape information to determine the most likely contact normal.
     */
    private Vector3f estimateCollisionNormal(CollisionObject objA, CollisionObject objB) {
        // Get transforms for both objects
        Transform transA = new Transform();
        Transform transB = new Transform();
        objA.getWorldTransform(transA);
        objB.getWorldTransform(transB);

        // Get centers of both objects
        Vector3f centerA = new Vector3f(transA.origin);
        Vector3f centerB = new Vector3f(transB.origin);

        // Calculate direction from B to A
        Vector3f direction = new Vector3f();
        direction.sub(centerA, centerB);

        // If centers are too close, we need to use shape information
        if (direction.lengthSquared() < 1e-4f) {
            // Get AABBs for both objects
            Vector3f aabbMinA = new Vector3f();
            Vector3f aabbMaxA = new Vector3f();
            Vector3f aabbMinB = new Vector3f();
            Vector3f aabbMaxB = new Vector3f();

            objA.getCollisionShape().getAabb(transA, aabbMinA, aabbMaxA);
            objB.getCollisionShape().getAabb(transB, aabbMinB, aabbMaxB);

            // Find the closest face based on penetration depths
            float penetrationX = Math.min(
                    aabbMaxA.x - aabbMinB.x,
                    aabbMaxB.x - aabbMinA.x
            );

            float penetrationY = Math.min(
                    aabbMaxA.y - aabbMinB.y,
                    aabbMaxB.y - aabbMinA.y
            );

            float penetrationZ = Math.min(
                    aabbMaxA.z - aabbMinB.z,
                    aabbMaxB.z - aabbMinA.z
            );

            // Use direction with minimum penetration
            if (penetrationX <= penetrationY && penetrationX <= penetrationZ) {
                // Determine X direction based on positions
                float centerDiffX = centerA.x - centerB.x;
                direction.set(centerDiffX > 0 ? 1 : -1, 0, 0);
            } else if (penetrationY <= penetrationX && penetrationY <= penetrationZ) {
                // Determine Y direction based on positions
                float centerDiffY = centerA.y - centerB.y;
                direction.set(0, centerDiffY > 0 ? 1 : -1, 0);
            } else {
                // Determine Z direction based on positions
                float centerDiffZ = centerA.z - centerB.z;
                direction.set(0, 0, centerDiffZ > 0 ? 1 : -1);
            }
        } else {
            // Normalize the direction vector
            direction.normalize();
        }

        return direction;
    }

    /**
     * Estimates a contact point between two collision objects.
     * This uses shape information to determine the most likely contact point.
     */
    private Vector3f estimateContactPoint(CollisionObject objA, CollisionObject objB) {
        // Get transforms for both objects
        Transform transA = new Transform();
        Transform transB = new Transform();
        objA.getWorldTransform(transA);
        objB.getWorldTransform(transB);

        // Get AABBs for both objects
        Vector3f aabbMinA = new Vector3f();
        Vector3f aabbMaxA = new Vector3f();
        Vector3f aabbMinB = new Vector3f();
        Vector3f aabbMaxB = new Vector3f();

        objA.getCollisionShape().getAabb(transA, aabbMinA, aabbMaxA);
        objB.getCollisionShape().getAabb(transB, aabbMinB, aabbMaxB);

        // Calculate the intersection of the AABBs
        Vector3f intersectionMin = new Vector3f();
        Vector3f intersectionMax = new Vector3f();

        intersectionMin.x = Math.max(aabbMinA.x, aabbMinB.x);
        intersectionMin.y = Math.max(aabbMinA.y, aabbMinB.y);
        intersectionMin.z = Math.max(aabbMinA.z, aabbMinB.z);

        intersectionMax.x = Math.min(aabbMaxA.x, aabbMaxB.x);
        intersectionMax.y = Math.min(aabbMaxA.y, aabbMaxB.y);
        intersectionMax.z = Math.min(aabbMaxA.z, aabbMaxB.z);

        // Use the center of the intersection as the contact point
        Vector3f contactPoint = new Vector3f();
        contactPoint.add(intersectionMin, intersectionMax);
        contactPoint.scale(0.5f);

        return contactPoint;
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
        int totalContacts = 0;
        for (Entity entity : trackedEntities) {
            List<Contact> contacts = detectContacts(entity);
            if (!contacts.isEmpty()) {
                entityContacts.put(entity, contacts);
                totalContacts += contacts.size();
            }
        }

        // Log contact collection stats
        if (DEBUG_COLLISIONS && totalContacts > 0) {
            SLogger.log(this, String.format(
                    "Collected %d contacts for %d entities (of %d tracked)",
                    totalContacts, entityContacts.size(), trackedEntities.size()));
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
     * Gets statistics about collision detection.
     *
     * @return A string containing collision stats
     */
    public String getCollisionStats() {
        return String.format(
                "Sweep tests: %d, Hits detected: %d, Entities with contacts: %d",
                sweepTestCount,
                hitDetectionCount,
                entityContacts.size());
    }

    /**
     * Resets collision statistics.
     */
    public void resetCollisionStats() {
        sweepTestCount = 0;
        hitDetectionCount = 0;
    }

    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return false; // Enable console logging for debugging
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
        private final CollisionObject object;

        /**
         * Creates a new SweepResult.
         */
        public SweepResult(float timeOfImpact, Vector3f hitNormal, Vector3f hitPoint,
                           LocalGrid grid, Entity collidedEntity, Entity sourceEntity,
                           CollisionObject object
                           ) {
            this.timeOfImpact = timeOfImpact;
            this.hitNormal = hitNormal;
            this.hitPoint = hitPoint;
            this.grid = grid;
            this.collidedEntity = collidedEntity;
            this.sourceEntity = sourceEntity;
            this.object = object;
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

        public CollisionObject getObject(){
            return object;
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

        @Override
        public String toString() {
            String collisionTarget = grid != null ? "grid" : (collidedEntity != null ? collidedEntity.getEntityName() : "unknown");
            return String.format(
                    "SweepResult{time=%.4f, target=%s, normal=(%.2f, %.2f, %.2f)}",
                    timeOfImpact,
                    collisionTarget,
                    hitNormal.x, hitNormal.y, hitNormal.z);
        }
    }
}