package net.starlight.stardance.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.starlight.stardance.mixinducks.OriginalCrosshairProvider;
import net.starlight.stardance.utils.SLogger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Client-side crosshair storage following VS2's MixinMinecraft pattern.
 * Stores original crosshair target for proper coordinate handling.
 */
@Mixin(MinecraftClient.class)
public class MixinMinecraftClient implements OriginalCrosshairProvider {

    @Shadow
    public ClientPlayerInteractionManager interactionManager;

    @Unique
    private HitResult originalCrosshairTarget;

    @Override
    public void stardance$setOriginalCrosshairTarget(HitResult hitResult) {
        this.originalCrosshairTarget = hitResult;
    }

    @Override
    public HitResult stardance$getOriginalCrosshairTarget() {
        return this.originalCrosshairTarget;
    }

    /**
     * Use original crosshair target for block placement (VS2's pattern).
     */
    @WrapOperation(
        method = "doItemUse",
        at = @At(value = "INVOKE", 
                target = "Lnet/minecraft/client/network/ClientPlayerInteractionManager;interactBlock(Lnet/minecraft/client/network/ClientPlayerEntity;Lnet/minecraft/util/Hand;Lnet/minecraft/util/hit/BlockHitResult;)Lnet/minecraft/util/ActionResult;")
    )
    private ActionResult useOriginalCrosshairForBlockPlacement(
            ClientPlayerInteractionManager instance, 
            net.minecraft.client.network.ClientPlayerEntity player,
            Hand hand, 
            BlockHitResult hitResult, 
            Operation<ActionResult> original) {
        
        try {
            // Use stored original crosshair if available
            if (this.originalCrosshairTarget instanceof BlockHitResult) {
                SLogger.log("MixinMinecraftClient", "Using original crosshair target for block interaction");
                return original.call(instance, player, hand, (BlockHitResult) this.originalCrosshairTarget);
            }
        } catch (Exception e) {
            SLogger.log("MixinMinecraftClient", "Error using original crosshair: " + e.getMessage());
        }
        
        // Fallback to provided hitResult
        return original.call(instance, player, hand, hitResult);
    }
}