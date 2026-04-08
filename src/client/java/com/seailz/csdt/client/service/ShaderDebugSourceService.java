package com.seailz.csdt.client.service;

import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShaderDebugSourceService {

    public static final int STORAGE_BINDING = 31;
    public static final String DEBUG_BUFFER_NAME = "CSDT_DebugBuffer";
    public static final int MAX_MESSAGE_LENGTH = 192;
    public static final int SLOT_WORDS = 3 + MAX_MESSAGE_LENGTH;
    private static final int VULKAN_MIN_GLSL_VERSION = 430;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String UNSUPPORTED_REPLACEMENT = "do { } while(false)";
    private static final Pattern VERSION_PATTERN = Pattern.compile("(?m)^\\s*#version\\s+(\\d+)([^\\n\\r]*)");

    private static final Map<Integer, DebugSite> SITES_BY_ID = new HashMap<>();
    private static final Map<String, List<Integer>> SITE_IDS_BY_SHADER = new HashMap<>();

    private static int nextSiteId;
    private static int generation;
    private static boolean warnedUnsupportedBackend;
    private static boolean warnedMissingVersion;
    private static boolean warnedNonAsciiLiteral;
    private static boolean warnedUnsupportedStringExpression;

    private ShaderDebugSourceService() {
    }

    public static synchronized void beginReload() {
        SITES_BY_ID.clear();
        SITE_IDS_BY_SHADER.clear();
        nextSiteId = 0;
        generation++;
        warnedUnsupportedBackend = false;
        warnedMissingVersion = false;
        warnedNonAsciiLiteral = false;
        warnedUnsupportedStringExpression = false;
    }

    public static synchronized int generation() {
        return generation;
    }

    public static synchronized int slotCapacity() {
        if (SITES_BY_ID.isEmpty()) {
            return 0;
        }
        return SITES_BY_ID.keySet().stream().max(Integer::compareTo).orElse(-1) + 1;
    }

    public static synchronized List<DebugSite> snapshotSites() {
        return SITES_BY_ID.values().stream()
                .sorted(Comparator.comparingInt(DebugSite::id))
                .toList();
    }

    public static synchronized String transformShaderSource(Identifier location, ShaderType type, String source) {
        List<DbgCall> calls = findDbgCalls(source);
        if (calls.isEmpty()) {
            clearSites(shaderKey(location, type));
            return source;
        }

        if (!source.contains("#version")) {
            clearSites(shaderKey(location, type));
            if (!warnedMissingVersion) {
                warnedMissingVersion = true;
                LOGGER.warn("Shader debug hooks require a #version line; dbg() will be stripped until that is fixed");
            }
            return replaceCalls(source, calls, unused -> UNSUPPORTED_REPLACEMENT);
        }

        BackendMode backendMode = currentBackendMode();
        if (!backendMode.supportsDebug) {
            clearSites(shaderKey(location, type));
            if (!warnedUnsupportedBackend) {
                warnedUnsupportedBackend = true;
                LOGGER.warn("Shader dbg() is only active on the OpenGL and Vulkan backends. Calls will be stripped on {}", backendMode.label);
            }
            return replaceCalls(source, calls, unused -> UNSUPPORTED_REPLACEMENT);
        }

        List<Integer> previousIds = new ArrayList<>(SITE_IDS_BY_SHADER.getOrDefault(shaderKey(location, type), List.of()));
        List<Integer> activeIds = new ArrayList<>(calls.size());
        for (int i = 0; i < calls.size(); i++) {
            int siteId = i < previousIds.size() ? previousIds.get(i) : nextSiteId++;
            DbgCall call = calls.get(i);
            activeIds.add(siteId);
            SITES_BY_ID.put(siteId, new DebugSite(siteId, location, type, call.line()));
        }
        for (int i = calls.size(); i < previousIds.size(); i++) {
            SITES_BY_ID.remove(previousIds.get(i));
        }
        SITE_IDS_BY_SHADER.put(shaderKey(location, type), activeIds);

        String rewritten = replaceCalls(source, calls, call -> buildReplacement(activeIds.get(call.index()), call.expression(), call.line(), location));
        if (backendMode == BackendMode.VULKAN) {
            rewritten = promoteVersionForVulkan(rewritten);
        }
        return injectHelpers(rewritten, backendMode);
    }

    private static String replaceCalls(String source, List<DbgCall> calls, ReplacementBuilder replacementBuilder) {
        StringBuilder builder = new StringBuilder(source.length() + (calls.size() * 256));
        int cursor = 0;
        for (DbgCall call : calls) {
            builder.append(source, cursor, call.start());
            builder.append(replacementBuilder.build(call));
            cursor = call.end();
        }
        builder.append(source, cursor, source.length());
        return builder.toString();
    }

    private static String buildReplacement(int siteId, String expression, int line, Identifier location) {
        List<String> appendStatements = buildAppendStatements(expression, line, location);
        StringBuilder builder = new StringBuilder(256 + appendStatements.size() * 64);
        builder.append("do {\n");
        builder.append("    uint csdt_dbg_msg[").append(MAX_MESSAGE_LENGTH).append("];\n");
        builder.append("    int csdt_dbg_len = 0;\n");
        for (String appendStatement : appendStatements) {
            builder.append("    ").append(appendStatement).append('\n');
        }
        builder.append("    csdt_dbg_commit(").append(siteId).append("u, csdt_dbg_msg, csdt_dbg_len);\n");
        builder.append("} while(false)");
        return builder.toString();
    }

    private static List<String> buildAppendStatements(String expression, int line, Identifier location) {
        List<String> appendStatements = new ArrayList<>();
        List<String> parts = concatParts(expression);
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (isStringLiteral(trimmed)) {
                for (int codePoint : decodeAsciiLiteral(trimmed, line, location)) {
                    appendStatements.add("csdt_dbg_push_char(csdt_dbg_msg, csdt_dbg_len, " + codePoint + "u);");
                }
            } else if (trimmed.indexOf('"') >= 0) {
                if (!warnedUnsupportedStringExpression) {
                    warnedUnsupportedStringExpression = true;
                    LOGGER.warn("Unsupported dbg() string expression in {}:{}; raw string usage will be replaced with a placeholder", location, line);
                }
                for (int codePoint : decodeAsciiLiteral("\"<unsupported dbg string expr>\"", line, location)) {
                    appendStatements.add("csdt_dbg_push_char(csdt_dbg_msg, csdt_dbg_len, " + codePoint + "u);");
                }
            } else {
                appendStatements.add("csdt_dbg_append_value(csdt_dbg_msg, csdt_dbg_len, (" + trimmed + "));");
            }
        }
        return appendStatements;
    }

    private static List<String> concatParts(String expression) {
        String unwrapped = unwrapOuterParentheses(expression.trim());
        List<String> parts = splitTopLevelConcat(unwrapped);
        if (parts.size() > 1 && parts.stream().anyMatch(part -> isStringLiteral(part.trim()))) {
            return parts;
        }
        return List.of(unwrapped);
    }

    private static String unwrapOuterParentheses(String expression) {
        String result = expression;
        while (isWrappedInParentheses(result)) {
            result = result.substring(1, result.length() - 1).trim();
        }
        return result;
    }

    private static boolean isWrappedInParentheses(String expression) {
        if (expression.length() < 2 || expression.charAt(0) != '(' || expression.charAt(expression.length() - 1) != ')') {
            return false;
        }
        int depth = 0;
        ScanState state = ScanState.NORMAL;
        for (int index = 0; index < expression.length(); index++) {
            char current = expression.charAt(index);
            char next = index + 1 < expression.length() ? expression.charAt(index + 1) : '\0';
            switch (state) {
                case LINE_COMMENT -> {
                    if (current == '\n') {
                        state = ScanState.NORMAL;
                    }
                }
                case BLOCK_COMMENT -> {
                    if (current == '*' && next == '/') {
                        state = ScanState.NORMAL;
                        index++;
                    }
                }
                case STRING -> {
                    if (current == '\\') {
                        index++;
                    } else if (current == '"') {
                        state = ScanState.NORMAL;
                    }
                }
                case NORMAL -> {
                    if (current == '/' && next == '/') {
                        state = ScanState.LINE_COMMENT;
                        index++;
                        continue;
                    }
                    if (current == '/' && next == '*') {
                        state = ScanState.BLOCK_COMMENT;
                        index++;
                        continue;
                    }
                    if (current == '"') {
                        state = ScanState.STRING;
                        continue;
                    }
                    if (current == '(') {
                        depth++;
                    } else if (current == ')') {
                        depth--;
                        if (depth == 0 && index < expression.length() - 1) {
                            return false;
                        }
                    }
                }
            }
        }
        return depth == 0;
    }

    private static boolean isStringLiteral(String token) {
        return token.length() >= 2 && token.charAt(0) == '"' && token.charAt(token.length() - 1) == '"';
    }

    private static List<Integer> decodeAsciiLiteral(String token, int line, Identifier location) {
        List<Integer> chars = new ArrayList<>();
        for (int index = 1; index < token.length() - 1; index++) {
            char current = token.charAt(index);
            if (current == '\\' && index + 1 < token.length() - 1) {
                char escaped = token.charAt(++index);
                chars.add(switch (escaped) {
                    case '\\' -> (int) '\\';
                    case '"' -> (int) '"';
                    case 'n' -> (int) '\n';
                    case 'r' -> (int) '\r';
                    case 't' -> (int) '\t';
                    case '0' -> 0;
                    default -> (int) escaped;
                });
                continue;
            }
            if (current > 127 && !warnedNonAsciiLiteral) {
                warnedNonAsciiLiteral = true;
                LOGGER.warn("Non-ASCII dbg() string content in {}:{} will be replaced with '?'", location, line);
            }
            chars.add(current > 127 ? (int) '?' : (int) current);
        }
        return chars;
    }

    private static List<String> splitTopLevelConcat(String expression) {
        List<String> parts = new ArrayList<>();
        int depthParen = 0;
        int depthBracket = 0;
        int depthBrace = 0;
        int partStart = 0;
        ScanState state = ScanState.NORMAL;
        for (int index = 0; index < expression.length(); index++) {
            char current = expression.charAt(index);
            char next = index + 1 < expression.length() ? expression.charAt(index + 1) : '\0';
            switch (state) {
                case LINE_COMMENT -> {
                    if (current == '\n') {
                        state = ScanState.NORMAL;
                    }
                }
                case BLOCK_COMMENT -> {
                    if (current == '*' && next == '/') {
                        state = ScanState.NORMAL;
                        index++;
                    }
                }
                case STRING -> {
                    if (current == '\\') {
                        index++;
                    } else if (current == '"') {
                        state = ScanState.NORMAL;
                    }
                }
                case NORMAL -> {
                    if (current == '/' && next == '/') {
                        state = ScanState.LINE_COMMENT;
                        index++;
                        continue;
                    }
                    if (current == '/' && next == '*') {
                        state = ScanState.BLOCK_COMMENT;
                        index++;
                        continue;
                    }
                    if (current == '"') {
                        state = ScanState.STRING;
                        continue;
                    }
                    if (current == '(') {
                        depthParen++;
                        continue;
                    }
                    if (current == ')') {
                        depthParen--;
                        continue;
                    }
                    if (current == '[') {
                        depthBracket++;
                        continue;
                    }
                    if (current == ']') {
                        depthBracket--;
                        continue;
                    }
                    if (current == '{') {
                        depthBrace++;
                        continue;
                    }
                    if (current == '}') {
                        depthBrace--;
                        continue;
                    }
                    if (current == '+' && next != '+' && next != '=' && depthParen == 0 && depthBracket == 0 && depthBrace == 0) {
                        parts.add(expression.substring(partStart, index));
                        partStart = index + 1;
                    }
                }
            }
        }
        parts.add(expression.substring(partStart));
        return parts;
    }

    private static List<DbgCall> findDbgCalls(String source) {
        List<DbgCall> calls = new ArrayList<>();
        ScanState state = ScanState.NORMAL;
        int line = 1;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            if (current == '\n') {
                line++;
            }
            switch (state) {
                case LINE_COMMENT -> {
                    if (current == '\n') {
                        state = ScanState.NORMAL;
                    }
                    continue;
                }
                case BLOCK_COMMENT -> {
                    if (current == '*' && next == '/') {
                        state = ScanState.NORMAL;
                        index++;
                    }
                    continue;
                }
                case STRING -> {
                    if (current == '\\') {
                        index++;
                    } else if (current == '"') {
                        state = ScanState.NORMAL;
                    }
                    continue;
                }
                case NORMAL -> {
                    if (current == '/' && next == '/') {
                        state = ScanState.LINE_COMMENT;
                        index++;
                        continue;
                    }
                    if (current == '/' && next == '*') {
                        state = ScanState.BLOCK_COMMENT;
                        index++;
                        continue;
                    }
                    if (current == '"') {
                        state = ScanState.STRING;
                        continue;
                    }
                    if (!matchesDbgToken(source, index)) {
                        continue;
                    }
                    int openParen = skipWhitespace(source, index + 3);
                    if (openParen >= source.length() || source.charAt(openParen) != '(') {
                        continue;
                    }
                    int closeParen = findMatchingParen(source, openParen);
                    if (closeParen < 0) {
                        continue;
                    }
                    calls.add(new DbgCall(calls.size(), index, closeParen + 1, source.substring(openParen + 1, closeParen), line));
                    index = closeParen;
                }
            }
        }
        return calls;
    }

    private static boolean matchesDbgToken(String source, int index) {
        if (index + 3 > source.length() || !source.startsWith("dbg", index)) {
            return false;
        }
        char previous = index > 0 ? source.charAt(index - 1) : '\0';
        char next = index + 3 < source.length() ? source.charAt(index + 3) : '\0';
        return !Character.isLetterOrDigit(previous) && previous != '_' && !Character.isLetterOrDigit(next) && next != '_';
    }

    private static int skipWhitespace(String source, int index) {
        int cursor = index;
        while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private static int findMatchingParen(String source, int openParen) {
        int depth = 1;
        ScanState state = ScanState.NORMAL;
        for (int index = openParen + 1; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            switch (state) {
                case LINE_COMMENT -> {
                    if (current == '\n') {
                        state = ScanState.NORMAL;
                    }
                }
                case BLOCK_COMMENT -> {
                    if (current == '*' && next == '/') {
                        state = ScanState.NORMAL;
                        index++;
                    }
                }
                case STRING -> {
                    if (current == '\\') {
                        index++;
                    } else if (current == '"') {
                        state = ScanState.NORMAL;
                    }
                }
                case NORMAL -> {
                    if (current == '/' && next == '/') {
                        state = ScanState.LINE_COMMENT;
                        index++;
                        continue;
                    }
                    if (current == '/' && next == '*') {
                        state = ScanState.BLOCK_COMMENT;
                        index++;
                        continue;
                    }
                    if (current == '"') {
                        state = ScanState.STRING;
                        continue;
                    }
                    if (current == '(') {
                        depth++;
                    } else if (current == ')') {
                        depth--;
                        if (depth == 0) {
                            return index;
                        }
                    }
                }
            }
        }
        return -1;
    }

    private static String injectHelpers(String source, BackendMode backendMode) {
        int versionIndex = source.indexOf("#version");
        int insertIndex = findHelperInsertionPoint(source, versionIndex);
        int lineNumber = countLines(source, insertIndex);
        return source.substring(0, insertIndex)
                + debugHelpers(backendMode)
                + "\n#line " + lineNumber + '\n'
                + source.substring(insertIndex);
    }

    private static int findHelperInsertionPoint(String source, int versionIndex) {
        int cursor = source.indexOf('\n', versionIndex);
        if (cursor < 0) {
            return source.length();
        }
        cursor++;
        while (cursor < source.length()) {
            int next = consumeWhitespaceAndComments(source, cursor);
            if (startsWithDirective(source, next, "#extension")) {
                cursor = consumeLine(source, next);
                continue;
            }
            return next;
        }
        return cursor;
    }

    private static int consumeWhitespaceAndComments(String source, int start) {
        int cursor = start;
        boolean advanced;
        do {
            advanced = false;
            while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) {
                cursor++;
                advanced = true;
            }
            if (cursor + 1 < source.length() && source.charAt(cursor) == '/' && source.charAt(cursor + 1) == '/') {
                cursor = consumeLine(source, cursor);
                advanced = true;
            } else if (cursor + 1 < source.length() && source.charAt(cursor) == '/' && source.charAt(cursor + 1) == '*') {
                cursor += 2;
                while (cursor + 1 < source.length() && !(source.charAt(cursor) == '*' && source.charAt(cursor + 1) == '/')) {
                    cursor++;
                }
                cursor = Math.min(source.length(), cursor + 2);
                advanced = true;
            }
        } while (advanced);
        return cursor;
    }

    private static boolean startsWithDirective(String source, int index, String directive) {
        return index >= 0 && index + directive.length() <= source.length() && source.startsWith(directive, index);
    }

    private static int consumeLine(String source, int index) {
        int nextLine = source.indexOf('\n', index);
        return nextLine < 0 ? source.length() : nextLine + 1;
    }

    private static int countLines(String source, int endExclusive) {
        int line = 1;
        for (int index = 0; index < endExclusive && index < source.length(); index++) {
            if (source.charAt(index) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String debugHelpers(BackendMode backendMode) {
        StringBuilder builder = new StringBuilder(8192);
        if (backendMode == BackendMode.OPENGL) {
            builder.append("#extension GL_ARB_shader_storage_buffer_object : require\n\n");
        }
        builder.append("const int CSDT_DBG_MAX_LEN_I = ").append(MAX_MESSAGE_LENGTH).append(";\n");
        builder.append("const uint CSDT_DBG_MAX_LEN = ").append(MAX_MESSAGE_LENGTH).append("u;\n");
        builder.append("const uint CSDT_DBG_SLOT_WORDS = ").append(SLOT_WORDS).append("u;\n");
        builder.append("const uint CSDT_DBG_LENGTH_OFFSET = 2u;\n");
        builder.append("const uint CSDT_DBG_CHAR_OFFSET = 3u;\n\n");
        builder.append("layout(std430, binding = ").append(STORAGE_BINDING).append(") buffer ").append(DEBUG_BUFFER_NAME).append(" {\n");
        builder.append("    uint csdt_dbg_data[];\n");
        builder.append("};\n\n");
        builder.append("""
void csdt_dbg_push_char(inout uint message[CSDT_DBG_MAX_LEN_I], inout int length, uint ch) {
    if (length >= CSDT_DBG_MAX_LEN_I) {
        return;
    }
    message[length] = ch & 127u;
    length++;
}

void csdt_dbg_append_uint(inout uint message[CSDT_DBG_MAX_LEN_I], inout int length, uint value) {
    if (value == 0u) {
        csdt_dbg_push_char(message, length, 48u);
        return;
    }
    uint digits[10];
    int digitCount = 0;
    while (value > 0u && digitCount < 10) {
        digits[digitCount] = value % 10u;
        value /= 10u;
        digitCount++;
    }
    for (int index = digitCount - 1; index >= 0; index--) {
        csdt_dbg_push_char(message, length, 48u + digits[index]);
    }
}

void csdt_dbg_append_int(inout uint message[CSDT_DBG_MAX_LEN_I], inout int length, int value) {
    if (value < 0) {
        csdt_dbg_push_char(message, length, 45u);
    }
    uint magnitude = value < 0 ? uint(-(value + 1)) + 1u : uint(value);
    csdt_dbg_append_uint(message, length, magnitude);
}

void csdt_dbg_append_float(inout uint message[CSDT_DBG_MAX_LEN_I], inout int length, float value) {
    if (isnan(value)) {
        csdt_dbg_push_char(message, length, 110u);
        csdt_dbg_push_char(message, length, 97u);
        csdt_dbg_push_char(message, length, 110u);
        return;
    }
    if (isinf(value)) {
        if (value < 0.0) {
            csdt_dbg_push_char(message, length, 45u);
        }
        csdt_dbg_push_char(message, length, 105u);
        csdt_dbg_push_char(message, length, 110u);
        csdt_dbg_push_char(message, length, 102u);
        return;
    }
    if (value < 0.0) {
        csdt_dbg_push_char(message, length, 45u);
        value = -value;
    }
    value = floor(value * 10000.0 + 0.5) / 10000.0;
    uint whole = uint(floor(value));
    float fractional = value - float(whole);
    csdt_dbg_append_uint(message, length, whole);
    csdt_dbg_push_char(message, length, 46u);
    int fractionalStart = length;
    for (int index = 0; index < 4; index++) {
        fractional *= 10.0;
        uint digit = uint(floor(fractional + 0.0001));
        csdt_dbg_push_char(message, length, 48u + digit);
        fractional -= float(digit);
    }
    while (length > fractionalStart + 1 && message[length - 1] == 48u) {
        length--;
    }
}

void csdt_dbg_append_value(inout uint message[CSDT_DBG_MAX_LEN_I], inout int length, bool value) {
    if (value) {
        csdt_dbg_push_char(message, length, 116u);
        csdt_dbg_push_char(message, length, 114u);
        csdt_dbg_push_char(message, length, 117u);
        csdt_dbg_push_char(message, length, 101u);
    } else {
        csdt_dbg_push_char(message, length, 102u);
        csdt_dbg_push_char(message, length, 97u);
        csdt_dbg_push_char(message, length, 108u);
        csdt_dbg_push_char(message, length, 115u);
        csdt_dbg_push_char(message, length, 101u);
    }
}

void csdt_dbg_append_value(inout uint message[CSDT_DBG_MAX_LEN_I], inout int length, int value) { csdt_dbg_append_int(message, length, value); }
void csdt_dbg_append_value(inout uint message[CSDT_DBG_MAX_LEN_I], inout int length, uint value) { csdt_dbg_append_uint(message, length, value); }
void csdt_dbg_append_value(inout uint message[CSDT_DBG_MAX_LEN_I], inout int length, float value) { csdt_dbg_append_float(message, length, value); }

void csdt_dbg_wrap_open(inout uint message[CSDT_DBG_MAX_LEN_I], inout int length) { csdt_dbg_push_char(message, length, 40u); }
void csdt_dbg_wrap_close(inout uint message[CSDT_DBG_MAX_LEN_I], inout int length) { csdt_dbg_push_char(message, length, 41u); }
void csdt_dbg_comma(inout uint message[CSDT_DBG_MAX_LEN_I], inout int length) {
    csdt_dbg_push_char(message, length, 44u);
    csdt_dbg_push_char(message, length, 32u);
}

""");
        appendVectorAppender(builder, "vec2", "csdt_dbg_append_float", 2);
        appendVectorAppender(builder, "vec3", "csdt_dbg_append_float", 3);
        appendVectorAppender(builder, "vec4", "csdt_dbg_append_float", 4);
        appendVectorAppender(builder, "ivec2", "csdt_dbg_append_int", 2);
        appendVectorAppender(builder, "ivec3", "csdt_dbg_append_int", 3);
        appendVectorAppender(builder, "ivec4", "csdt_dbg_append_int", 4);
        appendVectorAppender(builder, "uvec2", "csdt_dbg_append_uint", 2);
        appendVectorAppender(builder, "uvec3", "csdt_dbg_append_uint", 3);
        appendVectorAppender(builder, "uvec4", "csdt_dbg_append_uint", 4);
        appendVectorAppender(builder, "bvec2", "csdt_dbg_append_value", 2);
        appendVectorAppender(builder, "bvec3", "csdt_dbg_append_value", 3);
        appendVectorAppender(builder, "bvec4", "csdt_dbg_append_value", 4);
        builder.append("""
void csdt_dbg_commit(uint site, uint message[CSDT_DBG_MAX_LEN_I], int length) {
    uint base = site * CSDT_DBG_SLOT_WORDS;
    if (csdt_dbg_data[base + 1u] != 0u) {
        return;
    }
    if (atomicCompSwap(csdt_dbg_data[base + 1u], 0u, 1u) != 0u) {
        return;
    }
    uint clampedLength = uint(clamp(length, 0, CSDT_DBG_MAX_LEN_I));
    csdt_dbg_data[base + CSDT_DBG_LENGTH_OFFSET] = clampedLength;
    for (int index = 0; index < CSDT_DBG_MAX_LEN_I; index++) {
        csdt_dbg_data[base + CSDT_DBG_CHAR_OFFSET + uint(index)] = index < length ? message[index] : 0u;
    }
    memoryBarrierBuffer();
    csdt_dbg_data[base] = csdt_dbg_data[base] + 1u;
    memoryBarrierBuffer();
}
""");
        return builder.toString();
    }

    private static String promoteVersionForVulkan(String source) {
        Matcher matcher = VERSION_PATTERN.matcher(source);
        if (!matcher.find()) {
            return source;
        }

        int version = Integer.parseInt(matcher.group(1));
        if (version >= VULKAN_MIN_GLSL_VERSION) {
            return source;
        }

        String replacement = "#version " + VULKAN_MIN_GLSL_VERSION + matcher.group(2);
        return source.substring(0, matcher.start()) + replacement + source.substring(matcher.end());
    }

    private static void appendVectorAppender(StringBuilder builder, String type, String scalarAppender, int size) {
        String[] components = {"x", "y", "z", "w"};
        builder.append("void csdt_dbg_append_value(inout uint message[CSDT_DBG_MAX_LEN_I], inout int length, ")
                .append(type)
                .append(" value) {\n");
        builder.append("    csdt_dbg_wrap_open(message, length);\n");
        for (int index = 0; index < size; index++) {
            if (index > 0) {
                builder.append("    csdt_dbg_comma(message, length);\n");
            }
            builder.append("    ").append(scalarAppender).append("(message, length, value.").append(components[index]).append(");\n");
        }
        builder.append("    csdt_dbg_wrap_close(message, length);\n");
        builder.append("}\n\n");
    }

    private static void clearSites(String shaderKey) {
        List<Integer> removed = SITE_IDS_BY_SHADER.remove(shaderKey);
        if (removed == null) {
            return;
        }
        for (int siteId : removed) {
            SITES_BY_ID.remove(siteId);
        }
    }

    private static String shaderKey(Identifier location, ShaderType type) {
        return type.getName() + "|" + location;
    }

    private static BackendMode currentBackendMode() {
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return BackendMode.UNAVAILABLE;
        }
        String backendName = device.getDeviceInfo().backendName().toLowerCase(Locale.ROOT);
        if ("opengl".equals(backendName)) {
            return BackendMode.OPENGL;
        }
        if ("vulkan".equals(backendName)) {
            return BackendMode.VULKAN;
        }
        return BackendMode.UNSUPPORTED;
    }

    @FunctionalInterface
    private interface ReplacementBuilder {
        String build(DbgCall call);
    }

    private enum BackendMode {
        OPENGL("OpenGL", true),
        VULKAN("Vulkan", true),
        UNSUPPORTED("unsupported backend", false),
        UNAVAILABLE("uninitialized backend", true);

        private final String label;
        private final boolean supportsDebug;

        BackendMode(String label, boolean supportsDebug) {
            this.label = label;
            this.supportsDebug = supportsDebug;
        }
    }

    private enum ScanState {
        NORMAL,
        STRING,
        LINE_COMMENT,
        BLOCK_COMMENT
    }

    private record DbgCall(int index, int start, int end, String expression, int line) {
    }

    public record DebugSite(int id, Identifier location, ShaderType type, int line) {
    }
}
