package net.starlight.stardance.mixin.feature.block_interaction;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.starlight.stardance.utils.SLogger;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftClient.class)
public class MixinMinecraftClientAttack {


    @Inject(method = "doAttack", at = @At("HEAD"))
    private void debugDoAttackVeryStart(CallbackInfoReturnable<Boolean> cir) {
        SLogger.log("DoAttackSimple", "=== doAttack() ENTRY ===");
    }

    @Inject(method = "doAttack", at = @At("RETURN"))
    private void debugDoAttackReturn(CallbackInfoReturnable<Boolean> cir) {
        SLogger.log("DoAttackSimple", "doAttack() final result: " + cir.getReturnValue());
    }

    // Only target the block state check - this one should work
    @WrapOperation(
            method = "doAttack",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/world/ClientWorld;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;")
    )
    private BlockState interceptDoAttackBlockState(ClientWorld world, BlockPos pos, Operation<BlockState> original) {
        SLogger.log("DoAttackSimple", "doAttack() checking block state at: " + pos);
        BlockState state = original.call(world, pos);
        SLogger.log("DoAttackSimple", "Block state: " + state + " (isAir: " + state.isAir() + ")");

        // If GridSpace returns AIR, override it
        if (pos.getX() >= 20_000_000 && state.isAir()) {
            SLogger.log("DoAttackSimple", "OVERRIDING AIR with STONE for GridSpace");
            return net.minecraft.block.Blocks.STONE.getDefaultState();
        }

        return state;
    }
}