package net.stardance.physics.entity;

import com.bulletphysics.collision.broadphase.BroadphaseInterface;
import com.bulletphysics.collision.broadphase.BroadphaseNativeType;
import com.bulletphysics.collision.broadphase.CollisionAlgorithm;
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

    // Dedicated collision world for ghost object tests
    private final CollisionWorld ghostCollisionWorld;
    private final BroadphaseInterface ghostBroadphase;

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
     * Performs a basic sweep test to detect collisions along a movement path.
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

        sweepTestCount++;

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
                    hitDetectionCount++;

                    // Get hit info
                    CollisionObject hitObject = callback.hitCollisionObject;

                    // Create a sweep result
                    SweepResult result = createSweepResult(
                            callback.closestHitFraction,
                            callback.hitNormalWorld,
                            callback.hitPointWorld,
                            hitObject,
                            entity
                    );

                    // Log sweep test hit for debugging
                    if (DEBUG_COLLISIONS && entity instanceof PlayerEntity) {
                        SLogger.log(this, String.format(
                                "Sweep test hit: entity=%s, hitFraction=%.4f, normal=(%.2f, %.2f, %.2f)",
                                entity.getEntityName(),
                                callback.closestHitFraction,
                                callback.hitNormalWorld.x,
                                callback.hitNormalWorld.y,
                                callback.hitNormalWorld.z));
                    }

                    return result;
                }
            }
        }

        // No collision detected
        return null;
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
     * Fixed implementation of convexSweepTest that works with jBullet's API.
     */
    public SweepResult convexSweepTest(Entity entity, Vec3d movement, Map<Entity, EntityProxy> entityProxies) {
        // Skip if movement is too small
        if (movement.lengthSquared() < 1e-6) {
            return null;
        }

        // Track sweep tests for debugging
        sweepTestCount++;

        boolean isPlayer = entity instanceof PlayerEntity;

        // Debug log for players
        if (DEBUG_COLLISIONS && isPlayer) {
            SLogger.log(this, String.format(
                    "Performing convex sweep test for player. Movement=(%.4f, %.4f, %.4f)",
                    movement.x, movement.y, movement.z));
        }

        // Get the entity's collision object
        CollisionObject entityCollisionObject = getEntityCollisionObject(entity);
        if (entityCollisionObject == null) {
            if (DEBUG_COLLISIONS && isPlayer) {
                SLogger.log(this, "No collision object found for player");
            }
            return null;
        }

        // Make sure we're using the right collision shape (from the EntityProxy)
        CollisionShape entityShape = entityCollisionObject.getCollisionShape();
        if (!(entityShape instanceof ConvexShape)) {
            // If it's not a convex shape, we can't do a convex sweep test
            if (DEBUG_COLLISIONS) {
                SLogger.log(this, "Entity shape is not a convex shape: " + entityShape.getClass().getSimpleName());
            }
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

        SweepResult closestResult = null;
        float closestHitFraction = 1.0f;

        synchronized (physicsEngine.getPhysicsLock()) {
            DynamicsWorld dynamicsWorld = physicsEngine.getDynamicsWorld();

            // 1. First check for collisions with grid rigid bodies
            for (LocalGrid grid : physicsEngine.getGrids()) {
                RigidBody gridBody = grid.getRigidBody();
                if (gridBody == null) continue;

                // Skip if this is the entity's own grid
                if (gridBody.getUserPointer() == entity) continue;

                // Create callback for this specific sweep test
                ClosestConvexResultCallback callback = new ClosestConvexResultCallback(new Vector3f(), new Vector3f());

                // Perform sweep test against this grid
                dynamicsWorld.convexSweepTest((ConvexShape)entityShape, startTransform, endTransform, callback);

                // Check if we hit this grid
                if (callback.hasHit() && callback.closestHitFraction < closestHitFraction) {
                    closestHitFraction = callback.closestHitFraction;

                    // Create a sweep result from this hit
                    closestResult = new SweepResult(
                            callback.closestHitFraction,
                            new Vector3f(callback.hitNormalWorld),
                            new Vector3f(callback.hitPointWorld),
                            grid, // Grid that was hit
                            null, // No entity collision
                            entity
                    );

                    if (DEBUG_COLLISIONS && isPlayer) {
                        SLogger.log(this, String.format(
                                "jBullet sweep test hit grid %s at fraction %.4f, normal=(%.2f, %.2f, %.2f)",
                                grid.getGridId(),
                                callback.closestHitFraction,
                                callback.hitNormalWorld.x,
                                callback.hitNormalWorld.y,
                                callback.hitNormalWorld.z));
                    }
                }
            }

            // 2. Now check for collisions with other entities
            for (Map.Entry<Entity, EntityProxy> entry : entityProxies.entrySet()) {
                Entity otherEntity = entry.getKey();
                EntityProxy otherProxy = entry.getValue();

                // Skip self or invalid proxies
                if (otherEntity == entity || !otherProxy.isActive()) continue;

                CollisionObject otherCollisionObject = otherProxy.getCollisionObject();
                if (otherCollisionObject == null) continue;

                // Check for collision at start position
                boolean startCollision = checkCollision(entityCollisionObject, otherCollisionObject);

                // If already colliding, create a result with time=0
                if (startCollision) {
                    // We're already colliding, create a result with estimated info
                    Vector3f normal = estimateCollisionNormal(entityCollisionObject, otherCollisionObject);
                    Vector3f contactPoint = estimateContactPoint(entityCollisionObject, otherCollisionObject);

                    SweepResult result = new SweepResult(
                            0.0f, // Already colliding
                            normal,
                            contactPoint,
                            null, // No grid collision
                            otherEntity,
                            entity
                    );

                    if (closestResult == null || result.getTimeOfImpact() < closestResult.getTimeOfImpact()) {
                        closestResult = result;
                    }

                    if (DEBUG_COLLISIONS && isPlayer) {
                        SLogger.log(this, String.format(
                                "Already colliding with entity %s, normal=(%.2f, %.2f, %.2f)",
                                otherEntity.getUuidAsString(),
                                normal.x, normal.y, normal.z));
                    }
                }
                // If not already colliding, check for collision at end position
                else {
                    // Move entity to end position
                    Transform tempTransform = new Transform(startTransform);
                    tempTransform.origin.set(endTransform.origin);

                    // Save original transform
                    Transform originalTransform = new Transform();
                    entityCollisionObject.getWorldTransform(originalTransform);

                    // Temporarily set entity transform to end position
                    entityCollisionObject.setWorldTransform(tempTransform);

                    // Check for collision at end position
                    boolean endCollision = checkCollision(entityCollisionObject, otherCollisionObject);

                    // Restore original transform
                    entityCollisionObject.setWorldTransform(originalTransform);

                    if (endCollision) {
                        // We'd collide at the end, so estimate a time of impact
                        // For simplicity, use 0.5 as the time of impact
                        float timeOfImpact = 0.5f;

                        // Estimate normal and contact point
                        Vector3f normal = estimateCollisionNormal(entityCollisionObject, otherCollisionObject);
                        Vector3f contactPoint = estimateContactPoint(entityCollisionObject, otherCollisionObject);

                        SweepResult result = new SweepResult(
                                timeOfImpact,
                                normal,
                                contactPoint,
                                null, // No grid collision
                                otherEntity,
                                entity
                        );

                        if (closestResult == null || result.getTimeOfImpact() < closestResult.getTimeOfImpact()) {
                            closestResult = result;
                        }

                        if (DEBUG_COLLISIONS && isPlayer) {
                            SLogger.log(this, String.format(
                                    "Would collide with entity %s at end of movement, normal=(%.2f, %.2f, %.2f)",
                                    otherEntity.getUuidAsString(),
                                    normal.x, normal.y, normal.z));
                        }
                    }
                }
            }
        }

        // If we found a collision, return the closest result
        if (closestResult != null) {
            hitDetectionCount++;
            return closestResult;
        }

        // No collision detected
        if (DEBUG_COLLISIONS && isPlayer) {
            SLogger.log(this, "Player sweep test found no collisions");
        }

        return null;
    }

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

                // Create a temporary manifold to store contact points
                PersistentManifold manifold = new PersistentManifold();

                // Process collision
                algorithm.processCollision(entityCollisionObject, gridBody, null, manifold);

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

                // Clean up
                dispatcher.freeCollisionAlgorithm(algorithm);
            }

            // 2. Check for collisions with other entities
            for (Map.Entry<Entity, EntityProxy> entry : entityPhysicsManager.getEntityProxies().entrySet()) {
                Entity otherEntity = entry.getKey();
                EntityProxy otherProxy = entry.getValue();

                // Skip self or invalid proxies
                if (otherEntity == entity || !otherProxy.isActive()) continue;

                CollisionObject otherCollisionObject = otherProxy.getCollisionObject();
                if (otherCollisionObject == null) continue;

                // Get collision algorithm for this pair
                CollisionAlgorithm algorithm = dispatcher.findAlgorithm(
                        entityCollisionObject, otherCollisionObject,
                        null
                );

                if (algorithm == null) continue;

                // Create a temporary manifold to store contact points
                PersistentManifold manifold = new PersistentManifold();

                // Process collision
                algorithm.processCollision(entityCollisionObject, otherCollisionObject, null, manifold);

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

                    // The normal points from B to A, but we want it to point from other entity to this entity
                    // If this entity is A, we need to negate the normal
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
                    Contact contact = new Contact(entity, null, otherEntity, normal, contactPointWorld, penetration);
                    contacts.add(contact);
                }

                // Clean up
                dispatcher.freeCollisionAlgorithm(algorithm);
            }
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
     * Performs a direct collision test between an entity and a grid without relying on jBullet.
     * This is more reliable for detecting grid collisions than sweep tests.
     */
    private boolean directGridCollisionTest(Entity entity, LocalGrid grid) {
        // Get entity AABB
        Box entityBox = entity.getBoundingBox();

        // Get grid AABB
        Vector3f gridMin = new Vector3f();
        Vector3f gridMax = new Vector3f();
        grid.getAABB(gridMin, gridMax);

        // Convert to Box for easier testing
        Box gridBox = new Box(
                gridMin.x, gridMin.y, gridMin.z,
                gridMax.x, gridMax.y, gridMax.z
        );

        // If AABBs don't intersect, no collision
        if (!entityBox.intersects(gridBox)) {
            return false;
        }

        // Now we need a more detailed test - use a small margin to detect near misses
        double margin = 0.05;

        // Get entity center
        double entityCenterX = (entityBox.minX + entityBox.maxX) / 2;
        double entityCenterY = (entityBox.minY + entityBox.maxY) / 2;
        double entityCenterZ = (entityBox.minZ + entityBox.maxZ) / 2;

        // Get entity half-extents
        double entityHalfWidth = (entityBox.maxX - entityBox.minX) / 2;
        double entityHalfHeight = (entityBox.maxY - entityBox.minY) / 2;
        double entityHalfDepth = (entityBox.maxZ - entityBox.minZ) / 2;

        // Get the grid center and half-extents
        double gridCenterX = (gridMin.x + gridMax.x) / 2;
        double gridCenterY = (gridMin.y + gridMax.y) / 2;
        double gridCenterZ = (gridMin.z + gridMax.z) / 2;

        double gridHalfWidth = (gridMax.x - gridMin.x) / 2;
        double gridHalfHeight = (gridMax.y - gridMin.y) / 2;
        double gridHalfDepth = (gridMax.z - gridMin.z) / 2;

        // Calculate distance between centers on each axis
        double distX = Math.abs(entityCenterX - gridCenterX);
        double distY = Math.abs(entityCenterY - gridCenterY);
        double distZ = Math.abs(entityCenterZ - gridCenterZ);

        // Calculate sum of half-extents on each axis
        double sumHalfWidth = entityHalfWidth + gridHalfWidth;
        double sumHalfHeight = entityHalfHeight + gridHalfHeight;
        double sumHalfDepth = entityHalfDepth + gridHalfDepth;

        // If the distance between centers is less than the sum of half-extents plus margin,
        // we have a collision or near-collision on that axis
        boolean collisionX = distX <= sumHalfWidth + margin;
        boolean collisionY = distY <= sumHalfHeight + margin;
        boolean collisionZ = distZ <= sumHalfDepth + margin;

        // We need collision on all three axes for 3D intersection
        return collisionX && collisionY && collisionZ;
    }

    /**
     * Estimates the collision normal between an entity and a grid.
     */
    private Vector3f estimateGridNormal(Entity entity, LocalGrid grid) {
        // Get entity AABB
        Box entityBox = entity.getBoundingBox();

        // Get grid AABB
        Vector3f gridMin = new Vector3f();
        Vector3f gridMax = new Vector3f();
        grid.getAABB(gridMin, gridMax);

        // Get entity center
        double entityCenterX = (entityBox.minX + entityBox.maxX) / 2;
        double entityCenterY = (entityBox.minY + entityBox.maxY) / 2;
        double entityCenterZ = (entityBox.minZ + entityBox.maxZ) / 2;

        // Get the grid center
        double gridCenterX = (gridMin.x + gridMax.x) / 2;
        double gridCenterY = (gridMin.y + gridMax.y) / 2;
        double gridCenterZ = (gridMin.z + gridMax.z) / 2;

        // Direction from grid to entity
        double dirX = entityCenterX - gridCenterX;
        double dirY = entityCenterY - gridCenterY;
        double dirZ = entityCenterZ - gridCenterZ;

        // Find which axis has the smallest penetration
        double gridHalfWidth = (gridMax.x - gridMin.x) / 2;
        double gridHalfHeight = (gridMax.y - gridMin.y) / 2;
        double gridHalfDepth = (gridMax.z - gridMin.z) / 2;

        double penetrationX = gridHalfWidth - Math.abs(dirX);
        double penetrationY = gridHalfHeight - Math.abs(dirY);
        double penetrationZ = gridHalfDepth - Math.abs(dirZ);

        // Create normal based on smallest penetration axis
        Vector3f normal = new Vector3f(0, 0, 0);

        // Determine which axis has the smallest penetration (closest to grid edge)
        if (penetrationX <= penetrationY && penetrationX <= penetrationZ) {
            // X-axis has smallest penetration
            normal.x = dirX > 0 ? 1.0f : -1.0f;
        } else if (penetrationY <= penetrationX && penetrationY <= penetrationZ) {
            // Y-axis has smallest penetration
            normal.y = dirY > 0 ? 1.0f : -1.0f;
        } else {
            // Z-axis has smallest penetration
            normal.z = dirZ > 0 ? 1.0f : -1.0f;
        }

        return normal;
    }

    /**
     * Estimates a contact point between an entity and a grid.
     */
    private Vector3f estimateGridContactPoint(Entity entity, LocalGrid grid) {
        // Get entity AABB
        Box entityBox = entity.getBoundingBox();

        // Get grid AABB
        Vector3f gridMin = new Vector3f();
        Vector3f gridMax = new Vector3f();
        grid.getAABB(gridMin, gridMax);

        // Get entity center
        double entityCenterX = (entityBox.minX + entityBox.maxX) / 2;
        double entityCenterY = (entityBox.minY + entityBox.maxY) / 2;
        double entityCenterZ = (entityBox.minZ + entityBox.maxZ) / 2;

        // Get the grid center
        double gridCenterX = (gridMin.x + gridMax.x) / 2;
        double gridCenterY = (gridMin.y + gridMax.y) / 2;
        double gridCenterZ = (gridMin.z + gridMax.z) / 2;

        // Direction from grid to entity
        double dirX = entityCenterX - gridCenterX;
        double dirY = entityCenterY - gridCenterY;
        double dirZ = entityCenterZ - gridCenterZ;

        // Get grid half-extents
        double gridHalfWidth = (gridMax.x - gridMin.x) / 2;
        double gridHalfHeight = (gridMax.y - gridMin.y) / 2;
        double gridHalfDepth = (gridMax.z - gridMin.z) / 2;

        // Clamp direction to grid surface
        double surfaceX = gridCenterX + Math.signum(dirX) * gridHalfWidth;
        double surfaceY = gridCenterY + Math.signum(dirY) * gridHalfHeight;
        double surfaceZ = gridCenterZ + Math.signum(dirZ) * gridHalfDepth;

        // Find intersection point - closest point on grid surface to entity center
        Vector3f contactPoint = new Vector3f();

        // Determine which axis has the smallest penetration (closest to grid edge)
        double penetrationX = gridHalfWidth - Math.abs(dirX);
        double penetrationY = gridHalfHeight - Math.abs(dirY);
        double penetrationZ = gridHalfDepth - Math.abs(dirZ);

        if (penetrationX <= penetrationY && penetrationX <= penetrationZ) {
            // X-axis has smallest penetration - contact point is on X face
            contactPoint.x = (float)surfaceX;
            contactPoint.y = (float)entityCenterY;
            contactPoint.z = (float)entityCenterZ;
        } else if (penetrationY <= penetrationX && penetrationY <= penetrationZ) {
            // Y-axis has smallest penetration - contact point is on Y face
            contactPoint.x = (float)entityCenterX;
            contactPoint.y = (float)surfaceY;
            contactPoint.z = (float)entityCenterZ;
        } else {
            // Z-axis has smallest penetration - contact point is on Z face
            contactPoint.x = (float)entityCenterX;
            contactPoint.y = (float)entityCenterY;
            contactPoint.z = (float)surfaceZ;
        }

        return contactPoint;
    }

    /**
     * Internal implementation of convex sweep test using a ghost object.
     */
    private void convexSweepTestInternal(GhostObject ghostObject, Transform from,
                                         Transform to, MultiSweepCallback callback) {
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
                if (t < 0.1f) {
                    // This means we're already intersecting at the start
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

            // Skip entities that shouldn't interact
            Entity otherEntity = proxy.getEntity();
            if (!shouldEntitiesInteract(entity, otherEntity)) {
                return false;
            }

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
     * Determines if two entities should interact with each other.
     * Override this for entity-specific collision filtering.
     */
    private boolean shouldEntitiesInteract(Entity entity1, Entity entity2) {
        // Players shouldn't collide with other players for now
        if (entity1 instanceof PlayerEntity && entity2 instanceof PlayerEntity) {
            return false;
        }

        // Default: allow entities to interact
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
        return true; // Enable console logging for debugging
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
         * Fixed deflected movement calculation for SweepResult class.
         * This improves the handling of sliding motion when colliding at an angle.
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

            // Original movement length for reference
            float originalLength = movement.length();
            if (originalLength < 0.0001f) {
                return Vec3d.ZERO;
            }

            // Calculate remaining movement magnitude
            float remainingMagnitude = originalLength * remainingTime;

            // Project remaining movement onto collision plane
            Vector3f normal = new Vector3f(hitNormal);
            normal.normalize();

            // Calculate dot product to get component along normal
            float dot = movement.dot(normal);

            // Only deflect if moving into the surface
            if (dot < 0) {
                // Calculate the parallel component by removing the normal component
                Vector3f normalComponent = new Vector3f(normal);
                normalComponent.scale(dot);

                Vector3f tangentialComponent = new Vector3f(movement);
                tangentialComponent.sub(normalComponent);

                // Calculate the magnitude of the tangential component
                float tangentialLength = tangentialComponent.length();

                // If tangential component is too small, just return zero
                if (tangentialLength < 0.0001f) {
                    return Vec3d.ZERO;
                }

                // Normalize the tangential component
                tangentialComponent.scale(1.0f / tangentialLength);

                // Calculate the appropriate magnitude for the deflected movement
                // Use a factor of the original magnitude (not exceeding the original length)
                // This is a key fix - previously it might have expanded to the full magnitude
                float deflectedMagnitude = Math.min(remainingMagnitude * 0.8f, tangentialLength * remainingTime);

                // Scale the tangential component to the appropriate magnitude
                tangentialComponent.scale(deflectedMagnitude);

                // Convert back to Vec3d
                return new Vec3d(tangentialComponent.x, tangentialComponent.y, tangentialComponent.z);
            }

            // If not moving into surface, allow full movement
            return originalMovement.multiply(remainingTime);
        }

        @Override
        public String toString() {
            String collisionTarget = grid != null ? "grid" : (collidedEntity != null ? collidedEntity.getEntityName() : "unknown");
            return String.format(
                    "SweepResult{time=%.4f, target=%s, normal=(%.2f, %.2f, %.2f), hits=%d}",
                    timeOfImpact,
                    collisionTarget,
                    hitNormal.x, hitNormal.y, hitNormal.z,
                    allHits.size());
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