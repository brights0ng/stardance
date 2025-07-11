package net.starlight.stardance.mixin.feature.block_interaction;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.starlight.stardance.debug.InteractionDebugManager;
import net.starlight.stardance.physics.PhysicsEngine;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

import static net.starlight.stardance.Stardance.engineManager;

/**
 * SIMPLIFIED RAYCAST MIXIN: Uses JBullet physics engine for proper grid collision detection.
 *
 * This is the VS2-style approach:
 * 1. Perform physics raycast using JBullet collision detection
 * 2. Perform vanilla world raycast
 * 3. Return whichever hit is closer
 */
@Mixin(Entity.class)
public class MixinEntityRaycast implements ILoggingControl {

    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return InteractionDebugManager.isRaycastDebuggingEnabled();
    }

    /**
     * SIMPLIFIED: Uses physics engine raycast for proper collision detection.
     */
    @WrapOperation(
            method = "raycast",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/World;raycast(Lnet/minecraft/world/RaycastContext;)Lnet/minecraft/util/hit/BlockHitResult;")
    )
    private BlockHitResult interceptWorldRaycast(World world, RaycastContext context, Operation<BlockHitResult> original) {
        // Skip transformation if we're on client side
        if (world.isClient) {
            return original.call(world, context);
        }

        try {
            // 1. Perform physics raycast using JBullet collision detection
            PhysicsEngine engine = engineManager.getEngine(world);
            Optional<PhysicsEngine.PhysicsRaycastResult> physicsHit = Optional.empty();

            if (engine != null) {
                physicsHit = engine.raycastGrids(context.getStart(), context.getEnd());
            }

            // 2. Perform vanilla world raycast
            BlockHitResult worldResult = original.call(world, context);

            // 3. Compare results and return the closer hit
            if (physicsHit.isPresent()) {
                PhysicsEngine.PhysicsRaycastResult gridHit = physicsHit.get();

                // If world missed, use grid hit
                if (worldResult.getType() == HitResult.Type.MISS) {
                    if (stardance$isConsoleLoggingEnabled()) {
                        SLogger.log(this, String.format(
                                "Grid raycast HIT (no world): fraction=%.3f, GridSpace=%s",
                                gridHit.hitFraction, gridHit.gridSpacePos
                        ));
                    }
                    return gridHit.createBlockHitResult();
                }

                // Both hit - calculate distances and use closer one
                double worldDistance = context.getStart().distanceTo(worldResult.getPos());
                double gridDistance = context.getStart().distanceTo(gridHit.worldHitPos);

                if (gridDistance < worldDistance - 0.01) { // Small tolerance
                    if (stardance$isConsoleLoggingEnabled()) {
                        SLogger.log(this, String.format(
                                "Grid raycast HIT (closer): grid=%.3f, world=%.3f, GridSpace=%s",
                                gridDistance, worldDistance, gridHit.gridSpacePos
                        ));
                    }
                    return gridHit.createBlockHitResult();
                } else {
                    if (stardance$isConsoleLoggingEnabled()) {
                        SLogger.log(this, String.format(
                                "World raycast HIT (closer): grid=%.3f, world=%.3f, worldPos=%s",
                                gridDistance, worldDistance, worldResult.getPos()
                        ));
                    }
                    return worldResult;
                }
            }

            // No grid hit, return world result (hit or miss)
            if (stardance$isConsoleLoggingEnabled()) {
                String status = worldResult.getType() == HitResult.Type.MISS ? "MISS" : "HIT";
                SLogger.log(this, "No grid intersection, world raycast: " + status);
            }

            return worldResult;

        } catch (Exception e) {
            SLogger.log(this, "Error in raycast transformation: " + e.getMessage());
            e.printStackTrace();
            return original.call(world, context);
        }
    }
}