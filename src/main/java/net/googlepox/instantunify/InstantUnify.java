package net.googlepox.instantunify;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

import net.minecraftforge.registries.ForgeRegistries;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(InstantUnify.MOD_ID)
public class InstantUnify {

    public static final String MOD_ID = "instantunify";
    public static final Logger LOG = LogManager.getLogger(InstantUnify.class);

    private ForgeConfigSpec.ConfigValue<List<? extends String>> blacklist, whitelist, preferredMods, blacklistedMods;
    private ForgeConfigSpec.BooleanValue drop, harvest, gui, second, death, change;
    private ForgeConfigSpec.EnumValue<ListMode> listMode;
    private ForgeConfigSpec.ConfigValue<List<? extends List<? extends String>>> alts;
    private Map<String, List<String>> alternatives;

    private Cache<Item, ResourceLocation[]> tagNameCache = CacheBuilder.newBuilder()
            .expireAfterWrite(20, TimeUnit.SECONDS).build();

    public InstantUnify() {
        Pair<Object, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(b -> {
            blacklist = b.comment("Tag names that shouldn't be unified (supports regex)")
                    .defineList("blacklist", Arrays.asList("minecraft:.+", "forge:glass.+"), s -> s instanceof String);
            whitelist = b.comment("Tag names that should be unified (supports regex)").defineList("whitelist",
                    Arrays.asList("forge:ores\\/.+", "forge:ingots\\/.+", "forge:nuggets\\/.+", "forge:raw_materials\\/.+",
                            "forge:storage_blocks\\/.+", "forge:gems\\/.+", "forge:dusts\\/.+", "forge:gears\\/.+",
                            "forge:plates\\/.+", "forge:rods\\/.+"), s -> s instanceof String);
            listMode = b.defineEnum("listMode", ListMode.USE_BOTH_LISTS);
            preferredMods = b.comment("Preferred Mods").defineList("preferredMods",
                    Arrays.asList("minecraft", "ftbmaterials", "alltheores", "thermal_foundation", "immersiveengineering", "embers"),
                    s -> s instanceof String);
            blacklistedMods = b.comment("Blacklisted Mods")
                    .defineList("blacklistMods", Arrays.asList("chisel", "astralsorcery"), s -> s instanceof String);
            alts = b.comment("Tag names that should be unified even if they are different")
                    .defineList("alternatives", Arrays.asList(Arrays.asList("aluminum", "aluminium", "bauxite")),//
                            o -> o instanceof List && (((List) o).isEmpty() || ((List) o).get(0) instanceof String));
            b.push("Unify event");
            drop = b.comment("Unify when items drop").define("drop", true);
            //harvest = b.comment("Unify when blocks are harvested").define("harvest", true);
            death = b.comment("Unify drops when entities die").define("death", true);
            second = b.comment("Unify every second items in player's inventory").define("second", false);
            //gui = b.comment("Unify items in player's inventory when GUI is opened/closed").define("gui", false);
            change = b.comment("Unify items in player's inventory when they change").define("change", true);
            b.pop();
            return null;
        });
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, pair.getValue());
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void spawn(EntityJoinLevelEvent event) {
        if (drop.get() && event.getEntity() instanceof ItemEntity && !event.getLevel().isClientSide()) {
            replace(((ItemEntity) event.getEntity()).getItem())
                    .ifPresent(s -> ((ItemEntity) event.getEntity()).setItem(s));
        }
    }
/*
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void harvest(LootTableLoadEvent event) {
        if (harvest.get()) {
            LootTable table = event.getTable();
            table.
            event.getDrops().replaceAll(s -> replace(s).orElse(s));
        }
    } */

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void death(LivingDropsEvent event) {
        if (death.get()) {
            event.getDrops().forEach(e -> replace(e.getItem()).ifPresent(e::setItem));
        }
    }

    @SubscribeEvent
    public void tick(TickEvent.PlayerTickEvent event) {
        if (second.get() && event.phase == TickEvent.Phase.END && !event.player.level().isClientSide() && event.player.level()
                .getGameTime() % 20 == 18) {
            ServerPlayer player = (ServerPlayer) event.player;
            if (replaceInventory(player)) {
                player.containerMenu.broadcastChanges();
            }
        }
    }

    private boolean replaceInventory(ServerPlayer player) {
        boolean changed = false;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack slot = player.getInventory().getItem(i);
            Optional<ItemStack> op = replace(slot);
            if (op.isPresent()) {
                player.getInventory().setItem(i, op.get());
                changed = true;
            }
        }
        return changed;
    }

    //@SubscribeEvent
    public void open(PlayerContainerEvent event) {
        if (gui.get() && !event.getEntity().level().isClientSide()) {
            ServerPlayer player = (ServerPlayer) event.getEntity();
            if (replaceInventory(player)) {
                player.containerMenu.broadcastChanges();
            }
        }
    }

    @SubscribeEvent
    public void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer && !event.getEntity().level().isClientSide()) {
            event.getEntity().containerMenu.addSlotListener(new net.minecraft.world.inventory.ContainerListener() {
                @Override
                public void slotChanged(AbstractContainerMenu pContainerToSend, int pDataSlotIndex, ItemStack pStack) {
                    if (change.get()) {
                        replace(pContainerToSend.getItems().get(pDataSlotIndex))//
                                .ifPresent(s -> pContainerToSend.setItem(pDataSlotIndex, pContainerToSend.getStateId(), s));
                    }
                }

                @Override
                public void dataChanged(AbstractContainerMenu pContainerMenu, int pDataSlotIndex, int pValue) {

                }
            });
        }
    }

    private Optional<ItemStack> replace(ItemStack orig) {
        if (orig.isEmpty() || (orig.getTag() != null && !orig.getTag().isEmpty())
                || blacklistedMods.get().contains(ForgeRegistries.ITEMS.getKey(orig.getItem()).getNamespace())) {
            return Optional.empty();
        }
        final ResourceLocation[] tagNames = tagNames(orig);
        if (tagNames.length == 0) {
            return Optional.empty();
        }
        List<List<ItemStack>> itemLists = Arrays.stream(tagNames)//
                .map(rl -> ForgeRegistries.ITEMS.tags().createTagKey(rl))//
                .map(tag -> ForgeRegistries.ITEMS.getValues().stream()//
                        .sorted((i1, i2) -> {
                            int index1 = preferredMods.get().indexOf(ForgeRegistries.ITEMS.getKey(i1).getNamespace()),//
                                    index2 = preferredMods.get().indexOf(ForgeRegistries.ITEMS.getKey(i2).getNamespace());
                            return Integer.compare(index1 == -1 ? 999 : index1, index2 == -1 ? 999 : index2);
                        })//
                        .map(i -> new ItemStack(i, orig.getCount()))//
                        .collect(Collectors.toList()))//
                .filter(l -> !l.isEmpty())//
                .toList();
        for (List<ItemStack> items : itemLists) {
            for (ItemStack item : items) {
                if (Arrays.equals(tagNames, tagNames(item))) {
                    return Optional.of(item);
                }
            }
        }
        return Optional.empty();
    }

    private ResourceLocation[] tagNames(ItemStack s) {
        try {
            return tagNameCache.get(s.getItem(), () -> {
                Set<ResourceLocation> unmodifiableNames = s.getTags().map(TagKey::location).collect(Collectors.toSet());
                Set<ResourceLocation> names = new HashSet<>(unmodifiableNames);
                if (alternatives == null) {
                    alternatives = new HashMap<>();
                    for (List<? extends String> lis : alts.get()) {
                        for (String n : lis) {
                            List<String> copy = new ArrayList<>(lis);
                            Validate.isTrue(!n.contains(":"), ": is not allowed in alternative tag");
                            copy.remove(n);
                            if (!copy.isEmpty())
                                alternatives.put(n, copy);
                        }
                    }
                }
                for (ResourceLocation name : unmodifiableNames) {
                    for (Map.Entry<String, List<String>> e : alternatives.entrySet()) {
                        String key = e.getKey();
                        if (name.getPath().contains(key)) {
                            List<String> val = e.getValue();
                            for (String alt : val) {
                                names.add(new ResourceLocation(name.getNamespace(), name.getPath().replace(key, alt)));
                            }
                        }
                    }
                }
                return names.stream().filter(r ->//
                        ((listMode.get() != ListMode.USE_WHITELIST//
                                && listMode.get() != ListMode.USE_BOTH_LISTS)//
                                || whitelist.get().stream().anyMatch(ss -> Pattern.matches(ss, r.toString())))//
                                //###############################################
                                && ((listMode.get() != ListMode.USE_BLACKLIST//
                                && listMode.get() != ListMode.USE_BOTH_LISTS)//
                                || blacklist.get().stream().noneMatch(ss -> Pattern.matches(ss, r.toString()))))
                        .sorted().toArray(ResourceLocation[]::new);
            });
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private enum ListMode {
        USE_WHITELIST, USE_BLACKLIST, USE_BOTH_LISTS, USE_NO_LIST;
    }

}
