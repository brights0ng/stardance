package net.starlight.stardance.mixin.feature.block_interaction;

import com.bulletphysics.dynamics.RigidBody;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.debug.InteractionDebugManager;
import net.starlight.stardance.physics.PhysicsEngine;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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
    public boolean stardance$isChatLoggingEnabled() { return false; }
    @Override
    public boolean stardance$isConsoleLoggingEnabled() { return false; }

    /**
     * Target the exact method signature you found:
     * public HitResult raycast(double maxDistance, float tickDelta, boolean includeFluids)
     */
    @WrapOperation(
            method = "raycast(DFZ)Lnet/minecraft/util/hit/HitResult;", // <-- Exact signature
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/World;raycast(Lnet/minecraft/world/RaycastContext;)Lnet/minecraft/util/hit/BlockHitResult;")
    )
    private BlockHitResult interceptWorldRaycast(World world, RaycastContext context, Operation<BlockHitResult> original) {
        try {
            // 1. Perform physics raycast first
            PhysicsEngine engine = engineManager.getEngine(world);
            Optional<PhysicsEngine.PhysicsRaycastResult> physicsHit = Optional.empty();

            if (engine != null) {
                physicsHit = engine.raycastGrids(context.getStart(), context.getEnd());
            }

            // 2. If physics found a hit, ONLY return that - don't even do vanilla raycast
            if (physicsHit.isPresent()) {
                PhysicsEngine.PhysicsRaycastResult gridHit = physicsHit.get();

//                SLogger.log("MixinEntityRaycast", "Grid hit found - blocking world raycast");

                // Return grid hit with GridSpace coordinates (your server mixin expects this)
                return new BlockHitResult(
                        gridHit.worldHitPos,      // Visual hit position
                        Direction.UP,             // Hit side
                        gridHit.gridSpacePos,     // GridSpace coordinates for server
                        false
                );
            }

            // 3. Only if no grid hit, perform vanilla raycast
//            SLogger.log("MixinEntityRaycast", "No grid hit - using vanilla raycast");
            return original.call(world, context);

        } catch (Exception e) {
            SLogger.log("MixinEntityRaycast", "Error in raycast: " + e.getMessage());
            return original.call(world, context);
        }
    }
}