package net.starlight.stardance.mixin.feature.block_interaction;

import com.bulletphysics.dynamics.RigidBody;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
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
            method = "pick(DFZ)Lnet/minecraft/world/phys/HitResult;", // raycast -> pick
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;") // World->Level, RaycastContext->ClipContext
    )
    private BlockHitResult interceptWorldRaycast(Level world, ClipContext context, Operation<BlockHitResult> original) {
        try {
            // Store original crosshair target (client-side only)
            BlockHitResult vanillaResult = original.call(world, context);
            if (world.isClientSide) {
                Minecraft client = Minecraft.getInstance();
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