package com.seailz.csdt.client.mixins;

import com.seailz.csdt.client.service.GlobalsUniformOverrideService;
import com.seailz.csdt.client.service.ShaderDebugRuntimeService;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies any active overrides to the Globals uniform
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GlobalSettingsUniform;update(IIDJLnet/minecraft/client/DeltaTracker;ILnet/minecraft/world/phys/Vec3;Z)V"
            )
    )
    private void csdt$applyGlobalsOverrides(
            GlobalSettingsUniform instance,
            int width,
            int height,
            double glintAlpha,
            long gameTime,
            DeltaTracker deltaTracker,
            int menuBlurRadius,
            Vec3 cameraPos,
            boolean useRgss
    ) {
        GlobalsUniformOverrideService.ResolvedGlobalsUniform resolved = GlobalsUniformOverrideService.resolve(
                width,
                height,
                glintAlpha,
                gameTime,
                deltaTracker,
                menuBlurRadius,
                cameraPos,
                useRgss
        );
        instance.update(
                resolved.width(),
                resolved.height(),
                resolved.glintAlpha(),
                resolved.gameTime(),
                resolved.deltaTracker(),
                resolved.menuBlurRadius(),
                resolved.cameraPos(),
                resolved.useRgss()
        );
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void csdt$pollShaderDebugOutput(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        ShaderDebugRuntimeService.pollAndLogChanges();
    }
}
