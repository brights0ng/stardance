package net.starlight.stardance.mixin.feature.block_interaction;

import com.bulletphysics.dynamics.RigidBody;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.debug.InteractionDebugManager;
import net.starlight.stardance.mixinducks.OriginalCrosshairProvider;
import net.starlight.stardance.physics.PhysicsEngine;
import net.starlight.stardance.utils.GridSpaceRaycastUtils;
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
            method = "raycast(DFZ)Lnet/minecraft/util/hit/HitResult;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/World;raycast(Lnet/minecraft/world/RaycastContext;)Lnet/minecraft/util/hit/BlockHitResult;")
    )
    private BlockHitResult interceptWorldRaycast(World world, RaycastContext context, Operation<BlockHitResult> original) {
        try {
            // Store original crosshair target (client-side only)
            BlockHitResult vanillaResult = original.call(world, context);
            if (world.isClient) {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client instanceof OriginalCrosshairProvider) {
                    ((OriginalCrosshairProvider) client).stardance$setOriginalCrosshairTarget(vanillaResult);
                }
            }

            // Use enhanced raycast that includes grids
            return GridSpaceRaycastUtils.raycastIncludeGrids(world, context);

        } catch (Exception e) {
            SLogger.log("MixinEntityRaycast", "Error in raycast: " + e.getMessage());
            return original.call(world, context);
        }
    }
}