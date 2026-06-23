package ru.voidrp.cpm.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import ru.voidrp.cpm.CosmeticsConfig;
import ru.voidrp.cpm.CosmeticsManager;
import ru.voidrp.cpm.PlayerDataStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

public class CosmeticsCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger("VoidRpCpm");

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                CosmeticsManager manager,
                                PlayerDataStore store,
                                CosmeticsConfig config) {

        SuggestionProvider<CommandSourceStack> allItems = (ctx, b) -> {
            try { config.getItemNames().forEach(b::suggest); } catch (Exception ignored) {}
            return b.buildFuture();
        };

        SuggestionProvider<CommandSourceStack> ownedItems = (ctx, b) -> {
            try {
                if (ctx.getSource().getEntity() instanceof ServerPlayer p)
                    store.getOwned(p.getStringUUID()).forEach(b::suggest);
            } catch (Exception ignored) {}
            return b.buildFuture();
        };

        SuggestionProvider<CommandSourceStack> equippedSlots = (ctx, b) -> {
            if (ctx.getSource().getEntity() instanceof ServerPlayer p)
                store.getSlots(p.getStringUUID()).keySet().forEach(b::suggest);
            return b.buildFuture();
        };

        dispatcher.register(Commands.literal("vc")

            // /vc grant <player> <item>  — OP only
            .then(Commands.literal("grant")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("item", StringArgumentType.word())
                        .suggests(allItems)
                        .executes(ctx -> {
                            try {
                                ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                String item = StringArgumentType.getString(ctx, "item");
                                if (!config.isKnown(item)) {
                                    ctx.getSource().sendFailure(Component.literal("Предмет '" + item + "' не найден в cosmetics.json"));
                                    return 0;
                                }
                                store.grant(target.getStringUUID(), item);
                                ctx.getSource().sendSuccess(() -> Component.literal(
                                    "§a✔ Косметика '" + item + "' [" + config.getSlot(item) + "] выдана " + target.getName().getString()), true);
                                return 1;
                            } catch (Exception e) {
                                LOGGER.error("[VoidRpCpm] /vc grant error: {}", e.getMessage(), e);
                                ctx.getSource().sendFailure(Component.literal("§cОшибка при выдаче косметики"));
                                return 0;
                            }
                        })
                    )
                )
            )

            // /vc revoke <player> <item>  — OP only
            .then(Commands.literal("revoke")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("item", StringArgumentType.word())
                        .suggests(allItems)
                        .executes(ctx -> {
                            try {
                                ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                String item = StringArgumentType.getString(ctx, "item");
                                String slot = config.getSlot(item);
                                if (slot != null) {
                                    String slotItem = store.getSlotItem(target.getStringUUID(), slot);
                                    if (item.equals(slotItem)) manager.unequip(target, slot, store);
                                }
                                store.revoke(target.getStringUUID(), item);
                                ctx.getSource().sendSuccess(() -> Component.literal(
                                    "§a✔ Косметика '" + item + "' отозвана у " + target.getName().getString()), true);
                                return 1;
                            } catch (Exception e) {
                                LOGGER.error("[VoidRpCpm] /vc revoke error: {}", e.getMessage(), e);
                                ctx.getSource().sendFailure(Component.literal("§cОшибка при отзыве косметики"));
                                return 0;
                            }
                        })
                    )
                )
            )

            // /vc equip <item>
            .then(Commands.literal("equip")
                .requires(s -> true)
                .then(Commands.argument("item", StringArgumentType.word())
                    .suggests(ownedItems)
                    .executes(ctx -> {
                        try {
                            if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
                                ctx.getSource().sendFailure(Component.literal("Только для игроков"));
                                return 0;
                            }
                            String item = StringArgumentType.getString(ctx, "item");
                            String uuid = player.getStringUUID();
                            if (!store.ownsCosmetic(uuid, item)) {
                                player.sendSystemMessage(Component.literal("§cУ вас нет косметики '" + item + "'"));
                                return 0;
                            }
                            String slot = config.getSlot(item);
                            if (slot == null) {
                                player.sendSystemMessage(Component.literal("§cПредмет '" + item + "' не настроен в cosmetics.json"));
                                return 0;
                            }
                            if (!manager.isReady()) {
                                player.sendSystemMessage(Component.literal("§cМодель не загружена — положите .cpmproject в config/voidrp-cpm/models/ и сделайте /vc reload"));
                                return 0;
                            }
                            manager.equip(player, item, slot, store);
                            player.sendSystemMessage(Component.literal("§a[" + slot + "] Надели: §e" + item));
                            return 1;
                        } catch (Exception e) {
                            LOGGER.error("[VoidRpCpm] /vc equip error: {}", e.getMessage(), e);
                            ctx.getSource().sendFailure(Component.literal("§cОшибка при экипировке"));
                            return 0;
                        }
                    })
                )
            )

            // /vc unequip <slot>
            .then(Commands.literal("unequip")
                .requires(s -> true)
                .then(Commands.argument("slot", StringArgumentType.word())
                    .suggests(equippedSlots)
                    .executes(ctx -> {
                        try {
                            if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
                                ctx.getSource().sendFailure(Component.literal("Только для игроков"));
                                return 0;
                            }
                            String slot = StringArgumentType.getString(ctx, "slot");
                            if (!manager.unequip(player, slot, store)) {
                                player.sendSystemMessage(Component.literal("§7В слоте '" + slot + "' ничего нет"));
                                return 0;
                            }
                            player.sendSystemMessage(Component.literal("§7[" + slot + "] Снято."));
                            return 1;
                        } catch (Exception e) {
                            LOGGER.error("[VoidRpCpm] /vc unequip error: {}", e.getMessage(), e);
                            ctx.getSource().sendFailure(Component.literal("§cОшибка при снятии косметики"));
                            return 0;
                        }
                    })
                )
            )

            // /vc list
            .then(Commands.literal("list")
                .requires(s -> true)
                .executes(ctx -> {
                    try {
                        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
                            ctx.getSource().sendFailure(Component.literal("Только для игроков"));
                            return 0;
                        }
                        String uuid = player.getStringUUID();
                        Set<String> owned = store.getOwned(uuid);
                        Map<String, String> slots = store.getSlots(uuid);
                        if (owned.isEmpty()) {
                            player.sendSystemMessage(Component.literal("§7У вас нет косметик."));
                        } else {
                            player.sendSystemMessage(Component.literal("§6Ваши косметики:"));
                            for (String item : owned) {
                                String slot = config.getSlot(item);
                                String slotLabel = slot != null ? " §8[" + slot + "]" : "";
                                boolean equipped = item.equals(slots.get(slot));
                                String status = equipped ? " §a(надето)" : "";
                                player.sendSystemMessage(Component.literal("  §e" + item + slotLabel + status));
                            }
                            player.sendSystemMessage(Component.literal("§7/vc equip <название>  |  /vc unequip <слот>"));
                        }
                        return 1;
                    } catch (Exception e) {
                        LOGGER.error("[VoidRpCpm] /vc list error: {}", e.getMessage(), e);
                        ctx.getSource().sendFailure(Component.literal("§cОшибка при получении списка"));
                        return 0;
                    }
                })
            )

            // /vc reload  — OP only
            .then(Commands.literal("reload")
                .requires(s -> s.hasPermission(2))
                .executes(ctx -> {
                    try {
                        manager.loadWardrobe();
                        config.load();
                        boolean ready = manager.isReady();
                        String modelStatus = ready
                                ? "§a" + manager.getWardrobeFileName()
                                : "§cне найдена";
                        int count = config.getItemNames().size();
                        ctx.getSource().sendSuccess(() -> Component.literal(
                                "§a[VoidRpCpm] §7Модель: " + modelStatus +
                                " §7| §eКосметик: " + count), false);
                        if (!ready) {
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "§7  → Положите .cpmproject (или .cpmmodel) в §econfig/voidrp-cpm/models/§7 и повторите /vc reload"), false);
                        }
                        return 1;
                    } catch (Exception e) {
                        LOGGER.error("[VoidRpCpm] /vc reload error: {}", e.getMessage(), e);
                        ctx.getSource().sendFailure(Component.literal("§cОшибка при перезагрузке"));
                        return 0;
                    }
                })
            )
        );
    }
}
