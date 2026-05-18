package com.seailz.csdt.client.mixins;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.GpuDevice;
import com.seailz.csdt.client.service.UniformInspectorService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;
import java.util.function.Supplier;
import com.mojang.blaze3d.textures.GpuTexture;

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

    // Sampler inspection needs transfer-source access; vanilla lightmap is otherwise created write/bind-only.
    @ModifyArg(
            method = "createTexture(Ljava/lang/String;ILcom/mojang/blaze3d/GpuFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/GpuDeviceBackend;createTexture(Ljava/lang/String;ILcom/mojang/blaze3d/GpuFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;"
            ),
            index = 1
    )
    private int csdt$allowNamedTextureReadback(int usage) {
        return usage | GpuTexture.USAGE_COPY_SRC;
    }

    @ModifyArg(
            method = "createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/GpuFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/GpuDeviceBackend;createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/GpuFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;"
            ),
            index = 1
    )
    private int csdt$allowSuppliedTextureReadback(int usage) {
        return usage | GpuTexture.USAGE_COPY_SRC;
    }
}
