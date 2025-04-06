package net.stardance.physics.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;
import net.stardance.core.LocalGrid;
import net.stardance.physics.entity.ContactDetector.SweepResult;
import net.stardance.utils.ILoggingControl;
import net.stardance.utils.SLogger;

import javax.vecmath.Vector2f;
import javax.vecmath.Vector3d;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Resolves collisions between entities and physics objects.
 * Handles both pre-movement collision avoidance and post-movement position correction.
 */
public class CollisionResolver implements ILoggingControl {
    // Constants
    private static final float POSITION_CORRECTION_FACTOR = 0.8f;
    private static final float MINIMUM_CORRECTION_MAGNITUDE = 0.005f;
    private static final float MAXIMUM_CORRECTION_MAGNITUDE = 0.5f;
    private static final float GROUND_NORMAL_Y_THRESHOLD = 0.7071f; // cos(45 degrees)
    private static final float SLIDE_FRICTION = 0.92f; // Friction factor for sliding
    private static final float GROUND_FRICTION = 0.6f; // Friction factor when on ground

    // Reference to parent
    private final EntityPhysicsManager entityPhysicsManager;

    /**
     * Creates a new CollisionResolver.
     *
     * @param entityPhysicsManager The parent EntityPhysicsManager
     */
    public CollisionResolver(EntityPhysicsManager entityPhysicsManager) {
        this.entityPhysicsManager = entityPhysicsManager;
        SLogger.log(this, "CollisionResolver initialized");
    }

    /**
     * Resolves a pre-movement collision by calculating a safe movement path.
     * Called before entity movement occurs to prevent collisions.
     *
     * @param entity The entity to move
     * @param movement The proposed movement vector
     * @param result The sweep test result with collision information
     * @return A modified movement vector that avoids the collision
     */
    public Vec3d resolvePreMovementCollision(Entity entity, Vec3d movement, SweepResult result) {
        // Special case: if the time of impact is very small, entity may be already colliding
        if (result.getTimeOfImpact() < 0.01f) {
            // Try to push the entity out of the collision
            return resolvePenetration(entity, movement, result);
        }

        // Calculate safe movement up to collision point with safety margin
        double safetyMargin = 0.01;
        Vec3d safeMovement = result.getSafePosition(Vec3d.ZERO, movement, safetyMargin);

        // Calculate remaining time after collision
        float remainingTime = 1.0f - result.getTimeOfImpact();

        // If most of the movement is complete or no time remains, just return safe movement
        if (remainingTime < 0.01f) {
            return safeMovement;
        }

        // Get sliding movement for the remainder of the time
        Vec3d slidingMovement = calculateSlidingMovement(movement, result, remainingTime);

        // Combine safe movement and sliding movement
        return safeMovement.add(slidingMovement);
    }

    /**
     * Resolves a penetration by calculating a push-out vector.
     * Used when an entity is already colliding at the start of movement.
     *
     * @param entity The entity to correct
     * @param movement The proposed movement vector
     * @param result The sweep test result with collision information
     * @return A modified movement vector that resolves the penetration
     */
    private Vec3d resolvePenetration(Entity entity, Vec3d movement, SweepResult result) {
        // Get collision normal
        Vector3f normal = result.getHitNormal();

        // Create separation vector along normal
        float separationDistance = 0.05f; // Push out slightly more to avoid sticking
        Vec3d separationVector = new Vec3d(
                normal.x * separationDistance,
                normal.y * separationDistance,
                normal.z * separationDistance
        );

        // Project original movement onto the separation plane
        Vector3d movementVec = new Vector3d(movement.x, movement.y, movement.z);
        Vector3d normalVec = new Vector3d(normal.x, normal.y, normal.z);

        // Calculate dot product
        double dot = movementVec.dot(normalVec);

        // Only project if moving into the surface
        Vec3d projectedMovement = movement;
        if (dot < 0) {
            // Remove normal component from movement
            Vector3d normalComponent = new Vector3d(normalVec);
            normalComponent.scale(dot);
            movementVec.sub(normalComponent);

            // Convert back to Vec3d
            projectedMovement = new Vec3d(movementVec.x, movementVec.y, movementVec.z);
        }

        // Scale projected movement to avoid overshooting
        projectedMovement = projectedMovement.multiply(0.8); // Reduce speed slightly

        // Combine separation and projected movement
        return separationVector.add(projectedMovement);
    }

    /**
     * Calculates a sliding movement along a surface.
     * Used for the remaining movement after a collision.
     *
     * @param originalMovement Original movement vector
     * @param result Sweep test result with collision information
     * @param remainingTime Fraction of original movement time remaining
     * @return A sliding movement vector
     */
    private Vec3d calculateSlidingMovement(Vec3d originalMovement, SweepResult result, float remainingTime) {
        // Get the collision normal
        Vector3f normal = result.getHitNormal();

        // Convert to Vector3d for more precise math
        Vector3d movementVec = new Vector3d(
                originalMovement.x,
                originalMovement.y,
                originalMovement.z
        );

        Vector3d normalVec = new Vector3d(normal.x, normal.y, normal.z);
        normalVec.normalize();

        // Calculate dot product to get component along normal
        double dot = movementVec.dot(normalVec);

        // Only project if moving into the surface
        if (dot < 0) {
            // Calculate the rejected (perpendicular) component
            Vector3d parallelComponent = new Vector3d(normalVec);
            parallelComponent.scale(dot);

            // Remove parallel component to get perpendicular component
            Vector3d perpendicularComponent = new Vector3d(movementVec);
            perpendicularComponent.sub(parallelComponent);

            // Apply friction to sliding
            perpendicularComponent.scale(SLIDE_FRICTION);

            // Scale by remaining time
            perpendicularComponent.scale(remainingTime);

            // Make sure we're moving a significant amount
            if (perpendicularComponent.lengthSquared() < 1e-6) {
                return Vec3d.ZERO;
            }

            // Convert back to Vec3d
            return new Vec3d(
                    perpendicularComponent.x,
                    perpendicularComponent.y,
                    perpendicularComponent.z
            );
        }

        // If not moving into surface, allow full movement
        return originalMovement.multiply(remainingTime);
    }

    /**
     * Resolves post-movement collisions by adjusting entity position and velocity.
     * Called after entity movement to resolve any remaining penetrations.
     *
     * @param entity The entity that moved
     * @param contacts List of contacts detected after movement
     */
    public void resolvePostMovementCollision(Entity entity, List<Contact> contacts) {
        if (contacts.isEmpty()) {
            return;
        }

        // Sort contacts by penetration depth (deepest first)
        List<Contact> sortedContacts = new ArrayList<>(contacts);
        sortedContacts.sort(Comparator.comparing(Contact::getPenetrationDepth).reversed());

        // Process each contact
        for (Contact contact : sortedContacts) {
            // Skip if entity is dead or removed
            if (!entity.isAlive()) {
                return;
            }

            // Handle position correction
            handlePositionCorrection(entity, contact);

            // Handle velocity adjustment
            handleVelocityAdjustment(entity, contact);

            // Handle ground state
            if (contact.isGroundContact()) {
                setEntityOnGround(entity);

                // Apply ground friction to horizontal velocity
                applyGroundFriction(entity);
            }
        }
    }

    /**
     * Corrects entity position to resolve penetration.
     *
     * @param entity The entity to correct
     * @param contact The contact information
     */
    private void handlePositionCorrection(Entity entity, Contact contact) {
        // Skip if penetration is too small
        if (contact.getPenetrationDepth() < MINIMUM_CORRECTION_MAGNITUDE) {
            return;
        }

        // Calculate correction magnitude
        float correction = Math.min(
                contact.getPenetrationDepth() * POSITION_CORRECTION_FACTOR,
                MAXIMUM_CORRECTION_MAGNITUDE
        );

        // Create correction vector along normal
        Vector3f normal = contact.getContactNormal();
        Vector3f correctionVec = new Vector3f(normal);
        correctionVec.scale(correction);

        // Apply correction to entity position
        Vec3d correctionVecMC = new Vec3d(correctionVec.x, correctionVec.y, correctionVec.z);
        Vec3d newPos = entity.getPos().add(correctionVecMC);

        // Ensure new position doesn't collide with world blocks
        newPos = ensureSafePosition(entity, entity.getPos(), newPos);

        // Update entity position
        entity.setPosition(newPos);
    }

    /**
     * Ensures a new position doesn't cause collisions with world blocks.
     *
     * @param entity The entity to check
     * @param oldPos The old position
     * @param newPos The proposed new position
     * @return A safe position
     */
    private Vec3d ensureSafePosition(Entity entity, Vec3d oldPos, Vec3d newPos) {
        // Calculate movement vector
        Vec3d movement = newPos.subtract(oldPos);

        // Since adjustMovementForCollisions is private in Entity, we need a workaround
        // We'll use MixinEntity to access this functionality
        // For now, we'll simply use a basic collision check

        // Create a box at the new position
        net.minecraft.util.math.Box newBox = entity.getBoundingBox().offset(movement);

        // Check if the new box collides with world blocks
        boolean wouldCollide = !entity.getWorld().isSpaceEmpty(entity, newBox);

        // If there would be a collision, don't use the new position
        Vec3d adjustedMovement = wouldCollide ? Vec3d.ZERO : movement;

        // If the movement was modified, there would be a collision
        if (!adjustedMovement.equals(movement)) {
            // Use the adjusted movement instead
            return oldPos.add(adjustedMovement);
        }

        return newPos;
    }

    /**
     * Adjusts entity velocity based on collision.
     *
     * @param entity The entity to adjust
     * @param contact The contact information
     */
    private void handleVelocityAdjustment(Entity entity, Contact contact) {
        // Get current velocity
        Vec3d velocity = entity.getVelocity();

        // Early out if velocity is negligible
        if (velocity.lengthSquared() < 1e-6) {
            return;
        }

        // Get contact normal
        Vector3f normal = contact.getContactNormal();

        // Convert to Vector3d for more precise math
        Vector3d velocityVec = new Vector3d(velocity.x, velocity.y, velocity.z);
        Vector3d normalVec = new Vector3d(normal.x, normal.y, normal.z);

        // Calculate dot product to get component along normal
        double dot = velocityVec.dot(normalVec);

        // Only cancel velocity if moving into the surface
        if (dot < 0) {
            // Calculate reflected velocity (with damping)
            Vector3d normalComponent = new Vector3d(normalVec);
            normalComponent.scale(dot);

            // Remove normal component to get tangential component
            Vector3d tangentialComponent = new Vector3d(velocityVec);
            tangentialComponent.sub(normalComponent);

            // For ground contacts, dampen vertical velocity completely
            if (contact.isGroundContact()) {
                // Set new velocity with zero vertical component
                entity.setVelocity(tangentialComponent.x, 0, tangentialComponent.z);
            } else {
                // For side/ceiling contacts, dampen the normal component
                double restitution = 0.2; // Bounciness factor

                // Calculate damped reflection
                Vector3d reflectionComponent = new Vector3d(normalComponent);
                reflectionComponent.scale(-restitution);

                // Combine tangential and reflection components
                Vector3d newVelocity = new Vector3d(tangentialComponent);
                newVelocity.add(reflectionComponent);

                // Apply new velocity
                entity.setVelocity(newVelocity.x, newVelocity.y, newVelocity.z);
            }
        }

        // If this is a grid contact, add grid velocity influence
        if (contact.isGridContact()) {
            addGridVelocityInfluence(entity, contact);
        }
    }

    /**
     * Adds influence from grid velocity to entity.
     *
     * @param entity The entity to influence
     * @param contact The contact with grid information
     */
    private void addGridVelocityInfluence(Entity entity, Contact contact) {
        // Skip if not a grid contact
        if (!contact.isGridContact()) {
            return;
        }

        // Get grid velocity at contact point
        Vector3f gridVelocity = contact.getGridVelocityAtContactPoint();

        // Skip if grid isn't moving significantly
        if (gridVelocity.lengthSquared() < 1e-4) {
            return;
        }

        // Get current entity velocity
        Vec3d entityVelocity = entity.getVelocity();

        // Calculate influence factor based on contact type
        float influenceFactor = 0.3f; // Default influence

        if (contact.isGroundContact()) {
            // Stronger influence when standing on grid
            influenceFactor = 0.8f;

            // Special case: if grid is moving upward and entity is on top,
            // match the grid's vertical velocity to prevent bouncing
            if (gridVelocity.y > 0) {
                entityVelocity = new Vec3d(
                        entityVelocity.x,
                        Math.max(entityVelocity.y, gridVelocity.y * 0.9),
                        entityVelocity.z
                );
            }
        }

        // Apply grid velocity influence
        Vec3d gridInfluence = new Vec3d(
                gridVelocity.x * influenceFactor,
                gridVelocity.y * influenceFactor,
                gridVelocity.z * influenceFactor
        );

        // Add to entity velocity
        Vec3d newVelocity = entityVelocity.add(gridInfluence);

        // Apply new velocity
        entity.setVelocity(newVelocity);
    }

    /**
     * Applies friction to horizontal velocity when on ground.
     *
     * @param entity The entity to affect
     */
    private void applyGroundFriction(Entity entity) {
        // Get current velocity
        Vec3d velocity = entity.getVelocity();

        // Skip if not moving horizontally
        if (Math.abs(velocity.x) < 1e-6 && Math.abs(velocity.z) < 1e-6) {
            return;
        }

        // Apply friction to horizontal components
        Vec3d newVelocity = new Vec3d(
                velocity.x * GROUND_FRICTION,
                velocity.y,
                velocity.z * GROUND_FRICTION
        );

        // Apply new velocity
        entity.setVelocity(newVelocity);
    }

    /**
     * Checks if an entity should be considered on ground based on contacts.
     *
     * @param entity The entity to check
     * @param contacts List of contacts to check
     * @return true if the entity is on ground
     */
    public boolean checkIfOnGround(Entity entity, List<Contact> contacts) {
        for (Contact contact : contacts) {
            if (contact.isGroundContact()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Sets an entity's onGround state to true.
     * This is a helper method for fixing the entity's state.
     *
     * @param entity The entity to modify
     */
    private void setEntityOnGround(Entity entity) {
        // In a real implementation, this would use @Shadow fields in the mixin
        // For now, we'll use the public API
        entity.setOnGround(true);

        // Reset fall distance when landing
        if (entity.fallDistance > 0) {
            entity.fallDistance = 0;
        }
    }

    /**
     * Calculates a safe movement vector for an entity being pushed by a grid.
     * Ensures that the entity won't be pushed into world blocks.
     *
     * @param entity The entity being pushed
     * @param gridMovement The grid's movement vector
     * @param worldMovementLimit Maximum movement to avoid world blocks
     * @return A safe movement vector
     */
    public Vec3d calculateSafeEntityMovement(Entity entity, Vec3d gridMovement, Vec3d worldMovementLimit) {
        // Calculate how much the entity would move
        double movementFactor = 1.0;

        // Living entities resist movement more
        if (entity instanceof LivingEntity) {
            movementFactor = 0.8;
        }

        // Calculate initial proposed movement
        Vec3d proposedMovement = gridMovement.multiply(movementFactor);

        // Check if this would exceed world movement limits
        if (worldMovementLimit != null) {
            // Ensure we don't exceed any component of the world limit
            double xFactor = worldMovementLimit.x != 0 ?
                    Math.min(1.0, Math.abs(worldMovementLimit.x / proposedMovement.x)) : 1.0;
            double yFactor = worldMovementLimit.y != 0 ?
                    Math.min(1.0, Math.abs(worldMovementLimit.y / proposedMovement.y)) : 1.0;
            double zFactor = worldMovementLimit.z != 0 ?
                    Math.min(1.0, Math.abs(worldMovementLimit.z / proposedMovement.z)) : 1.0;

            // Get the smallest factor
            double limitFactor = Math.min(Math.min(xFactor, yFactor), zFactor);

            // Apply limit factor if needed
            if (limitFactor < 1.0) {
                proposedMovement = proposedMovement.multiply(limitFactor * 0.9); // Add small safety margin
            }
        }

        // Since we can't call entity.adjustMovementForCollisions directly,
        // we'll use a basic collision check

        // Check if the proposed movement would cause a block collision
        net.minecraft.util.math.Box newBox = entity.getBoundingBox().offset(proposedMovement);
        boolean wouldCollide = !entity.getWorld().isSpaceEmpty(entity, newBox);

        // If there would be a collision, apply a smaller movement
        Vec3d safeMovement = wouldCollide ? proposedMovement.multiply(0.5) : proposedMovement;

        return safeMovement;
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