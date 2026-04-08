package com.seailz.csdt.client.mixins;

import com.seailz.csdt.client.service.ShaderDebugRuntimeService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder")
public abstract class GlCommandEncoderMixin {

    @Inject(method = "trySetup", at = @At("TAIL"))
    private void csdt$bindShaderDebugStorage(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            ShaderDebugRuntimeService.bindStorageBuffer();
        }
    }
}
