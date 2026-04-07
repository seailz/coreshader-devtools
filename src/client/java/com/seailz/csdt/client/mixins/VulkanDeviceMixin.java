package com.seailz.csdt.client.mixins;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(VulkanDevice.class)
public abstract class VulkanDeviceMixin {
    @Shadow
    @Final
    private Map<?, IntermediaryShaderModule> shaderCache;

    @Inject(method = "clearPipelineCache", at = @At("TAIL"))
    private void csdt$clearShaderCache(CallbackInfo ci) {
        this.shaderCache.clear();
    }
}
