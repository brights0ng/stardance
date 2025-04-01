package net.stardance.physics.entity;

import com.bulletphysics.collision.broadphase.Dispatcher;
import com.bulletphysics.collision.dispatch.CollisionObject;
import com.bulletphysics.collision.narrowphase.ManifoldPoint;
import com.bulletphysics.collision.narrowphase.PersistentManifold;
import com.bulletphysics.dynamics.DynamicsWorld;
import com.bulletphysics.linearmath.Transform;
import net.minecraft.entity.Entity;
import net.stardance.core.LocalGrid;
import net.stardance.utils.ILoggingControl;
import net.stardance.utils.SLogger;

import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a single contact point between an entity and another object.
 */
public class Contact {
    private final Entity entity;
    private final Object collidedWith;
    private final Vector3f contactPoint;
    private final Vector3f contactNormal;
    private final float penetrationDepth;
    private final Vector3f relativeVelocity;

    /**
     * Creates a new Contact instance.
     *
     * @param entity The entity involved in the contact
     * @param collidedWith The object the entity collided with
     * @param contactPoint Point in world space where contact occurred
     * @param contactNormal Normal vector pointing from the contact surface
     * @param penetrationDepth How deeply the objects are intersecting
     * @param relativeVelocity Relative velocity at the contact point
     */
    public Contact(Entity entity, Object collidedWith, Vector3f contactPoint,
                   Vector3f contactNormal, float penetrationDepth,
                   Vector3f relativeVelocity) {
        this.entity = entity;
        this.collidedWith = collidedWith;
        this.contactPoint = new Vector3f(contactPoint);
        this.contactNormal = new Vector3f(contactNormal);
        this.penetrationDepth = penetrationDepth;
        this.relativeVelocity = new Vector3f(relativeVelocity);
    }

    public Entity getEntity() {
        return entity;
    }

    public Object getCollidedWith() {
        return collidedWith;
    }

    public Vector3f getContactPoint() {
        return new Vector3f(contactPoint);
    }

    public Vector3f getContactNormal() {
        return new Vector3f(contactNormal);
    }

    public float getPenetrationDepth() {
        return penetrationDepth;
    }

    public Vector3f getRelativeVelocity() {
        return new Vector3f(relativeVelocity);
    }

    /**
     * Checks if this contact is with a LocalGrid.
     */
    public boolean isGridContact() {
        return collidedWith instanceof LocalGrid;
    }

    /**
     * Gets the collided grid, if this is a grid contact.
     *
     * @return The LocalGrid, or null if not a grid contact
     */
    public LocalGrid getGrid() {
        return isGridContact() ? (LocalGrid) collidedWith : null;
    }

    /**
     * Calculates the grid's velocity at the contact point.
     *
     * @return Velocity vector, or zero vector if not a grid contact
     */
    public Vector3f getGridVelocityAtContactPoint() {
        if (!isGridContact()) {
            return new Vector3f(0, 0, 0);
        }

        LocalGrid grid = getGrid();
        if (grid.getRigidBody() == null) {
            return new Vector3f(0, 0, 0);
        }

        // Get grid's velocity
        Vector3f linearVel = new Vector3f();
        Vector3f angularVel = new Vector3f();
        grid.getRigidBody().getLinearVelocity(linearVel);
        grid.getRigidBody().getAngularVelocity(angularVel);

        // Calculate contact point relative to grid center
        Transform gridTransform = new Transform();
        grid.getRigidBody().getWorldTransform(gridTransform);

        Vector3f gridCenter = new Vector3f();
        gridTransform.origin.get(gridCenter);

        Vector3f relativePos = new Vector3f();
        relativePos.sub(contactPoint, gridCenter);

        // Calculate velocity at contact point (v = linear + angular × r)
        Vector3f velocityAtPoint = new Vector3f(linearVel);
        Vector3f angularComponent = new Vector3f();
        angularComponent.cross(angularVel, relativePos);
        velocityAtPoint.add(angularComponent);

        return velocityAtPoint;
    }

    @Override
    public String toString() {
        String collidedWithStr = isGridContact() ?
                "Grid[" + getGrid().getGridId() + "]" :
                collidedWith.getClass().getSimpleName();

        return "Contact{" +
                "entity=" + entity.getType().getName().getString() +
                ", collidedWith=" + collidedWithStr +
                ", normal=" + contactNormal +
                ", depth=" + penetrationDepth +
                '}';
    }
}
