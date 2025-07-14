package net.starlight.stardance.mixinducks;

import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;

public interface LevelDuck {
    BlockHitResult stardance$clipDirect(ClipContext context);
}