package net.starlight.stardance.mixin.feature.block_interaction;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.starlight.stardance.utils.SLogger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public class MixinClientPlayerInteractionManagerPackets {

    // This is likely where the server packet is actually sent
    @Inject(method = "updateBlockBreakingProgress", at = @At("HEAD"))
    private void debugUpdateBreaking(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        SLogger.log("PacketDebug", "=== updateBlockBreakingProgress ===");
        SLogger.log("PacketDebug", "Position: " + pos);
        SLogger.log("PacketDebug", "Is GridSpace: " + (pos.getX() >= 20_000_000));
    }

    @Inject(method = "updateBlockBreakingProgress", at = @At("RETURN"))
    private void debugUpdateBreakingResult(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        SLogger.log("PacketDebug", "updateBlockBreakingProgress result: " + cir.getReturnValue());
    }

}