package com.bbconnectmod.ysm;

import com.bbconnectmod.BbConnectMod;
import com.elfmcys.ysm.client.gui.PlayerModelScreen;
import com.elfmcys.ysm.client.gui.button.CatalogModelButton;
import com.elfmcys.ysm.model.ModelRuntime;
import com.elfmcys.ysm.model.catalog.ReloadResult;
import com.elfmcys.ysm.model.catalog.client.ClientCatalogSnapshot;
import com.elfmcys.ysm.model.catalog.source.ModelSourceKind;
import com.elfmcys.ysm.model.service.ClientModelService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

/**
 * Every Yes Steve Model reference lives in this class. Callers must check
 * {@code ModList.get().isLoaded("yes_steve_model")} before touching it, so the mod still
 * loads when the model mod is absent.
 */
public final class YsmBridge {
    private YsmBridge() {}

    /** A model card that is backed by a model folder on disk and can be opened in Blockbench. */
    public record FolderCard(CatalogModelButton button, Path directory, String displayPath) {}

    public static boolean isModelScreen(Screen screen) {
        return screen instanceof PlayerModelScreen;
    }

    /**
     * Folder-form model cards of the current screen page. Archive and encrypted sources are
     * skipped: Blockbench can only round-trip models that are stored as readable folders.
     */
    public static List<FolderCard> folderCards(Screen screen) {
        List<CatalogModelButton> cards = new ArrayList<>();
        for (var child : screen.children()) {
            if (child instanceof CatalogModelButton card) cards.add(card);
        }
        if (cards.isEmpty()) return List.of();
        ClientModelService service = ClientModelService.current().orElse(null);
        if (service == null) return List.of();
        ClientCatalogSnapshot snapshot = service.catalog();

        List<FolderCard> result = new ArrayList<>();
        for (CatalogModelButton card : cards) {
            var entry = snapshot.find(card.modelHash()).orElse(null);
            if (entry == null) continue;
            Path directory = folderOf(entry);
            if (directory != null) result.add(new FolderCard(card, directory, entry.displayPath()));
        }
        return result;
    }

    /** Same resolution Yes Steve Model uses for its own "open model folder" shortcut. */
    private static @Nullable Path folderOf(
        com.elfmcys.ysm.model.catalog.client.entry.ClientCatalogEntry entry) {
        var binding = entry.content().storageBinding();
        if (binding == null) return null;
        var source = binding.localSource().orElse(null);
        if (source == null) return null;
        if (source.key().sourceKind() != ModelSourceKind.CURRENT_RAW_DIRECTORY) return null;
        return source.logicalPath();
    }

    /** Asks the model catalog to rescan the model folders; completes on a later client tick. */
    public static CompletableFuture<String> reloadCatalog() {
        try {
            CompletableFuture<ReloadResult> reload = ModelRuntime.system().catalog().reload();
            return reload.thenApply(result -> result.status()
                + "/" + result.modelCount() + " models/" + result.errorCount() + " errors");
        } catch (Throwable failure) {
            BbConnectMod.LOGGER.warn("BB Connect could not reload the Yes Steve Model catalog", failure);
            return CompletableFuture.completedFuture("ysm-unavailable");
        }
    }
}
