package net.starlight.stardance.mixin.feature.block_interaction;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.world.World;
import net.starlight.stardance.utils.SLogger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

@Mixin(ProjectileUtil.class)
public class MixinProjectileUtilSafety {
    
    @WrapOperation(
        method = "raycast",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;getOtherEntities(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/Box;Ljava/util/function/Predicate;)Ljava/util/List;")
    )
    private static List<Entity> safeEntityLookup(World world, Entity except, net.minecraft.util.math.Box box,
                                                 java.util.function.Predicate<Entity> predicate, Operation<List<Entity>> original) {
        try {
            // Check if the bounding box contains GridSpace coordinates
            if (box.minX >= 20_000_000 || box.minZ >= 20_000_000 || 
                box.maxX >= 20_000_000 || box.maxZ >= 20_000_000) {
                
                return java.util.Collections.emptyList();
            }
            
            return original.call(world, except, box, predicate);
            
        } catch (IllegalArgumentException e) {
            return java.util.Collections.emptyList();
        }
    }
}