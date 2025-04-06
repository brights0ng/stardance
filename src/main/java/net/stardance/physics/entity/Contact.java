package net.stardance.physics.entity;

import net.minecraft.entity.Entity;
import net.stardance.core.LocalGrid;

import javax.vecmath.Vector3f;

/**
 * Represents a contact point between an entity and another object (grid or entity).
 * Stores information about the contact for collision resolution.
 */
public class Contact {
    // The entity involved in the contact
    private final Entity entity;

    // What the entity collided with (either grid or entity, not both)
    private final LocalGrid grid;
    private final Entity collidedWith;

    // Contact information
    private final Vector3f contactNormal;
    private final Vector3f contactPoint;
    private final float penetrationDepth;

    // Additional information for grid contacts
    private Vector3f gridVelocityAtContactPoint;

    /**
     * Creates a new Contact between an entity and another object.
     *
     * @param entity The entity involved in the contact
     * @param grid The grid involved in the contact (null if entity-entity contact)
     * @param collidedWith The other entity involved (null if entity-grid contact)
     * @param contactNormal The contact normal (pointing from the object to the entity)
     * @param contactPoint The contact point in world space
     * @param penetrationDepth The penetration depth
     */
    public Contact(Entity entity, LocalGrid grid, Entity collidedWith,
                   Vector3f contactNormal, Vector3f contactPoint, float penetrationDepth) {
        this.entity = entity;
        this.grid = grid;
        this.collidedWith = collidedWith;
        this.contactNormal = new Vector3f(contactNormal);
        this.contactPoint = new Vector3f(contactPoint);
        this.penetrationDepth = penetrationDepth;

        // Ensure normal is normalized
        if (this.contactNormal.lengthSquared() > 0) {
            this.contactNormal.normalize();
        } else {
            // Default to up if normal is zero
            this.contactNormal.set(0, 1, 0);
        }

        // Initialize grid velocity as zero
        this.gridVelocityAtContactPoint = new Vector3f(0, 0, 0);
    }

    /**
     * Gets the entity involved in the contact.
     */
    public Entity getEntity() {
        return entity;
    }

    /**
     * Gets the grid involved in the contact (may be null).
     */
    public LocalGrid getGrid() {
        return grid;
    }

    /**
     * Gets the other entity involved in the contact (may be null).
     */
    public Entity getCollidedWith() {
        return collidedWith;
    }

    /**
     * Gets the contact normal.
     * The normal points from the object to the entity.
     */
    public Vector3f getContactNormal() {
        return new Vector3f(contactNormal);
    }

    /**
     * Gets the contact point in world space.
     */
    public Vector3f getContactPoint() {
        return new Vector3f(contactPoint);
    }

    /**
     * Gets the penetration depth.
     */
    public float getPenetrationDepth() {
        return penetrationDepth;
    }

    /**
     * Sets the grid's velocity at the contact point.
     * Only relevant for entity-grid contacts.
     */
    public void setGridVelocityAtContactPoint(Vector3f velocity) {
        this.gridVelocityAtContactPoint = new Vector3f(velocity);
    }

    /**
     * Gets the grid's velocity at the contact point.
     * Only relevant for entity-grid contacts.
     */
    public Vector3f getGridVelocityAtContactPoint() {
        return new Vector3f(gridVelocityAtContactPoint);
    }

    /**
     * Checks if this is a grid contact.
     */
    public boolean isGridContact() {
        return grid != null;
    }

    /**
     * Checks if this is an entity contact.
     */
    public boolean isEntityContact() {
        return collidedWith != null;
    }

    /**
     * Checks if this contact would count as ground.
     * A contact is considered ground if the normal is pointing upward at a
     * sufficient angle (within 45 degrees of straight up).
     */
    public boolean isGroundContact() {
        // Threshold is cos(45 degrees) ≈ 0.7071
        return contactNormal.y > 0.7071f;
    }

    /**
     * Checks if this contact is a side contact.
     * A side contact has a significant horizontal component.
     */
    public boolean isSideContact() {
        // Check if horizontal component is significant
        float horizontalComponent = (float) Math.sqrt(
                contactNormal.x * contactNormal.x +
                        contactNormal.z * contactNormal.z
        );

        return horizontalComponent > 0.5f;
    }

    /**
     * Checks if this contact is a ceiling contact.
     * A ceiling contact has the normal pointing significantly downward.
     */
    public boolean isCeilingContact() {
        return contactNormal.y < -0.7071f;
    }

    @Override
    public String toString() {
        String objectType = isGridContact() ? "Grid" : "Entity";
        return "Contact{" +
                "with=" + objectType +
                ", normal=" + contactNormal +
                ", depth=" + penetrationDepth +
                ", point=" + contactPoint +
                '}';
    }
}