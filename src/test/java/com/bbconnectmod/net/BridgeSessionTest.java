package com.bbconnectmod.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Wire-protocol tests for the game side of the bridge. They run a fake Blockbench plugin
 * on a loopback socket, so the exact messages the mod sends and accepts are verified
 * without Minecraft.
 */
class BridgeSessionTest {

    @TempDir
    Path tempDir;

    /** Records listener callbacks for assertions. */
    private static final class RecordingListener implements BridgeSession.Listener {
        final List<String> events = new CopyOnWriteArrayList<>();
        final CountDownLatch synced = new CountDownLatch(1);
        final AtomicReference<String> reloadResult = new AtomicReference<>("unset");
        final AtomicInteger reloadFileCount = new AtomicInteger(-1);

        @Override
        public void connected(String address) {
            events.add("connected:" + address);
        }

        @Override
        public void connectionFailed(String address, String detail) {
            events.add("connectionFailed:" + address + ":" + detail);
        }

        @Override
        public void handshake(String pluginVersion) {
            events.add("handshake:" + pluginVersion);
        }

        @Override
        public void opened() {
            events.add("opened");
        }

        @Override
        public void pluginError(String message) {
            events.add("pluginError:" + message);
        }

        @Override
        public CompletableFuture<String> reload(int fileCount) {
            reloadFileCount.set(fileCount);
            return CompletableFuture.completedFuture("COMMITTED/1 models/0 errors");
        }

        @Override
        public void synced(int fileCount, String reloadStatus, boolean failed) {
            reloadResult.set(reloadStatus);
            events.add("synced:" + fileCount + ":" + reloadStatus + ":" + failed);
            synced.countDown();
        }

        @Override
        public void disconnected() {
            events.add("disconnected");
        }
    }

    private static JsonObject readMessage(BufferedReader reader) throws Exception {
        String line = reader.readLine();
        assertTrue(line != null, "connection closed before a message arrived");
        return JsonParser.parseString(line).getAsJsonObject();
    }

    private static void writeMessage(BufferedWriter writer, JsonObject payload) throws Exception {
        writer.write(payload.toString());
        writer.write('\n');
        writer.flush();
    }

    @Test
    void sendsHelloAndOpenThenAcknowledgesSave() throws Exception {
        Path modelDir = Files.createDirectory(tempDir.resolve("demo_model"));
        RecordingListener listener = new RecordingListener();

        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort();
            BridgeSession session = new BridgeSession("127.0.0.1", port, listener);
            session.openModel(modelDir, "demo_model");

            server.setSoTimeout(5000);
            try (Socket peer = server.accept()) {
                peer.setSoTimeout(5000);
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8));
                writer.write("{\"v\":1,\"type\":\"hello_ack\",\"plugin\":\"bbconnectmod\","
                    + "\"plugin_version\":\"1.0.0\",\"port\":" + port + "}\n");
                writer.flush();

                JsonObject hello = readMessage(reader);
                assertEquals("hello", hello.get("type").getAsString());
                assertEquals(BridgeSession.PROTOCOL_VERSION, hello.get("v").getAsInt());
                assertEquals("bbconnectmod", hello.get("mod").getAsString());
                assertEquals("1.20.1", hello.get("mc").getAsString());

                JsonObject open = readMessage(reader);
                assertEquals("open", open.get("type").getAsString());
                assertEquals(modelDir.toAbsolutePath().normalize().toString(), open.get("path").getAsString());
                assertEquals("demo_model", open.get("model").getAsString());

                // The plugin announces the open model, then pushes a save.
                JsonObject opened = BridgeSession.message("opened");
                opened.addProperty("path", modelDir.toString());
                writeMessage(writer, opened);

                JsonObject save = BridgeSession.message("save");
                save.addProperty("path", modelDir.toString());
                var files = new com.google.gson.JsonArray();
                files.add("models/main.json");
                files.add("textures/default.png");
                save.add("files", files);
                writeMessage(writer, save);

                JsonObject ack = readMessage(reader);
                assertEquals("sync_ack", ack.get("type").getAsString());
                assertTrue(ack.get("ok").getAsBoolean());
                assertEquals(2, ack.get("files").getAsInt());
                assertEquals("COMMITTED/1 models/0 errors", ack.get("reload").getAsString());
                assertEquals(modelDir.toString(), ack.get("path").getAsString());

                // Ping must be answered with pong.
                JsonObject ping = BridgeSession.message("ping");
                writeMessage(writer, ping);
                JsonObject pong = readMessage(reader);
                assertEquals("pong", pong.get("type").getAsString());
            }

            assertTrue(listener.synced.await(5, TimeUnit.SECONDS), "sync callback did not fire");
            assertEquals(2, listener.reloadFileCount.get());
            assertEquals("COMMITTED/1 models/0 errors", listener.reloadResult.get());
            assertTrue(listener.events.stream().anyMatch(e -> e.startsWith("connected:127.0.0.1:")));
            assertTrue(listener.events.contains("handshake:1.0.0"));
            assertTrue(listener.events.contains("opened"));
            assertTrue(listener.events.stream().anyMatch(e -> e.startsWith("synced:2:")), listener.events.toString());
            session.close();
        }
    }

    @Test
    void reportsPluginErrorAndClosesOnRequest() throws Exception {
        Path modelDir = Files.createDirectory(tempDir.resolve("broken_model"));
        RecordingListener listener = new RecordingListener();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            BridgeSession session = new BridgeSession("127.0.0.1", server.getLocalPort(), listener);
            session.openModel(modelDir, "broken_model");
            try (Socket peer = server.accept()) {
                peer.setSoTimeout(5000);
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                readMessage(reader); // hello
                readMessage(reader); // open
                JsonObject error = BridgeSession.message("error");
                error.addProperty("message", "ysm.json missing");
                writeMessage(new BufferedWriter(new OutputStreamWriter(peer.getOutputStream(),
                    StandardCharsets.UTF_8)), error);
                long deadline = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < deadline
                    && listener.events.stream().noneMatch(e -> e.startsWith("pluginError:"))) {
                    Thread.sleep(20);
                }
                assertTrue(listener.events.stream().anyMatch(e -> e.startsWith("pluginError:ysm.json missing")),
                    listener.events.toString());
            }
            session.close();
        }
    }

    @Test
    void rejectsNonDirectoryTargetsWithoutDialling() throws Exception {
        Path file = Files.createFile(tempDir.resolve("not_a_folder.json"));
        RecordingListener listener = new RecordingListener();
        BridgeSession session = new BridgeSession("127.0.0.1", 1, listener);
        session.openModel(file, "not_a_folder");
        assertTrue(listener.events.stream().anyMatch(e -> e.startsWith("pluginError:Not a model folder")),
            listener.events.toString());
        assertFalse(session.isConnected());
        session.close();
    }
}
