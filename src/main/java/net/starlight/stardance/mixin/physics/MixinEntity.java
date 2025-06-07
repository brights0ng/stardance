package net.starlight.stardance.mixin.physics;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.starlight.stardance.physics.PhysicsEngine;
import net.starlight.stardance.physics.entity.Contact;
import net.starlight.stardance.physics.entity.ContactDetector;
import net.starlight.stardance.Stardance;
import net.starlight.stardance.physics.entity.EntityPhysicsManager;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.vecmath.Vector3f;
import java.util.List;

/**
 * Mixin to intercept and modify entity movement for proper grid collision.
 * Uses an approach similar to Valkyrien Skies 2 for smooth sliding.
 */
@Mixin(Entity.class)
public abstract class MixinEntity implements ILoggingControl {

    @Shadow
    public abstract Vec3d getVelocity();

    @Shadow
    public abstract void setVelocity(double x, double y, double z);

    @Shadow
    public abstract Box getBoundingBox();

    @Shadow
    public abstract boolean isOnGround();

    @Shadow
    public abstract void setOnGround(boolean onGround);

    @Shadow protected abstract void tryCheckBlockCollision();

    @Shadow public abstract boolean isFireImmune();

    @Shadow private boolean onGround;

    @Shadow public float fallDistance;

    @Shadow public abstract boolean saveSelfNbt(NbtCompound nbt);

    @Shadow protected abstract void checkBlockCollision();

    @Shadow @Final private static Logger LOGGER;

    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return true; // Enabling console logging for debugging
    }

    /**
     * Intercept the collision handling in Entity.move() to incorporate grid collisions.
     * Let vanilla handle the world collisions after we've adjusted for grids.
     */
    @WrapOperation(
            method = "move",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/Entity;adjustMovementForCollisions(Lnet/minecraft/util/math/Vec3d;)Lnet/minecraft/util/math/Vec3d;"
            )
    )
    private Vec3d collideWithGrids(Entity entity, Vec3d originalMovement, Operation<Vec3d> vanillaCollide) {
        // Skip for non-physical entities
        if (entity.isSpectator() || entity.noClip) {
            return vanillaCollide.call(entity, originalMovement);
        }

        // Get physics engine
        PhysicsEngine engine = Stardance.engineManager.getEngine(entity.getWorld());
        if (engine == null) {
            return vanillaCollide.call(entity, originalMovement);
        }

        EntityPhysicsManager manager = engine.getEntityPhysicsManager();
        if (manager == null || !manager.getTrackedEntities().contains(entity)) {
            return vanillaCollide.call(entity, originalMovement);
        }

        // Get the entity's current bounding box for collision detection
        Box entityBox = entity.getBoundingBox();

        // Store original movement for debugging and comparison
//        Vec3d originalMovementCopy = new Vec3d(originalMovement.x, originalMovement.y, originalMovement.z);

        // Adjust movement for grid collisions first
        Vec3d adjustedMovement = adjustMovementForGridCollisions(entity, originalMovement, entityBox);
//        Vec3d adjustedMovement = originalMovement;

        // Debug log only when movement is actually adjusted
        if (!adjustedMovement.equals(originalMovement) && originalMovement.lengthSquared() > 0.0001) {
            SLogger.log(this, String.format("Entity %s movement adjusted - Original: (%.4f, %.4f, %.4f), Adjusted: (%.4f, %.4f, %.4f)",
                    entity.getEntityName(),
                    originalMovement.x, originalMovement.y, originalMovement.z,
                    adjustedMovement.x, adjustedMovement.y, adjustedMovement.z));
        }

        // Then let vanilla handle world block collisions
        Vec3d collisionResultWithWorld = vanillaCollide.call(entity, adjustedMovement);

        // If we collided with world blocks, clear any grid contact info
        double squaredDistance = calculateSquaredDistance(adjustedMovement, collisionResultWithWorld);
        if (squaredDistance > 1e-6) {
            // We hit a world block, adjust velocity accordingly
            manager.adjustVelocityAfterCollision(entity, adjustedMovement, collisionResultWithWorld);

            // Debug log for world collision
            SLogger.log(this, String.format("Entity %s collided with world blocks - Adjusted: (%.4f, %.4f, %.4f), Result: (%.4f, %.4f, %.4f)",
                    entity.getEntityName(),
                    adjustedMovement.x, adjustedMovement.y, adjustedMovement.z,
                    collisionResultWithWorld.x, collisionResultWithWorld.y, collisionResultWithWorld.z));
        }

        return collisionResultWithWorld;
    }

    /**
     * Calculate squared distance between two vectors
     */
    @Unique
    private double calculateSquaredDistance(Vec3d a, Vec3d b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        double dz = a.z - b.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Cancel the problematic velocity zeroing in vanilla code and replace with our smarter version.
     * This is the critical part for maintaining momentum while sliding along surfaces.
     */
    @Inject(
            method = "move",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/Entity;setVelocity(DDD)V"
            ),
            cancellable = true
    )
    private void preserveMomentumAfterCollision(MovementType type, Vec3d movement,
                                                CallbackInfo ci, @Local(ordinal = 1) Vec3d collisionResult) {
        Entity self = (Entity)(Object)this;

        // Skip for non-physical entities
        if (self.isSpectator() || self.noClip) {
            return;
        }

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

        Vec3d currentVelocity = getVelocity();

        // Skip velocity adjustment if not moving
        if (currentVelocity.lengthSquared() < 1e-6) {
            return;
        }

        // Normalize collision response
        Vector3d collisionResponseNormal = new Vector3d(collisionResponse);
        collisionResponseNormal.normalize();

        // Calculate dot product to determine if moving into the surface
        double parallelComponent = collisionResponseNormal.dot(
                currentVelocity.x, currentVelocity.y, currentVelocity.z
        );

        // Only remove the parallel component of velocity if moving into the surface
        if (parallelComponent < 0) {
            // Calculate new velocity by removing parallel component
            Vec3d newVelocity = new Vec3d(
                    currentVelocity.x - collisionResponseNormal.x * parallelComponent,
                    currentVelocity.y - collisionResponseNormal.y * parallelComponent,
                    currentVelocity.z - collisionResponseNormal.z * parallelComponent
            );

            // Apply the new velocity
            setVelocity(newVelocity.x, newVelocity.y, newVelocity.z);

            // Debug log for velocity adjustment
            if (self instanceof PlayerEntity) {
                SLogger.log(this, String.format("Player velocity adjusted - Before: (%.4f, %.4f, %.4f), After: (%.4f, %.4f, %.4f)",
                        currentVelocity.x, currentVelocity.y, currentVelocity.z,
                        newVelocity.x, newVelocity.y, newVelocity.z));
            }
        }

        // Still perform collision checks for non-movement collisions (e.g., standing in lava)
        checkBlockCollision();

        // Cancel the vanilla velocity zeroing behavior
        ci.cancel();
    }

    /**
     * After vanilla movement completes, apply gentle grid collision adjustments.
     * This handles any remaining collision resolution needed.
     */
    @Inject(
            method = "move",
            at = @At("TAIL") // After all vanilla movement is complete
    )
    private void afterVanillaMovement(MovementType movementType, Vec3d movement, CallbackInfo ci) {
        Entity self = (Entity)(Object)this;

        // Skip for non-physical entities
        if (self.isSpectator() || self.noClip) {
            return;
        }

        // Get physics engine
        PhysicsEngine engine = Stardance.engineManager.getEngine(self.getWorld());
        if (engine == null) {
            return;
        }

        EntityPhysicsManager manager = engine.getEntityPhysicsManager();
        if (manager == null) {
            return;
        }

        // Force detect fresh contacts after movement
        List<Contact> contacts = manager.getContactDetector().detectContacts(self);

        // Only proceed if we have contacts
        if (contacts.isEmpty()) {
            return;
        }

        // DEBUG: Log contact information
        if (self instanceof PlayerEntity) {
            SLogger.log(this, String.format("Player has %d contacts after movement", contacts.size()));
            for (int i = 0; i < contacts.size(); i++) {
                Contact contact = contacts.get(i);
                String contactType = contact.isGridContact() ? "grid" : "entity";
                String normalStr = String.format("(%.2f, %.2f, %.2f)",
                        contact.getContactNormal().x,
                        contact.getContactNormal().y,
                        contact.getContactNormal().z);

                SLogger.log(this, String.format("Contact %d: type=%s, normal=%s, depth=%.4f",
                        i, contactType, normalStr, contact.getPenetrationDepth()));
            }
        }

        // Process post-movement collisions
        manager.processPostMovement(self, movement, movement);

        // Check if any contact would consider us on ground
        boolean wasOnGround = self.isOnGround();
        boolean nowOnGround = wasOnGround;

        for (Contact contact : contacts) {
            if (contact.isGroundContact()) {
                nowOnGround = true;
                break;
            }
        }

        // Update ground state if needed
        if (nowOnGround != wasOnGround) {
            self.setOnGround(nowOnGround);

            // Reset fall distance if we're now on ground
            if (nowOnGround) {
                self.fallDistance = 0;
            }

            if (self instanceof PlayerEntity) {
                SLogger.log(this, "Player ground state changed: " + wasOnGround + " -> " + nowOnGround);
            }
        }
    }

    /**
     * Improved version of adjustMovementForGridCollisions that better handles angled collisions.
     * This fixes the "sliding acceleration" issue by carefully handling parallel components.
     */
    @Unique
    private Vec3d adjustMovementForGridCollisions(Entity entity, Vec3d movement, Box entityBox) {
        // Skip if no movement
        if (movement.lengthSquared() < 0.0001) {
            return movement;
        }

        // Get physics engine
        PhysicsEngine engine = Stardance.engineManager.getEngine(entity.getWorld());
        if (engine == null) {
            return movement;
        }

        // Get entity physics manager
        EntityPhysicsManager manager = engine.getEntityPhysicsManager();
        if (manager == null) {
            return movement;
        }

        // Force track this entity if it's not already tracked
        manager.forceTrackEntity(entity);

        // First check for existing contacts before the movement
        // This is critical for handling penetrations and stationary grids
        List<Contact> currentContacts = manager.getContactDetector().getContactsForEntity(entity);
        boolean hasSignificantPenetration = false;
        Contact deepestContact = null;

        // Find the deepest contact with enough penetration to matter
        for (Contact contact : currentContacts) {
            if (contact.getPenetrationDepth() > 0.05f) {
                hasSignificantPenetration = true;
                if (deepestContact == null || contact.getPenetrationDepth() > deepestContact.getPenetrationDepth()) {
                    deepestContact = contact;
                }
            }
        }

        // If we're already penetrating significantly, handle it specially
        if (hasSignificantPenetration && deepestContact != null) {
            boolean isPlayer = entity instanceof PlayerEntity;

            // Get the penetration normal
            Vector3f normal = deepestContact.getContactNormal();
            normal.negate();

            // Calculate how much of our movement is going further into the penetration
            Vector3d normalVec = new Vector3d(normal.x, normal.y, normal.z);
            normalVec.normalize();

            Vector3d movementVec = new Vector3d(movement.x, movement.y, movement.z);
            double originalLength = movementVec.length();

            double dot = movementVec.dot(normalVec);

            // If we're moving further into the object, adjust the movement
            if (dot < 0) {
                // Create a separation vector to push us out of penetration
                float separationDistance = deepestContact.getPenetrationDepth() * 1.1f; // Add 10% to ensure we're out
                Vec3d separationVector = new Vec3d(
                        normal.x * separationDistance,
                        normal.y * separationDistance,
                        normal.z * separationDistance
                );

                // Calculate the tangential component
                Vector3d normalComponent = new Vector3d(normalVec);
                normalComponent.mul(dot);

                Vector3d tangentialComponent = new Vector3d(movementVec);
                tangentialComponent.sub(normalComponent);

                // Get tangential length
                double tangentialLength = tangentialComponent.length();

                // If there is a tangential component, properly scale it
                if (tangentialLength > 0.0001) {
                    // Normalize and scale to a fraction of the original movement magnitude
                    // This prevents "speed boosts" in the tangential direction
                    tangentialComponent.mul(Math.min(originalLength, tangentialLength) / tangentialLength);

                    // Apply a friction factor for sliding
                    tangentialComponent.mul(0.8);
                }

                // Convert adjusted movement back to Vec3d and combine with separation
                Vec3d tangentialMovement = new Vec3d(tangentialComponent.x, tangentialComponent.y, tangentialComponent.z);
                Vec3d combinedMovement = separationVector.add(tangentialMovement);

                // Log the adjustment for debugging
                if (isPlayer) {
                    String contactType = deepestContact.isGridContact() ? "grid" : "entity";
                    SLogger.log(this, String.format(
                            "Player movement adjusted due to existing penetration with %s - depth=%.4f, normal=(%.2f, %.2f, %.2f), " +
                                    "original=(%.4f, %.4f, %.4f), adjusted=(%.4f, %.4f, %.4f)",
                            contactType,
                            deepestContact.getPenetrationDepth(),
                            normal.x, normal.y, normal.z,
                            movement.x, movement.y, movement.z,
                            combinedMovement.x, combinedMovement.y, combinedMovement.z));
                }

                return combinedMovement;
            }
        }

        // Perform sweep test to detect collision with grids
        ContactDetector.SweepResult result = manager.getContactDetector().convexSweepTest(
                entity, movement, manager.getEntityProxies());

        // If no collision detected, return original movement
        // TO FIX: make a more rigorous system that checks masks and groups
        if (result == null
//                || !result.getObject().getCollisionShape().getShapeType().isCompound()
        ) {
            return movement;
        }

        // Log collision detection for debugging
        boolean isPlayer = entity instanceof PlayerEntity;
        if (isPlayer) {
            String hitType = result.getGrid() != null ? "grid" : "entity";
            SLogger.log(this, String.format(
                    "Player collided with %s at time %.4f, normal=(%.2f, %.2f, %.2f)",
                    hitType,
                    result.getTimeOfImpact(),
                    result.getHitNormal().x,
                    result.getHitNormal().y,
                    result.getHitNormal().z));
        }

        // Calculate safe movement (just before collision)
        double safetyMargin = 0.01;
        double safeTime = Math.max(0, result.getTimeOfImpact() - safetyMargin);
        Vec3d safeMovement = movement.multiply(safeTime);

        // Only if we still have significant movement remaining after safe movement
        double remainingTime = 1.0 - safeTime;
        if (remainingTime > 0.01) {
            // Get collision normal
            Vector3f normal = result.getHitNormal();

            // Convert to Vector3d for more precise math
            Vector3d normalVec = new Vector3d(normal.x, normal.y, normal.z);
            normalVec.normalize();

            // Calculate remaining movement
            Vec3d remainingMovement = movement.multiply(remainingTime);

            // Convert to Vector3d
            Vector3d remainingVec = new Vector3d(
                    remainingMovement.x,
                    remainingMovement.y,
                    remainingMovement.z
            );

            // Calculate dot product
            double dot = remainingVec.dot(normalVec);

            // Only deflect if moving into the surface
            Vec3d deflectedMovement;
            if (dot < 0) {
                // Remove normal component to get tangential component
                Vector3d normalComponent = new Vector3d(normalVec);
                normalComponent.mul(dot);

                Vector3d tangentialComponent = new Vector3d(remainingVec);
                tangentialComponent.sub(normalComponent);

                // Calculate tangential length
                double tangentialLength = tangentialComponent.length();

                // If tangential component is negligible, no deflection
                if (tangentialLength < 0.0001) {
                    deflectedMovement = Vec3d.ZERO;
                } else {
                    // Normalize tangential component
                    tangentialComponent.mul(1.0 / tangentialLength);

                    // Scale to at most the magnitude of original tangential component
                    // This is key to preventing speed boosts
                    double deflectionMagnitude = Math.min(
                            remainingMovement.length() * 0.8,  // 80% of remaining length
                            tangentialLength                   // Original tangential length
                    );

                    tangentialComponent.mul(deflectionMagnitude);

                    // Convert back to Vec3d
                    deflectedMovement = new Vec3d(
                            tangentialComponent.x,
                            tangentialComponent.y,
                            tangentialComponent.z
                    );
                }
            } else {
                // If not moving into surface, use remaining movement
                deflectedMovement = remainingMovement;
            }

            // Combine safe movement with deflected movement
            Vec3d combinedMovement = safeMovement.add(deflectedMovement);

            if (isPlayer) {
                SLogger.log(this, String.format(
                        "Player pre-movement resolution - time=%.2f, safe=(%.2f, %.2f, %.2f), deflected=(%.2f, %.2f, %.2f), combined=(%.2f, %.2f, %.2f)",
                        result.getTimeOfImpact(),
                        safeMovement.x, safeMovement.y, safeMovement.z,
                        deflectedMovement.x, deflectedMovement.y, deflectedMovement.z,
                        combinedMovement.x, combinedMovement.y, combinedMovement.z));
            }

            return combinedMovement;
        }

        // If minimal remaining time, just use safe movement
        if (isPlayer) {
            SLogger.log(this, String.format(
                    "Player using safe movement only: safe=%.2f, pos=(%.2f, %.2f, %.2f)",
                    safeTime,
                    safeMovement.x, safeMovement.y, safeMovement.z));
        }

        return safeMovement;
    }

    /**
     * Check if the entity should be considered on ground based on grid contacts.
     */
    @Inject(
            method = "move",
            at = @At(value = "RETURN")
    )
    private void checkGridGroundState(MovementType type, Vec3d movement, CallbackInfo ci) {
        Entity self = (Entity)(Object)this;

        // Skip if entity is spectator or has noclip
        if (self.isSpectator() || self.noClip) {
            return;
        }

        // Get physics engine
        PhysicsEngine engine = Stardance.engineManager.getEngine(self.getWorld());
        if (engine == null) {
            return;
        }

        // Get contacts from the physics system - use fresh detection
        List<Contact> contacts = engine.getEntityPhysicsManager().getContactDetector().detectContacts(self);

        // Skip if no contacts
        if (contacts.isEmpty()) {
            return;
        }

        boolean isOnGrid = false;
        boolean isPlayer = self instanceof PlayerEntity;
        float bestGroundNormal = 0f;

        // Check if any contacts indicate the entity is on a grid
        for (Contact contact : contacts) {
            if (contact.isGridContact()) {
                Vector3f normal = contact.getContactNormal();
                // A normal with Y>0.7 is considered ground (less than 45 degrees from vertical)
                if (normal.y > 0.7f && contact.getPenetrationDepth() > 0.01f) {
                    isOnGrid = true;
                    bestGroundNormal = Math.max(bestGroundNormal, normal.y);
                }
            }
        }

        // If the entity is on a grid, directly set the onGround field
        if (isOnGrid) {
            // Force set using field
            self.setOnGround(true);

            // Reset fall distance
            self.fallDistance = 0;

            // Log for debugging
            if (isPlayer) {
                SLogger.log(this, String.format(
                        "Player is now on grid ground - normal.y=%.2f",
                        bestGroundNormal));
            }
        }
    }

    /**
     * Provides access to the onGround field for physics system.
     * Public accessor required by EntityPhysicsManager.
     *
     * @param value The new onGround value
     */
    public void stardance$setOnGround(boolean value) {
        this.onGround = value;
    }

    /**
     * Provides access to the fallDistance field for physics system.
     * Public accessor required by EntityPhysicsManager.
     *
     * @param value The new fallDistance value
     */
    public void stardance$setFallDistance(float value) {
        this.fallDistance = value;
    }
}