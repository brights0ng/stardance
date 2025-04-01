package net.stardance.utils;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.server.world.ServerWorld;
import net.stardance.Stardance;
import org.slf4j.Logger;

import static net.stardance.Stardance.serverInstance;

public class SLogger {
    // Reference to your mod's logger
    private static final Logger LOGGER = Stardance.LOGGER;

    // Method to log a message based on ILoggingControl
    public static void log(ILoggingControl logger, String message) {
        String logMessage = "[" + logger.getSimpleName() + "] " + message;

        // Log to console if enabled
        if (logger.stardance$isConsoleLoggingEnabled() && !logger.stardance$isChatLoggingEnabled()) {
            LOGGER.info(logMessage);
        }

        // Send to chat if enabled
        if (logger.stardance$isChatLoggingEnabled() & serverInstance != null) {
            for (ServerWorld world : serverInstance.getWorlds()) {
                sendToChat(world, logMessage);
            }        }
    }

    // Method to send a message to all players in the world
    private static void sendToChat(ServerWorld world, String message) {
        Text text = Text.literal(message).formatted(Formatting.YELLOW);

        for (ServerPlayerEntity player : world.getPlayers()) {
            player.sendMessage(text, false);
        }
    }
}
