package com.bbconnectmod.net;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One outbound bridge connection to the Blockbench plugin.
 *
 * <p>The game dials the plugin, exchanges newline delimited JSON and keeps the socket so
 * later saves can be pushed without another click. This class carries no Minecraft
 * dependency: the game side supplies a {@link Listener} for reloads and user feedback,
 * which also makes the wire protocol testable without a running client.
 */
public final class BridgeSession implements AutoCloseable {
    public static final int PROTOCOL_VERSION = 1;
    public static final String MOD_VERSION = "1.0.0";

    private static final Logger LOGGER = LoggerFactory.getLogger("bbconnectmod");
    private static final Gson GSON = new Gson();
    private static final int CONNECT_TIMEOUT_MS = 4_000;
    private static final Object WRITE_LOCK = new Object();

    /** Callbacks the game side implements; every method must be cheap and thread safe. */
    public interface Listener {
        /** The socket is up. */
        void connected(String address);

        /** The socket could not be opened. */
        void connectionFailed(String address, String detail);

        /** The plugin accepted the handshake. */
        void handshake(@Nullable String pluginVersion);

        /** The plugin reported that the model folder is open in Blockbench. */
        void opened();

        /** The plugin reported an error. */
        void pluginError(String message);

        /**
         * Reload the game side after the plugin pushed files. Completes with a short status
         * string that is reported back to the plugin in {@code sync_ack}.
         */
        CompletableFuture<String> reload(int fileCount);

        /** The reload finished (or failed) and was reported to the plugin. */
        void synced(int fileCount, String reloadStatus, boolean failed);

        /** The connection ended, for whatever reason. */
        void disconnected();
    }

    private final String host;
    private final int port;
    private final Listener listener;

    private volatile Socket socket;
    private volatile BufferedWriter writer;
    private volatile boolean connected;
    private volatile String lastStatus = "";

    public BridgeSession(String host, int port, Listener listener) {
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    public boolean isConnected() {
        return connected;
    }

    public String lastStatus() {
        return lastStatus;
    }

    /**
     * Dials the plugin on a worker thread and asks it to load the given model folder.
     * Any previous connection of this session is closed first.
     */
    public void openModel(Path directory, String displayPath) {
        if (!Files.isDirectory(directory)) {
            listener.pluginError("Not a model folder: " + directory);
            return;
        }
        String target = host + ":" + port;
        close();
        Thread worker = new Thread(() -> run(directory, displayPath), "bbconnect-client");
        worker.setDaemon(true);
        worker.start();
    }

    private void run(Path directory, String displayPath) {
        String address = host + ":" + port;
        try (Socket newSocket = new Socket()) {
            newSocket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            newSocket.setTcpNoDelay(true);
            BufferedReader in = new BufferedReader(
                new InputStreamReader(newSocket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(newSocket.getOutputStream(), StandardCharsets.UTF_8));
            synchronized (this) {
                socket = newSocket;
                writer = out;
                connected = true;
            }
            lastStatus = "connected";
            listener.connected(address);

            JsonObject hello = message("hello");
            hello.addProperty("mod", "bbconnectmod");
            hello.addProperty("mod_version", MOD_VERSION);
            hello.addProperty("mc", "1.20.1");
            write(out, hello);

            JsonObject open = message("open");
            open.addProperty("path", directory.toAbsolutePath().normalize().toString());
            open.addProperty("model", displayPath);
            write(out, open);

            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonObject payload = parse(line);
                if (payload != null) handle(payload);
            }
        } catch (IOException failure) {
            LOGGER.warn("BB Connect could not reach {}", address, failure);
            listener.connectionFailed(address, String.valueOf(failure.getMessage()));
        } finally {
            close();
            listener.disconnected();
        }
    }

    private void handle(JsonObject payload) {
        String type = stringValue(payload, "type");
        if (type == null) return;
        switch (type) {
            case "hello_ack" -> {
                String pluginVersion = stringValue(payload, "plugin_version");
                lastStatus = "ready " + (pluginVersion == null ? "" : pluginVersion);
                listener.handshake(pluginVersion);
            }
            case "opened" -> {
                lastStatus = "opened";
                listener.opened();
            }
            case "save" -> handleSave(payload);
            case "error" -> {
                lastStatus = "error";
                String detail = String.valueOf(stringValue(payload, "message"));
                LOGGER.warn("BB Connect plugin error: {}", detail);
                listener.pluginError(detail);
            }
            case "ping" -> send(message("pong"));
            default -> LOGGER.debug("BB Connect ignored message type {}", type);
        }
    }

    private void handleSave(JsonObject payload) {
        String path = stringValue(payload, "path");
        List<String> files = readFiles(payload);
        CompletableFuture<String> reload = listener.reload(files.size());
        reload.whenComplete((status, failure) -> {
            String result = failure != null ? "failed" : status;
            lastStatus = "synced " + result;
            JsonObject ack = message("sync_ack");
            ack.addProperty("path", path);
            ack.addProperty("ok", failure == null);
            ack.addProperty("reload", result);
            ack.addProperty("files", files.size());
            send(ack);
            listener.synced(files.size(), result, failure != null);
        });
    }

    private static List<String> readFiles(JsonObject payload) {
        List<String> files = new ArrayList<>();
        JsonElement rawFiles = payload.get("files");
        if (rawFiles != null && rawFiles.isJsonArray()) {
            for (JsonElement element : rawFiles.getAsJsonArray()) {
                if (element.isJsonPrimitive()) files.add(element.getAsString());
            }
        }
        return files;
    }

    public void send(JsonObject payload) {
        BufferedWriter out = writer;
        if (out == null) return;
        try {
            write(out, payload);
        } catch (IOException failure) {
            LOGGER.debug("BB Connect send failed", failure);
            close();
        }
    }

    private static void write(BufferedWriter out, JsonObject payload) throws IOException {
        synchronized (WRITE_LOCK) {
            out.write(GSON.toJson(payload));
            out.write('\n');
            out.flush();
        }
    }

    public synchronized void close() {
        connected = false;
        writer = null;
        Socket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
                // Closing an already broken socket is not actionable.
            }
        }
    }

    public static JsonObject message(String type) {
        JsonObject object = new JsonObject();
        object.addProperty("v", PROTOCOL_VERSION);
        object.addProperty("type", type);
        return object;
    }

    private static JsonObject parse(String line) {
        try {
            JsonElement parsed = JsonParser.parseString(line);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException invalid) {
            LOGGER.debug("BB Connect ignored malformed line: {}", line);
            return null;
        }
    }

    private static @Nullable String stringValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) return null;
        return value.getAsString();
    }
}
