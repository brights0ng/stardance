package net.starlight.stardance.core;

import com.bulletphysics.collision.shapes.CollisionShape;
import com.bulletphysics.collision.shapes.CompoundShapeChild;
import com.bulletphysics.linearmath.Transform;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

/*
    Defines LocalBlock data
 */

public class LocalBlock {
    private final BlockPos position;
    private final BlockState state;
    private final float mass;
    private CollisionShape collisionShape;
    private Transform transform;
    private CompoundShapeChild compoundShapeChild; // Add this field

    public LocalBlock(BlockPos position, BlockState state) {
        this.position = position;
        this.state = state;
        this.mass = 50.0f; // Default mass
    }

    // Getters and setters
    public CollisionShape getCollisionShape() {
        return collisionShape;
    }

    public void setCollisionShape(CollisionShape collisionShape) {
        this.collisionShape = collisionShape;
    }

    public Transform getTransform() {
        return transform;
    }

    public void setTransform(Transform transform) {
        this.transform = transform;
    }

    public CompoundShapeChild getCompoundShapeChild() {
        return compoundShapeChild;
    }

    public void setCompoundShapeChild(CompoundShapeChild compoundShapeChild) {
        this.compoundShapeChild = compoundShapeChild;
    }

    public BlockPos getPosition() {
        return position;
    }

    public BlockState getState() {
        return state;
    }

    public float getMass() {
        return mass;
    }
}
