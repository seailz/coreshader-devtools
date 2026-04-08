package com.seailz.csdt.client.mixins;

import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import com.seailz.csdt.client.service.ShaderDebugSourceService;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.LongBuffer;
import java.util.List;

@Mixin(VulkanBindGroupLayout.class)
public abstract class VulkanBindGroupLayoutMixin {

    @Inject(method = "create", at = @At("HEAD"), cancellable = true)
    private static void csdt$createShaderDebugAwareLayout(
            VulkanDevice device,
            List<VulkanBindGroupLayout.Entry> entries,
            String name,
            CallbackInfoReturnable<VulkanBindGroupLayout> cir
    ) {
        if (entries.stream().noneMatch(entry -> ShaderDebugSourceService.DEBUG_BUFFER_NAME.equals(entry.name()))) {
            return;
        }

        long layoutHandle;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(entries.size(), stack);
            for (int index = 0; index < entries.size(); index++) {
                VulkanBindGroupLayout.Entry entry = entries.get(index);
                boolean debugEntry = ShaderDebugSourceService.DEBUG_BUFFER_NAME.equals(entry.name());
                bindings.get(index)
                        .descriptorType(debugEntry ? VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER : descriptorType(entry))
                        .descriptorCount(1)
                        .binding(debugEntry ? ShaderDebugSourceService.STORAGE_BINDING : index)
                        .stageFlags(VK12.VK_SHADER_STAGE_VERTEX_BIT | VK12.VK_SHADER_STAGE_FRAGMENT_BIT);
            }
            VkDescriptorSetLayoutCreateInfo setCreateInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR)
                    .pBindings(bindings);
            LongBuffer pointer = stack.mallocLong(1);
            VulkanUtils.crashIfFailure(
                    VK12.vkCreateDescriptorSetLayout(device.vkDevice(), setCreateInfo, null, pointer),
                    "Can't set layout for " + name
            );
            layoutHandle = pointer.get(0);
        }

        cir.setReturnValue(new VulkanBindGroupLayout(layoutHandle, entries));
    }

    private static int descriptorType(VulkanBindGroupLayout.Entry entry) {
        return switch (entry.type()) {
            case UNIFORM_BUFFER -> VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            case SAMPLED_IMAGE -> VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            case TEXEL_BUFFER -> VK12.VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER;
        };
    }
}
