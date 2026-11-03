package com.bbconnectmod;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * BB Connect: exposes Yes Steve Model folder models to Blockbench Community.
 *
 * <p>The mod never listens on a socket. In-game it dials the Blockbench plugin (see
 * {@link com.bbconnectmod.net.BbConnectClient}), so nothing is reachable from the network
 * unless a connection was started from inside the game.
 */
@Mod(BbConnectMod.MOD_ID)
public final class BbConnectMod {
    public static final String MOD_ID = "bbconnectmod";
    public static final Logger LOGGER = LogUtils.getLogger();

    public BbConnectMod() {
        if (FMLEnvironment.dist.isClient()) {
            ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
            LOGGER.debug("BB Connect client config registered");
        }
    }
}
