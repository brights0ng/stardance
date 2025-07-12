package net.starlight.stardance.mixin.feature.block_interaction;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.starlight.stardance.utils.SLogger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientPlayerInteractionManager.class)
public class MixinClientPlayerInteractionManagerValidation {

    @WrapOperation(
        method = "attackBlock",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;squaredDistanceTo(DDD)D")
    )
    private double interceptDistanceCheck(ClientPlayerEntity player, double x, double y, double z, Operation<Double> original) {
        double originalDistance = original.call(player, x, y, z);
        SLogger.log("ClientDebug", "Distance check: " + Math.sqrt(originalDistance) + " blocks to (" + x + ", " + y + ", " + z + ")");
        
        // If this is a GridSpace coordinate, override with a reasonable distance
        if (x >= 20_000_000 || z >= 20_000_000) {
            SLogger.log("ClientDebug", "GridSpace coordinate detected, overriding distance validation");
            return 4.0; // Return 2 blocks distance (squared = 4)
        }
        
        return originalDistance;
    }
}