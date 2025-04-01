package net.stardance.physics.entity;

import com.bulletphysics.dynamics.DynamicsWorld;
import net.minecraft.entity.Entity;
import net.stardance.physics.entity.Contact;
import net.stardance.physics.entity.ContactDetector;
import net.stardance.physics.entity.EntityCollisionHandler;
import net.stardance.utils.ILoggingControl;
import net.stardance.utils.SLogger;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Collects and caches contact information for entities.
 * Provides a simplified interface for accessing entity contacts.
 */
public class EntityContactCollector implements ILoggingControl {
    // The contact detector that does the actual work
    private final ContactDetector contactDetector;

    // The entity collision handler for reference
    private final EntityCollisionHandler collisionHandler;

    // Cached contacts from the last collection
    private Map<Entity, List<Contact>> cachedContacts = new ConcurrentHashMap<>();

    /**
     * Creates a new EntityContactCollector with the given collision handler.
     */
    public EntityContactCollector(EntityCollisionHandler collisionHandler) {
        this.collisionHandler = collisionHandler;
        this.contactDetector = new ContactDetector(collisionHandler.getDynamicsWorld());
    }

    /**
     * Collects all entity contacts in the physics world.
     * Should be called once per tick after physics simulation.
     */
    public void collectContacts(DynamicsWorld dynamicsWorld) {
        // Collect contacts using the contact detector
        cachedContacts = contactDetector.collectContacts();

        SLogger.log(this, "Collected contacts for " + cachedContacts.size() + " entities");
    }

    /**
     * Gets all contacts for a specific entity.
     */
    public List<Contact> getContactsForEntity(Entity entity) {
        return cachedContacts.getOrDefault(entity, Collections.emptyList());
    }

    /**
     * Gets the number of entities with contacts.
     */
    public int getEntityCount() {
        return cachedContacts.size();
    }

    /**
     * Clears all cached contacts.
     */
    public void clearContacts() {
        cachedContacts.clear();
    }

    /**
     * Helper class to represent a single entity contact.
     * Used for simplified access to contact information.
     */
    public static class EntityContact {
        private final Contact contact;

        public EntityContact(Contact contact) {
            this.contact = contact;
        }

        /**
         * Gets the entity involved in this contact.
         */
        public Entity getEntity() {
            return contact.getEntity();
        }

        /**
         * Gets the object the entity collided with.
         */
        public Object getCollidedWith() {
            return contact.getCollidedWith();
        }

        /**
         * Checks if this contact is with the ground.
         */
        public boolean isGroundContact() {
            return contact.getContactNormal().y > 0.7071f; // cos(45°)
        }

        /**
         * Gets the underlying contact object.
         */
        public Contact getContact() {
            return contact;
        }
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