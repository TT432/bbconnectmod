package com.bbconnectmod.ysm;

import com.bbconnectmod.BbConnectMod;
import com.elfmcys.ysm.capability.ModelInfoCapability;
import com.elfmcys.ysm.capability.ModelInfoCapabilityProvider;
import com.elfmcys.ysm.model.catalog.snapshot.ServerCatalog;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.service.ServerModelService;
import com.elfmcys.ysm.model.session.server.state.Selection;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.network.forge.SessionProtocolHandler;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Keeps a player on the same model across a catalog reload.
 *
 * <p>Yes Steve Model stores the selection as the content hash of the model, so rewriting a model
 * folder changes its id and the selection falls back to the default model. The hash itself cannot
 * be preserved, but the catalog <em>path</em> can: this class pins the path before the reload and
 * re-applies the selection to the reloaded model afterwards.
 *
 * <p>Everything that touches the capability runs on the integrated server thread, because the
 * capability and the server catalog are server-side state.
 */
public final class SelectionKeeper {
    private static final long CAPTURE_TIMEOUT_SECONDS = 5;
    private static final long RESTORE_TIMEOUT_SECONDS = 10;

    private SelectionKeeper() {}

    /** The model a player is on, addressed by catalog path so it survives a content change. */
    public record Pin(UUID player, String path, String texture, Hash256 modelId) {}

    /** Remembers the local player's selection. Empty when there is nothing to restore. */
    public static CompletableFuture<Optional<Pin>> capture(Minecraft client) {
        CompletableFuture<Optional<Pin>> result = new CompletableFuture<>();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || client.player == null) {
            result.complete(Optional.empty());
            return result;
        }
        try {
            server.execute(() -> {
                Optional<Pin> pin = Optional.empty();
                try {
                    pin = pin(server, client);
                } catch (Throwable failure) {
                    BbConnectMod.LOGGER.warn("BB Connect could not pin the model selection", failure);
                }
                result.complete(pin);
            });
        } catch (Throwable failure) {
            BbConnectMod.LOGGER.warn("BB Connect could not schedule the model selection pin", failure);
            result.complete(Optional.empty());
        }
        return result.completeOnTimeout(Optional.empty(), CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Re-applies the pinned selection to the reloaded catalog. Completes with the reload status
     * plus a {@code | selection:…} suffix describing what happened.
     */
    public static CompletableFuture<String> restore(Minecraft client, Optional<Pin> pinned, String status) {
        if (pinned.isEmpty()) return CompletableFuture.completedFuture(status);
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) return CompletableFuture.completedFuture(status + " | selection:unavailable");

        CompletableFuture<String> result = new CompletableFuture<>();
        try {
            server.execute(() -> {
                String outcome;
                try {
                    outcome = restore(server, pinned.get());
                } catch (Throwable failure) {
                    BbConnectMod.LOGGER.warn("BB Connect could not restore the model selection", failure);
                    outcome = "failed";
                }
                result.complete(status + " | selection:" + outcome);
            });
        } catch (Throwable failure) {
            BbConnectMod.LOGGER.warn("BB Connect could not schedule the model selection restore", failure);
            result.complete(status + " | selection:failed");
        }
        return result.completeOnTimeout(status + " | selection:timeout", RESTORE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static Optional<Pin> pin(MinecraftServer server, Minecraft client) {
        ServerPlayer player = server.getPlayerList().getPlayer(client.player.getUUID());
        if (player == null) return Optional.empty();
        ModelInfoCapability capability = player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP).orElse(null);
        if (capability == null || capability.getModelId() == null) return Optional.empty();
        ServerCatalog catalog = serverCatalog();
        if (catalog == null) return Optional.empty();
        ManagedContainer model = catalog.find(capability.getModelId()).orElse(null);
        // An unresolvable id means the selection already fell back; the path is gone, nothing to pin.
        if (model == null) return Optional.empty();
        return Optional.of(new Pin(player.getUUID(), model.location().value(), capability.getSelectTexture(),
            capability.getModelId()));
    }

    private static String restore(MinecraftServer server, Pin pinned) {
        ServerPlayer player = server.getPlayerList().getPlayer(pinned.player());
        if (player == null) return "no-player";
        ModelInfoCapability capability = player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP).orElse(null);
        if (capability == null) return "no-capability";
        ServerCatalog catalog = serverCatalog();
        if (catalog == null) return "no-catalog";

        ManagedContainer reloaded = catalog.findPath(pinned.path()).orElse(null);
        if (reloaded == null) return "missing";
        Hash256 modelId = reloaded.representation().modelId();
        if (modelId.equals(capability.getModelId())) return "unchanged";

        /*
         * Only repair a fallback. If the player really switched to another resolvable model while
         * the reload was running, that choice wins; an unresolvable id is the fallback to repair.
         */
        Hash256 current = capability.getModelId();
        Hash256 fallbackId = catalog.defaultModel().map(model -> model.representation().modelId()).orElse(null);
        if (current != null && !current.equals(fallbackId) && catalog.find(current).isPresent()) return "declined";

        // The metadata settings accessor returns a quickbuf proto type that is not on the compile
        // classpath, so fall back to the same rule Yes Steve Model uses for built-in defaults.
        var textures = reloaded.view().getPlayer().getTextureNames();
        String texture = textures.contains(pinned.texture()) ? pinned.texture()
            : textures.stream().sorted().findFirst().orElse("");
        boolean applied = SessionProtocolHandler.applyCommandSelection(player, capability,
            new Selection.Model(modelId, texture), true);
        if (applied) BbConnectMod.LOGGER.info("BB Connect kept {} on {} ({})", player.getScoreboardName(),
            pinned.path(), texture);
        return applied ? "kept" : "rejected";
    }

    private static ServerCatalog serverCatalog() {
        return ServerModelService.current().flatMap(ServerModelService::catalog).orElse(null);
    }
}
