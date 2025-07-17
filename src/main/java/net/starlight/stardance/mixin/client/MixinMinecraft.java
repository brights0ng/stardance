package net.starlight.stardance.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Client-side crosshair management for consistent block placement on grids.
 * Stores original crosshair target before transformations.
 */
@Mixin(Minecraft.class)
public class MixinMinecraft {

    @Unique
    private HitResult stardance$originalCrosshairTarget;

    @WrapOperation(
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;useItemOn(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;"
        ),
        method = "startUseItem"
    )
    private InteractionResult useOriginalCrosshairForBlockPlacement(
            final MultiPlayerGameMode gameMode, final LocalPlayer player, 
            final InteractionHand hand, final BlockHitResult blockHitResult, 
            final Operation<InteractionResult> original) {
        
        // Store the original hit result
        this.stardance$originalCrosshairTarget = blockHitResult;

        // Use original result for placement to ensure consistency
        return original.call(gameMode, player, hand, blockHitResult);
    }
}