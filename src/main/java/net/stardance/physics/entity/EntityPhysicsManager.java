package net.stardance.physics.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
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

    // Debugging flags
    private static final boolean DEBUG_TRACKING = true;
    private static final boolean DEBUG_COLLISIONS = true;

    // Performance optimization - adjust these to balance accuracy vs performance
    private static final int ENTITY_UPDATE_FREQUENCY = 5; // Only update every N ticks
    private static final int PLAYER_UPDATE_FREQUENCY = 1; // Update players more frequently

    // Reference to parent PhysicsEngine
    private final PhysicsEngine physicsEngine;

    // Reference to the server world
    private final ServerWorld world;

    // Entity tracking
    private final Map<Entity, EntityProxy> entityProxies = new ConcurrentHashMap<>();
    private final Map<SubchunkCoordinates, Set<Entity>> entitiesBySubchunk = new ConcurrentHashMap<>();
    private final Set<Entity> trackedEntities = ConcurrentHashMap.newKeySet();
    private final Set<Entity> forcedEntities = ConcurrentHashMap.newKeySet(); // Entities that should always be tracked

    // Helper components
    private final ContactDetector contactDetector;
    private final CollisionResolver collisionResolver;
    private final EntityProxyFactory proxyFactory;

    // Stat tracking
    private long lastStatsTime = 0;
    private int entityUpdateCount = 0;
    private int movementAdjustmentCount = 0;

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
        // First check if we need to print stats
        long currentTime = world.getTime();
        if (currentTime - lastStatsTime > 200) { // Every 10 seconds
            printStats();
            lastStatsTime = currentTime;
        }

        // Get all active subchunks
        Set<SubchunkCoordinates> activeSubchunks = physicsEngine.getSubchunkManager().getActiveSubchunks();

        // Clear existing entity tracking first to prevent excess proxies
        clearUnusedEntityTracking();

        // Create a set of entities to track this tick
        Set<Entity> entitiesToTrack = ConcurrentHashMap.newKeySet();

        // Always include forced entities
        entitiesToTrack.addAll(forcedEntities);

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
        if (DEBUG_TRACKING && !trackedEntities.isEmpty() && currentTime % 20 == 0) {
            int playerCount = 0;
            for (Entity entity : trackedEntities) {
                if (entity instanceof PlayerEntity) {
                    playerCount++;
                }
            }

            SLogger.log(this, String.format(
                    "Tracking %d entities (%d players) across %d active subchunks",
                    trackedEntities.size(), playerCount, activeSubchunks.size()));
        }
    }

    /**
     * Clears all entity tracking data that's no longer needed.
     * This helps prevent memory leaks and unnecessary physics calculations.
     */
    private void clearUnusedEntityTracking() {
        // Remove entities from tracking that are no longer valid
        Iterator<Entity> it = trackedEntities.iterator();
        while (it.hasNext()) {
            Entity entity = it.next();
            if (!entity.isAlive() || entity.isRemoved()) {
                // Remove from tracking
                EntityProxy proxy = entityProxies.remove(entity);
                if (proxy != null) {
                    proxy.dispose();
                }

                // Remove from forced entities too if present
                forcedEntities.remove(entity);

                it.remove();

                if (DEBUG_TRACKING && entity instanceof PlayerEntity) {
                    SLogger.log(this, "Player removed from tracking: " + entity.getEntityName());
                }
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
        // Remove entities no longer in active subchunks (except forced entities)
        trackedEntities.removeIf(entity -> {
            if (!entitiesToTrack.contains(entity) && !forcedEntities.contains(entity)) {
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

        // Get current tick for frequency checks
        long currentTick = world.getTime();

        // Only update proxies for entities that actually need updates
        for (Entity entity : trackedEntities) {
            // Check if this entity should be updated this tick
            boolean shouldUpdate = false;

            // Players always update frequently
            if (entity instanceof PlayerEntity) {
                shouldUpdate = currentTick % PLAYER_UPDATE_FREQUENCY == 0;
            } else {
                // Other entities update less frequently, staggered by entity ID
                shouldUpdate = currentTick % ENTITY_UPDATE_FREQUENCY == entity.getId() % ENTITY_UPDATE_FREQUENCY;
            }

            // Update if needed
            if (shouldUpdate || needsProxyUpdate(entity)) {
                updateEntityProxy(entity);
                entityUpdateCount++;
            }
        }
    }

    /**
     * Determines if an entity's proxy needs updating this tick.
     * Optimization to avoid updating every entity proxy every tick.
     */
    private boolean needsProxyUpdate(Entity entity) {
        // For now, always update players
        if (entity instanceof PlayerEntity) {
            return true;
        }

        // Update if entity is moving
        if (entity.getVelocity().lengthSquared() > 0.0001) {
            return true;
        }

        // Update if bounding box has changed
        EntityProxy proxy = entityProxies.get(entity);
        if (proxy != null) {
            Box currentBox = entity.getBoundingBox();
            Box lastBox = proxy.getLastBoundingBox();

            return lastBox != null && !currentBox.equals(lastBox);
        }

        // Otherwise, no need to update
        return false;
    }

    /**
     * Starts tracking an entity by creating a proxy for it.
     * Proxy creation is lazy - only created when actually needed.
     */
    private void startTrackingEntity(Entity entity) {
        trackedEntities.add(entity);

        if (DEBUG_TRACKING && entity instanceof PlayerEntity) {
            SLogger.log(this, "Started tracking player: " + entity.getEntityName());
        }
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

            if (DEBUG_TRACKING && entity instanceof PlayerEntity) {
                SLogger.log(this, "Created physics proxy for player: " + entity.getEntityName());
            }
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

            if (DEBUG_TRACKING && entity instanceof PlayerEntity) {
                SLogger.log(this, "Stopped tracking player: " + entity.getEntityName());
            }
        }
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

        // Always track players
        if (entity instanceof PlayerEntity) {
            return true;
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
        boolean isPlayer = entity instanceof PlayerEntity;

        // Skip if no significant movement
        if (movement.lengthSquared() < 1e-6) {
            return movement;
        }

        if (!trackedEntities.contains(entity)) {
            // Force track entity if not already tracked
            forceTrackEntity(entity);
        }

        EntityProxy proxy = entityProxies.get(entity);
        if (proxy == null || !proxy.isActive()) {
            // No proxy yet, return original movement
            return movement;
        }

        // Perform sweep test to detect collision with grids
        ContactDetector.SweepResult result = contactDetector.convexSweepTest(entity, movement, entityProxies);

        // If no collision detected, return original movement
        if (result == null) {
            return movement;
        }

        movementAdjustmentCount++;

        if (DEBUG_COLLISIONS && isPlayer) {
            String hitType = result.getGrid() != null ? "grid" : "entity";
            SLogger.log(this, String.format(
                    "Player pre-movement collision detected with %s at time %.4f",
                    hitType, result.getTimeOfImpact()));
        }

        // Collision detected, calculate adjusted movement
        Vec3d adjustedMovement = collisionResolver.resolvePreMovementCollision(entity, movement, result);

        if (DEBUG_COLLISIONS && isPlayer && !adjustedMovement.equals(movement)) {
            SLogger.log(this, String.format(
                    "Player movement adjusted from (%.2f, %.2f, %.2f) to (%.2f, %.2f, %.2f)",
                    movement.x, movement.y, movement.z,
                    adjustedMovement.x, adjustedMovement.y, adjustedMovement.z));
        }

        return adjustedMovement;
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
        boolean isPlayer = entity instanceof PlayerEntity;

        if (!trackedEntities.contains(entity)) {
            return;
        }

        EntityProxy proxy = entityProxies.get(entity);
        if (proxy == null || !proxy.isActive()) {
            return;
        }

        // Update proxy position after movement
        proxy.updateFromEntity(entity);

        // Detect fresh contacts after movement
        List<Contact> contacts = contactDetector.detectContacts(entity);

        // Skip if no contacts
        if (contacts.isEmpty()) {
            return;
        }

        if (DEBUG_COLLISIONS && isPlayer) {
            SLogger.log(this, String.format(
                    "Player has %d contacts after movement", contacts.size()));
        }

        // Adjust position and velocity based on contacts
        collisionResolver.resolvePostMovementCollision(entity, contacts);

        // Update ground state if needed
        boolean onGround = collisionResolver.checkIfOnGround(entity, contacts);
        if (onGround && !entity.isOnGround()) {
            // Entity is on ground, set state
            entity.setOnGround(true);
            entity.fallDistance = 0;

            if (DEBUG_COLLISIONS && isPlayer) {
                SLogger.log(this, "Player is now on ground due to grid contact");
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
        boolean isPlayer = entity instanceof PlayerEntity;

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

        // Skip if entity isn't moving
        if (currentVelocity.lengthSquared() < 1e-6) {
            return;
        }

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
            // Calculate new velocity
            Vec3d newVelocity = new Vec3d(
                    currentVelocity.x - normalizedResponse.x * parallelComponent,
                    currentVelocity.y - normalizedResponse.y * parallelComponent,
                    currentVelocity.z - normalizedResponse.z * parallelComponent
            );

            // Apply the adjusted velocity
            entity.setVelocity(newVelocity);
            movementAdjustmentCount++;

            if (DEBUG_COLLISIONS && isPlayer) {
                SLogger.log(this, String.format(
                        "Player velocity adjusted after world collision - from=(%.2f, %.2f, %.2f), to=(%.2f, %.2f, %.2f)",
                        currentVelocity.x, currentVelocity.y, currentVelocity.z,
                        newVelocity.x, newVelocity.y, newVelocity.z));
            }
        }
    }

    /**
     * Forces an entity to be tracked even if it's not in an active subchunk.
     * Useful for player entities or other important entities.
     */
    public void forceTrackEntity(Entity entity) {
        if (!entity.isAlive() || entity.isSpectator()) {
            return;
        }

        // Add to forced entities set
        forcedEntities.add(entity);

        // Add to tracked entities if not already present
        if (!trackedEntities.contains(entity)) {
            startTrackingEntity(entity);

            if (DEBUG_TRACKING && entity instanceof PlayerEntity) {
                SLogger.log(this, "Force tracking player: " + entity.getEntityName());
            }
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
                forcedEntities.add(entity);
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

        // Skip if no potential collisions
        if (potentialCollisions.isEmpty()) {
            return;
        }

        // Check each entity for a safe move
        int collisionCount = 0;
        for (Entity entity : potentialCollisions) {
            if (handleGridToEntityCollision(grid, entity, worldMovementLimit)) {
                collisionCount++;
            }
        }

        if (DEBUG_COLLISIONS && collisionCount > 0) {
            SLogger.log(this, String.format(
                    "Grid entity collisions: handled %d collisions of %d potential entities",
                    collisionCount, potentialCollisions.size()));
        }
    }

    /**
     * Handles a specific grid-to-entity collision.
     *
     * @param grid The grid involved in the collision
     * @param entity The entity involved in the collision
     * @param worldMovementLimit The maximum safe movement vector
     * @return True if a collision was handled, false otherwise
     */
    private boolean handleGridToEntityCollision(LocalGrid grid, Entity entity, Vec3d worldMovementLimit) {
        boolean isPlayer = entity instanceof PlayerEntity;

        // First check if they're actually in contact
        boolean inContact = contactDetector.checkEntityGridContact(entity, grid);

        if (!inContact) {
            return false;
        }

        // Get the grid's velocity at the entity's position
        Vector3f entityPosVector = new Vector3f(
                (float)entity.getX(),
                (float)entity.getY(),
                (float)entity.getZ()
        );

        Vector3f gridVelocity = grid.getVelocityAtPoint(entityPosVector);

        // Skip if grid isn't moving
        if (gridVelocity.lengthSquared() < 1e-6) {
            return false;
        }

        Vec3d gridVelocityVec = new Vec3d(gridVelocity.x, gridVelocity.y, gridVelocity.z);

        // Calculate a push factor based on relative mass
        float pushFactor = calculatePushFactor(grid, entity);

        // Create a safe movement that won't push entity into world blocks
        Vec3d safeMovement = collisionResolver.calculateSafeEntityMovement(
                entity, gridVelocityVec.multiply(pushFactor), worldMovementLimit);

        // Skip if no meaningful movement
        if (safeMovement.lengthSquared() < 1e-6) {
            return false;
        }

        // Apply the movement to the entity
        entity.move(net.minecraft.entity.MovementType.SELF, safeMovement);
        movementAdjustmentCount++;

        // If entity is on the ground relative to this grid, also apply some of the grid's
        // horizontal velocity to the entity
        if (contactDetector.isEntityOnGrid(entity, grid)) {
            applyHorizontalVelocityIfOnGround(entity, grid, gridVelocity, pushFactor);
        }

        if (DEBUG_COLLISIONS && isPlayer) {
            SLogger.log(this, String.format(
                    "Player pushed by grid - gridVel=(%.2f, %.2f, %.2f), movement=(%.2f, %.2f, %.2f)",
                    gridVelocity.x, gridVelocity.y, gridVelocity.z,
                    safeMovement.x, safeMovement.y, safeMovement.z));
        }

        return true;
    }

    /**
     * Calculates a push factor based on relative masses.
     */
    private float calculatePushFactor(LocalGrid grid, Entity entity) {
        // Players are harder to push
        if (entity instanceof PlayerEntity) {
            return 0.8f;
        }

        // Living entities are harder to push than non-living
        if (entity instanceof net.minecraft.entity.LivingEntity) {
            return 0.9f;
        }

        // Default push factor for other entities
        return 1.0f;
    }

    /**
     * Applies horizontal velocity to an entity if it's on top of a grid.
     */
    private void applyHorizontalVelocityIfOnGround(Entity entity, LocalGrid grid,
                                                   Vector3f gridVelocity, float factor) {
        boolean isPlayer = entity instanceof PlayerEntity;

        // Skip if no horizontal velocity to apply
        if (Math.abs(gridVelocity.x) < 1e-6 && Math.abs(gridVelocity.z) < 1e-6) {
            return;
        }

        // Get current entity velocity
        Vec3d entityVel = entity.getVelocity();

        // Calculate new horizontal velocity influenced by grid
        Vec3d newVelocity = new Vec3d(
                entityVel.x + gridVelocity.x * factor * 0.8,
                entityVel.y,
                entityVel.z + gridVelocity.z * factor * 0.8
        );

        // Apply the new velocity
        entity.setVelocity(newVelocity);

        if (DEBUG_COLLISIONS && isPlayer) {
            SLogger.log(this, String.format(
                    "Applied horizontal grid velocity to player on ground - from=(%.2f, %.2f, %.2f), to=(%.2f, %.2f, %.2f)",
                    entityVel.x, entityVel.y, entityVel.z,
                    newVelocity.x, newVelocity.y, newVelocity.z));
        }
    }

    /**
     * Prints statistics about entity tracking and collision handling.
     */
    private void printStats() {
        if (!DEBUG_TRACKING && !DEBUG_COLLISIONS) {
            return;
        }

        int playerCount = 0;
        int proxyCount = entityProxies.size();

        for (Entity entity : trackedEntities) {
            if (entity instanceof PlayerEntity) {
                playerCount++;
            }
        }

        SLogger.log(this, String.format(
                "EntityPhysicsManager Stats - Entities: %d, Players: %d, Proxies: %d, Updates: %d, Adjustments: %d",
                trackedEntities.size(), playerCount, proxyCount, entityUpdateCount, movementAdjustmentCount));

        // Reset counters
        entityUpdateCount = 0;
        movementAdjustmentCount = 0;

        // Print component stats
        SLogger.log(this, "CollisionDetector: " + contactDetector.getCollisionStats());
        SLogger.log(this, "CollisionResolver: " + collisionResolver.getResolutionStats());

        // Reset component stats
        contactDetector.resetCollisionStats();
        collisionResolver.resetResolutionStats();
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
        return true; // Enable console logging for debugging
    }
}