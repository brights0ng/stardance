package net.stardance.physics.entity;

import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.stardance.core.LocalGrid;
import net.stardance.physics.PhysicsEngine;
import net.stardance.physics.SubchunkCoordinates;
import net.stardance.utils.ILoggingControl;
import net.stardance.utils.SLogger;

import javax.vecmath.Vector3d;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages physics interactions between entities and physics objects.
 * Responsible for tracking entities, creating proxies, and handling collisions.
 */
public class EntityPhysicsManager implements ILoggingControl {

    // Reference to parent PhysicsEngine
    private final PhysicsEngine physicsEngine;

    // Reference to the server world
    private final ServerWorld world;

    // Entity tracking
    private final Map<Entity, EntityProxy> entityProxies = new ConcurrentHashMap<>();
    private final Map<SubchunkCoordinates, Set<Entity>> entitiesBySubchunk = new ConcurrentHashMap<>();
    private final Set<Entity> trackedEntities = ConcurrentHashMap.newKeySet();

    // Helper components
    private final ContactDetector contactDetector;
    private final CollisionResolver collisionResolver;
    private final EntityProxyFactory proxyFactory;

    /**
     * Creates a new EntityPhysicsManager for the given engine and world.
     */
    public EntityPhysicsManager(PhysicsEngine physicsEngine, ServerWorld world) {
        this.physicsEngine = physicsEngine;
        this.world = world;

        // Initialize helper components
        this.contactDetector = new ContactDetector(this, physicsEngine);
        this.collisionResolver = new CollisionResolver(this);
        this.proxyFactory = new EntityProxyFactory(physicsEngine);

        SLogger.log(this, "EntityPhysicsManager initialized for world: " + world.getRegistryKey().getValue());
    }

    /**
     * Updates tracked entities based on active subchunks.
     * Called each tick to refresh entity tracking.
     */
    public void updateEntitiesInSubchunks(ServerWorld world) {
        // Get all active subchunks
        Set<SubchunkCoordinates> activeSubchunks = physicsEngine.getSubchunkManager().getActiveSubchunks();

        // Clear existing entity tracking first to prevent excess proxies
        clearUnusedEntityTracking();

        // Create a set of entities to track this tick
        Set<Entity> entitiesToTrack = ConcurrentHashMap.newKeySet();

        // Update entities by subchunk
        for (SubchunkCoordinates coords : activeSubchunks) {
            // Get or create the set of entities in this subchunk
            Set<Entity> entitiesInSubchunk = entitiesBySubchunk.computeIfAbsent(
                    coords, k -> ConcurrentHashMap.newKeySet());

            // Clear existing entities for this subchunk
            entitiesInSubchunk.clear();

            // Calculate subchunk bounds
            int x = coords.x * 16;
            int y = coords.y * 16;
            int z = coords.z * 16;
            Box subchunkBox = new Box(x, y, z, x + 16, y + 16, z + 16);

            // Find entities in this subchunk - only in loaded chunks
            if (world.isChunkLoaded(x >> 4, z >> 4)) {
                List<Entity> entitiesInBox = world.getEntitiesByClass(Entity.class, subchunkBox, this::shouldTrackEntity);

                for (Entity entity : entitiesInBox) {
                    entitiesInSubchunk.add(entity);
                    entitiesToTrack.add(entity);
                }
            }
        }

        // Update master tracking list
        updateTrackedEntities(entitiesToTrack);

        // Log tracking stats in debug mode
        if (!trackedEntities.isEmpty()) {
            SLogger.log(this, "Tracking " + trackedEntities.size() + " entities across " +
                    activeSubchunks.size() + " active subchunks");
        }
    }

    /**
     * Clears all entity tracking data that's no longer needed.
     * This helps prevent memory leaks and unnecessary physics calculations.
     */
    private void clearUnusedEntityTracking() {
        // Remove entities from tracking that are no longer valid
        for (Iterator<Entity> it = trackedEntities.iterator(); it.hasNext();) {
            Entity entity = it.next();
            if (!entity.isAlive() || entity.isRemoved()) {
                // Remove from tracking
                EntityProxy proxy = entityProxies.remove(entity);
                if (proxy != null) {
                    proxy.dispose();
                }
                it.remove();
            }
        }

        // Clean up subchunk map - remove entries for no-longer-active subchunks
        Set<SubchunkCoordinates> activeSubchunks = physicsEngine.getSubchunkManager().getActiveSubchunks();
        entitiesBySubchunk.keySet().removeIf(coords -> !activeSubchunks.contains(coords));
    }

    /**
     * Updates the master list of tracked entities.
     * Creates proxies for new entities and removes proxies for entities no longer tracked.
     */
    private void updateTrackedEntities(Set<Entity> entitiesToTrack) {
        // Remove entities no longer in active subchunks
        trackedEntities.removeIf(entity -> {
            if (!entitiesToTrack.contains(entity) || !entity.isAlive()) {
                stopTrackingEntity(entity);
                return true;
            }
            return false;
        });

        // Add new entities
        for (Entity entity : entitiesToTrack) {
            if (!trackedEntities.contains(entity)) {
                startTrackingEntity(entity);
            }
        }

        // Only update proxies for entities that actually need updates
        // This is an optimization to avoid unnecessary work
        for (Entity entity : trackedEntities) {
            if (needsProxyUpdate(entity)) {
                updateEntityProxy(entity);
            }
        }
    }

    /**
     * Determines if an entity's proxy needs updating this tick.
     * Optimization to avoid updating every entity proxy every tick.
     */
    private boolean needsProxyUpdate(Entity entity) {
        // For now, always update players
        if (entity instanceof net.minecraft.entity.player.PlayerEntity) {
            return true;
        }

        // Update if entity is moving
        if (entity.getVelocity().lengthSquared() > 0.0001) {
            return true;
        }

        // Update every few ticks regardless, to handle other state changes
        return world.getTime() % 10 == entity.getId() % 10;
    }

    /**
     * Starts tracking an entity by creating a proxy for it.
     * Proxy creation is now lazy - only created when actually needed.
     */
    private void startTrackingEntity(Entity entity) {
        trackedEntities.add(entity);
        // We don't create the proxy here anymore - it will be created on demand
        SLogger.log(this, "Started tracking entity: " + entity);
    }

    /**
     * Gets or creates a proxy for an entity.
     * This implements lazy initialization of proxies.
     */
    private EntityProxy getOrCreateEntityProxy(Entity entity) {
        EntityProxy proxy = entityProxies.get(entity);
        if (proxy == null) {
            // Create new proxy on demand
            proxy = proxyFactory.createProxy(entity);
            entityProxies.put(entity, proxy);
        }
        return proxy;
    }

    /**
     * Stops tracking an entity and removes its proxy.
     */
    private void stopTrackingEntity(Entity entity) {
        EntityProxy proxy = entityProxies.remove(entity);
        if (proxy != null) {
            proxy.dispose(); // Clean up any resources
        }
        SLogger.log(this, "Stopped tracking entity: " + entity);
    }

    /**
     * Updates the physics proxy for an entity.
     */
    private void updateEntityProxy(Entity entity) {
        // Get or create the proxy just-in-time
        EntityProxy proxy = getOrCreateEntityProxy(entity);
        if (proxy != null) {
            proxy.updateFromEntity(entity);
        }
    }

    /**
     * Determines if an entity should be tracked.
     * Filters out entities that don't need physics interactions.
     */
    private boolean shouldTrackEntity(Entity entity) {
        // Skip entities that don't need physics
        if (entity.isSpectator() || entity.noClip) {
            return false;
        }

        // Skip non-collidable entities
        if (!entity.isPushable()) {
            return false;
        }

        // Add more filters based on entity type if needed
        return true;
    }

    /**
     * Analyzes potential movement and adjusts it for grid collisions.
     * Called by the entity mixin before vanilla collision handling.
     *
     * @param entity The entity being moved
     * @param movement The proposed movement vector
     * @return A modified movement vector that avoids grid collisions
     */
    public Vec3d analyzePotentialMovement(Entity entity, Vec3d movement) {
        if (!trackedEntities.contains(entity)) {
            // Force track entity if not already tracked
            forceTrackEntity(entity);
        }

        EntityProxy proxy = entityProxies.get(entity);
        if (proxy == null || !proxy.isActive()) {
            return movement;
        }

        // Perform sweep test to detect collision with grids
        ContactDetector.SweepResult result = contactDetector.convexSweepTest(entity, movement, entityProxies);

        if (result == null) {
            // No collision detected, return original movement
            return movement;
        }

        // Collision detected, calculate adjusted movement
        return collisionResolver.resolvePreMovementCollision(entity, movement, result);
    }

    /**
     * Processes post-movement collisions and adjusts entity state.
     * Called by the entity mixin after vanilla movement.
     *
     * @param entity The entity that moved
     * @param originalMovement The original movement vector
     * @param actualMovement The actual movement after vanilla collision handling
     */
    public void processPostMovement(Entity entity, Vec3d originalMovement, Vec3d actualMovement) {
        if (!trackedEntities.contains(entity)) {
            return;
        }

        EntityProxy proxy = entityProxies.get(entity);
        if (proxy == null || !proxy.isActive()) {
            return;
        }

        // Update proxy position
        proxy.updateFromEntity(entity);

        // Detect contacts after movement
        List<Contact> contacts = contactDetector.detectContacts(entity);

        if (!contacts.isEmpty()) {
            // Adjust position and velocity based on contacts
            collisionResolver.resolvePostMovementCollision(entity, contacts);

            // Update ground state
            boolean onGround = collisionResolver.checkIfOnGround(entity, contacts);
            if (onGround) {
                // Entity is on ground, set state
                setEntityOnGround(entity);
            }
        }
    }

    /**
     * Adjusts entity velocity after a collision.
     * Maintains momentum for sliding along surfaces.
     *
     * @param entity The entity to adjust
     * @param movement The original movement vector
     * @param collisionResult The collision-adjusted movement vector
     */
    public void adjustVelocityAfterCollision(Entity entity, Vec3d movement, Vec3d collisionResult) {
        // Calculate collision response vector
        Vector3d collisionResponse = new Vector3d(
                collisionResult.x - movement.x,
                collisionResult.y - movement.y,
                collisionResult.z - movement.z
        );

        // Skip if collision response is negligible
        if (collisionResponse.lengthSquared() < 1e-6) {
            return;
        }

        // Get current velocity
        Vec3d currentVelocity = entity.getVelocity();

        // Normalize collision response
        Vector3d normalizedResponse = new Vector3d(collisionResponse);
        normalizedResponse.normalize();

        // Calculate parallel component of velocity
        double parallelComponent = normalizedResponse.dot(
                new Vector3d(currentVelocity.x, currentVelocity.y, currentVelocity.z)
        );

        // Only cancel velocity component parallel to collision normal
        // and only if moving toward the collision (parallelComponent < 0)
        if (parallelComponent < 0) {
            entity.setVelocity(
                    currentVelocity.x - normalizedResponse.x * parallelComponent,
                    currentVelocity.y - normalizedResponse.y * parallelComponent,
                    currentVelocity.z - normalizedResponse.z * parallelComponent
            );
        }
    }

    /**
     * Forces an entity to be tracked even if it's not in an active subchunk.
     * Useful for player entities or other important entities.
     */
    public void forceTrackEntity(Entity entity) {
        if (!trackedEntities.contains(entity) && entity.isAlive() && !entity.isSpectator()) {
            startTrackingEntity(entity);
        }
    }

    /**
     * Forces tracking of all entities within the given box.
     * Useful for testing or ensuring entities near players are tracked.
     *
     * @param box The box to search for entities
     * @return The number of entities that were added to tracking
     */
    public int forceTrackNearbyEntities(Box box) {
        int count = 0;

        for (Entity entity : world.getEntitiesByClass(Entity.class, box, this::shouldTrackEntity)) {
            if (!trackedEntities.contains(entity)) {
                startTrackingEntity(entity);
                count++;
            }
        }

        return count;
    }

    /**
     * Handles the case when a grid collides with an entity.
     * Ensures that entities won't be pushed into world blocks.
     *
     * @param grid The grid that collided with entities
     * @param worldMovementLimit The maximum movement that won't cause entities to intersect world blocks
     */
    public void handleGridEntityCollisions(LocalGrid grid, Vec3d worldMovementLimit) {
        // Get entities that might be affected by this grid
        Vector3f minAabb = new Vector3f();
        Vector3f maxAabb = new Vector3f();
        grid.getAABB(minAabb, maxAabb);

        // Expand AABB to account for entity movement
        float expansion = 1.0f;  // 1 block expansion
        minAabb.x -= expansion;
        minAabb.y -= expansion;
        minAabb.z -= expansion;
        maxAabb.x += expansion;
        maxAabb.y += expansion;
        maxAabb.z += expansion;

        Box gridBox = new Box(
                minAabb.x, minAabb.y, minAabb.z,
                maxAabb.x, maxAabb.y, maxAabb.z
        );

        // Get all entities in this box
        List<Entity> potentialCollisions = world.getEntitiesByClass(
                Entity.class, gridBox, this::shouldTrackEntity);

        // Check each entity for a safe move
        for (Entity entity : potentialCollisions) {
            handleGridToEntityCollision(grid, entity, worldMovementLimit);
        }
    }

    /**
     * Handles a specific grid-to-entity collision.
     *
     * @param grid The grid involved in the collision
     * @param entity The entity involved in the collision
     * @param worldMovementLimit The maximum safe movement vector
     */
    private void handleGridToEntityCollision(LocalGrid grid, Entity entity, Vec3d worldMovementLimit) {
        // First check if they're actually in contact
        boolean inContact = contactDetector.checkEntityGridContact(entity, grid);

        if (!inContact) {
            return;
        }

        // Get the grid's velocity at the entity's position
        Vector3f entityPosVector = new Vector3f(
                (float)entity.getX(),
                (float)entity.getY(),
                (float)entity.getZ()
        );

        Vector3f gridVelocity = grid.getVelocityAtPoint(entityPosVector);
        Vec3d gridVelocityVec = new Vec3d(gridVelocity.x, gridVelocity.y, gridVelocity.z);

        // Calculate a push factor based on relative mass
        float pushFactor = calculatePushFactor(grid, entity);

        // Create a safe movement that won't push entity into world blocks
        Vec3d safeMovement = collisionResolver.calculateSafeEntityMovement(
                entity, gridVelocityVec.multiply(pushFactor), worldMovementLimit);

        // Apply the movement to the entity
        if (!safeMovement.equals(Vec3d.ZERO)) {
            entity.move(net.minecraft.entity.MovementType.SELF, safeMovement);

            // If entity is on the ground relative to this grid, also apply some of the grid's
            // horizontal velocity to the entity
            applyHorizontalVelocityIfOnGround(entity, grid, gridVelocity, pushFactor);
        }
    }

    /**
     * Calculates a push factor based on relative masses.
     */
    private float calculatePushFactor(LocalGrid grid, Entity entity) {
        // This could be adjusted based on entity type, mass, etc.
        return 1.0f;
    }

    /**
     * Applies horizontal velocity to an entity if it's on top of a grid.
     */
    private void applyHorizontalVelocityIfOnGround(Entity entity, LocalGrid grid,
                                                   Vector3f gridVelocity, float factor) {
        // Check if the entity is on top of the grid
        if (contactDetector.isEntityOnGrid(entity, grid)) {
            // Get current entity velocity
            Vec3d entityVel = entity.getVelocity();

            // Apply grid's horizontal velocity component
            entity.setVelocity(
                    entityVel.x + gridVelocity.x * factor * 0.8,
                    entityVel.y,
                    entityVel.z + gridVelocity.z * factor * 0.8
            );
        }
    }

    /**
     * Sets the entity's onGround state.
     * Using a helper method to avoid direct field access issues.
     */
    private void setEntityOnGround(Entity entity) {
        // This will be replaced with proper field access in the mixin
        // In MixinEntity this should use @Shadow fields
        try {
            // Just setting the onGround field
            entity.setOnGround(true);
        } catch (Exception e) {
            SLogger.log(this, "Failed to set entity on ground: " + e.getMessage());
        }
    }

    /**
     * Gets the ContactDetector component.
     */
    public ContactDetector getContactDetector() {
        return contactDetector;
    }

    /**
     * Gets the CollisionResolver component.
     */
    public CollisionResolver getCollisionResolver() {
        return collisionResolver;
    }

    /**
     * Gets the current set of tracked entities.
     */
    public Set<Entity> getTrackedEntities() {
        return Collections.unmodifiableSet(trackedEntities);
    }

    /**
     * Gets the map of entity proxies.
     */
    public Map<Entity, EntityProxy> getEntityProxies() {
        return Collections.unmodifiableMap(entityProxies);
    }

    /**
     * Gets the entities in a specific subchunk.
     */
    public Set<Entity> getEntitiesInSubchunk(SubchunkCoordinates coords) {
        Set<Entity> entities = entitiesBySubchunk.get(coords);
        if (entities != null) {
            return Collections.unmodifiableSet(entities);
        }
        return Collections.emptySet();
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