package com.bbconnectmod.net;

import com.bbconnectmod.BbConnectMod;
import com.bbconnectmod.Config;
import com.bbconnectmod.ysm.SelectionKeeper;
import com.bbconnectmod.ysm.YsmBridge;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModList;

/**
 * Minecraft-facing side of the bridge: reads the client config, routes user feedback to
 * chat and reloads the Yes Steve Model catalog when the plugin pushes a model.
 * All wire logic lives in {@link BridgeSession}.
 */
public final class BbConnectClient {
    private static final BbConnectClient INSTANCE = new BbConnectClient();
    private static final int RELOAD_BUSY_RETRIES = 6;
    private static final long RELOAD_BUSY_RETRY_MS = 400;

    private volatile BridgeSession session;
    private volatile String lastStatus = "";

    private BbConnectClient() {}

    public static BbConnectClient get() {
        return INSTANCE;
    }

    public boolean isConnected() {
        BridgeSession current = session;
        return current != null && current.isConnected();
    }

    public String lastStatus() {
        BridgeSession current = session;
        return current != null ? current.lastStatus() : lastStatus;
    }

    /** Opens (or reuses) the bridge connection and asks the plugin to load the model folder. */
    public void openModel(java.nio.file.Path directory, String displayPath) {
        session().openModel(directory, displayPath);
    }

    private BridgeSession session() {
        BridgeSession current = session;
        if (current != null) return current;
        synchronized (this) {
            if (session == null) {
                session = new BridgeSession(readString(Config.HOST, "127.0.0.1"), readInt(Config.PORT, 41234),
                    new MinecraftListener());
            }
            return session;
        }
    }

    private final class MinecraftListener implements BridgeSession.Listener {
        @Override
        public void connected(String address) {
            lastStatus = "connected " + address;
            chat(Component.translatable("bbconnectmod.message.connected", address), false);
        }

        @Override
        public void connectionFailed(String address, String detail) {
            lastStatus = "connect failed";
            chat(Component.translatable("bbconnectmod.message.connect_failed", address, detail), true);
        }

        @Override
        public void handshake(String pluginVersion) {
            lastStatus = "ready " + (pluginVersion == null ? "" : pluginVersion);
            BbConnectMod.LOGGER.info("BB Connect handshake accepted by plugin {}", pluginVersion);
        }

        @Override
        public void opened() {
            lastStatus = "opened";
            chat(Component.translatable("bbconnectmod.message.opened"), false);
        }

        @Override
        public void pluginError(String message) {
            lastStatus = "error";
            chat(Component.translatable("bbconnectmod.message.plugin_error", message), true);
        }

        @Override
        public CompletableFuture<String> reload(int fileCount) {
            Minecraft client = Minecraft.getInstance();
            boolean ysmReload = ModList.get().isLoaded("ysm") && readBoolean(Config.AUTO_RELOAD, true);
            CompletableFuture<String> reload = ysmReload
                ? reloadKeepingSelection(client)
                : CompletableFuture.completedFuture("skipped");
            if (readBoolean(Config.RELOAD_RESOURCE_PACKS, false)) {
                client.execute(client::reloadResourcePacks);
            }
            return reload;
        }

        @Override
        public void synced(int fileCount, String reloadStatus, boolean failed) {
            lastStatus = "synced " + reloadStatus;
            chat(Component.translatable("bbconnectmod.message.synced", String.valueOf(fileCount), reloadStatus),
                failed);
        }

        @Override
        public void disconnected() {
            lastStatus = "disconnected";
        }

        /**
         * Pins the current model selection, reloads the catalog on the client thread and repairs
         * the selection afterwards: model ids are content hashes, so a pushed edit changes the id
         * and Yes Steve Model would otherwise drop the player back to the default model.
         */
        private CompletableFuture<String> reloadKeepingSelection(Minecraft client) {
            return SelectionKeeper.capture(client).thenCompose(pin -> {
                CompletableFuture<String> started = new CompletableFuture<>();
                client.execute(() -> started.complete(null));
                return started.thenCompose(ignored -> reloadWithRetry(0))
                    .thenCompose(status -> SelectionKeeper.restore(client, pin, status));
            });
        }

        /**
         * Reloads, retrying while Yes Steve Model reports an in-progress scan. A busy scan keeps
         * the previous catalog, so the files this save just wrote would silently never be used.
         */
        private static CompletableFuture<String> reloadWithRetry(int attempt) {
            CompletableFuture<String> reload;
            try {
                reload = YsmBridge.reloadCatalog();
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
            CompletableFuture<String> result = new CompletableFuture<>();
            reload.whenComplete((status, failure) -> {
                if (failure != null) {
                    result.completeExceptionally(failure);
                    return;
                }
                if (status != null && status.startsWith("BUSY") && attempt < RELOAD_BUSY_RETRIES) {
                    CompletableFuture.delayedExecutor(RELOAD_BUSY_RETRY_MS, TimeUnit.MILLISECONDS)
                        .execute(() -> reloadWithRetry(attempt + 1).whenComplete((next, nextFailure) -> {
                            if (nextFailure != null) result.completeExceptionally(nextFailure);
                            else result.complete(next);
                        }));
                    return;
                }
                result.complete(status);
            });
            return result;
        }
    }

    /*
     * Config values are read defensively: a client can act before the config file finishes
     * loading, and ForgeConfigSpec values throw in that window.
     */
    private static String readString(ForgeConfigSpec.ConfigValue<String> value, String fallback) {
        try {
            String current = value.get();
            return current == null || current.isBlank() ? fallback : current;
        } catch (RuntimeException notLoaded) {
            return fallback;
        }
    }

    private static int readInt(ForgeConfigSpec.IntValue value, int fallback) {
        try {
            return value.get();
        } catch (RuntimeException notLoaded) {
            return fallback;
        }
    }

    private static boolean readBoolean(ForgeConfigSpec.BooleanValue value, boolean fallback) {
        try {
            return value.get();
        } catch (RuntimeException notLoaded) {
            return fallback;
        }
    }

    private static void chat(Component text, boolean failure) {
        if (!readBoolean(Config.CHAT_MESSAGES, true)) return;
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.player == null) return;
            client.player.displayClientMessage(Component.literal("[BB Connect] ").append(text), failure);
        });
    }
}
