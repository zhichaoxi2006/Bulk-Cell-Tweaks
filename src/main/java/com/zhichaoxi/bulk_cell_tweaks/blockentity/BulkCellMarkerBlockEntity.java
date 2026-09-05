package com.zhichaoxi.bulk_cell_tweaks.blockentity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.zhichaoxi.bulk_cell_tweaks.BulkCellTweaks;

import appeng.api.config.Actionable;
import appeng.api.implementations.blockentities.IChestOrDrive;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.IStorageMounts;
import appeng.api.storage.IStorageProvider;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageCells;
import appeng.api.storage.StorageHelper;
import appeng.api.storage.cells.StorageCell;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import appeng.blockentity.grid.AENetworkedInvBlockEntity;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.blockentity.storage.MEChestBlockEntity;
import appeng.me.storage.DriveWatcher;
import appeng.parts.AEBasePart;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;

import gripe._90.megacells.definition.MEGAItems;
import gripe._90.megacells.item.cell.BulkCellInventory;
import gripe._90.megacells.item.cell.BulkCellItem;
import gripe._90.megacells.item.part.CellDockPart;
import gripe._90.megacells.misc.CompressionChain;
import gripe._90.megacells.misc.CompressionService;

/**
 * 扫描 ME 网络中已挂载（驱动器 / ME 箱子 / Cell Dock）的 BulkCellItem，
 * 每页 63 格，往格子里放物品即把该物品写入对应 cell 的过滤，空格则清除过滤。
 */
public class BulkCellMarkerBlockEntity extends AENetworkedBlockEntity implements InternalInventoryHost {

    public static final int SLOTS_PER_PAGE = 63;

    /** 显示缓冲，槽位内容始终以网络中 cell 的过滤为准；持久化仅作为离线缓存。 */
    private final AppEngInternalInventory configInv = new AppEngInternalInventory(this, SLOTS_PER_PAGE);
    /** 当前网络中的 BulkCellItem 列表（瞬态，服务端有效）。 */
    private final List<CellRef> cells = new ArrayList<>();
    private int page;
    private boolean refreshing;

    public BulkCellMarkerBlockEntity(BlockPos pos, BlockState blockState) {
        // 实例只在注册完成后创建，直接引用注册常量是安全的
        super(BulkCellTweaks.BULK_CELL_MARKER_BE.get(), pos, blockState);
        getMainNode().setFlags(GridFlags.REQUIRE_CHANNEL)
                .setInWorldNode(true)
                .setExposedOnSides(EnumSet.allOf(Direction.class))
                .setIdlePowerUsage(2.0);
    }

    // ---- InternalInventoryHost ----

    public InternalInventory getInternalInventory() {
        return configInv;
    }

    @Override
    public void saveChangedInventory(AppEngInternalInventory inv) {
        markForUpdate();
    }

    @Override
    public void onChangeInventory(AppEngInternalInventory inv, int slot) {
        if (refreshing || isClientSide()) {
            return;
        }
        if (inv != configInv) {
            return;
        }
        int idx = page * SLOTS_PER_PAGE + slot;
        if (idx >= cells.size()) {
            return;
        }
        CellRef ref = cells.get(idx);
        ItemStack cellStack = ref.getCellStack();
        if (cellStack.isEmpty() || !(cellStack.getItem() instanceof BulkCellItem)) {
            refreshCells(); // cell 已被移走，重新同步映射
            return;
        }
        ItemStack marked = configInv.getStackInSlot(slot);
        // 先写目标过滤再去重：clearDuplicateMarks 里的 repopulateSlots 会按
        // cell 实际过滤重画全部显示槽，后写会把玩家刚放进的标记抹掉
        writeCellFilter(ref, marked.isEmpty() ? null : GenericStack.fromItemStack(marked));
        if (!marked.isEmpty()) {
            clearDuplicateMarks(ref, AEItemKey.of(marked));
        }
        markForUpdate();
    }

    /**
     * 标记去重（标记搬家）：把物品 X 标记到 target 时，先把其他过滤与 X
     * 完全相同的盘的内容全部搬进 target（搬得下才清标记——先转移后清除，
     * 否则物品会滞留在一个无过滤的大宗盘里，之后再也无法被转移），再清除
     * 其标记。压缩卡 cell 因链覆盖（过滤为同链其他变体）的不动。
     */
    private void clearDuplicateMarks(CellRef target, AEItemKey mark) {
        IActionSource src = IActionSource.ofMachine(this);
        // 与 IO 接口一致：搬移消耗网格能量（BulkCellInventory 自身不扣能）
        IEnergySource energy = getActionableNode().getGrid().getEnergyService();
        boolean cleared = false;
        boolean targetTouched = false;
        for (CellRef ref : cells) {
            if (ref == target) {
                continue;
            }
            GenericStack filter = bulkFilter(ref.getCellStack());
            if (filter != null && filter.what() instanceof AEItemKey key && key.equals(mark)) {
                if (!moveContentInto(target, ref, src, energy)) {
                    continue; // 目标盘装不下或能量不足，保留该盘的标记
                }
                writeCellFilter(ref, null);
                targetTouched = true;
                cleared = true;
            }
        }
        if (targetTouched) {
            // 内容并入后写回目标盘，触发机器重建 + 网络刷新
            ItemStack targetStack = target.getCellStack();
            target.setCellStack(targetStack);
        }
        if (cleared) {
            repopulateSlots(); // 被清掉的格子立即显示为空
        }
    }

    /**
     * 把 from 盘的全部内容搬进 to 盘：先用 SIMULATE 确认 to 装得下（装不下
     * 返回 false 且什么都不搬），再逐个抽出插入。插入走
     * StorageHelper.poweredInsert——与 IO 接口一致的供能机制（按可用能量
     * 缩水），能量不足放回剩余并返回 false。两个盘都经便携包装
     * （ISaveProvider 传 null，persist 直接写回 stack 实例）操作；from 的
     * 写回由调用方 writeCellFilter 触发，to 的写回由调用方 setCellStack 触发。
     */
    private boolean moveContentInto(CellRef to, CellRef from, IActionSource src, IEnergySource energy) {
        ItemStack toStack = to.getCellStack();
        ItemStack fromStack = from.getCellStack();
        StorageCell toCell = StorageCells.getCellInventory(toStack, null);
        StorageCell fromCell = StorageCells.getCellInventory(fromStack, null);
        List<GenericStack> contents = new ArrayList<>();
        for (var entry : fromCell.getAvailableStacks()) {
            if (entry.getKey() instanceof AEItemKey key && entry.getLongValue() > 0) {
                contents.add(new GenericStack(key, entry.getLongValue()));
            }
        }
        for (GenericStack stack : contents) {
            if (toCell.insert(stack.what(), stack.amount(), Actionable.SIMULATE, src) < stack.amount()) {
                return false; // 装不下，整组不搬（避免搬一半滞留）
            }
        }
        for (GenericStack stack : contents) {
            long extracted = fromCell.extract(stack.what(), stack.amount(), Actionable.MODULATE, src);
            long inserted = StorageHelper.poweredInsert(energy, toCell, stack.what(), extracted, src);
            if (inserted < extracted) {
                // 能量不足：把插不下的部分放回原盘，整组不搬（保持 from 的标记）
                fromCell.insert(stack.what(), extracted - inserted, Actionable.MODULATE, src);
                return false;
            }
        }
        return true;
    }

    /**
     * 自动标记按钮：按 ME 网络内物品数量从多到少，把物品写入过滤为空的 cell
     * （已被现有过滤覆盖的物品跳过，无空 cell 时停止）；随后把网络内所有
     * 已标记 cell（含既有过滤）对应的物品转移进各自的大宗盘（压缩卡 cell
     * 覆盖整条压缩链；容量不足的物品保留在网络）。
     */
    public void autoMark() {
        if (level == null || level.isClientSide) {
            return;
        }
        refreshCells();
        getMainNode().ifPresent(grid -> {
            IActionSource src = IActionSource.ofMachine(this);
            // 与 IO 接口一致：搬移消耗网格能量（BulkCellInventory 自身不扣能）
            IEnergySource energy = grid.getEnergyService();
            // 修复 pass：无过滤但装满的滞留盘补回标记（写过滤不搬物品，不耗能）
            repairStrandedCells();
            // 去重 pass：多个过滤完全相同的大宗，内容并入一个盘、其余取消标记
            consolidateDuplicateMarks(src, energy);
            KeyCounter counts = grid.getStorageService().getCachedInventory();
            List<AEItemKey> ordered = new ArrayList<>();
            for (var entry : counts) {
                if (entry.getKey() instanceof AEItemKey itemKey) {
                    ordered.add(itemKey);
                }
            }
            ordered.sort(Comparator.comparingLong((AEItemKey key) -> counts.get(key)).reversed()
                    .thenComparing(AEItemKey::getId));
            // 标记 pass：数量降序写入过滤为空的 cell
            for (AEItemKey key : ordered) {
                if (anyCellCovers(key)) {
                    continue;
                }
                CellRef empty = null;
                for (CellRef ref : cells) {
                    ItemStack cellStack = ref.getCellStack();
                    // 只把真正空白的盘用于新标记；"无过滤但装满"的滞留盘留给玩家检修
                    if (!cellStack.isEmpty() && bulkFilter(cellStack) == null
                            && storedContent(cellStack).isEmpty()) {
                        empty = ref;
                        break;
                    }
                }
                if (empty == null) {
                    break;
                }
                writeCellFilter(empty, new GenericStack(key, 1));
            }
            // 转移 pass：所有带过滤的 cell 收进对应物品
            List<StorageSource> sources = collectNonBulkSources(grid);
            Set<IGridNode> extractedNodes = new HashSet<>();
            for (CellRef ref : cells) {
                transferIntoCell(ref, src, sources, extractedNodes, energy);
            }
            // 抽取后强制重挂载被抽过的 provider：枚举时 mountInventories 会触发
            // 驱动器 updateState 重建 watcher（先置空再新建），网络 NetworkStorage
            // 里挂的仍是旧实例、缓存视图陈旧——不刷新的话终端里来源库存"没有消耗"。
            IStorageService storage = grid.getStorageService();
            for (IGridNode node : extractedNodes) {
                storage.refreshNodeStorageProvider(node);
            }
            if (!extractedNodes.isEmpty()) {
                storage.invalidateCache();
            }
        });
        repopulateSlots();
        markForUpdate();
    }

    private boolean anyCellCovers(AEItemKey key) {
        for (CellRef ref : cells) {
            if (cellCovers(ref, key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 修复 pass：给"无过滤但装满"的滞留盘补回标记（过滤 = 盘内存放的首个物品；
     * 装压缩卡时整条链随之覆盖）。只写过滤不搬物品；补回的标记若与其他盘重复，
     * 会交给随后的去重 pass 合并。
     */
    private void repairStrandedCells() {
        for (CellRef ref : cells) {
            ItemStack cellStack = ref.getCellStack();
            if (cellStack.isEmpty() || !(cellStack.getItem() instanceof BulkCellItem)) {
                continue;
            }
            if (bulkFilter(cellStack) != null) {
                continue;
            }
            for (var entry : storedContent(cellStack)) {
                if (entry.getKey() instanceof AEItemKey key && entry.getLongValue() > 0) {
                    writeCellFilter(ref, new GenericStack(key, 1));
                    break;
                }
            }
        }
    }

    /**
     * 去重 pass：多个过滤完全相同的大宗盘，把内容全部并入其中一个盘（keeper），
     * 其余盘取消标记（腾出的空盘随后可被标记 pass 重新标记）。keeper 取组内
     * 第一个能装下其他所有盘内容的盘（按 cells 顺序）；没人装得下则整组保持
     * 原状。只按精确相同的过滤分组，压缩链覆盖（过滤为同链其他变体）不算。
     */
    private void consolidateDuplicateMarks(IActionSource src, IEnergySource energy) {
        Map<AEItemKey, List<CellRef>> groups = new LinkedHashMap<>();
        for (CellRef ref : cells) {
            ItemStack cellStack = ref.getCellStack();
            if (cellStack.isEmpty()) {
                continue;
            }
            GenericStack filter = bulkFilter(cellStack);
            if (filter != null && filter.what() instanceof AEItemKey key) {
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(ref);
            }
        }
        for (List<CellRef> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            CellRef keeper = null;
            for (CellRef candidate : group) {
                if (canHoldAll(candidate, group, src)) {
                    keeper = candidate;
                    break;
                }
            }
            if (keeper == null) {
                continue;
            }
            for (CellRef other : group) {
                if (other == keeper) {
                    continue;
                }
                // 先转移内容、装得下才取消 other 的标记（写回触发机器重建 + 网络刷新）
                if (moveContentInto(keeper, other, src, energy)) {
                    writeCellFilter(other, null);
                }
            }
            // keeper 的 stack 写回触发重建，网络看到合并后的内容
            ItemStack keeperStack = keeper.getCellStack();
            keeper.setCellStack(keeperStack);
        }
    }

    /** candidate 能否装下组内其他所有盘的全部内容（SIMULATE 探测，不实际写入）。 */
    private boolean canHoldAll(CellRef candidate, List<CellRef> group, IActionSource src) {
        ItemStack candidateStack = candidate.getCellStack();
        StorageCell candidateCell = StorageCells.getCellInventory(candidateStack, null);
        for (CellRef other : group) {
            if (other == candidate) {
                continue;
            }
            StorageCell otherCell = StorageCells.getCellInventory(other.getCellStack(), null);
            for (var entry : otherCell.getAvailableStacks()) {
                if (entry.getKey() instanceof AEItemKey key && entry.getLongValue() > 0
                        && candidateCell.insert(key, entry.getLongValue(), Actionable.SIMULATE, src)
                                < entry.getLongValue()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 网络里除大宗 cell 外的存储来源（大宗盘只作转移目标，不互相抽取）。 */
    private record StorageSource(MEStorage storage, KeyCounter counts, IGridNode node) {
    }

    /**
     * 枚举网络全部存储来源并跳过大宗存储 inv：遍历节点上的 IStorageProvider，
     * 用自定义 IStorageMounts 收集其挂载的 MEStorage（与 StorageService 重建
     * 网格存储时对 provider 的调用同款机制，重调无副作用）。大宗 cell 的
     * 识别：驱动器 / CellDock 挂的是 DriveWatcher，取 getCell() 看底层是否
     * BulkCellInventory；ME 箱子挂的 ChestMonitorHandler 无公开访问器，直接
     * 看箱子里的 cell 物品。
     */
    private List<StorageSource> collectNonBulkSources(IGrid grid) {
        List<StorageSource> sources = new ArrayList<>();
        for (IGridNode node : grid.getNodes()) {
            if (!node.isActive()) {
                continue;
            }
            IStorageProvider provider = node.getService(IStorageProvider.class);
            if (provider == null) {
                continue;
            }
            Object owner = node.getOwner();
            if (owner instanceof MEChestBlockEntity chest
                    && chest.getCell().getItem() instanceof BulkCellItem) {
                continue; // ME 箱子挂的是大宗 cell，整节点不作来源
            }
            List<MEStorage> mounted = new ArrayList<>();
            provider.mountInventories((storage, priority) -> mounted.add(storage));
            for (MEStorage storage : mounted) {
                if (storage instanceof DriveWatcher watcher
                        && watcher.getCell() instanceof BulkCellInventory) {
                    continue; // 驱动器 / CellDock 里的大宗 cell
                }
                sources.add(new StorageSource(storage, storage.getAvailableStacks(), node));
            }
        }
        return sources;
    }

    /**
     * 把该 cell 过滤对应的网络物品转移进盘内：只从非大宗来源（常规 cell、
     * 储物总线等）抽取——已全在盘内的物品天然不动，大宗盘之间也不互抽。
     * 按来源逐个抽取；插入用抽取后新建的 cell 包装（ISaveProvider 传 null 时
     * persist 直接写回 stack 实例），避免旧包装的缓存状态覆盖抽取结果。
     * 插入走 StorageHelper.poweredInsert——与 IO 接口一致的供能机制（按可用
     * 能量缩水），能量不足的部分放回来源。
     */
    private void transferIntoCell(CellRef ref, IActionSource src, List<StorageSource> sources,
            Set<IGridNode> extractedNodes, IEnergySource energy) {
        ItemStack cellStack = ref.getCellStack();
        if (cellStack.isEmpty() || !(cellStack.getItem() instanceof BulkCellItem bulk)) {
            return;
        }
        GenericStack filter = bulkFilter(cellStack);
        if (filter == null || !(filter.what() instanceof AEItemKey filterKey)) {
            return;
        }
        Set<AEItemKey> targets = new LinkedHashSet<>();
        targets.add(filterKey);
        if (bulk.getUpgrades(cellStack).isInstalled(MEGAItems.COMPRESSION_CARD)) {
            CompressionChain chain = CompressionService.getChain(filterKey);
            for (int i = 0; i < chain.size(); i++) {
                targets.add(AEItemKey.of(chain.getItem(i)));
            }
        }
        boolean moved = false;
        for (AEItemKey key : targets) {
            long inSources = 0;
            for (StorageSource source : sources) {
                inSources += source.counts().get(key);
            }
            if (inSources <= 0) {
                continue; // 非大宗来源里没有该物品
            }
            StorageCell cell = StorageCells.getCellInventory(cellStack, null);
            long capacity = cell.insert(key, inSources, Actionable.SIMULATE, src);
            long toMove = Math.min(inSources, capacity);
            long remaining = toMove;
            for (StorageSource source : sources) {
                if (remaining <= 0) {
                    break;
                }
                long got = source.storage().extract(key, remaining, Actionable.MODULATE, src);
                if (got > 0) {
                    extractedNodes.add(source.node());
                }
                remaining -= got;
            }
            long extracted = toMove - remaining;
            // 抽取后再包装一次，确保读到抽取后的最新盘内状态
            StorageCell fresh = StorageCells.getCellInventory(cellStack, null);
            long inserted = StorageHelper.poweredInsert(energy, fresh, key, extracted, src);
            long remainder = extracted - inserted;
            if (remainder > 0) {
                // 能量不足：插不下的部分放回来源（来源刚腾出等量空间，必能装下）
                for (StorageSource source : sources) {
                    if (remainder <= 0) {
                        break;
                    }
                    remainder -= source.storage().insert(key, remainder, Actionable.MODULATE, src);
                }
            }
            if (inserted > 0) {
                moved = true;
            }
        }
        if (moved) {
            // setter 触发机器重建 StorageCell 并刷新网格存储
            ref.setCellStack(cellStack);
        }
    }

    /** 给指定显示槽对应的 cell 装入压缩卡（手持压缩卡右键槽位）；返回装不下的剩余堆。 */
    public ItemStack installCard(int slot, ItemStack card) {
        int idx = page * SLOTS_PER_PAGE + slot;
        if (idx >= cells.size()) {
            return card;
        }
        CellRef ref = cells.get(idx);
        ItemStack cellStack = ref.getCellStack();
        if (cellStack.isEmpty() || !(cellStack.getItem() instanceof BulkCellItem bulk)) {
            refreshCells(); // cell 已被移走，重新同步映射
            return card;
        }
        ItemStack remainder = bulk.getUpgrades(cellStack).addItems(card);
        ref.setCellStack(cellStack); // 触发机器重建 StorageCell（压缩卡影响 cell 行为）
        markForUpdate();
        return remainder;
    }

    /** 从指定显示槽对应的 cell 取出一张压缩卡（空手右键槽位）。 */
    public ItemStack unloadCard(int slot) {
        int idx = page * SLOTS_PER_PAGE + slot;
        if (idx >= cells.size()) {
            return ItemStack.EMPTY;
        }
        CellRef ref = cells.get(idx);
        ItemStack cellStack = ref.getCellStack();
        if (cellStack.isEmpty() || !(cellStack.getItem() instanceof BulkCellItem bulk)) {
            refreshCells();
            return ItemStack.EMPTY;
        }
        ItemStack removed = bulk.getUpgrades(cellStack).removeItems(1,
                MEGAItems.COMPRESSION_CARD.stack(),
                stack -> stack.is(MEGAItems.COMPRESSION_CARD.asItem()));
        ref.setCellStack(cellStack);
        markForUpdate();
        return removed;
    }

    /**
     * 该 cell 是否已覆盖此标记：
     * - 过滤与该物品完全相同（任何 cell，现行为）；
     * - 或装有压缩卡且过滤与标记同属一条压缩链（压缩卡 cell 会存储整条链上的所有变体）。
     */
    private boolean cellCovers(CellRef ref, AEItemKey mark) {
        ItemStack cellStack = ref.getCellStack();
        if (cellStack.isEmpty() || !(cellStack.getItem() instanceof BulkCellItem bulk)) {
            return false;
        }
        GenericStack filter = bulkFilter(cellStack);
        if (filter == null || !(filter.what() instanceof AEItemKey key)) {
            return false;
        }
        if (key.equals(mark)) {
            return true;
        }
        if (bulk.getUpgrades(cellStack).isInstalled(MEGAItems.COMPRESSION_CARD)) {
            CompressionChain chain = CompressionService.getChain(mark);
            return !chain.isEmpty() && chain.containsVariant(key);
        }
        return false;
    }

    private static GenericStack bulkFilter(ItemStack cellStack) {
        return cellStack.getItem() instanceof BulkCellItem bulk
                ? bulk.getConfigInventory(cellStack).getStack(0)
                : null;
    }

    /** cell 内存放的物品快照（"无过滤但装满"的滞留盘检修用；仅对 BulkCellItem 调用）。 */
    private static KeyCounter storedContent(ItemStack cellStack) {
        return StorageCells.getCellInventory(cellStack, null).getAvailableStacks();
    }

    /** 该显示槽对应的 cell 是否"无过滤但装满"（滞留盘，可空手左键检修）。 */
    public boolean isStranded(int slot) {
        int idx = page * SLOTS_PER_PAGE + slot;
        if (idx >= cells.size()) {
            return false;
        }
        ItemStack cellStack = cells.get(idx).getCellStack();
        return !cellStack.isEmpty() && cellStack.getItem() instanceof BulkCellItem
                && bulkFilter(cellStack) == null && !storedContent(cellStack).isEmpty();
    }

    /**
     * 检修：把"无过滤但装满"的滞留盘内容抽出并供能插入回网络（与 IO 接口
     * 一致）——网络会自动路由到对应过滤的大宗盘或普通存储；能量不足的部分
     * 留在盘内。
     */
    public void drainCell(int slot) {
        if (level == null || level.isClientSide) {
            return;
        }
        int idx = page * SLOTS_PER_PAGE + slot;
        if (idx >= cells.size()) {
            return;
        }
        CellRef ref = cells.get(idx);
        ItemStack cellStack = ref.getCellStack();
        if (cellStack.isEmpty() || !(cellStack.getItem() instanceof BulkCellItem)) {
            refreshCells();
            return;
        }
        if (bulkFilter(cellStack) != null) {
            return; // 只检修滞留盘
        }
        getMainNode().ifPresent(grid -> {
            IActionSource src = IActionSource.ofMachine(this);
            IEnergySource energy = grid.getEnergyService();
            MEStorage net = grid.getStorageService().getInventory();
            StorageCell cell = StorageCells.getCellInventory(cellStack, null);
            for (var entry : cell.getAvailableStacks()) {
                if (!(entry.getKey() instanceof AEItemKey key) || entry.getLongValue() <= 0) {
                    continue;
                }
                long extracted = cell.extract(key, entry.getLongValue(), Actionable.MODULATE, src);
                long inserted = StorageHelper.poweredInsert(energy, net, key, extracted, src);
                if (inserted < extracted) {
                    // 能量不足：差额放回盘内
                    cell.insert(key, extracted - inserted, Actionable.MODULATE, src);
                }
            }
            // 写回触发机器重建 + 网络刷新
            ref.setCellStack(cellStack);
        });
        repopulateSlots();
        markForUpdate();
    }

    /** 把过滤写入 cell 并触发机器重算（ConfigInventory 绑定同一 stack 实例，setStack 立即写回数据组件）。 */
    private void writeCellFilter(CellRef ref, GenericStack filter) {
        ItemStack cellStack = ref.getCellStack();
        if (cellStack.getItem() instanceof BulkCellItem bulk) {
            bulk.getConfigInventory(cellStack).setStack(0, filter);
        }
        // setter 触发机器自身的 onChangeInventory -> 重建 StorageCell + 刷新网格存储
        ref.setCellStack(cellStack);
    }

    // ---- 扫描与翻页 ----

    public void refreshCells() {
        if (level == null || level.isClientSide) {
            return;
        }
        cells.clear();
        getMainNode().ifPresent(grid -> {
            List<CellRef> found = new ArrayList<>();
            // getActiveMachines/getMachines 按具体 owner 类索引（Grid.add 的 multimap 键 = owner.getClass()），
            // 传接口/超类恒为空集 —— 只能遍历全部节点后按类型过滤
            for (IGridNode node : grid.getNodes()) {
                if (!node.isActive()) {
                    continue;
                }
                Object owner = node.getOwner();
                if (owner instanceof AENetworkedInvBlockEntity machine) {
                    // 带主库存的网络方块实体（驱动器等），直接用主库存扫
                    InternalInventory inv = machine.getInternalInventory();
                    for (int i = 0; i < inv.size(); i++) {
                        if (inv.getStackInSlot(i).getItem() instanceof BulkCellItem) {
                            found.add(CellRef.of(inv, i, machinePos(machine), sideOf(machine)));
                        }
                    }
                } else if (owner instanceof IChestOrDrive machine) {
                    // ME 箱子 / CellDock 等（不是 AENetworkedInvBlockEntity 的 cell 容器）
                    for (int i = 0; i < machine.getCellCount(); i++) {
                        Item item = machine.getCellItem(i); // 空槽返回 null
                        if (item instanceof BulkCellItem) {
                            found.add(CellRef.of(machine, i, machinePos(machine), sideOf(machine)));
                        }
                    }
                }
            }
            found.sort(CELL_REF_ORDER);
            cells.addAll(found);
        });
        page = Mth.clamp(page, 0, lastPage());
        repopulateSlots();
    }

    public void gotoPage(int delta) {
        if (level == null || level.isClientSide) {
            return;
        }
        refreshCells();
        int target = Mth.clamp(page + delta, 0, lastPage());
        if (target == page) {
            return;
        }
        page = target;
        repopulateSlots();
        markForUpdate();
    }

    private void repopulateSlots() {
        refreshing = true;
        try {
            for (int slot = 0; slot < SLOTS_PER_PAGE; slot++) {
                int idx = page * SLOTS_PER_PAGE + slot;
                ItemStack display = ItemStack.EMPTY;
                if (idx < cells.size()) {
                    ItemStack cellStack = cells.get(idx).getCellStack();
                    if (!cellStack.isEmpty() && cellStack.getItem() instanceof BulkCellItem bulk) {
                        GenericStack gs = bulk.getConfigInventory(cellStack).getStack(0);
                        if (gs != null && gs.what() instanceof AEItemKey key) {
                            display = key.toStack();
                        } else {
                            // 无过滤但装满的滞留盘：显示盘内存放的物品，便于检修
                            for (var entry : storedContent(cellStack)) {
                                if (entry.getKey() instanceof AEItemKey key && entry.getLongValue() > 0) {
                                    display = key.toStack();
                                    break;
                                }
                            }
                        }
                    }
                }
                configInv.setItemDirect(slot, display);
            }
        } finally {
            refreshing = false;
        }
    }

    // ---- 网格状态变化时重同步 ----

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.State reason) {
        super.onMainNodeStateChanged(reason);
        if (level != null && !level.isClientSide) {
            refreshCells();
        }
    }

    // ---- 持久化 ----

    @Override
    public void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("page", page);
        NonNullList<ItemStack> stacks = NonNullList.withSize(SLOTS_PER_PAGE, ItemStack.EMPTY);
        for (int i = 0; i < SLOTS_PER_PAGE; i++) {
            stacks.set(i, configInv.getStackInSlot(i));
        }
        tag.put("config", ContainerHelper.saveAllItems(new CompoundTag(), stacks, registries));
    }

    @Override
    public void loadTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadTag(tag, registries);
        page = tag.getInt("page");
        NonNullList<ItemStack> stacks = NonNullList.withSize(SLOTS_PER_PAGE, ItemStack.EMPTY);
        if (tag.contains("config", Tag.TAG_COMPOUND)) {
            ContainerHelper.loadAllItems(tag.getCompound("config"), stacks, registries);
        }
        refreshing = true;
        for (int i = 0; i < SLOTS_PER_PAGE; i++) {
            configInv.setItemDirect(i, stacks.get(i));
        }
        refreshing = false;
    }

    // ---- 访问器 ----

    public int getPage() {
        return page;
    }

    public List<CellRef> getCells() {
        return cells;
    }

    private int lastPage() {
        return Math.max(0, (cells.size() - 1) / SLOTS_PER_PAGE);
    }

    // ---- cell 引用 ----

    /** cell 引用：持有者可能是 AENetworkedInvBlockEntity（用主库存）或 IChestOrDrive（ME 箱子/CellDock），按类型分派存取。 */
    public record CellRef(Supplier<ItemStack> getter, Consumer<ItemStack> setter,
            int slotIndex, GlobalPos pos, int side) {

        public ItemStack getCellStack() {
            return getter.get();
        }

        public void setCellStack(ItemStack stack) {
            setter.accept(stack);
        }

        /** 带主库存的网络方块实体（驱动器等）：直接读写主库存槽。 */
        static CellRef of(InternalInventory inv, int slot, GlobalPos pos, int side) {
            return new CellRef(() -> inv.getStackInSlot(slot),
                    stack -> inv.setItemDirect(slot, stack), slot, pos, side);
        }

        /** IChestOrDrive（ME 箱子 / CellDock / 驱动器）：按具体类型分派。 */
        static CellRef of(IChestOrDrive machine, int slot, GlobalPos pos, int side) {
            return new CellRef(() -> getStack(machine, slot),
                    stack -> setStack(machine, slot, stack), slot, pos, side);
        }

        private static ItemStack getStack(IChestOrDrive machine, int slot) {
            if (machine instanceof MEChestBlockEntity chest) {
                return chest.getCell();
            }
            if (machine instanceof DriveBlockEntity drive) {
                return drive.getInternalInventory().getStackInSlot(slot);
            }
            if (machine instanceof CellDockPart dock) {
                return dock.getCellInventory().getStackInSlot(slot);
            }
            return ItemStack.EMPTY;
        }

        private static void setStack(IChestOrDrive machine, int slot, ItemStack stack) {
            if (machine instanceof MEChestBlockEntity chest) {
                chest.setCell(stack);
            } else if (machine instanceof DriveBlockEntity drive) {
                drive.getInternalInventory().setItemDirect(slot, stack);
            } else if (machine instanceof CellDockPart dock) {
                dock.getCellInventory().setItemDirect(slot, stack);
            }
        }
    }

    private static GlobalPos machinePos(Object machine) {
        BlockEntity be = machine instanceof BlockEntity blockEntity ? blockEntity
                : machine instanceof AEBasePart part ? part.getBlockEntity() : null;
        return GlobalPos.of(be != null && be.getLevel() != null ? be.getLevel().dimension() : Level.OVERWORLD,
                be != null ? be.getBlockPos() : BlockPos.ZERO);
    }

    private static int sideOf(Object machine) {
        return machine instanceof AEBasePart part ? part.getSide().ordinal() : 0;
    }

    private static final Comparator<CellRef> CELL_REF_ORDER = Comparator
            .comparing((CellRef ref) -> ref.pos().dimension().location())
            .thenComparing(ref -> ref.pos().pos())
            .thenComparingInt(CellRef::side)
            .thenComparingInt(CellRef::slotIndex);
}
