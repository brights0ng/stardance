package net.stardance.mixin.physics;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.stardance.physics.PhysicsEngine;
import net.stardance.physics.entity.CollisionResolver;
import net.stardance.physics.entity.Contact;
import net.stardance.physics.entity.ContactDetector.SweepResult;
import net.stardance.Stardance;
import net.stardance.physics.entity.EntityPhysicsManager;
import net.stardance.utils.ILoggingControl;
import net.stardance.utils.SLogger;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

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
        return false;
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
        // Get physics engine
        PhysicsEngine engine = Stardance.engineManager.getEngine(entity.getWorld());
        if (entity.isSpectator() || entity.noClip || engine == null || !engine.getEntityPhysicsManager().getTrackedEntities().contains(entity)) {
            return vanillaCollide.call(entity, originalMovement);
        }

        // Get the entity's current bounding box for collision detection
        Box entityBox = entity.getBoundingBox();

        // Adjust movement for grid collisions first
        Vec3d adjustedMovement = adjustMovementForGridCollisions(entity, originalMovement, entityBox);
        LOGGER.info("Original: " + originalMovement + ", Adjusted: " + adjustedMovement);

        // Then let vanilla handle world block collisions
        Vec3d collisionResultWithWorld = vanillaCollide.call(entity, adjustedMovement);

        // If we collided with world blocks, clear any grid contact info
        double squaredDistance = calculateSquaredDistance(adjustedMovement, collisionResultWithWorld);
        if (squaredDistance > 1e-12) {
            // We hit a world block, so clear any grid-specific state if needed
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

        Vector3d collisionResponse = new Vector3d(
                collisionResult.x - movement.x,
                collisionResult.y - movement.y,
                collisionResult.z - movement.z
        );

        if (collisionResponse.lengthSquared() > 1e-6) {
            Vec3d currentVelocity = getVelocity();

            Vector3d collisionResponseNormal = new Vector3d(collisionResponse);
            collisionResponseNormal.normalize();

            double parallelComponent = collisionResponseNormal.dot(
                    currentVelocity.x, currentVelocity.y, currentVelocity.z
            );

            // Only remove the parallel component of velocity if moving into the surface
            if (parallelComponent < 0) {
                setVelocity(
                        currentVelocity.x - collisionResponseNormal.x * parallelComponent,
                        currentVelocity.y - collisionResponseNormal.y * parallelComponent,
                        currentVelocity.z - collisionResponseNormal.z * parallelComponent
                );
            }
        }

        // Still perform tryCheckInsideBlocks since we're cancelling part of move()
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

        // Get contacts from the physics system
        List<Contact> contacts = engine.getEntityPhysicsManager().getContactDetector().getContactsForEntity(self);

        // If no contacts, nothing to do
        if (contacts.isEmpty()) {
            return;
        }

        // Get the collision resolver
        CollisionResolver resolver = engine.getEntityPhysicsManager().getCollisionResolver();

        // Process post-movement collision with our physics system
        engine.getEntityPhysicsManager().processPostMovement(self, movement, movement);

        // Log collision resolution for debugging
        if (self instanceof PlayerEntity && !contacts.isEmpty()) {
            SLogger.log(this, "Applied collision resolution for player with " +
                    contacts.size() + " contacts");
        }
    }

    /**
     * Adjust movement vector to account for grid collisions.
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

        // Perform sweep test to detect collision with grids
        SweepResult result = manager.getContactDetector().convexSweepTest(
                entity, movement, manager.getEntityProxies());

        // If no collision detected, return original movement
        if (result == null) {
            return movement;
        }

        // Calculate safe movement (just before collision)
        double safeTime = Math.max(0, result.getTimeOfImpact() - 0.001);
        Vec3d safeMovement = movement.multiply(safeTime);

        // Only if we still have significant movement remaining after safe movement
        double remainingTime = 1.0 - safeTime;
        if (remainingTime > 0.01) {
            // Calculate deflected component for remaining movement (sliding)
            Vec3d remainingMovement = movement.multiply(remainingTime);
            Vec3d deflectedMovement = result.getDeflectedMovement(remainingMovement, (float) remainingTime);

            // Combine safe movement with deflected remaining movement
            return safeMovement.add(deflectedMovement);
        }

        return safeMovement;
    }

    /**
     * Instead of injecting at isOnGround directly, we'll check a field or method
     * that determines ground state to avoid recursive calls.
     */
    @Inject(
            method = "move",
            at = @At("RETURN")
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

        // Get contacts from the physics system
        List<Contact> contacts = engine.getEntityPhysicsManager().getContactDetector().getContactsForEntity(self);

        boolean isOnGrid = false;

        // Check if any contacts indicate the entity is on a grid
        for (Contact contact : contacts) {
            if (contact.isGridContact()) {
                Vector3f normal = contact.getContactNormal();
                float depth = contact.getPenetrationDepth();

                // If normal points up significantly and has sufficient contact depth
                if (normal.y > 0.7071f && depth > 0.001f) {
                    isOnGrid = true;
                    break;
                }
            }
        }

        // If the entity is on a grid, directly set the onGround field
        if (isOnGrid) {
            self.setOnGround(true);
            self.fallDistance = 0;
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