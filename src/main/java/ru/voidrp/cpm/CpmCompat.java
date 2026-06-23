package ru.voidrp.cpm;

import com.tom.cpm.api.ICPMPlugin;
import com.tom.cpm.api.IClientAPI;
import com.tom.cpm.api.ICommonAPI;

public class CpmCompat implements ICPMPlugin {

    @Override
    public void initCommon(ICommonAPI api) {
        VoidRpCpm.getCosmeticsManager().setApi(api);
        VoidRpCpm.LOGGER.info("[VoidRpCpm] CPM Common API ready");
    }

    @Override
    public void initClient(IClientAPI api) {
        // Server-only mod, nothing needed client-side
    }

    @Override
    public String getOwnerModId() {
        return VoidRpCpm.MOD_ID;
    }
}
