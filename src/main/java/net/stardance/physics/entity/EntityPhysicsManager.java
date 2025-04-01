package net.stardance.physics.entity;

import com.bulletphysics.dynamics.DynamicsWorld;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.stardance.physics.PhysicsEngine;
import net.stardance.physics.SubchunkCoordinates;
import net.stardance.physics.SubchunkManager;
import net.stardance.utils.ILoggingControl;
import net.stardance.utils.SLogger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central manager for entity physics interactions.
 * Tracks entities in active subchunks and manages their physics proxies.
 */
public class EntityPhysicsManager implements ILoggingControl {
    // Core references
    private final PhysicsEngine engine;
    private final ServerWorld world;
    private final DynamicsWorld dynamicsWorld;
    private final SubchunkManager subchunkManager;

    // Entity tracking
    private final Map<SubchunkCoordinates, Set<Entity>> entitiesBySubchunk = new HashMap<>();
    private final Map<Entity, Set<SubchunkCoordinates>> subchunksByEntity = new HashMap<>();
    private final Set<Entity> trackedEntities = ConcurrentHashMap.newKeySet();

    // Proxy management
    private final Map<Entity, EntityProxy> entityProxies = new ConcurrentHashMap<>();

    // Contact detection and resolution
    private final ContactDetector contactDetector;
    private final CollisionResolver collisionResolver;

    // Prioritization settings
    private final Set<Entity> priorityEntities = Collections.newSetFromMap(new WeakHashMap<>());
    private static final int MAX_ENTITIES_PER_SUBCHUNK = 32; // Limit for performance

    /**
     * Creates a new EntityPhysicsManager for the given physics engine.
     */
    public EntityPhysicsManager(PhysicsEngine engine, ServerWorld world) {
        this.engine = engine;
        this.world = world;
        this.dynamicsWorld = engine.getDynamicsWorld();
        this.subchunkManager = engine.getSubchunkManager();

        // Initialize subsystems
        this.contactDetector = new ContactDetector(dynamicsWorld);
        this.collisionResolver = new CollisionResolver();

        SLogger.log(this, "EntityPhysicsManager initialized for world: " + world);
    }

    /**
     * Updates entity tracking for all active subchunks.
     * Called once per tick to maintain accurate entity tracking.
     */
    public void updateEntitiesInSubchunks(ServerWorld world) {
        // First, clear any tracking data for entities that no longer exist
        cleanupRemovedEntities();

        // For each active subchunk, find entities within its bounds
        for (SubchunkCoordinates coords : subchunkManager.getActiveSubchunks()) {
            // Convert subchunk coordinates to world coordinates
            int minX = coords.x * 16;
            int minY = coords.y * 16;
            int minZ = coords.z * 16;

            // Create a box for this subchunk
            Box subchunkBox = new Box(
                    minX, minY, minZ,
                    minX + 16, minY + 16, minZ + 16
            );

            // Find all entities that intersect this box
            List<Entity> entitiesInBox = world.getEntitiesByClass(
                    Entity.class,
                    subchunkBox,
                    entity -> !shouldIgnoreEntity(entity)
            );

            // Prioritize and limit entities if needed
            if (entitiesInBox.size() > MAX_ENTITIES_PER_SUBCHUNK) {
                entitiesInBox = prioritizeEntities(entitiesInBox);
            }

            // Update our tracking maps
            Set<Entity> entitiesInSubchunk = entitiesBySubchunk.computeIfAbsent(
                    coords, k -> new HashSet<>()
            );

            // Clear the previous entities for this subchunk
            entitiesInSubchunk.clear();

            // Add all entities in this subchunk
            for (Entity entity : entitiesInBox) {
                // Add to tracked entities set
                trackedEntities.add(entity);

                // Add to subchunk's entity set
                entitiesInSubchunk.add(entity);

                // Update reverse mapping
                Set<SubchunkCoordinates> entitySubchunks = subchunksByEntity.computeIfAbsent(
                        entity, k -> new HashSet<>()
                );
                entitySubchunks.add(coords);
            }
        }

        // Update proxies for all currently tracked entities
        updateEntityProxies();

        SLogger.log(this, "Updated entity tracking: " +
                trackedEntities.size() + " entities across " +
                entitiesBySubchunk.size() + " subchunks");
    }

    /**
     * Prioritizes entities for tracking when there are too many in a subchunk.
     * Players and important entities are prioritized.
     */
    private List<Entity> prioritizeEntities(List<Entity> entities) {
        // Sort entities by priority
        entities.sort((a, b) -> {
            // Priority entities first
            if (priorityEntities.contains(a) && !priorityEntities.contains(b)) {
                return -1;
            } else if (!priorityEntities.contains(a) && priorityEntities.contains(b)) {
                return 1;
            }

            // Then player entities
            boolean aIsPlayer = a.getType().equals(net.minecraft.entity.EntityType.PLAYER);
            boolean bIsPlayer = b.getType().equals(net.minecraft.entity.EntityType.PLAYER);
            if (aIsPlayer && !bIsPlayer) {
                return -1;
            } else if (!aIsPlayer && bIsPlayer) {
                return 1;
            }

            // Then larger entities
            Box aBox = a.getBoundingBox();
            Box bBox = b.getBoundingBox();
            double aVolume = (aBox.maxX - aBox.minX) * (aBox.maxY - aBox.minY) * (aBox.maxZ - aBox.minZ);
            double bVolume = (bBox.maxX - bBox.minX) * (bBox.maxY - bBox.minY) * (bBox.maxZ - bBox.minZ);
            return Double.compare(bVolume, aVolume);
        });

        // Take only the top MAX_ENTITIES_PER_SUBCHUNK entities
        return entities.subList(0, Math.min(entities.size(), MAX_ENTITIES_PER_SUBCHUNK));
    }

    /**
     * Updates all entity proxies for currently tracked entities.
     * Creates new proxies if needed, updates existing ones.
     */
    public void updateEntityProxies() {
        // Process all currently tracked entities
        for (Entity entity : trackedEntities) {
            // Skip if the entity should be ignored
            if (shouldIgnoreEntity(entity)) {
                continue;
            }

            // Get or create proxy
            EntityProxy proxy = entityProxies.get(entity);
            if (proxy == null) {
                // Create a new proxy using our factory
                proxy = EntityProxyFactory.createProxy(entity, dynamicsWorld);
                entityProxies.put(entity, proxy);
                SLogger.log(this, "Created new proxy for entity: " + entity.getUuid());
            } else {
                // Update existing proxy
                proxy.update();
            }
        }

        // Remove proxies for entities no longer tracked
        Iterator<Map.Entry<Entity, EntityProxy>> it = entityProxies.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Entity, EntityProxy> entry = it.next();
            if (!trackedEntities.contains(entry.getKey())) {
                // Remove the proxy
                entry.getValue().remove();
                it.remove();
                SLogger.log(this, "Removed proxy for entity: " + entry.getKey().getUuid());
            }
        }
    }

    /**
     * Sets an entity as a priority entity for tracking.
     * Priority entities are always tracked when in range.
     */
    public void setPriorityEntity(Entity entity, boolean isPriority) {
        if (isPriority) {
            priorityEntities.add(entity);
        } else {
            priorityEntities.remove(entity);
        }
    }

    /**
     * Analyzes a potential movement vector for an entity and returns
     * a modified "safe" movement that accounts for collisions.
     */
    public Vec3d analyzePotentialMovement(Entity entity, Vec3d movement) {
        // If movement is essentially zero, no need to process
        if (movement.lengthSquared() < 0.0001) {
            return movement;
        }

        // If entity isn't being tracked, no collisions to worry about
        if (!trackedEntities.contains(entity)) {
            return movement;
        }

        // Perform sweep test to detect collisions
        ContactDetector.SweepResult result = contactDetector.performBestSweepTest(
                entity, movement, entityProxies);

        // If no collision, movement is already safe
        if (result == null) {
            return movement;
        }

        // Calculate safe movement with a small safety margin
        double safetyMargin = 0.01; // 1cm safety margin
        Vec3d safeMovement = result.getSafePosition(entity.getPos(), movement, safetyMargin)
                .subtract(entity.getPos());

        // Calculate remaining movement time
        float remainingTime = 1.0f - result.getTimeOfImpact();

        // If no time remaining, just return the safe movement
        if (remainingTime <= 0.01f) {
            return safeMovement;
        }

        // Calculate deflected movement
        Vec3d deflectedMovement = result.getDeflectedMovement(movement, remainingTime);

        // Combine safe movement with deflected movement
        return safeMovement.add(deflectedMovement);
    }

    /**
     * Checks if an entity should be ignored for physics simulation.
     */
    private boolean shouldIgnoreEntity(Entity entity) {
        // Skip spectators and noclip entities
        if (entity.isSpectator() || entity.noClip) {
            return true;
        }

        // Skip if entity is dead or removed
        if (entity.isRemoved()) {
            return true;
        }

        // Additional filtering logic can be added here

        return false;
    }

    /**
     * Forces tracking of all entities in the specified box.
     * Useful for debug and for ensuring critical entities are tracked.
     */
    public int forceTrackNearbyEntities(Box box) {
        List<Entity> entitiesInBox = world.getEntitiesByClass(
                Entity.class, box, entity -> !entity.isSpectator()
        );

        int count = 0;
        for (Entity entity : entitiesInBox) {
            if (shouldIgnoreEntity(entity)) {
                continue;
            }

            trackedEntities.add(entity);
            count++;

            // Make sure it has a proxy
            if (!entityProxies.containsKey(entity)) {
                EntityProxy proxy = new EntityProxy(entity, dynamicsWorld);
                entityProxies.put(entity, proxy);
            }
        }

        SLogger.log(this, "Forced tracking of " + count + " entities");
        return count;
    }

    /**
     * Removes tracking data for entities that no longer exist.
     */
    private void cleanupRemovedEntities() {
        // Remove from trackedEntities
        trackedEntities.removeIf(Entity::isRemoved);

        // Remove from subchunksByEntity
        Iterator<Map.Entry<Entity, Set<SubchunkCoordinates>>> entryIt =
                subchunksByEntity.entrySet().iterator();
        while (entryIt.hasNext()) {
            if (entryIt.next().getKey().isRemoved()) {
                entryIt.remove();
            }
        }

        // Remove from entitiesBySubchunk
        for (Set<Entity> entities : entitiesBySubchunk.values()) {
            entities.removeIf(Entity::isRemoved);
        }
    }

    /**
     * Gets all entities currently being tracked.
     */
    public Set<Entity> getTrackedEntities() {
        return Collections.unmodifiableSet(trackedEntities);
    }

    /**
     * Gets all entities in a specific subchunk.
     */
    public Set<Entity> getEntitiesInSubchunk(SubchunkCoordinates coords) {
        return entitiesBySubchunk.getOrDefault(coords, Collections.emptySet());
    }

    /**
     * Gets all entity proxies.
     */
    public Map<Entity, EntityProxy> getEntityProxies() {
        return Collections.unmodifiableMap(entityProxies);
    }

    /**
     * Gets the contact detector.
     */
    public ContactDetector getContactDetector() {
        return contactDetector;
    }

    /**
     * Gets the collision resolver.
     */
    public CollisionResolver getCollisionResolver() {
        return collisionResolver;
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