package com.seailz.csdt.client.mixins;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException;
import com.seailz.csdt.client.service.Mc307387FixService;
import com.seailz.csdt.client.service.ShaderDebugSourceService;
import org.lwjgl.vulkan.VK12;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(GlslCompiler.class)
public abstract class GlslCompilerMixin {

    @Inject(method = "addToBindGroup", at = @At("HEAD"), cancellable = true)
    private static void csdt$acceptAllShaderUniforms(
            List<VulkanBindGroupLayout.Entry> entries,
            IntermediaryShaderModule module,
            RenderPipeline pipeline,
            CallbackInfo ci
    ) throws ShaderCompileException {
        if (!Mc307387FixService.isEnabled()) {
            return;
        }

        Mc307387FixService.addShaderDefinedResources(entries, module, pipeline);
        ci.cancel();
    }

    @Inject(method = "compile", at = @At("RETURN"), cancellable = true)
    private void csdt$appendShaderDebugBinding(
            VulkanDevice device,
            RenderPipeline pipeline,
            IntermediaryShaderModule vertex,
            IntermediaryShaderModule fragment,
            CallbackInfoReturnable<GlslCompiler.CompiledModules> cir
    ) {
        if (ShaderDebugSourceService.slotCapacity() <= 0) {
            return;
        }

        GlslCompiler.CompiledModules compiledModules = cir.getReturnValue();
        VulkanBindGroupLayout existingLayout = compiledModules.layout();
        if (existingLayout.entries().stream().anyMatch(entry -> ShaderDebugSourceService.DEBUG_BUFFER_NAME.equals(entry.name()))) {
            return;
        }

        ArrayList<VulkanBindGroupLayout.Entry> entries = new ArrayList<>(existingLayout.entries());
        entries.add(new VulkanBindGroupLayout.Entry(
                VulkanBindGroupLayout.VulkanBindGroupEntryType.UNIFORM_BUFFER,
                ShaderDebugSourceService.DEBUG_BUFFER_NAME,
                null
        ));

        VulkanBindGroupLayout debugLayout = VulkanBindGroupLayout.create(device, entries, pipeline.getLocation() + " (CSDT dbg)");
        if (existingLayout.handle() != 0L) {
            VK12.vkDestroyDescriptorSetLayout(device.vkDevice(), existingLayout.handle(), null);
        }
        cir.setReturnValue(new GlslCompiler.CompiledModules(
                compiledModules.vertex(),
                compiledModules.fragment(),
                debugLayout
        ));
    }
}
