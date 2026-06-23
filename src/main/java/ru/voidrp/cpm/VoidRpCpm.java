package ru.voidrp.cpm;

import com.mojang.logging.LogUtils;
import com.tom.cpm.api.ICPMPlugin;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.InterModComms;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.InterModEnqueueEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;
import ru.voidrp.cpm.command.CosmeticsCommand;

import java.util.function.Supplier;

@Mod(VoidRpCpm.MOD_ID)
public class VoidRpCpm {
    public static final String MOD_ID = "voidrp_cpm";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static CosmeticsManager cosmeticsManager;
    private static PlayerDataStore playerDataStore;
    private static CosmeticsConfig cosmeticsConfig;
    private static ModConfig modConfig;

    public VoidRpCpm(IEventBus modBus, ModContainer container) {
        modBus.addListener(this::setup);
        modBus.addListener(this::enqueueIMC);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLogin);
        NeoForge.EVENT_BUS.addListener(this::onCommand);
    }

    private void setup(FMLCommonSetupEvent event) {
        try {
            modConfig = ModConfig.load();
            cosmeticsManager = new CosmeticsManager();
            cosmeticsManager.setModConfig(modConfig);
            playerDataStore = new PlayerDataStore();
            cosmeticsConfig = new CosmeticsConfig();
            cosmeticsManager.loadWardrobe();
            LOGGER.info("[VoidRpCpm] Initialized — wardrobe ready: {}", cosmeticsManager.isReady());
            LOGGER.info("[VoidRpCpm] ── Designer guide ─────────────────────────────────────────");
            LOGGER.info("[VoidRpCpm]  1. In Blockbench CPM plugin, create animations for each cosmetic");
            LOGGER.info("[VoidRpCpm]  2. Name animations using: <slot>_<name>");
            LOGGER.info("[VoidRpCpm]     head_tophat | body_cape | legs_pants | feet_boots | wings_angel");
            LOGGER.info("[VoidRpCpm]  3. Each animation toggles visibility of its model group");
            LOGGER.info("[VoidRpCpm]  4. Export → .cpmproject (or .cpmmodel), save .bbmodel → place both in config/voidrp-cpm/models/");
            LOGGER.info("[VoidRpCpm]  5. Run /vc reload — cosmetics.json is auto-generated from .bbmodel");
            LOGGER.info("[VoidRpCpm] ────────────────────────────────────────────────────────────");
        } catch (Exception e) {
            LOGGER.error("[VoidRpCpm] Failed to initialize — cosmetics will be disabled: {}", e.getMessage(), e);
        }
    }

    private void enqueueIMC(InterModEnqueueEvent event) {
        try {
            InterModComms.sendTo("cpm", "api", () -> (Supplier<ICPMPlugin>) CpmCompat::new);
        } catch (Exception e) {
            LOGGER.error("[VoidRpCpm] Failed to register CPM API — is CPM installed? {}", e.getMessage());
        }
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        try {
            CosmeticsCommand.register(event.getDispatcher(), cosmeticsManager, playerDataStore, cosmeticsConfig);
        } catch (Exception e) {
            LOGGER.error("[VoidRpCpm] Failed to register /vc commands: {}", e.getMessage(), e);
        }
    }

    private void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        try {
            if (!(event.getEntity() instanceof ServerPlayer player)) return;
            if (cosmeticsManager == null || playerDataStore == null) return;
            var server = player.getServer();
            if (server == null) return;
            server.execute(() -> {
                try {
                    cosmeticsManager.applyOnLogin(player, playerDataStore);
                } catch (Exception e) {
                    LOGGER.error("[VoidRpCpm] Error in applyOnLogin for {}: {}", player.getName().getString(), e.getMessage(), e);
                }
            });
        } catch (Exception e) {
            LOGGER.error("[VoidRpCpm] onPlayerLogin error: {}", e.getMessage(), e);
        }
    }

    private void onCommand(CommandEvent event) {
        try {
            String input = event.getParseResults().getReader().getString().trim();
            if (!input.startsWith("cpmclient") && !input.startsWith("cpm ") && !input.equals("cpm")) return;
            if (!(event.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer player)) return;
            if (!hasPermission(player)) {
                event.setCanceled(true);
                player.sendSystemMessage(Component.literal("§cУ вас нет доступа к командам CPM"));
            }
        } catch (Exception e) {
            LOGGER.warn("[VoidRpCpm] onCommand error: {}", e.getMessage());
        }
    }

    public static boolean hasPermission(ServerPlayer player) {
        try {
            if (player == null) return false;
            if (player.hasPermissions(2)) return true;
            if (playerDataStore == null) return false;
            return !playerDataStore.getOwned(player.getStringUUID()).isEmpty();
        } catch (Exception e) {
            LOGGER.warn("[VoidRpCpm] hasPermission error: {}", e.getMessage());
            return false;
        }
    }

    public static CosmeticsManager getCosmeticsManager() { return cosmeticsManager; }
    public static PlayerDataStore getPlayerDataStore() { return playerDataStore; }
    public static CosmeticsConfig getCosmeticsConfig() { return cosmeticsConfig; }
}
