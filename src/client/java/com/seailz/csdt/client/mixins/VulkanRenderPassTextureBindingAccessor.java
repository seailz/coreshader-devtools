package com.seailz.csdt.client.mixins;

import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "com.mojang.blaze3d.vulkan.VulkanRenderPass$TextureViewAndSampler")
public interface VulkanRenderPassTextureBindingAccessor {

    @Accessor("view")
    VulkanGpuTextureView csdt$getView();

    @Accessor("sampler")
    VulkanGpuSampler csdt$getSampler();
}
