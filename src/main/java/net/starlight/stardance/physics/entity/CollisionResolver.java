package net.starlight.stardance.physics.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.starlight.stardance.physics.entity.ContactDetector.SweepResult;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;

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

    // Debug flag - set to true for verbose collision resolution logging
    private static final boolean DEBUG_RESOLUTION = true;

    // Reference to parent
    private final EntityPhysicsManager entityPhysicsManager;

    // Counters for statistics and debugging
    private int collisionsResolved = 0;
    private int positionCorrectionsApplied = 0;
    private int velocityAdjustmentsApplied = 0;

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
    public Vec3 resolvePreMovementCollision(Entity entity, Vec3 movement, SweepResult result) {
        boolean isPlayer = entity instanceof Player;

        // Special case: if the time of impact is very small, entity may be already colliding
        if (result.getTimeOfImpact() < 0.01f) {
            // We're already colliding at the start, handle penetration
            Vec3 resolvedMovement = resolvePenetration(entity, movement, result);

            if (DEBUG_RESOLUTION && isPlayer) {
                SLogger.log(this, String.format(
                        "Player penetrating at start, resolving movement from (%.2f, %.2f, %.2f) to (%.2f, %.2f, %.2f)",
                        movement.x, movement.y, movement.z,
                        resolvedMovement.x, resolvedMovement.y, resolvedMovement.z));
            }

            collisionsResolved++;
            return resolvedMovement;
        }

        // Calculate safe movement up to collision point with safety margin
        double safetyMargin = 0.01;
        Vec3 safeMovement = result.getSafePosition(Vec3.ZERO, movement, safetyMargin);

        // Calculate remaining time after collision
        float remainingTime = 1.0f - result.getTimeOfImpact();

        // If most of the movement is complete or no time remains, just return safe movement
        if (remainingTime < 0.01f) {
            if (DEBUG_RESOLUTION && isPlayer) {
                SLogger.log(this, String.format(
                        "Player collision at end of movement, using safe movement: (%.2f, %.2f, %.2f), toi=%.4f",
                        safeMovement.x, safeMovement.y, safeMovement.z,
                        result.getTimeOfImpact()));
            }

            collisionsResolved++;
            return safeMovement;
        }

        // Get sliding movement for the remainder of the time
        Vec3 slidingMovement = calculateSlidingMovement(movement, result, remainingTime);

        // Combine safe movement and sliding movement
        Vec3 combinedMovement = safeMovement.add(slidingMovement);

        if (DEBUG_RESOLUTION && isPlayer) {
            SLogger.log(this, String.format(
                    "Player collision resolved with sliding: safe=(%.2f, %.2f, %.2f), slide=(%.2f, %.2f, %.2f), combined=(%.2f, %.2f, %.2f)",
                    safeMovement.x, safeMovement.y, safeMovement.z,
                    slidingMovement.x, slidingMovement.y, slidingMovement.z,
                    combinedMovement.x, combinedMovement.y, combinedMovement.z));
        }

        collisionsResolved++;
        return combinedMovement;
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
    private Vec3 resolvePenetration(Entity entity, Vec3 movement, SweepResult result) {
        // Get collision normal
        Vector3f normal = result.getHitNormal();

        // Create separation vector along normal
        float separationDistance = 0.05f; // Push out slightly more to avoid sticking
        Vec3 separationVector = new Vec3(
                normal.x * separationDistance,
                normal.y * separationDistance,
                normal.z * separationDistance
        );

        // Project original movement onto the separation plane
        Vector3d movementVec = new Vector3d(movement.x, movement.y, movement.z);
        Vector3d normalVec = new Vector3d(normal.x, normal.y, normal.z);
        normalVec.normalize();

        // Calculate dot product
        double dot = movementVec.dot(normalVec);

        // Only project if moving into the surface
        Vec3 projectedMovement = movement;
        if (dot < 0) {
            // Remove normal component from movement
            Vector3d normalComponent = new Vector3d(normalVec);
            normalComponent.scale(dot);
            movementVec.sub(normalComponent);

            // Convert back to Vec3d
            projectedMovement = new Vec3(movementVec.x, movementVec.y, movementVec.z);
        }

        // Scale projected movement to avoid overshooting
        projectedMovement = projectedMovement.scale(0.8); // Reduce speed slightly

        // Combine separation and projected movement
        Vec3 resolvedMovement = separationVector.add(projectedMovement);

        // Log for debugging
        if (DEBUG_RESOLUTION && entity instanceof Player) {
            SLogger.log(this, String.format(
                    "Penetration resolved - normal=(%.2f, %.2f, %.2f), separation=(%.2f, %.2f, %.2f), final=(%.2f, %.2f, %.2f)",
                    normal.x, normal.y, normal.z,
                    separationVector.x, separationVector.y, separationVector.z,
                    resolvedMovement.x, resolvedMovement.y, resolvedMovement.z));
        }

        return resolvedMovement;
    }

    /**
     * Improved sliding movement calculation to prevent unnatural speed boosts.
     * This fixes the issue where sliding along a surface accelerates the entity.
     *
     * @param originalMovement Original movement vector
     * @param result Sweep test result with collision information
     * @param remainingTime Fraction of original movement time remaining
     * @return A sliding movement vector with appropriate magnitude
     */
    private Vec3 calculateSlidingMovement(Vec3 originalMovement, SweepResult result, float remainingTime) {
        // Skip if no remaining time
        if (remainingTime <= 0.01f) {
            return Vec3.ZERO;
        }

        // Get the collision normal
        Vector3f normal = result.getHitNormal();

        // Convert to Vector3d for more precise math
        Vector3d movementVec = new Vector3d(
                originalMovement.x,
                originalMovement.y,
                originalMovement.z
        );

        // Original length for reference
        double originalLength = movementVec.length();
        if (originalLength < 0.0001) {
            return Vec3.ZERO;
        }

        Vector3d normalVec = new Vector3d(normal.x, normal.y, normal.z);
        normalVec.normalize();

        // Calculate dot product to get component along normal
        double dot = movementVec.dot(normalVec);

        // Only project if moving into the surface
        if (dot < 0) {
            // Calculate the parallel component by removing the normal component
            Vector3d normalComponent = new Vector3d(normalVec);
            normalComponent.scale(dot);

            Vector3d tangentialComponent = new Vector3d(movementVec);
            tangentialComponent.sub(normalComponent);

            // Calculate tangential component length
            double tangentialLength = tangentialComponent.length();

            // If tangential component is too small, just return zero
            if (tangentialLength < 0.0001) {
                return Vec3.ZERO;
            }

            // Normalize the tangential component
            double scaleFactor = 1.0 / tangentialLength;
            tangentialComponent.scale(scaleFactor);

            // Scale by appropriate magnitude - key fix here:
            // Limit the magnitude to be proportional to the tangential component of the original vector
            // This prevents "speed boosts" in the tangential direction
            double slideMagnitude = Math.min(
                    originalLength * remainingTime * SLIDE_FRICTION,  // Regular slide
                    tangentialLength * remainingTime                  // Don't exceed original tangential component
            );

            // Apply the calculated magnitude
            tangentialComponent.scale(slideMagnitude);

            // Convert back to Vec3d
            return new Vec3(
                    tangentialComponent.x,
                    tangentialComponent.y,
                    tangentialComponent.z
            );
        }

        // If not moving into surface, allow full remaining movement but with friction
        return originalMovement.scale(remainingTime * SLIDE_FRICTION);
    }

    /**
     * Resolves post-movement collisions by adjusting entity position and velocity.
     * Called after entity movement to resolve any remaining penetrations.
     *
     * @param entity The entity that moved
     * @param contacts List of contacts detected after movement
     */
    public void resolvePostMovementCollision(Entity entity, List<Contact> contacts) {
        boolean isPlayer = entity instanceof Player;

        if (contacts.isEmpty()) {
            return;
        }

        // Sort contacts by penetration depth (deepest first)
        List<Contact> sortedContacts = new ArrayList<>(contacts);
        sortedContacts.sort(Comparator.comparing(Contact::getPenetrationDepth).reversed());

        if (DEBUG_RESOLUTION && isPlayer) {
            SLogger.log(this, String.format(
                    "Resolving %d post-movement contacts for player", sortedContacts.size()));
        }

        // Process each contact
        for (Contact contact : sortedContacts) {
            // Skip if entity is dead or removed
            if (!entity.isAlive()) {
                return;
            }

            // Handle velocity adjustment
            handleVelocityAdjustment(entity, contact);

            // Handle position correction
            handlePositionCorrection(entity, contact);

            // Handle ground state
            if (contact.isGroundContact()) {
                setEntityOnGround(entity);

                // Apply ground friction to horizontal velocity
                applyGroundFriction(entity);

                if (DEBUG_RESOLUTION && isPlayer) {
                    SLogger.log(this, "Player contact is ground contact, applying friction");
                }
            }
        }

        collisionsResolved++;
    }

    /**
     * Corrects entity position to resolve penetration.
     *
     * @param entity The entity to correct
     * @param contact The contact information
     */
    private void handlePositionCorrection(Entity entity, Contact contact) {
        boolean isPlayer = entity instanceof Player;

        // Skip if penetration is too small
        float penetration = contact.getPenetrationDepth();
        if (penetration < MINIMUM_CORRECTION_MAGNITUDE) {
            return;
        }

        // Calculate a stronger correction factor for deeper penetrations
        // Use a higher factor (1.0 instead of 0.8) to ensure the entity gets fully pushed out
        float correction = Math.min(
                penetration * 1.0f,
                MAXIMUM_CORRECTION_MAGNITUDE
        );

        // Create correction vector along normal
        Vector3f normal = contact.getContactNormal();
        Vector3f correctionVec = new Vector3f(normal);
        correctionVec.scale(correction);

        // Apply correction to entity position
        Vec3 correctionVecMC = new Vec3(correctionVec.x, correctionVec.y, correctionVec.z);
        Vec3 oldPos = entity.position();
        Vec3 newPos = oldPos.subtract(correctionVecMC);

        // For lateral collisions (side contacts), use a higher safety margin
        double safetyMargin = normal.y < 0.2f ? 0.01 : 0.001;

        // Add a small nudge in Y direction for side collisions to prevent sticking
        if (Math.abs(normal.x) > 0.9f || Math.abs(normal.z) > 0.9f) {
            // This is a side collision, add a tiny upward nudge
            newPos = newPos.add(0, 0.01, 0);
        }

        // Ensure new position doesn't collide with world blocks
        Vec3 safePos = ensureSafePosition(entity, oldPos, newPos);

        // Skip if position didn't actually change
        if (safePos.equals(oldPos)) {
            // If we can't move to the safe position, try a more aggressive correction
            // Scale up by 1.5x and try again
            correctionVec.scale(1.5f);
            newPos = oldPos.add(new Vec3(correctionVec.x, correctionVec.y, correctionVec.z));

            // Try again with the more aggressive correction
            safePos = ensureSafePosition(entity, oldPos, newPos);

            // If still no change, log the issue and give up
            if (safePos.equals(oldPos) && isPlayer) {
                SLogger.log(this, "WARNING: Failed to correct player position despite multiple attempts");
                return;
            }
        }

        // Use entity.refreshPositionAndAngles instead of setPosition to ensure proper updates
        // This is crucial for preventing the position correction from being overridden
//        entity.refreshPositionAndAngles(safePos.x, safePos.y, safePos.z, entity.getYaw(), entity.getPitch());

        // Also directly update the position to ensure it takes effect immediately
        entity.setPos(safePos);

        // Force update the entity's bounding box
        entity.getBoundingBox();

        positionCorrectionsApplied++;

        // Log correction for debugging
        if (DEBUG_RESOLUTION && isPlayer) {
            Vector3f contactNormal = contact.getContactNormal();

            SLogger.log(this, String.format(
                    "Player position corrected - depth=%.4f, normal=(%.2f, %.2f, %.2f), delta=(%.4f, %.4f, %.4f), pos=(%.2f, %.2f, %.2f)",
                    penetration,
                    contactNormal.x, contactNormal.y, contactNormal.z,
                    safePos.x - oldPos.x, safePos.y - oldPos.y, safePos.z - oldPos.z,
                    safePos.x, safePos.y, safePos.z));
        }
    }

    /**
     * Ensures a new position doesn't cause collisions with world blocks.
     * This improved version tries multiple scaled movements if necessary.
     *
     * @param entity The entity to check
     * @param oldPos The old position
     * @param newPos The proposed new position
     * @return A safe position
     */
    private Vec3 ensureSafePosition(Entity entity, Vec3 oldPos, Vec3 newPos) {
        // Calculate movement vector
        Vec3 movement = newPos.subtract(oldPos);

        // Skip if movement is negligible
        if (movement.lengthSqr() < 1e-6) {
            return oldPos;
        }

        boolean isPlayer = entity instanceof Player;

        // Try with full movement first
        AABB fullBox = entity.getBoundingBox().move(movement);
        if (entity.level().noCollision(entity, fullBox)) {
            return newPos;
        }

        // Full movement blocked, try binary search to find maximum safe distance
        double minScale = 0.0;
        double maxScale = 1.0;
        double currentScale = 0.5;
        double bestSafeScale = 0.0;

        // Use binary search to efficiently find the maximum safe movement
        for (int i = 0; i < 8; i++) { // 8 iterations should give sufficient precision
            currentScale = (minScale + maxScale) / 2.0;
            Vec3 scaledMovement = movement.scale(currentScale);
            AABB scaledBox = entity.getBoundingBox().move(scaledMovement);

            if (entity.level().noCollision(entity, scaledBox)) {
                // This scale is safe, try a larger one
                bestSafeScale = currentScale;
                minScale = currentScale;
            } else {
                // This scale is unsafe, try a smaller one
                maxScale = currentScale;
            }
        }

        // If we found a safe scale
        if (bestSafeScale > 0.001) {
            Vec3 safePos = oldPos.add(movement.scale(bestSafeScale));

            if (DEBUG_RESOLUTION && isPlayer) {
                SLogger.log(this, String.format(
                        "World collision detected - scaled movement to %.2f%% for safe position (%.2f, %.2f, %.2f)",
                        bestSafeScale * 100.0,
                        safePos.x, safePos.y, safePos.z));
            }

            return safePos;
        }

        // Try moving in individual axes if combined movement failed
        // This is important for sliding along walls
        Vec3 xMovement = new Vec3(movement.x, 0, 0);
        AABB xBox = entity.getBoundingBox().move(xMovement);
        boolean xSafe = entity.level().noCollision(entity, xBox);

        Vec3 yMovement = new Vec3(0, movement.y, 0);
        AABB yBox = entity.getBoundingBox().move(yMovement);
        boolean ySafe = entity.level().noCollision(entity, yBox);

        Vec3 zMovement = new Vec3(0, 0, movement.z);
        AABB zBox = entity.getBoundingBox().move(zMovement);
        boolean zSafe = entity.level().noCollision(entity, zBox);

        // Apply the individual movements that are safe
        Vec3 safePos = oldPos;
        if (xSafe) {
            safePos = safePos.add(xMovement);
        }
        if (ySafe) {
            safePos = safePos.add(yMovement);
        }
        if (zSafe) {
            safePos = safePos.add(zMovement);
        }

        // If we made any progress with individual axes
        if (!safePos.equals(oldPos)) {
            if (DEBUG_RESOLUTION && isPlayer) {
                SLogger.log(this, String.format(
                        "Applied component-wise movement - xSafe=%s, ySafe=%s, zSafe=%s, pos=(%.2f, %.2f, %.2f)",
                        xSafe, ySafe, zSafe,
                        safePos.x, safePos.y, safePos.z));
            }
            return safePos;
        }

        // If all else fails, prioritize vertical movement to prevent falling through floors
        if (Math.abs(movement.y) > 0.01) {
            // Try just a small fraction of the Y movement
            double yScale = movement.y > 0 ? 0.2 : 0.1; // Allow more upward than downward
            Vec3 smallYMovement = new Vec3(0, movement.y * yScale, 0);
            AABB smallYBox = entity.getBoundingBox().move(smallYMovement);

            if (entity.level().noCollision(entity, smallYBox)) {
                Vec3 yOnlyPos = oldPos.add(smallYMovement);

                if (DEBUG_RESOLUTION && isPlayer) {
                    SLogger.log(this, String.format(
                            "Applied minimal Y movement of %.4f, pos=(%.2f, %.2f, %.2f)",
                            smallYMovement.y,
                            yOnlyPos.x, yOnlyPos.y, yOnlyPos.z));
                }

                return yOnlyPos;
            }
        }

        // No safe movement found, return original position
        if (DEBUG_RESOLUTION && isPlayer) {
            SLogger.log(this, "Could not find any safe movement, returning original position");
        }

        return oldPos;
    }

    /**
     * Adjusts entity velocity based on collision.
     *
     * @param entity The entity to adjust
     * @param contact The contact information
     */
    private void handleVelocityAdjustment(Entity entity, Contact contact) {
        boolean isPlayer = entity instanceof Player;

        // Get current velocity
        Vec3 velocity = entity.getDeltaMovement();

        // Early out if velocity is negligible
        if (velocity.lengthSqr() < 1e-6) {
            return;
        }

        // Get contact normal
        Vector3f normal = contact.getContactNormal();

        // Convert to Vector3d for more precise math
        Vector3d velocityVec = new Vector3d(velocity.x, velocity.y, velocity.z);
        Vector3d normalVec = new Vector3d(normal.x, normal.y, normal.z);
        normalVec.normalize();

        // Calculate dot product to get component along normal
        double dot = velocityVec.dot(normalVec);

        // Only cancel velocity if moving into the surface
        if (dot < 0) {
            Vec3 newVelocity;

            // Calculate reflected velocity (with damping)
            Vector3d normalComponent = new Vector3d(normalVec);
            normalComponent.scale(dot);

            // Remove normal component to get tangential component
            Vector3d tangentialComponent = new Vector3d(velocityVec);
            tangentialComponent.sub(normalComponent);

            // For ground contacts, dampen vertical velocity completely
            if (contact.isGroundContact()) {
                // Set new velocity with zero vertical component
                newVelocity = new Vec3(tangentialComponent.x, 0, tangentialComponent.z);
            } else {
                // For side/ceiling contacts, dampen the normal component
                double restitution = 0.2; // Bounciness factor

                // Calculate damped reflection
                Vector3d reflectionComponent = new Vector3d(normalComponent);
                reflectionComponent.scale(-restitution);

                // Combine tangential and reflection components
                Vector3d resultantVelocity = new Vector3d(tangentialComponent);
                resultantVelocity.add(reflectionComponent);

                // Convert to Vec3d
                newVelocity = new Vec3(resultantVelocity.x, resultantVelocity.y, resultantVelocity.z);
            }

            // Apply new velocity
            entity.setDeltaMovement(newVelocity);
            velocityAdjustmentsApplied++;

            // Log velocity adjustment for debugging
            if (DEBUG_RESOLUTION && isPlayer) {
                SLogger.log(this, String.format(
                        "Player velocity adjusted - from=(%.2f, %.2f, %.2f), to=(%.2f, %.2f, %.2f), onGround=%s",
                        velocity.x, velocity.y, velocity.z,
                        newVelocity.x, newVelocity.y, newVelocity.z,
                        contact.isGroundContact() ? "true" : "false"));
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
        boolean isPlayer = entity instanceof Player;

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
        Vec3 entityVelocity = entity.getDeltaMovement();

        // Calculate influence factor based on contact type
        float influenceFactor = 0.3f; // Default influence

        if (contact.isGroundContact()) {
            // Stronger influence when standing on grid
            influenceFactor = 0.8f;

            // Special case: if grid is moving upward and entity is on top,
            // match the grid's vertical velocity to prevent bouncing
            if (gridVelocity.y > 0) {
                entityVelocity = new Vec3(
                        entityVelocity.x,
                        Math.max(entityVelocity.y, gridVelocity.y * 0.9),
                        entityVelocity.z
                );
            }
        }

        // Apply grid velocity influence
        Vec3 gridInfluence = new Vec3(
                gridVelocity.x * influenceFactor,
                gridVelocity.y * influenceFactor,
                gridVelocity.z * influenceFactor
        );

        // Add to entity velocity
        Vec3 newVelocity = entityVelocity.add(gridInfluence);

        // Apply new velocity
        entity.setDeltaMovement(newVelocity);
        velocityAdjustmentsApplied++;

        // Log grid influence for debugging
        if (DEBUG_RESOLUTION && isPlayer && gridInfluence.lengthSqr() > 1e-4) {
            SLogger.log(this, String.format(
                    "Player affected by grid velocity - grid=(%.2f, %.2f, %.2f), influence=(%.2f, %.2f, %.2f), result=(%.2f, %.2f, %.2f)",
                    gridVelocity.x, gridVelocity.y, gridVelocity.z,
                    gridInfluence.x, gridInfluence.y, gridInfluence.z,
                    newVelocity.x, newVelocity.y, newVelocity.z));
        }
    }

    /**
     * Applies friction to horizontal velocity when on ground.
     *
     * @param entity The entity to affect
     */
    private void applyGroundFriction(Entity entity) {
        // Get current velocity
        Vec3 velocity = entity.getDeltaMovement();

        // Skip if not moving horizontally
        if (Math.abs(velocity.x) < 1e-6 && Math.abs(velocity.z) < 1e-6) {
            return;
        }

        // Apply friction to horizontal components
        Vec3 newVelocity = new Vec3(
                velocity.x * GROUND_FRICTION,
                velocity.y,
                velocity.z * GROUND_FRICTION
        );

        // Apply new velocity
        entity.setDeltaMovement(newVelocity);
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
        // Set on ground
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
    public Vec3 calculateSafeEntityMovement(Entity entity, Vec3 gridMovement, Vec3 worldMovementLimit) {
        boolean isPlayer = entity instanceof Player;

        // Calculate how much the entity would move
        double movementFactor = 1.0;

        // Living entities resist movement more
        if (entity instanceof LivingEntity) {
            movementFactor = 0.8;
        }

        // Calculate initial proposed movement
        Vec3 proposedMovement = gridMovement.scale(movementFactor);

        // Check if this would exceed world movement limits
        if (worldMovementLimit != null) {
            // Ensure we don't exceed any component of the world limit
            double xFactor = worldMovementLimit.x != 0 && proposedMovement.x != 0 ?
                    Math.min(1.0, Math.abs(worldMovementLimit.x / proposedMovement.x)) : 1.0;
            double yFactor = worldMovementLimit.y != 0 && proposedMovement.y != 0 ?
                    Math.min(1.0, Math.abs(worldMovementLimit.y / proposedMovement.y)) : 1.0;
            double zFactor = worldMovementLimit.z != 0 && proposedMovement.z != 0 ?
                    Math.min(1.0, Math.abs(worldMovementLimit.z / proposedMovement.z)) : 1.0;

            // Get the smallest factor
            double limitFactor = Math.min(Math.min(xFactor, yFactor), zFactor);

            // Apply limit factor if needed
            if (limitFactor < 1.0) {
                proposedMovement = proposedMovement.scale(limitFactor * 0.9); // Add small safety margin

                if (DEBUG_RESOLUTION && isPlayer) {
                    SLogger.log(this, String.format(
                            "Player grid movement limited by world: factor=%.2f, limited=(%.4f, %.4f, %.4f)",
                            limitFactor,
                            proposedMovement.x, proposedMovement.y, proposedMovement.z));
                }
            }
        }

        // Check if the proposed movement would cause a block collision
        net.minecraft.world.phys.AABB newBox = entity.getBoundingBox().move(proposedMovement);
        boolean wouldCollide = !entity.level().noCollision(entity, newBox);

        // If there would be a collision, apply a smaller movement
        Vec3 safeMovement = wouldCollide ? proposedMovement.scale(0.5) : proposedMovement;

        if (wouldCollide && DEBUG_RESOLUTION && isPlayer) {
            SLogger.log(this, String.format(
                    "Player grid movement would collide with world, reducing by half: (%.4f, %.4f, %.4f)",
                    safeMovement.x, safeMovement.y, safeMovement.z));
        }

        return safeMovement;
    }

    /**
     * Gets statistics about collision resolution.
     *
     * @return A string containing resolution stats
     */
    public String getResolutionStats() {
        return String.format(
                "Collisions resolved: %d, Position corrections: %d, Velocity adjustments: %d",
                collisionsResolved,
                positionCorrectionsApplied,
                velocityAdjustmentsApplied);
    }

    /**
     * Resets resolution statistics.
     */
    public void resetResolutionStats() {
        collisionsResolved = 0;
        positionCorrectionsApplied = 0;
        velocityAdjustmentsApplied = 0;
    }

    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return false; // Enable console logging for debugging
    }
}