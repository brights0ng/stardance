package net.starlight.stardance.mixin.feature.block_interaction;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.starlight.stardance.utils.SLogger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public class MixinClientPlayerInteractionManagerDebug {
    @Inject(method = "attackBlock", at = @At("HEAD"))
    private void debugAttackBlockStart(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        SLogger.log("AttackBlockDebug", "=== attackBlock() START ===");
        SLogger.log("AttackBlockDebug", "Position: " + pos);
        SLogger.log("AttackBlockDebug", "Direction: " + direction);
        SLogger.log("AttackBlockDebug", "Is GridSpace: " + (pos.getX() >= 20_000_000));
    }

    @Inject(method = "attackBlock", at = @At("RETURN"))
    private void debugAttackBlockResult(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        SLogger.log("AttackBlockDebug", "attackBlock() result: " + cir.getReturnValue());
    }

    // Check if there's another block state validation in attackBlock
    @WrapOperation(
            method = "attackBlock",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/world/ClientWorld;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;")
    )
    private BlockState interceptAttackBlockState(ClientWorld world, BlockPos pos, Operation<BlockState> original) {
        SLogger.log("AttackBlockDebug", "attackBlock() checking block state at: " + pos);
        BlockState state = original.call(world, pos);
        SLogger.log("AttackBlockDebug", "attackBlock() block state: " + state + " (isAir: " + state.isAir() + ")");

        // Same override for GridSpace
        if (pos.getX() >= 20_000_000 && state.isAir()) {
            SLogger.log("AttackBlockDebug", "OVERRIDING AIR with STONE in attackBlock");
            return net.minecraft.block.Blocks.STONE.getDefaultState();
        }

        return state;
    }
}