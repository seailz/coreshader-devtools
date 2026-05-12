package com.seailz.csdt.client.service;

import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class McpControlServerService {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 34783;

    private static HttpServer server;

    private McpControlServerService() {
    }

    public static synchronized void start() {
        if (server != null) {
            return;
        }

        String host = System.getProperty("csdt.control.host", DEFAULT_HOST);
        int port = Integer.getInteger("csdt.control.port", DEFAULT_PORT);
        try {
            HttpServer httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);
            httpServer.createContext("/health", exchange -> handle(exchange, McpControlServerService::handleHealth));
            httpServer.createContext("/refresh-shaders", exchange -> handle(exchange, McpControlServerService::handleRefreshShaders));
            httpServer.createContext("/screenshot", exchange -> handle(exchange, McpControlServerService::handleScreenshot));
            httpServer.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "Shader DevTools MCP control");
                thread.setDaemon(true);
                return thread;
            }));
            httpServer.start();
            server = httpServer;
            LOGGER.info("Shader DevTools MCP control server listening on http://{}:{}", host, port);
        } catch (Exception exception) {
            LOGGER.error("Failed to start Shader DevTools MCP control server", exception);
        }
    }

    private static void handle(HttpExchange exchange, Handler handler) throws IOException {
        try {
            handler.handle(exchange);
        } catch (Exception exception) {
            LOGGER.error("Shader DevTools MCP control request failed", exception);
            sendJson(exchange, 500, "{\"ok\":false,\"error\":\"" + jsonEscape(exception.toString()) + "\"}");
        } finally {
            exchange.close();
        }
    }

    private static void handleHealth(HttpExchange exchange) throws IOException {
        if (!acceptsGetOrPost(exchange)) {
            sendMethodNotAllowed(exchange);
            return;
        }

        sendJson(exchange, 200, "{\"ok\":true}");
    }

    private static void handleRefreshShaders(HttpExchange exchange) throws IOException {
        if (!acceptsGetOrPost(exchange)) {
            sendMethodNotAllowed(exchange);
            return;
        }

        ShaderReloadService.reloadAllShaders();
        sendJson(exchange, 202, "{\"ok\":true,\"status\":\"queued\"}");
    }

    private static void handleScreenshot(HttpExchange exchange) throws IOException {
        if (!acceptsGetOrPost(exchange)) {
            sendMethodNotAllowed(exchange);
            return;
        }

        try {
            Path path = ScreenshotCaptureService.captureScreenshot().get(30, TimeUnit.SECONDS);
            sendJson(exchange, 200, "{\"ok\":true,\"path\":\"" + jsonEscape(path.toString()) + "\"}");
        } catch (TimeoutException exception) {
            LOGGER.error("Timed out while taking screenshot for MCP request", exception);
            sendJson(exchange, 504, "{\"ok\":false,\"error\":\"Timed out while taking screenshot\"}");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.error("Interrupted while taking screenshot for MCP request", exception);
            sendJson(exchange, 500, "{\"ok\":false,\"error\":\"Interrupted while taking screenshot\"}");
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            LOGGER.error("Failed to take screenshot for MCP request", cause);
            sendJson(exchange, 500, "{\"ok\":false,\"error\":\"" + jsonEscape(cause.toString()) + "\"}");
        } catch (Exception exception) {
            LOGGER.error("Failed to take screenshot for MCP request", exception);
            sendJson(exchange, 500, "{\"ok\":false,\"error\":\"" + jsonEscape(exception.toString()) + "\"}");
        }
    }

    private static boolean acceptsGetOrPost(HttpExchange exchange) {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        return "GET".equals(method) || "POST".equals(method);
    }

    private static void sendMethodNotAllowed(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Allow", "GET, POST");
        sendJson(exchange, 405, "{\"ok\":false,\"error\":\"Method not allowed\"}");
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(bytes);
        }
    }

    private static String jsonEscape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }
}
