package com.zhichaoxi.bulk_cell_tweaks;

import com.zhichaoxi.bulk_cell_tweaks.client.BulkCellMarkerScreen;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

import appeng.init.client.InitScreens;

@Mod(value = BulkCellTweaks.MODID, dist = Dist.CLIENT)
public class BulkCellTweaksClient {

    public BulkCellTweaksClient(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::registerScreens);
    }

    private void registerScreens(RegisterMenuScreensEvent event) {
        // StyleManager 只加载 ae2 命名空间的样式，因此样式文件放在 assets/ae2/screens/ 下
        InitScreens.register(event, BulkCellTweaks.BULK_CELL_MARKER_MENU.get(),
                BulkCellMarkerScreen::new, "/screens/bulk_cell_marker.json");
    }
}
