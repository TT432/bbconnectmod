package com.bbconnectmod;

import net.minecraftforge.common.ForgeConfigSpec;

/** Client configuration. Both ends of the bridge can pick the port; the plugin must match. */
public final class Config {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.ConfigValue<String> HOST;
    public static final ForgeConfigSpec.IntValue PORT;
    public static final ForgeConfigSpec.BooleanValue AUTO_RELOAD;
    public static final ForgeConfigSpec.BooleanValue RELOAD_RESOURCE_PACKS;
    public static final ForgeConfigSpec.BooleanValue CHAT_MESSAGES;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("bridge");
        HOST = builder
            .comment("Host of the Blockbench plugin listener. Keep it on the loopback interface.")
            .define("host", "127.0.0.1");
        PORT = builder
            .comment("Port of the Blockbench plugin listener. Must match the plugin setting.")
            .defineInRange("port", 41234, 1024, 65535);
        builder.pop();

        builder.push("sync");
        AUTO_RELOAD = builder
            .comment("Reload the Yes Steve Model catalog after the plugin writes a model back.")
            .define("auto_reload", true);
        RELOAD_RESOURCE_PACKS = builder
            .comment("Also reload client resource packs (the F3+T reload) after a sync.")
            .define("reload_resource_packs", false);
        CHAT_MESSAGES = builder
            .comment("Report connection and sync results in chat.")
            .define("chat_messages", true);
        builder.pop();

        SPEC = builder.build();
    }

    private Config() {}
}
