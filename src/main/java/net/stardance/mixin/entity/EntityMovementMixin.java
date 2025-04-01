package net.stardance.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.stardance.core.LocalGrid;
import net.stardance.physics.PhysicsEngine;
import net.stardance.physics.entity.CollisionResolver;
import net.stardance.physics.entity.Contact;
import net.stardance.physics.entity.ContactDetector.SweepResult;
import net.stardance.Stardance;
import net.stardance.utils.ILoggingControl;
import net.stardance.utils.SLogger;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import javax.vecmath.Vector3f;
import java.util.List;

/**
 * Mixin to intercept and modify entity movement for proper grid collision.
 * Uses an approach similar to Valkyrien Skies 2 for smooth sliding.
 */
@Mixin(Entity.class)
public abstract class EntityMovementMixin implements ILoggingControl {

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

    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return true;
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

        // Get the entity's current bounding box for collision detection
        Box entityBox = entity.getBoundingBox();

        // Adjust movement for grid collisions first
        Vec3d adjustedMovement = adjustMovementForGridCollisions(entity, originalMovement, entityBox);

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
            cancellable = true,
            locals = LocalCapture.CAPTURE_FAILHARD
    )
    private void preserveMomentumAfterCollision(MovementType type, Vec3d movement,
                                                CallbackInfo ci, Vec3d collisionResult) {
        Entity self = (Entity)(Object)this;

        // Skip for non-physical entities
        if (self.isSpectator() || self.noClip) {
            return;
        }

        Vector3d collisionResponse = new Vector3d(collisionResult.x - movement.x,collisionResult.y - movement.y,collisionResult.z - movement.z);

        if(collisionResponse.lengthSquared() > 1e-6){
            Vec3d currentVelocity = getVelocity();

            Vector3d collisionResponseNormal = new Vector3d(collisionResponse).normalize();

            double parallelComponent = collisionResponseNormal.dot(
                    currentVelocity.x, currentVelocity.y, currentVelocity.z
            );

            // Only remove the parallel component of velocity (preserving perpendicular motion)
            setVelocity(
                    currentVelocity.x - collisionResponseNormal.x * parallelComponent,
                    currentVelocity.y - collisionResponseNormal.y * parallelComponent,
                    currentVelocity.z - collisionResponseNormal.z * parallelComponent
            );
        }

        // Still perform tryCheckInsideBlocks since we're cancelling part of move()
        tryCheckBlockCollision();

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

        // Apply gentle position correction and ground detection
        resolver.applyGentlePositionCorrection(self, contacts);

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

        // Get current server tick
        long currentTick = entity.getWorld().getTime();

        // Perform sweep test to detect collisions with grids
        SweepResult result = engine.getEntityPhysicsManager()
                .getContactDetector().performBestSweepTest(
                        entity, movement, engine.getEntityPhysicsManager().getEntityProxies());

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
            Vec3d deflectedMovement = result.getDeflectedMovement(remainingMovement, remainingTime);

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
        List<Contact> contacts = engine.getEntityPhysicsManager()
                .getContactDetector().getContactsForEntity(self);

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
        // We need to use reflection or accessor mixin for this
        if (isOnGrid) {
            // For now, we'll use a workaround to set onGround
            // In a full implementation, you'd use an accessor mixin
            // This is a placeholder until we can implement proper access
            (self).setOnGround(true);
        }
    }
}