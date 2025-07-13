package net.starlight.stardance.item;

import com.bulletphysics.dynamics.RigidBody;
import com.google.common.collect.Multimap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.physics.PhysicsEngine;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;

import javax.vecmath.Vector3f;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;

import static net.starlight.stardance.Stardance.engineManager;

/**
 * A wand item that applies impulse forces to grids.
 * Right-click to pull the nearest grid toward the clicked point.
 */
public class ImpulseToolItem extends Item implements ILoggingControl {
    private static final float DEFAULT_IMPULSE_STRENGTH = 1000.0f;
    private static final float MAX_IMPULSE_DISTANCE = 64.0f;

    public ImpulseToolItem(Properties settings) {
        super(settings);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level world, Player player, InteractionHand hand) {
        if (world.isClientSide) {
            return InteractionResultHolder.pass(player.getItemInHand(hand));
        }

        // Get the item stack
        ItemStack stack = player.getItemInHand(hand);

        // Perform a raycast to find what the player is looking at
        HitResult hitResult = player.pick(MAX_IMPULSE_DISTANCE, 0.0f, false);

        // Only proceed if we hit a block
        if (hitResult.getType() != HitResult.Type.BLOCK) {
            player.displayClientMessage(Component.literal("No block in range. Point at a block and try again."), true);
            return InteractionResultHolder.fail(stack);
        }

        BlockHitResult blockHit = (BlockHitResult) hitResult;
        Vec3 hitPos = blockHit.getLocation();

        // Find the nearest physics grid
        Optional<LocalGrid> nearestGrid = findNearestGrid(world, hitPos);

        if (nearestGrid.isPresent()) {
            LocalGrid grid = nearestGrid.get();

            // Apply an impulse toward the hit position
            applyImpulseToward(grid, hitPos, player.isShiftKeyDown() ? DEFAULT_IMPULSE_STRENGTH * 3 : DEFAULT_IMPULSE_STRENGTH);

            // Notify the player
            player.displayClientMessage(Component.literal("Applied impulse to grid!"), true);
            return InteractionResultHolder.success(stack);
        } else {
            player.displayClientMessage(Component.literal("No physics grids found nearby."), true);
            return InteractionResultHolder.fail(stack);
        }
    }

    /**
     * Finds the nearest LocalGrid to a given position.
     */
    private Optional<LocalGrid> findNearestGrid(Level world, Vec3 position) {
        if (!(world instanceof ServerLevel)) {
            return Optional.empty();
        }

        ServerLevel serverWorld = (ServerLevel) world;
        PhysicsEngine engine = engineManager.getEngine(serverWorld);

        if (engine == null) {
            SLogger.log(this, "No physics engine found for world");
            return Optional.empty();
        }

        Set<LocalGrid> grids = engine.getGrids();

        // Find the nearest grid by comparing distances to their centers
        return grids.stream()
                .min(Comparator.comparingDouble(grid -> {
                    RigidBody body = grid.getRigidBody();
                    if (body == null) return Double.MAX_VALUE;

                    Vector3f center = new Vector3f();
                    body.getCenterOfMassPosition(center);

                    double dx = center.x - position.x;
                    double dy = center.y - position.y;
                    double dz = center.z - position.z;

                    return dx*dx + dy*dy + dz*dz;
                }));
    }

    /**
     * Applies an impulse to a grid, pulling it toward the specified position.
     */
    private void applyImpulseToward(LocalGrid grid, Vec3 targetPos, float strength) {
        RigidBody body = grid.getRigidBody();
        if (body == null) return;

        // Get the grid's current position
        Vector3f currentPos = new Vector3f();
        body.getCenterOfMassPosition(currentPos);

        // Calculate direction vector from grid to target
        Vector3f impulseDir = new Vector3f(
                (float)(targetPos.x - currentPos.x),
                (float)(targetPos.y - currentPos.y),
                (float)(targetPos.z - currentPos.z)
        );

        // Normalize and scale by strength
        float length = impulseDir.length();
        if (length > 0.001f) {
            impulseDir.scale(strength / length);

            // Apply central impulse to the rigid body
            body.applyCentralImpulse(impulseDir);

            // Wake up the body to ensure it responds to the impulse
            body.activate(true);

            SLogger.log(this, "Applied impulse: " + impulseDir + " to grid at " + currentPos);
        }
    }

    @Override
    public Multimap<Attribute, AttributeModifier> getAttributeModifiers(ItemStack stack, EquipmentSlot slot) {
        return super.getAttributeModifiers(stack, slot);
    }

    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return false;
    }
}