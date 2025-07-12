package net.starlight.stardance.mixin.feature.block_interaction;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.starlight.stardance.utils.SLogger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public class MixinClientPlayerInteractionManagerReach {

    @Inject(method = "getReachDistance", at = @At("RETURN"), cancellable = true)
    private void overrideReachDistance(CallbackInfoReturnable<Float> cir) {
        // If player is looking at a grid, allow infinite reach
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player != null) {
            HitResult hit = player.raycast(64.0, 0.0f, false);
            if (hit instanceof BlockHitResult) {
                BlockPos pos = ((BlockHitResult) hit).getBlockPos();
                if (pos.getX() >= 20_000_000) { // GridSpace coordinate
//                    SLogger.log("ClientReach", "Overriding reach distance for GridSpace block");
                    cir.setReturnValue(Float.MAX_VALUE); // Infinite reach
                }
            }
        }
    }
}