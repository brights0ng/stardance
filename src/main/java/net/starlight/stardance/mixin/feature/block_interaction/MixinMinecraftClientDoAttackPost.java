package net.starlight.stardance.mixin.feature.block_interaction;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.starlight.stardance.utils.SLogger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftClient.class)
public class MixinMinecraftClientDoAttackPost {

    @Inject(method = "handleInputEvents", at = @At("HEAD"))
    private void debugInputHandling(CallbackInfo ci) {
        // Check what happens in the main input handling loop
        if (MinecraftClient.getInstance().options.attackKey.wasPressed()) {
            SLogger.log("InputFlow", "=== ATTACK KEY INPUT HANDLING ===");
        }
    }

    @WrapOperation(
            method = "handleInputEvents",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MinecraftClient;doAttack()Z")
    )
    private boolean interceptDoAttackCall(MinecraftClient client, Operation<Boolean> original) {
        SLogger.log("InputFlow", "=== handleInputEvents calling doAttack() ===");
        boolean result = original.call(client);
        SLogger.log("InputFlow", "doAttack() returned: " + result);

        // Check what happens after doAttack
        SLogger.log("InputFlow", "Checking what happens after doAttack...");

        return result;
    }
}