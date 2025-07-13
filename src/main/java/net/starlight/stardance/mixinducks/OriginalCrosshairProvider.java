package net.starlight.stardance.mixinducks;

import net.minecraft.world.phys.HitResult;

/**
 * Interface for storing original crosshair targets (VS2 pattern).
 */
public interface OriginalCrosshairProvider {
    void stardance$setOriginalCrosshairTarget(HitResult hitResult);
    HitResult stardance$getOriginalCrosshairTarget();
}