package com.seailz.csdt.client.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSyntaxException;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.StrictJsonParser;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class ShaderResourceOverrideService {

    private static final FileToIdConverter POST_EFFECT_ID_CONVERTER = FileToIdConverter.json("post_effect");
    private static final Pattern FRAGMENT_OUTPUT_PATTERN = Pattern.compile("out\\s+vec4\\s+(\\w+)\\s*;");
    private static final Pattern MAIN_PATTERN = Pattern.compile("void\\s+main\\s*\\(\\s*\\)\\s*\\{");
    private static final Map<String, String> SELECTED_PACKS = new HashMap<>();
    private static final Set<String> VISUALIZED_FRAGMENT_SHADERS = new HashSet<>();

    private ShaderResourceOverrideService() {
    }

    public static synchronized void selectSource(String resourcePath, String packId) {
        SELECTED_PACKS.put(resourcePath, packId);
    }

    public static synchronized void clearSourceSelection(String resourcePath) {
        SELECTED_PACKS.remove(resourcePath);
    }

    public static synchronized String selectedPack(String resourcePath) {
        return SELECTED_PACKS.get(resourcePath);
    }

    public static synchronized void setVisualized(String resourcePath, boolean visualized) {
        if (visualized) {
            VISUALIZED_FRAGMENT_SHADERS.add(resourcePath);
        } else {
            VISUALIZED_FRAGMENT_SHADERS.remove(resourcePath);
        }
    }

    public static synchronized boolean isVisualized(String resourcePath) {
        return VISUALIZED_FRAGMENT_SHADERS.contains(resourcePath);
    }

    public static synchronized void clearVisualizations() {
        VISUALIZED_FRAGMENT_SHADERS.clear();
    }

    public static synchronized ShaderManager.Configs applyOverrides(ShaderManager.Configs configs, ResourceManager resourceManager) {
        if (SELECTED_PACKS.isEmpty() && VISUALIZED_FRAGMENT_SHADERS.isEmpty()) {
            return configs;
        }

        Map<Object, String> shaderSources = new HashMap<>(configs.shaderSources());
        Map<Identifier, PostChainConfig> postChains = new HashMap<>(configs.postChains());

        Set<String> allPaths = new HashSet<>(SELECTED_PACKS.keySet());
        allPaths.addAll(VISUALIZED_FRAGMENT_SHADERS);
        for (String resourcePath : allPaths) {
            Identifier location = Identifier.parse(resourcePath);
            ShaderInventoryService.ShaderResourceVersion version = selectVersion(resourceManager, location, SELECTED_PACKS.get(resourcePath));
            if (version == null) {
                continue;
            }

            if (location.getPath().startsWith("post_effect/")) {
                applyPostEffectOverride(postChains, location, version);
                continue;
            }

            ShaderType shaderType = ShaderType.byLocation(location);
            if (shaderType == null) {
                continue;
            }

            try {
                String source = loadShaderSource(resourceManager, location, version);
                if (VISUALIZED_FRAGMENT_SHADERS.contains(resourcePath) && shaderType == ShaderType.FRAGMENT) {
                    source = visualizeFragmentShader(source);
                }
                shaderSources.put(shaderSourceKey(shaderType.idConverter().fileToId(location), shaderType), source);
            } catch (IOException ignored) {
            }
        }

        return new ShaderManager.Configs((Map) shaderSources, postChains);
    }

    private static ShaderInventoryService.ShaderResourceVersion selectVersion(ResourceManager resourceManager, Identifier location, String preferredPack) {
        var versions = ShaderInventoryService.listVersionsForLocation(resourceManager, location);
        if (versions.isEmpty()) {
            return null;
        }
        if (preferredPack != null) {
            for (var version : versions) {
                if (preferredPack.equals(version.packId())) {
                    return version;
                }
            }
        }
        return versions.getLast();
    }

    private static void applyPostEffectOverride(Map<Identifier, PostChainConfig> postChains, Identifier location, ShaderInventoryService.ShaderResourceVersion version) {
        try {
            String source = ShaderInventoryService.loadText(version);
            JsonElement json = StrictJsonParser.parse(new StringReader(source));
            PostChainConfig config = PostChainConfig.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow(JsonSyntaxException::new);
            postChains.put(POST_EFFECT_ID_CONVERTER.fileToId(location), config);
        } catch (JsonParseException | IOException ignored) {
        }
    }

    private static String loadShaderSource(ResourceManager resourceManager, Identifier location, ShaderInventoryService.ShaderResourceVersion version) throws IOException {
        String source = ShaderInventoryService.loadText(version);
        Map<Identifier, net.minecraft.server.packs.resources.Resource> shaderResources = resourceManager.listResources("shaders", id ->
                id.getPath().endsWith(".vsh") || id.getPath().endsWith(".fsh") || id.getPath().endsWith(".glsl"));
        try {
            Method method = ShaderManager.class.getDeclaredMethod("createPreprocessor", Map.class, Identifier.class);
            method.setAccessible(true);
            GlslPreprocessor preprocessor = (GlslPreprocessor) method.invoke(null, shaderResources, location);
            ShaderType shaderType = ShaderType.byLocation(location);
            String processed = String.join("", preprocessor.process(source));
            return shaderType == null ? processed : ShaderDebugSourceService.transformShaderSource(location, shaderType, processed);
        } catch (ReflectiveOperationException exception) {
            throw new IOException("Failed to preprocess shader source", exception);
        }
    }

    private static Object shaderSourceKey(Identifier id, ShaderType shaderType) {
        try {
            Class<?> keyClass = Class.forName("net.minecraft.client.renderer.ShaderManager$ShaderSourceKey");
            Constructor<?> constructor = keyClass.getDeclaredConstructor(Identifier.class, ShaderType.class);
            constructor.setAccessible(true);
            return constructor.newInstance(id, shaderType);
        } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException | IllegalAccessException |
                 InvocationTargetException exception) {
            throw new IllegalStateException("Failed to create ShaderSourceKey", exception);
        }
    }

    private static String visualizeFragmentShader(String source) {
        Matcher outputMatcher = FRAGMENT_OUTPUT_PATTERN.matcher(source);
        if (!outputMatcher.find()) {
            return source;
        }

        Matcher mainMatcher = MAIN_PATTERN.matcher(source);
        if (!mainMatcher.find()) {
            return source;
        }

        int bodyStart = mainMatcher.end();
        int depth = 1;
        int index = bodyStart;
        while (index < source.length() && depth > 0) {
            char current = source.charAt(index++);
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
            }
        }

        if (depth != 0) {
            return source;
        }

        String outputName = outputMatcher.group(1);
        String replacementBody = "\n    " + outputName + " = vec4(1.0, 0.0, 0.0, 1.0);\n";
        return source.substring(0, bodyStart) + replacementBody + source.substring(index - 1);
    }
}
