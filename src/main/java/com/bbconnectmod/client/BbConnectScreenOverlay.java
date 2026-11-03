package com.bbconnectmod.client;

import com.bbconnectmod.BbConnectMod;
import com.bbconnectmod.net.BbConnectClient;
import com.bbconnectmod.ysm.YsmBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

/**
 * Draws a "Connect to BB" hotspot under every folder-form model card of the Yes Steve Model
 * model screen and handles its click. Rendering and hit testing are both derived from the
 * live screen children, so paging, searching and re-initialisation need no extra bookkeeping.
 */
@Mod.EventBusSubscriber(modid = BbConnectMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BbConnectScreenOverlay {
    private static final String YSM_MOD_ID = "ysm";
    private static final int HOTSPOT_HEIGHT = 12;
    private static final int COLOR_IDLE = 0xD0202020;
    private static final int COLOR_HOVER = 0xD0386B3C;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_STATUS = 0xFFB0B0B0;

    private BbConnectScreenOverlay() {}

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (!ModList.get().isLoaded(YSM_MOD_ID)) return;
        Screen screen = event.getScreen();
        if (!YsmBridge.isModelScreen(screen)) return;

        var cards = YsmBridge.folderCards(screen);
        if (cards.isEmpty()) {
            renderStatus(event.getGuiGraphics(), screen);
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        Font font = Minecraft.getInstance().font;
        for (var card : cards) {
            Hotspot hotspot = Hotspot.of(card);
            boolean hovered = hotspot.contains(event.getMouseX(), event.getMouseY());
            graphics.fill(hotspot.left(), hotspot.top(), hotspot.right(), hotspot.bottom(),
                hovered ? COLOR_HOVER : COLOR_IDLE);
            graphics.drawCenteredString(font, hotspot.label(), hotspot.centerX(), hotspot.top() + 2, COLOR_TEXT);
        }
        renderStatus(graphics, screen);
    }

    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != 0) return;
        if (!ModList.get().isLoaded(YSM_MOD_ID)) return;
        Screen screen = event.getScreen();
        if (!YsmBridge.isModelScreen(screen)) return;
        for (var card : YsmBridge.folderCards(screen)) {
            Hotspot hotspot = Hotspot.of(card);
            if (!hotspot.contains(event.getMouseX(), event.getMouseY())) continue;
            BbConnectClient.get().openModel(card.directory(), card.displayPath());
            event.setCanceled(true);
            return;
        }
    }

    private static void renderStatus(GuiGraphics graphics, Screen screen) {
        BbConnectClient client = BbConnectClient.get();
        if (!client.isConnected()) return;
        String status = "BB " + client.lastStatus();
        graphics.drawString(Minecraft.getInstance().font, status, 4, screen.height - 12, COLOR_STATUS, false);
    }

    /** The area and label of the connection hotspot below one model card. */
    private record Hotspot(int left, int top, int right, int bottom, String label) {
        static Hotspot of(YsmBridge.FolderCard card) {
            int left = card.button().getX();
            int top = card.button().getY() + card.button().getHeight() - HOTSPOT_HEIGHT;
            int right = left + card.button().getWidth();
            return new Hotspot(left, top, right, top + HOTSPOT_HEIGHT, "连接到 BB");
        }

        boolean contains(double x, double y) {
            return x >= left && x < right && y >= top && y < bottom;
        }

        int centerX() {
            return (left + right) / 2;
        }
    }
}
