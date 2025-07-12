package net.starlight.stardance.mixin.feature.block_interaction;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public class MixinClientPlayerInteractionManagerEarly {

    // Intercept hasExtendedReach or similar early validation methods
    @Inject(method = "hasExtendedReach", at = @At("RETURN"), cancellable = true)
    private void allowGridExtendedReach(CallbackInfoReturnable<Boolean> cir) {
        // Allow extended reach for grids
        cir.setReturnValue(true);
    }
}