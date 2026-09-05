package com.zhichaoxi.bulk_cell_tweaks.block;

import com.zhichaoxi.bulk_cell_tweaks.BulkCellTweaks;
import com.zhichaoxi.bulk_cell_tweaks.blockentity.BulkCellMarkerBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import appeng.block.AEBaseEntityBlock;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import org.jetbrains.annotations.NotNull;

public class BulkCellMarkerBlock extends AEBaseEntityBlock<BulkCellMarkerBlockEntity> {

    public BulkCellMarkerBlock() {
        super(metalProps());
    }

    @Override
    protected @NotNull InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                        Player player, BlockHitResult hitResult) {
        if (!(player instanceof ServerPlayer)) {
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        BulkCellMarkerBlockEntity be = getBlockEntity(level, pos);
        if (be != null) {
            be.refreshCells();
            MenuOpener.open(BulkCellTweaks.BULK_CELL_MARKER_MENU.get(), player, MenuLocators.forBlockEntity(be));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
