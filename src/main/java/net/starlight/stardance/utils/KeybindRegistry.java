package net.starlight.stardance.utils;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.starlight.stardance.render.CollisionShapeRenderer;
import org.lwjgl.glfw.GLFW;

public class KeybindRegistry {

    private static KeyMapping toggleDebugKey;
    private static KeyMapping toggleDebugRenderingKey;


    public static void registerKeyBindings() {
        toggleDebugKey = new KeyMapping(
                "key.stardance.toggle_debug",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F9,
                "category.stardance"
        );
        toggleDebugRenderingKey = new KeyMapping(
                "key.stardance.toggle_debug_rendering",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F10, // F10 key
                "category.stardance"
        );

        KeyBindingHelper.registerKeyBinding(toggleDebugKey);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleDebugKey.consumeClick()) {
                CollisionShapeRenderer.ENABLED = !CollisionShapeRenderer.ENABLED;
                client.player.displayClientMessage(Component.literal("Debug renderer " + (CollisionShapeRenderer.ENABLED ? "enabled" : "disabled") + "."), true);
            }
        });
    }
}
