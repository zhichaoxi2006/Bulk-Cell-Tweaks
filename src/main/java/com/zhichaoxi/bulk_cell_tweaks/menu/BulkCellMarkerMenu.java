package com.zhichaoxi.bulk_cell_tweaks.menu;

import com.zhichaoxi.bulk_cell_tweaks.BulkCellTweaks;
import com.zhichaoxi.bulk_cell_tweaks.blockentity.BulkCellMarkerBlockEntity;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;

import appeng.helpers.InventoryAction;
import appeng.menu.AEBaseMenu;
import appeng.menu.SlotSemantics;
import appeng.menu.slot.IOptionalSlotHost;
import appeng.menu.slot.OptionalFakeSlot;

import gripe._90.megacells.definition.MEGAItems;

import static com.zhichaoxi.bulk_cell_tweaks.blockentity.BulkCellMarkerBlockEntity.SLOTS_PER_PAGE;

public class BulkCellMarkerMenu extends AEBaseMenu implements IOptionalSlotHost {

    private static final String ACTION_PAGE_DELTA = "pageDelta";
    private static final String ACTION_AUTO_MARK = "autoMark";

    private int page;
    private int totalCells;

    public BulkCellMarkerMenu(int windowId, Inventory playerInventory, BulkCellMarkerBlockEntity host) {
        super(BulkCellTweaks.BULK_CELL_MARKER_MENU.get(), windowId, playerInventory, host);
        if (isServerSide()) {
            page = host.getPage();
            totalCells = host.getCells().size();
        }
        registerClientAction(ACTION_PAGE_DELTA, Integer.class, this::handlePageDelta);
        registerClientAction(ACTION_AUTO_MARK, this::handleAutoMark);
        // 服务端→客户端状态同步用 vanilla DataSlot（AE2 的 sendClientAction 只能客户端→服务端，服务端调用无效）
        DataSlot pageSlot = new DataSlot() {
            @Override
            public int get() {
                return page;
            }

            @Override
            public void set(int value) {
                page = value;
            }
        };
        addDataSlot(pageSlot);
        DataSlot totalCellsSlot = new DataSlot() {
            @Override
            public int get() {
                return totalCells;
            }

            @Override
            public void set(int value) {
                totalCells = value;
            }
        };
        addDataSlot(totalCellsSlot);
        for (int i = 0; i < SLOTS_PER_PAGE; i++) {
            // 每格堆叠上限 1，与元件工作台一致（过滤只存 1 个物品）
            addSlot(new OptionalFakeSlot(host.getInternalInventory(), this, i, i) {
                @Override
                public int getMaxStackSize() {
                    return 1;
                }

                @Override
                public int getMaxStackSize(ItemStack stack) {
                    return 1;
                }
            }, SlotSemantics.CONFIG);
        }
        createPlayerInventorySlots(playerInventory);
    }

    public void receiveInitialData(int page, int totalCells) {
        this.page = page;
        this.totalCells = totalCells;
    }

    private void handlePageDelta(Integer delta) {
        BulkCellMarkerBlockEntity be = (BulkCellMarkerBlockEntity) getBlockEntity();
        if (be == null) {
            return;
        }
        be.gotoPage(delta);
        page = be.getPage();
        totalCells = be.getCells().size();
        // 立即把 DataSlot 与槽位内容推给客户端（vanilla super 会同步 DataSlot + 槽位）
        broadcastChanges();
    }

    @Override
    public boolean isSlotEnabled(int groupNum) {
        return page * SLOTS_PER_PAGE + groupNum < totalCells;
    }

    public void prevPage() {
        sendClientAction(ACTION_PAGE_DELTA, -1);
    }

    public void nextPage() {
        sendClientAction(ACTION_PAGE_DELTA, 1);
    }

    public void autoMark() {
        sendClientAction(ACTION_AUTO_MARK);
    }

    private void handleAutoMark() {
        if (getBlockEntity() instanceof BulkCellMarkerBlockEntity be) {
            be.autoMark();
            // DataSlot + 槽位内容立即同步到客户端（标记结果直接可见）
            broadcastChanges();
        }
    }

    /**
     * 显示槽（0..62）右键（SPLIT_OR_PLACE_SINGLE）：手持压缩卡 → 装入对应 cell；
     * 空手 → 从对应 cell 卸载压缩卡。FakeSlot 的点击走 AE2 的 InventoryActionPacket →
     * doAction 路径（不经过 vanilla clicked），拦截必须挂在这里。
     */
    @Override
    public void doAction(ServerPlayer player, InventoryAction action, int id, long extraId) {
        // 滞留盘检修：空手左键（PICKUP_OR_SET_DOWN）→ 把"无过滤但装满"的 cell
        // 内容抽回网络（供能插入，与 IO 接口一致）
        if (action == InventoryAction.PICKUP_OR_SET_DOWN
                && id >= 0 && id < SLOTS_PER_PAGE
                && getCarried().isEmpty()) {
            if (getBlockEntity() instanceof BulkCellMarkerBlockEntity be && be.isStranded(id)) {
                be.drainCell(id);
                broadcastChanges();
                return;
            }
        }
        if (action == InventoryAction.SPLIT_OR_PLACE_SINGLE
                && id >= 0 && id < SLOTS_PER_PAGE) {
            ItemStack carried = getCarried();
            if (carried.isEmpty() || carried.is(MEGAItems.COMPRESSION_CARD.asItem())) {
                if (getBlockEntity() instanceof BulkCellMarkerBlockEntity be) {
                    if (carried.isEmpty()) {
                        ItemStack removed = be.unloadCard(id);
                        if (!removed.isEmpty()) {
                            setCarried(removed);
                        }
                    } else {
                        setCarried(be.installCard(id, carried));
                    }
                }
                return;
            }
        }
        super.doAction(player, action, id, extraId);
    }

    public int getPage() {
        return page;
    }

    public int getLastPage() {
        return Math.max(0, (totalCells - 1) / SLOTS_PER_PAGE);
    }

    public int getPageCount() {
        return totalCells == 0 ? 0 : getLastPage() + 1;
    }

}
