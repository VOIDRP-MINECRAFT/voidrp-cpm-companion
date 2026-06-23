package ru.voidrp.cpm.mixin;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.voidrp.cpm.VoidRpCpm;

/**
 * Blocks non-permitted players from uploading their own CPM models.
 * Targets NetHandler.setSkin(Object player, byte[] data, boolean forced)
 * which is called when the client sends a SetSkinC2S packet.
 * forced=false means it came from the client; forced=true means it's server-set.
 */
@Mixin(targets = "com.tom.cpm.shared.network.NetHandler", remap = false)
public class SkinRestrictionMixin {

    @Inject(
        method = "setSkin(Ljava/lang/Object;[BZ)V",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void voidrp$checkSkinPermission(Object playerObj, byte[] data, boolean forced, CallbackInfo ci) {
        if (forced) return; // Server-set models always allowed
        if (playerObj instanceof ServerPlayer player) {
            if (!VoidRpCpm.hasPermission(player)) {
                VoidRpCpm.LOGGER.debug("[VoidRpCpm] Blocked CPM skin upload from {} (no permission)", player.getName().getString());
                ci.cancel();
            }
        }
    }
}
