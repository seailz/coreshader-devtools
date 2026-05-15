package com.seailz.csdt.client.mixins;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.seailz.csdt.client.service.SamplerInspectionService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderPass.class)
public abstract class RenderPassMixin {

    @Inject(method = "setPipeline", at = @At("HEAD"))
    private void csdt$rememberSamplerPipeline(RenderPipeline pipeline, CallbackInfo ci) {
        SamplerInspectionService.rememberPipeline((RenderPass) (Object) this, pipeline);
    }

    @Inject(method = "bindTexture", at = @At("HEAD"))
    private void csdt$captureSamplerBinding(String name, GpuTextureView view, GpuSampler sampler, CallbackInfo ci) {
        SamplerInspectionService.captureBinding((RenderPass) (Object) this, name, view, sampler);
    }

    @Inject(method = "close", at = @At("TAIL"))
    private void csdt$forgetSamplerPipeline(CallbackInfo ci) {
        SamplerInspectionService.forgetRenderPass((RenderPass) (Object) this);
    }
}
