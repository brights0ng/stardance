package net.starlight.stardance.mixin.accessors;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.gen.Accessor;


@Mixin(Entity.class)
public interface EntityAccessor {
    @Invoker("adjustMovementForCollisions")
    Vec3d invokeAdjustMovementForCollisions(Vec3d movement);

    @Accessor("onGround")
    void setOnGround(boolean onGround);
}