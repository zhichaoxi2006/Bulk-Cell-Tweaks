package com.zhichaoxi.bulk_cell_tweaks.client;

import java.util.List;

import com.zhichaoxi.bulk_cell_tweaks.BulkCellTweaks;
import com.zhichaoxi.bulk_cell_tweaks.menu.BulkCellMarkerMenu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Inventory;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.Icon;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.IconButton;

public class BulkCellMarkerScreen extends AEBaseScreen<BulkCellMarkerMenu> {

    private final IconButton prevButton;
    private final IconButton nextButton;
    private final IconButton autoMarkButton;

    public BulkCellMarkerScreen(BulkCellMarkerMenu menu, Inventory playerInventory,
            Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style);
        // 左侧工具栏翻页按钮，贴合 AE 风格（16x16 全尺寸）
        this.prevButton = new IconButton(btn -> getMenu().prevPage()) {
            @Override
            protected Icon getIcon() {
                return Icon.ARROW_LEFT;
            }

            @Override
            public List<Component> getTooltipMessage() {
                return List.of(Component.translatable("gui." + BulkCellTweaks.MODID + ".prevPage"));
            }
        };
        this.nextButton = new IconButton(btn -> getMenu().nextPage()) {
            @Override
            protected Icon getIcon() {
                return Icon.ARROW_RIGHT;
            }

            @Override
            public List<Component> getTooltipMessage() {
                return List.of(Component.translatable("gui." + BulkCellTweaks.MODID + ".nextPage"));
            }
        };
        this.autoMarkButton = new IconButton(btn -> getMenu().autoMark()) {
            @Override
            protected Icon getIcon() {
                return Icon.SORT_BY_AMOUNT;
            }

            @Override
            public List<Component> getTooltipMessage() {
                return List.of(Component.translatable("gui." + BulkCellTweaks.MODID + ".autoMark"));
            }
        };
        addToLeftToolbar(prevButton);
        addToLeftToolbar(nextButton);
        addToLeftToolbar(autoMarkButton);
    }

    @Override
    protected void init() {
        super.init();
        int pageCount = menu.getPageCount();
        Component pageText = Component.translatable("gui." + BulkCellTweaks.MODID + ".BulkCellMarker")
                .append(Component.translatable("gui." + BulkCellTweaks.MODID + ".page",
                        menu.getPage() + 1, pageCount));
        setTextContent(TEXT_ID_DIALOG_TITLE, pageText);
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        // 翻页按钮始终显示，不可用时置灰（隐藏会不合时宜地消失）
        int page = menu.getPage();
        int pageCount = menu.getPageCount();
        prevButton.active = page > 0;
        nextButton.active = page < menu.getLastPage();
        // 网络里没有任何 bulk cell 时置灰
        autoMarkButton.active = pageCount > 0;
        MutableComponent pageText = Component.translatable("gui." + BulkCellTweaks.MODID + ".BulkCellMarker");

        if (pageCount > 0) {
            pageText.append(" ")
                    .append(Component.translatable("gui." + BulkCellTweaks.MODID + ".page",
                            menu.getPage() + 1, pageCount));
        }

        setTextContent(TEXT_ID_DIALOG_TITLE, pageText);
    }
}

