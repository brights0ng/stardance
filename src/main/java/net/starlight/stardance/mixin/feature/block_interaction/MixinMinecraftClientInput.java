package net.starlight.stardance.mixin.feature.block_interaction;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.starlight.stardance.utils.SLogger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class MixinMinecraftClientInput {

    @Inject(method = "tick", at = @At("TAIL"))
    private void forceGridSpaceUpdate(CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();

        // Check if we're attacking a GridSpace block
        if (client.options.attackKey.isPressed() &&
                client.crosshairTarget instanceof BlockHitResult &&
                client.interactionManager != null) {

            BlockHitResult blockHit = (BlockHitResult) client.crosshairTarget;
            BlockPos pos = blockHit.getBlockPos();

            if (pos.getX() >= 20_000_000) {
                SLogger.log("ForceUpdate", "FORCING updateBlockBreakingProgress for GridSpace: " + pos);

                // Manually call updateBlockBreakingProgress
                client.interactionManager.updateBlockBreakingProgress(pos, blockHit.getSide());
            }
        }
    }
}