package net.starlight.stardance.mixin;

import net.starlight.stardance.utils.SLogger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * VS2-style Mixin Plugin for conditional mixin loading and debugging.
 * Allows us to conditionally enable/disable mixins based on environment or configuration.
 */
public class StardanceMixinPlugin implements IMixinConfigPlugin {

    private static final String MIXIN_PACKAGE = "net.starlight.stardance.mixin";
    
    @Override
    public void onLoad(String mixinPackage) {
        SLogger.log("StardanceMixinPlugin", "Loading Stardance mixins from package: " + mixinPackage);
    }

    @Override
    public String getRefMapperConfig() {
        return null; // Use default refmap configuration
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // Conditional mixin loading logic
        
        // Always apply core mixins
        if (mixinClassName.contains("EntityAccessor")) {
            return true;
        }
        
        // Always apply interaction mixins (our Phase 3 implementation)
        if (mixinClassName.contains("feature.block_interaction")) {
            SLogger.log("StardanceMixinPlugin", "Applying interaction mixin: " + mixinClassName);
            return true;
        }
        
        // Always apply physics mixins  
        if (mixinClassName.contains("physics.MixinEntity")) {
            SLogger.log("StardanceMixinPlugin", "Applying physics mixin: " + mixinClassName);
            return true;
        }
        
        // Debug: Log any mixins we're not explicitly handling
        if (mixinClassName.startsWith(MIXIN_PACKAGE)) {
            SLogger.log("StardanceMixinPlugin", "Applying mixin: " + mixinClassName + " -> " + targetClassName);
            return true;
        }
        
        return true; // Default: apply all Stardance mixins
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        // Log target classes for debugging
        SLogger.log("StardanceMixinPlugin", "Mixin targets: " + myTargets);
    }

    @Override
    public List<String> getMixins() {
        return null; // Use mixins defined in JSON
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // Log mixin application for debugging
        SLogger.log("StardanceMixinPlugin", "Pre-applying mixin: " + mixinClassName + " -> " + targetClassName);
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // Log successful mixin application
        SLogger.log("StardanceMixinPlugin", "Successfully applied mixin: " + mixinClassName + " -> " + targetClassName);
    }
}