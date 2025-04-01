package net.stardance.physics.entity;

import com.bulletphysics.collision.broadphase.Dispatcher;
import com.bulletphysics.collision.dispatch.CollisionObject;
import com.bulletphysics.collision.dispatch.CollisionWorld;
import com.bulletphysics.collision.narrowphase.ManifoldPoint;
import com.bulletphysics.collision.narrowphase.PersistentManifold;
import com.bulletphysics.collision.shapes.BoxShape;
import com.bulletphysics.collision.shapes.CompoundShape;
import com.bulletphysics.collision.shapes.ConvexShape;
import com.bulletphysics.dynamics.DynamicsWorld;
import com.bulletphysics.linearmath.Transform;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.stardance.core.LocalGrid;
import net.stardance.physics.WorldBlockMarker;
import net.stardance.physics.entity.Contact;
import net.stardance.utils.ILoggingControl;
import net.stardance.utils.SLogger;

import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Collects and processes contacts between entities and other objects
 * in the physics world.
 */
public class ContactDetector implements ILoggingControl {

    // Core reference
    private final DynamicsWorld dynamicsWorld;

    // Contact storage
    private final Map<Entity, List<Contact>> entityContacts = new ConcurrentHashMap<>();

    // Contact filtering
    private final Set<EntityType<?>> ignoredEntityTypes = new HashSet<>();
    private final float minimumContactDepth = 0.001f; // Ignore tiny penetrations

    /**
     * Creates a new ContactDetector.
     *
     * @param dynamicsWorld The physics world to detect contacts in
     */
    public ContactDetector(DynamicsWorld dynamicsWorld) {
        this.dynamicsWorld = dynamicsWorld;

        // Initialize ignored entity types
        initializeIgnoredTypes();
    }

    /**
     * Sets up entity types to ignore for contact detection.
     * Primarily small, non-physical entities.
     */
    private void initializeIgnoredTypes() {
        // Ignore various particle, special effect and non-physical entities
        ignoredEntityTypes.add(EntityType.AREA_EFFECT_CLOUD);
        ignoredEntityTypes.add(EntityType.ITEM_FRAME);
        ignoredEntityTypes.add(EntityType.PAINTING);
        // Add more as needed
    }
    /**
     * Collects all contacts in the physics world.
     * Should be called after stepping the physics simulation.
     *
     * @return Map of entities to their contact lists
     */
    public Map<Entity, List<Contact>> collectContacts() {
        // Clear previous contacts
        entityContacts.clear();

        // Get the collision dispatcher
        Dispatcher dispatcher = dynamicsWorld.getDispatcher();

        // Process all manifolds
        int numManifolds = dispatcher.getNumManifolds();
        for (int i = 0; i < numManifolds; i++) {
            PersistentManifold manifold = dispatcher.getManifoldByIndexInternal(i);
            processManifold(manifold);
        }

        return entityContacts;
    }

    /**
     * Process a single collision manifold to extract contact information.
     */
    private void processManifold(PersistentManifold manifold) {
        CollisionObject bodyA = (CollisionObject) manifold.getBody1();
        CollisionObject bodyB = (CollisionObject) manifold.getBody0();

        // Add this check to skip problematic combinations
        if (bodyA.getCollisionShape() instanceof BoxShape &&
                !(bodyB.getCollisionShape() instanceof CompoundShape)) {
            // Log and skip to prevent ClassCastException
            SLogger.log(this, "Skipping problematic shape combination: " +
                    bodyA.getCollisionShape().getClass().getSimpleName() + " vs " +
                    bodyB.getCollisionShape().getClass().getSimpleName());
            return;
        }

        // Extract entities and objects
        Entity entityA = getEntityFromCollisionObject(bodyA);
        Entity entityB = getEntityFromCollisionObject(bodyB);
        Object objectA = getUserObject(bodyA);
        Object objectB = getUserObject(bodyB);

        // We're only interested in entity-grid contacts
        boolean isEntityGridContact =
                (entityA != null && objectB instanceof LocalGrid) ||
                        (entityB != null && objectA instanceof LocalGrid);

        if (!isEntityGridContact) {
            return;
        }

        // Determine which is the entity and which is the grid
        boolean entityIsA = entityA != null;
        Entity entity = entityIsA ? entityA : entityB;
        Object collidedWith = entityIsA ? objectB : objectA;
        boolean normalFlip = !entityIsA;

        // Skip ignored entity types
        if (ignoredEntityTypes.contains(entity.getType())) {
            return;
        }

        // Process each contact point in the manifold
        int numContacts = manifold.getNumContacts();
        for (int j = 0; j < numContacts; j++) {
            ManifoldPoint point = manifold.getContactPoint(j);

            // Skip if not actually in contact (positive distance)
            if (point.getDistance() > 0) {
                continue;
            }

            // Skip very shallow contacts
            float penetrationDepth = -point.getDistance();
            if (penetrationDepth < minimumContactDepth) {
                continue;
            }

            // Extract contact information
            Vector3f contactPoint = new Vector3f();
            point.getPositionWorldOnB(contactPoint);

            // Normal points from B to A
            Vector3f normal = new Vector3f(point.normalWorldOnB);
            if (normalFlip) {
                normal.negate();
            }

            // Calculate relative velocity at contact point
            Vector3f relativeVelocity = calculateRelativeVelocity(
                    entity,
                    collidedWith instanceof LocalGrid ? (LocalGrid) collidedWith : null,
                    contactPoint
            );

            // Create contact record
            Contact contact = new Contact(
                    entity,
                    collidedWith,
                    contactPoint,
                    normal,
                    penetrationDepth,
                    relativeVelocity
            );

            // Store the contact
            entityContacts
                    .computeIfAbsent(entity, k -> new ArrayList<>())
                    .add(contact);

            SLogger.log(this, "Collected contact: " + contact);
        }
    }

    /**
     * Calculates relative velocity between entity and grid at contact point.
     * This is crucial for calculating proper collision response.
     */
    private Vector3f calculateRelativeVelocity(Entity entity, LocalGrid grid, Vector3f contactPoint) {
        // Get entity velocity
        Vector3f entityVel = new Vector3f(
                (float) entity.getVelocity().x,
                (float) entity.getVelocity().y,
                (float) entity.getVelocity().z
        );

        // If no grid, just return entity velocity
        if (grid == null || grid.getRigidBody() == null) {
            return entityVel;
        }

        // Get grid's velocity at contact point - this is crucial for moving platforms
        Vector3f linearVel = new Vector3f();
        Vector3f angularVel = new Vector3f();
        grid.getRigidBody().getLinearVelocity(linearVel);
        grid.getRigidBody().getAngularVelocity(angularVel);

        Transform gridTransform = new Transform();
        grid.getRigidBody().getWorldTransform(gridTransform);

        Vector3f gridCenter = new Vector3f();
        gridTransform.origin.get(gridCenter);

        Vector3f relativePos = new Vector3f();
        relativePos.sub(contactPoint, gridCenter);

        // Calculate velocity at contact point (v = linear + angular × r)
        Vector3f gridVelAtPoint = new Vector3f(linearVel);
        Vector3f angularComponent = new Vector3f();
        angularComponent.cross(angularVel, relativePos);
        gridVelAtPoint.add(angularComponent);

        // Calculate relative velocity (entity - grid)
        Vector3f relativeVel = new Vector3f();
        relativeVel.sub(entityVel, gridVelAtPoint);

        return relativeVel;
    }

    /**
     * Performs a sweep test for an entity moving along a path.
     * Detects the first collision along the movement path.
     *
     * @param entity   Entity that is moving
     * @param movement Movement vector
     * @return SweepResult containing collision information, or null if no collision
     */
    public SweepResult sweepTest(Entity entity, Vec3d movement) {
        // If movement is essentially zero, no collision can occur
        if (movement.lengthSquared() < 0.0001) {
            return null;
        }

        // Get entity's current bounding box
        Box entityBox = entity.getBoundingBox();

        // Calculate start and end positions (center points)
        Vec3d startPos = entity.getPos();
        Vec3d endPos = startPos.add(movement);

        // Convert to bullet physics vectors
        Vector3f from = new Vector3f((float) startPos.x, (float) startPos.y, (float) startPos.z);
        Vector3f to = new Vector3f((float) endPos.x, (float) endPos.y, (float) endPos.z);

        // Create a ray cast callback with a custom collision filter
        CustomRayResultCallback callback = new CustomRayResultCallback(from, to);

        // Set up filter to match our entity proxy configuration
        callback.collisionFilterGroup = EntityProxy.ENTITY_GROUP;
        callback.collisionFilterMask = EntityProxy.ENTITY_MASK;

        // Perform the raycast in the dynamics world
        dynamicsWorld.rayTest(from, to, callback);

        // If no hit, check with ConvexSweepTest for better accuracy
        if (!callback.hasHit()) {
            // Get the entity's collision shape from its proxy
            // This would require tracking entity proxies or finding them
            // For now, let's log that we'll need to implement this
            SLogger.log(this, "No ray hit, would need convex sweep test for entity " + entity.getUuid());
            return null;
        }

        // Extract hit information
        Vector3f hitPointWorld = callback.hitPointWorld;
        Vector3f hitNormalWorld = callback.hitNormalWorld;
        CollisionObject hitObject = callback.collisionObject;
        Object userObj = hitObject != null ? hitObject.getUserPointer() : null;

        // Create and return the sweep result
        SweepResult result = new SweepResult(
                callback.closestHitFraction,
                hitPointWorld,
                hitNormalWorld,
                userObj,
                true, // Assume all collisions are blocking for now
                entity
        );

        SLogger.log(this, "Sweep test for " + entity.getType().getName().getString() +
                ": " + result);

        return result;
    }

    /**
     * Performs a multi-sweep test that detects all collisions along a movement path.
     *
     * @param entity   Entity that is moving
     * @param movement Movement vector
     * @return List of SweepResults, ordered by time of impact
     */
    public List<SweepResult> sweepTestAll(Entity entity, Vec3d movement) {
        List<SweepResult> results = new ArrayList<>();
        Vec3d remainingMovement = movement;
        Vec3d currentPos = entity.getPos();

        // Limit the number of iterations to prevent infinite loops
        int maxIterations = 5;

        for (int i = 0; i < maxIterations; i++) {
            // If remaining movement is essentially zero, stop
            if (remainingMovement.lengthSquared() < 0.0001) {
                break;
            }

            // Perform a single sweep test from current position
            SweepResult result = sweepTest(entity, remainingMovement);

            // If no collision, we're done
            if (result == null) {
                break;
            }

            // Add result to the list
            results.add(result);

            // Calculate time remaining after this collision
            float remainingTime = 1.0f - result.getTimeOfImpact();

            // If this is a blocking collision and we've used up almost all the time, stop
            if (result.isBlocking() && remainingTime < 0.01f) {
                break;
            }

            // Calculate deflected movement and continue
            Vec3d deflectedMovement = result.getDeflectedMovement(remainingMovement, remainingTime);

            // Update position and remaining movement for next iteration
            currentPos = result.getSafePosition(currentPos, remainingMovement, 0.01);
            remainingMovement = deflectedMovement;
        }

        return results;
    }

    /**
     * Performs a sweep test using the entity's actual collision shape.
     * This is more accurate than a simple ray test because it takes the entity's
     * shape into account.
     *
     * @param entity Entity that is moving
     * @param movement Movement vector
     * @param entityProxies Map of entities to their physics proxies
     * @return SweepResult containing collision information, or null if no collision
     */
    public SweepResult convexSweepTest(Entity entity, Vec3d movement, Map<Entity, EntityProxy> entityProxies) {
        // If movement is essentially zero, no collision can occur
        if (movement.lengthSquared() < 0.0001) {
            return null;
        }

        // Get entity's physics proxy to access its collision shape
        EntityProxy proxy = entityProxies.get(entity);
        if (proxy == null) {
            SLogger.log(this, "No physics proxy found for entity: " + entity.getUuid());
            return null;
        }

        // Get the collision object and ensure it has a valid shape
        CollisionObject collisionObject = proxy.getCollisionObject();
        if (collisionObject == null || collisionObject.getCollisionShape() == null) {
            SLogger.log(this, "No valid collision shape for entity: " + entity.getUuid());
            return null;
        }

        // Check if the shape is a convex shape (required for sweep test)
        if (!(collisionObject.getCollisionShape() instanceof ConvexShape)) {
            SLogger.log(this, "Entity shape is not a convex shape, falling back to ray test: " + entity.getUuid());
            return sweepTest(entity, movement);
        }

        ConvexShape convexShape = (ConvexShape) collisionObject.getCollisionShape();

        // Calculate start and end transforms
        Transform startTransform = new Transform();
        Transform endTransform = new Transform();

        // Get current transform from collision object
        collisionObject.getWorldTransform(startTransform);

        // Create end transform by copying start and updating position
        endTransform.set(startTransform);
        endTransform.origin.x += movement.x;
        endTransform.origin.y += movement.y;
        endTransform.origin.z += movement.z;

        // Create callback for sweep test
        ConvexSweepCallback callback = new ConvexSweepCallback(entity);

        // Perform the sweep test
        SLogger.log(this, "Performing convex sweep test for entity: " + entity.getType().getName().getString());

        dynamicsWorld.convexSweepTest(
                convexShape,
                startTransform,
                endTransform,
                callback
        );

        // If no hit, we're done
        if (!callback.hasHit()) {
            SLogger.log(this, "No collision detected in convex sweep test");
            return null;
        }

        // Extract hit information
        SLogger.log(this, "Convex sweep test hit at fraction: " + callback.closestHitFraction);

        // Create and return the sweep result
        Vector3f hitPointWorld = callback.hitPointWorld;
        Vector3f hitNormalWorld = callback.hitNormalWorld;
        CollisionObject hitObject = callback.hitObject;
        Object userObj = hitObject != null ? hitObject.getUserPointer() : null;

        SweepResult result = new SweepResult(
                callback.closestHitFraction,
                hitPointWorld,
                hitNormalWorld,
                userObj,
                true, // Assume all collisions are blocking for now
                entity
        );

        SLogger.log(this, "Convex sweep result: " + result);
        return result;
    }

    /**
     * Gets all contacts specifically for entity-grid interactions.
     */
    public List<Contact> getGridContactsForEntity(Entity entity) {
        List<Contact> allContacts = getContactsForEntity(entity);
        List<Contact> gridContacts = new ArrayList<>();

        for (Contact contact : allContacts) {
            if (contact.isGridContact()) {
                gridContacts.add(contact);
            }
        }

        return gridContacts;
    }

    /**
     * Groups contacts by the grid they're with.
     */
    public Map<LocalGrid, List<Contact>> getContactsByGrid(Entity entity) {
        Map<LocalGrid, List<Contact>> result = new HashMap<>();
        List<Contact> contacts = getContactsForEntity(entity);

        for (Contact contact : contacts) {
            if (contact.isGridContact()) {
                LocalGrid grid = contact.getGrid();
                result.computeIfAbsent(grid, k -> new ArrayList<>()).add(contact);
            }
        }

        return result;
    }

    /**
     * Checks if an entity has any contacts with the given grid.
     */
    public boolean hasContactsWithGrid(Entity entity, LocalGrid grid) {
        List<Contact> contacts = getContactsForEntity(entity);

        for (Contact contact : contacts) {
            if (contact.isGridContact() && contact.getGrid() == grid) {
                return true;
            }
        }

        return false;
    }

    /**
     * Custom callback for convex sweep test that filters for grid-only collisions.
     */
    private class ConvexSweepCallback extends CollisionWorld.ConvexResultCallback {
        private final Entity sourceEntity;
        public CollisionObject hitObject;
        public Vector3f hitPointWorld = new Vector3f();
        public Vector3f hitNormalWorld = new Vector3f();
        public float closestHitFraction = 1.0f;

        public ConvexSweepCallback(Entity sourceEntity) {
            this.sourceEntity = sourceEntity;

            // Set up collision filtering to match our entity proxy configuration
            this.collisionFilterGroup = EntityProxy.ENTITY_GROUP;
            this.collisionFilterMask = EntityProxy.ENTITY_MASK;
        }

        @Override
        public float addSingleResult(CollisionWorld.LocalConvexResult convexResult, boolean normalInWorldSpace) {
            Object userPointer = convexResult.hitCollisionObject.getUserPointer();

            // Filter out unwanted collision types

            // Skip entity-entity collisions
            if (userPointer instanceof Entity) {
                Entity hitEntity = (Entity) userPointer;
                if (hitEntity == sourceEntity) {
                    // Skip self-collision
                    return 1.0f; // Continue the sweep test
                }

                // Skip all entity-entity collisions
                return 1.0f;  // Continue the sweep test
            }

            // Skip world block collisions - we only want grid collisions
            if (userPointer instanceof WorldBlockMarker) {
                // Skip world block collision
                return 1.0f; // Continue the sweep test
            }

            // Keep only grid collisions
            if (!(userPointer instanceof LocalGrid)) {
                // Skip all non-grid object collisions
                return 1.0f; // Continue the sweep test
            }

            // Only record the hit if it's closer than previous hits
            if (convexResult.hitFraction < closestHitFraction) {
                closestHitFraction = convexResult.hitFraction;
                hitObject = convexResult.hitCollisionObject;

                // Store hit point
                if (convexResult.hitPointLocal != null) {
                    hitPointWorld.set(convexResult.hitPointLocal);
                }

                // Store hit normal
                if (normalInWorldSpace) {
                    hitNormalWorld.set(convexResult.hitNormalLocal);
                } else {
                    // Need to convert from local to world space
                    // For now just store as is, but in a full implementation we'd transform it
                    hitNormalWorld.set(convexResult.hitNormalLocal);
                }

                // We hit something, but keep going to find potential closer hits
                return closestHitFraction;
            }

            return closestHitFraction;
        }

        public boolean hasHit() {
            return closestHitFraction < 1.0f;
        }
    }

    /**
     * Performs the most accurate sweep test available for the given entity.
     * Tries convex sweep test first, falls back to ray test if necessary.
     *
     * @param entity Entity that is moving
     * @param movement Movement vector
     * @param entityProxies Map of entities to their physics proxies
     * @return SweepResult containing collision information, or null if no collision
     */
    public SweepResult performBestSweepTest(Entity entity, Vec3d movement, Map<Entity, EntityProxy> entityProxies) {
//        try {
////            // Try convex sweep test first (more accurate)
////            SweepResult result = convexSweepTest(entity, movement, entityProxies);
////
////            // If successful, return the result
////            if (result != null) {
////                return result;
////            }
////
////            // If convex sweep failed, fall back to ray test
////            SLogger.log(this, "Convex sweep failed, falling back to ray test");
////            return sweepTest(entity, movement);
////        } catch (Exception e) {
////            // If any errors occur, log them and fall back to ray test
////            SLogger.log(this, "Error in convex sweep test: " + e.getMessage());
////            return sweepTest(entity, movement);
////        }
        return convexSweepTest(entity, movement, entityProxies);
    }

    /**
     * Gets the Entity from a collision object's user pointer, if it is an entity.
     */
    private Entity getEntityFromCollisionObject(CollisionObject obj) {
        Object userPointer = obj.getUserPointer();
        if (userPointer instanceof Entity) {
            return (Entity) userPointer;
        }
        return null;
    }

    /**
     * Gets the user object from a collision object.
     */
    private Object getUserObject(CollisionObject obj) {
        return obj.getUserPointer();
    }

    /**
     * Gets all contacts for a specific entity.
     */
    public List<Contact> getContactsForEntity(Entity entity) {
        return entityContacts.getOrDefault(entity, Collections.emptyList());
    }

    /**
     * Gets all entities that have contacts.
     */
    public Set<Entity> getEntitiesWithContacts() {
        return entityContacts.keySet();
    }

    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return false;
    }

    /**
     * Custom ray result callback for our specific needs.
     */
    private static class CustomRayResultCallback extends CollisionWorld.ClosestRayResultCallback {
        public CustomRayResultCallback(Vector3f rayFromWorld, Vector3f rayToWorld) {
            super(rayFromWorld, rayToWorld);
        }

        @Override
        public float addSingleResult(CollisionWorld.LocalRayResult rayResult, boolean normalInWorldSpace) {
            // Skip if the hit object is an entity (we only want collisions with grid objects)
            if (rayResult.collisionObject.getUserPointer() instanceof Entity) {
                return 1.0f; // Continue the ray test
            }

            // Use the parent implementation for all other objects
            return super.addSingleResult(rayResult, normalInWorldSpace);
        }
    }

    /**
     * Results from a movement sweep test.
     * Contains information about a collision detected during movement.
     */
    public static class SweepResult {
        // Time of impact (0-1), where 0 is start and 1 is end of movement
        private final float timeOfImpact;

        // Point in world space where the collision occurred
        private final Vector3f hitPoint;

        // Surface normal at the collision point
        private final Vector3f hitNormal;

        // Object that was hit
        private final Object hitObject;

        // Whether this collision should block movement
        private final boolean isBlocking;

        // The entity that is moving
        private final Entity entity;

        /**
         * Creates a new SweepResult.
         */
        public SweepResult(float timeOfImpact, Vector3f hitPoint, Vector3f hitNormal,
                           Object hitObject, boolean isBlocking, Entity entity) {
            this.timeOfImpact = timeOfImpact;
            this.hitPoint = new Vector3f(hitPoint);
            this.hitNormal = new Vector3f(hitNormal);
            this.hitObject = hitObject;
            this.isBlocking = isBlocking;
            this.entity = entity;
        }

        /**
         * Gets the time of impact (0-1).
         */
        public float getTimeOfImpact() {
            return timeOfImpact;
        }

        /**
         * Gets the point where the collision occurred.
         */
        public Vector3f getHitPoint() {
            return new Vector3f(hitPoint);
        }

        /**
         * Gets the surface normal at the collision point.
         */
        public Vector3f getHitNormal() {
            return new Vector3f(hitNormal);
        }

        /**
         * Gets the object that was hit.
         */
        public Object getHitObject() {
            return hitObject;
        }

        /**
         * Checks if this is a grid collision.
         */
        public boolean isGridCollision() {
            return hitObject instanceof LocalGrid;
        }

        /**
         * Gets the grid that was hit, if this is a grid collision.
         */
        public LocalGrid getGrid() {
            return isGridCollision() ? (LocalGrid) hitObject : null;
        }

        /**
         * Checks if this collision should block movement.
         */
        public boolean isBlocking() {
            return isBlocking;
        }

        /**
         * Gets the entity that is moving.
         */
        public Entity getEntity() {
            return entity;
        }

        /**
         * Calculates the safe position to move to before collision.
         *
         * @param startPos     Original start position
         * @param movement     Original movement vector
         * @param safetyMargin Small distance to stay away from surface
         * @return Safe position just before collision
         */
        public Vec3d getSafePosition(Vec3d startPos, Vec3d movement, double safetyMargin) {
            // Calculate position just before collision
            double safeTime = Math.max(0, timeOfImpact - safetyMargin);
            return startPos.add(movement.multiply(safeTime));
        }

        /**
         * Calculates a deflected movement vector that slides along the collision surface.
         *
         * @param movement      Original movement vector
         * @param remainingTime Portion of movement time remaining after collision
         * @return Deflected movement vector
         */
        public Vec3d getDeflectedMovement(Vec3d movement, double remainingTime) {
            if (remainingTime <= 0.001) {
                return Vec3d.ZERO;
            }

            // Convert movement to Vector3f for calculations
            Vector3f movementVec = new Vector3f(
                    (float) movement.x,
                    (float) movement.y,
                    (float) movement.z
            );

            // Calculate remaining movement magnitude
            float remainingDistance = movementVec.length() * (float) remainingTime;

            // Calculate dot product of movement and normal
            float dotProduct = movementVec.dot(hitNormal);

            // Create a vector representing movement into the surface
            Vector3f normalComponent = new Vector3f(hitNormal);
            normalComponent.scale(dotProduct);

            // Subtract normal component to get movement along the surface
            Vector3f tangentialComponent = new Vector3f(movementVec);
            tangentialComponent.sub(normalComponent);

//            // Scale to the remaining movement distance
//            if (tangentialComponent.lengthSquared() > 0.00001f) {
//                tangentialComponent.normalize();
//                tangentialComponent.scale(remainingDistance);
//            }

            // Convert back to Vec3d
            return new Vec3d(
                    tangentialComponent.x,
                    tangentialComponent.y,
                    tangentialComponent.z
            );
        }

        @Override
        public String toString() {
            return String.format(
                    "SweepResult{time=%.3f, point=%s, normal=%s, object=%s, blocking=%b}",
                    timeOfImpact,
                    hitPoint,
                    hitNormal,
                    hitObject instanceof LocalGrid ? "Grid[" + ((LocalGrid) hitObject).getGridId() + "]" : hitObject,
                    isBlocking
            );
        }
    }
}