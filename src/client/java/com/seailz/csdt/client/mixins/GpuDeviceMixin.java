package com.seailz.csdt.client.mixins;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(GpuDevice.class)
public abstract class GpuDeviceMixin {

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
