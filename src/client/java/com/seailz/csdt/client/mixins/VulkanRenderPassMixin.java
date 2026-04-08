package com.seailz.csdt.client.mixins;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import com.seailz.csdt.client.service.ShaderDebugRuntimeService;
import com.seailz.csdt.client.service.ShaderDebugSourceService;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferViewCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.function.Consumer;

@Mixin(VulkanRenderPass.class)
public abstract class VulkanRenderPassMixin {

    @Shadow
    @Final
    private VulkanDevice device;

    @Shadow
    @Final
    private Consumer<Destroyable> garbageQueue;

    @Shadow
    private boolean anyDescriptorDirty;

    @Shadow
    protected VulkanRenderPipeline pipeline;

    @Shadow
    @Final
    protected HashMap<String, GpuBufferSlice> uniforms;

    @Shadow
    @Final
    protected HashMap<String, Object> textures;

    @Shadow
    protected abstract VkCommandBuffer secondaryCommandBuffer();

    @Inject(method = "pushDescriptors", at = @At("HEAD"), cancellable = true)
    private void csdt$pushShaderDebugDescriptor(CallbackInfo ci) {
        if (this.pipeline == null) {
            return;
        }

        VulkanBindGroupLayout layout = this.pipeline.layout();
        boolean hasDebugEntry = layout.entries().stream().anyMatch(entry -> ShaderDebugSourceService.DEBUG_BUFFER_NAME.equals(entry.name()));
        if (!hasDebugEntry) {
            return;
        }

        if (!this.anyDescriptorDirty) {
            ci.cancel();
            return;
        }

        if (VulkanRenderPass.VALIDATION) {
            for (RenderPipeline.UniformDescription uniform : this.pipeline.info().getUniforms()) {
                GpuBufferSlice value = this.uniforms.get(uniform.name());
                if (value == null) {
                    throw new IllegalStateException("Missing uniform " + uniform.name() + " (should be " + uniform.type() + ")");
                }
                if (uniform.type() == UniformType.UNIFORM_BUFFER) {
                    if (value.buffer().isClosed()) {
                        throw new IllegalStateException("Uniform buffer " + uniform.name() + " is already closed");
                    }
                    if ((value.buffer().usage() & GpuBuffer.USAGE_UNIFORM) == 0) {
                        throw new IllegalStateException("Uniform buffer " + uniform.name() + " must have GpuBuffer.USAGE_UNIFORM");
                    }
                }
                if (uniform.type() != UniformType.TEXEL_BUFFER) {
                    continue;
                }
                if (value.offset() != 0L || value.length() != value.buffer().size()) {
                    throw new IllegalStateException("Uniform texel buffers do not support a slice of a buffer, must be entire buffer");
                }
                if (uniform.gpuFormat() == null) {
                    throw new IllegalStateException("Invalid uniform texel buffer " + uniform.name() + " (missing a texture format)");
                }
            }
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(layout.entries().size(), stack);
            for (int index = 0; index < layout.entries().size(); index++) {
                VulkanBindGroupLayout.Entry entry = layout.entries().get(index);
                VkWriteDescriptorSet set = writes.get(index);
                set.sType$Default();
                set.dstBinding(ShaderDebugSourceService.DEBUG_BUFFER_NAME.equals(entry.name()) ? ShaderDebugSourceService.STORAGE_BINDING : index);
                set.dstArrayElement(0);
                set.descriptorCount(1);

                if (ShaderDebugSourceService.DEBUG_BUFFER_NAME.equals(entry.name())) {
                    GpuBufferSlice buffer = ShaderDebugRuntimeService.storageSlice();
                    if (buffer == null) {
                        throw new IllegalStateException("Shader debug buffer is unavailable for Vulkan descriptors");
                    }
                    VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
                    bufferInfo.buffer(((VulkanGpuBuffer) buffer.buffer()).vkBuffer());
                    bufferInfo.offset(buffer.offset());
                    bufferInfo.range(buffer.length());
                    set.descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
                    set.pBufferInfo(bufferInfo);
                    continue;
                }

                switch (entry.type()) {
                    case UNIFORM_BUFFER -> {
                        GpuBufferSlice buffer = this.uniforms.get(entry.name());
                        if (buffer == null) {
                            throw new IllegalStateException("Missing uniform " + entry.name() + " (should be " + entry.type() + ")");
                        }
                        VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
                        bufferInfo.buffer(((VulkanGpuBuffer) buffer.buffer()).vkBuffer());
                        bufferInfo.offset(buffer.offset());
                        bufferInfo.range(buffer.length());
                        set.descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
                        set.pBufferInfo(bufferInfo);
                    }
                    case SAMPLED_IMAGE -> {
                        Object value = this.textures.get(entry.name());
                        if (value == null) {
                            throw new IllegalStateException("Missing sampler " + entry.name());
                        }
                        VulkanRenderPassTextureBindingAccessor binding = (VulkanRenderPassTextureBindingAccessor) value;
                        VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
                        imageInfo.sampler(binding.csdt$getSampler().vkSampler());
                        imageInfo.imageView(binding.csdt$getView().vkImageView());
                        imageInfo.imageLayout(VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                        set.descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
                        set.pImageInfo(imageInfo);
                    }
                    case TEXEL_BUFFER -> {
                        GpuBufferSlice value = this.uniforms.get(entry.name());
                        if (value == null) {
                            throw new IllegalStateException("Missing uniform " + entry.name() + " (should be " + entry.type() + ")");
                        }
                        LongBuffer bufferViewPtr = stack.mallocLong(1);
                        VkBufferViewCreateInfo viewCreateInfo = VkBufferViewCreateInfo.calloc(stack).sType$Default();
                        viewCreateInfo.buffer(((VulkanGpuBuffer) value.buffer()).vkBuffer());
                        viewCreateInfo.offset(value.offset());
                        viewCreateInfo.range(value.length());
                        if (entry.texelBufferFormat() == null) {
                            throw new IllegalStateException("Texel buffer " + entry.name() + " is missing a GPU format");
                        }
                        viewCreateInfo.format(VulkanConst.toVk(entry.texelBufferFormat()));
                        VulkanUtils.crashIfFailure(
                                VK12.vkCreateBufferView(this.device.vkDevice(), viewCreateInfo, null, bufferViewPtr),
                                "Couldn't create buffer view for texel buffer"
                        );
                        long bufferViewHandle = bufferViewPtr.get(0);
                        this.garbageQueue.accept(() -> VK12.vkDestroyBufferView(this.device.vkDevice(), bufferViewHandle, null));
                        set.descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER);
                        set.pTexelBufferView(bufferViewPtr);
                    }
                }
            }
            KHRPushDescriptor.vkCmdPushDescriptorSetKHR(
                    this.secondaryCommandBuffer(),
                    VK12.VK_PIPELINE_BIND_POINT_GRAPHICS,
                    this.pipeline.pipelineLayout(),
                    0,
                    writes
            );
        }

        this.anyDescriptorDirty = false;
        ci.cancel();
    }
}
