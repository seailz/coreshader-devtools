package com.seailz.csdt.client.mixins;

import com.mojang.blaze3d.vulkan.VulkanConst;
import com.seailz.csdt.client.service.ShaderDebugRuntimeService;
import org.lwjgl.vulkan.VK12;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VulkanConst.class)
public abstract class VulkanConstMixin {

    @Inject(method = "bufferUsageToVk", at = @At("RETURN"), cancellable = true)
    private static void csdt$addShaderDebugStorageUsage(int usage, CallbackInfoReturnable<Integer> cir) {
        if ((usage & ShaderDebugRuntimeService.VULKAN_STORAGE_USAGE) != 0) {
            cir.setReturnValue(cir.getReturnValue() | VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        }
    }
}
