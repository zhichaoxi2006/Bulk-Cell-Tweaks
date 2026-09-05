package com.zhichaoxi.bulk_cell_tweaks;

import appeng.api.AECapabilities;
import appeng.api.networking.IInWorldGridNodeHost;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.zhichaoxi.bulk_cell_tweaks.block.BulkCellMarkerBlock;
import com.zhichaoxi.bulk_cell_tweaks.blockentity.BulkCellMarkerBlockEntity;
import com.zhichaoxi.bulk_cell_tweaks.menu.BulkCellMarkerMenu;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import appeng.blockentity.AEBaseBlockEntity;
import appeng.menu.implementations.MenuTypeBuilder;

@Mod(BulkCellTweaks.MODID)
public class BulkCellTweaks {
    public static final String MODID = "bulk_cell_tweaks";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredBlock<BulkCellMarkerBlock> BULK_CELL_MARKER_BLOCK =
            BLOCKS.register("bulk_cell_marker", BulkCellMarkerBlock::new);
    public static final DeferredItem<BlockItem> BULK_CELL_MARKER_ITEM =
            ITEMS.registerSimpleBlockItem("bulk_cell_marker", BULK_CELL_MARKER_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BulkCellMarkerBlockEntity>> BULK_CELL_MARKER_BE =
            BLOCK_ENTITIES.register("bulk_cell_marker",
                    () -> BlockEntityType.Builder.of(BulkCellMarkerBlockEntity::new, BULK_CELL_MARKER_BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<BulkCellMarkerMenu>> BULK_CELL_MARKER_MENU =
            MENUS.register("bulk_cell_marker",
                    () -> MenuTypeBuilder.create(BulkCellMarkerMenu::new, BulkCellMarkerBlockEntity.class)
                            .withInitialData(
                                    (BulkCellMarkerBlockEntity be, RegistryFriendlyByteBuf buf) -> {
                                        buf.writeVarInt(be.getPage());
                                        buf.writeVarInt(be.getCells().size());
                                    },
                                    (BulkCellMarkerBlockEntity be, BulkCellMarkerMenu menu, RegistryFriendlyByteBuf buf) ->
                                            menu.receiveInitialData(buf.readVarInt(), buf.readVarInt()))
                            .buildUnregistered(ResourceLocation.fromNamespaceAndPath(MODID, "bulk_cell_marker")));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB =
            CREATIVE_TABS.register("tab", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + MODID))
                    .icon(() -> new ItemStack(BULK_CELL_MARKER_ITEM.get()))
                    .displayItems((params, output) -> output.accept(BULK_CELL_MARKER_ITEM.get()))
                    .build());

    public BulkCellTweaks(IEventBus modEventBus, ModContainer modContainer) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        MENUS.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);
        modEventBus.addListener(this::commonSetup);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        BULK_CELL_MARKER_BLOCK.get().setBlockEntity(
                BulkCellMarkerBlockEntity.class, BULK_CELL_MARKER_BE.get(), null, null);
        AEBaseBlockEntity.registerBlockEntityItem(BULK_CELL_MARKER_BE.get(), BULK_CELL_MARKER_ITEM.get());
    }

    @EventBusSubscriber(modid = MODID)
    public static class ModEvents
    {
        @SubscribeEvent
        public static void registerCapabilities(RegisterCapabilitiesEvent event) {
                event.registerBlockEntity(
                        AECapabilities.IN_WORLD_GRID_NODE_HOST, BULK_CELL_MARKER_BE.get(), (be, context) -> (IInWorldGridNodeHost) be);
        }
    }
}
