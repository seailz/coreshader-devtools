package com.seailz.csdt.client.mixins;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.GpuDevice;
import com.seailz.csdt.client.service.UniformInspectorService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;
import java.util.function.Supplier;

@Mixin(GpuDevice.class)
public abstract class GpuDeviceMixin {

    @Inject(method = "createBuffer(Ljava/util/function/Supplier;ILjava/nio/ByteBuffer;)Lcom/mojang/blaze3d/buffers/GpuBuffer;", at = @At("RETURN"))
    private void csdt$recordUniformInspectorInitialBuffer(
            Supplier<String> label,
            int usage,
            ByteBuffer source,
            CallbackInfoReturnable<GpuBuffer> cir
    ) {
        UniformInspectorService.recordCreatedBuffer(cir.getReturnValue(), source);
    }
}
