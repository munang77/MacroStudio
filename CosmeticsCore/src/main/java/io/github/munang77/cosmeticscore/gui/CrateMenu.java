package io.github.munang77.cosmeticscore.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import io.github.munang77.cosmeticscore.CosmeticsCore;
import io.github.munang77.cosmeticscore.Messages;
import io.github.munang77.cosmeticscore.cosmetic.Cosmetic;
import io.github.munang77.cosmeticscore.crate.CrateService;
import io.github.munang77.cosmeticscore.util.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

/** 뽑기 연출: 가운데 칸에서 후보가 빠르게 돌다가 점점 느려지며 당첨 코스메틱에 멈춘다. 보상은 이미 지급된 상태다. */
public final class CrateMenu extends Menu {

    private static final int SIZE = 27;
    private static final int CENTER = 13;
    /** 멈추기 전까지 바뀌는 간격(틱). 뒤로 갈수록 느려진다. */
    private static final int[] DELAYS = {1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 4, 4, 5, 6, 8};

    private final Cosmetic reward;
    private final List<Cosmetic> pool;
    private boolean finished;

    private CrateMenu(CosmeticsCore plugin, Player viewer, Cosmetic reward, List<Cosmetic> pool) {
        super(plugin, viewer, SIZE, plugin.messages().get("menu.crate.title"));
        this.reward = reward;
        this.pool = pool.isEmpty() ? List.of(reward) : new ArrayList<>(pool);
    }

    /** 뽑기를 하고, 성공하면 연출 창을 연다. 실패하면 이유를 알린다. */
    public static void openCrate(CosmeticsCore plugin, Player player) {
        Messages msg = plugin.messages();
        CrateService.Result result = plugin.crates().open(player);
        switch (result.outcome()) {
            case OK -> {
                CrateMenu menu = new CrateMenu(plugin, player, result.reward(), result.pool());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        menu.open();
                        menu.spin();
                    }
                });
            }
            case DISABLED -> msg.send(player, "crate-disabled");
            case COMPLETE -> msg.send(player, "crate-complete");
            case NO_KEY -> msg.send(player, "crate-no-key");
            case NO_MONEY -> msg.send(player, "crate-no-money",
                    "price", plugin.economy().format(plugin.settings().cratePrice()));
            case ECONOMY_MISSING -> msg.send(player, "economy-missing");
        }
    }

    @Override
    protected void render() {
        inventory.clear();
        ItemStack frame = new ItemBuilder(Material.PURPLE_STAINED_GLASS_PANE).name(" ").hideTooltipExtras().build();
        for (int i = 0; i < SIZE; i++) {
            inventory.setItem(i, frame);
        }
        inventory.setItem(4, new ItemBuilder(Material.HOPPER).name(plugin.messages().get("menu.crate.pointer")).build());
        show(pool.get(ThreadLocalRandom.current().nextInt(pool.size())), false);
    }

    private void show(Cosmetic cosmetic, boolean won) {
        List<String> lore = new ArrayList<>();
        lore.add(plugin.messages().get("menu.item.rarity", "rarity", plugin.settings().rarity(cosmetic.rarity()).name()));
        inventory.setItem(CENTER, cosmetic.icon().builder(plugin.getLogger())
                .name(cosmetic.name())
                .lore(lore)
                .glow(won)
                .hideTooltipExtras()
                .build());
    }

    private void spin() {
        new BukkitRunnable() {
            private int step;
            private int wait;

            @Override
            public void run() {
                if (!viewer.isOnline()) {
                    cancel();
                    return;
                }
                if (wait-- > 0) {
                    return;
                }
                if (step >= DELAYS.length) {
                    finish();
                    cancel();
                    return;
                }
                wait = DELAYS[step++] - 1;
                show(pool.get(ThreadLocalRandom.current().nextInt(pool.size())), false);
                sound("block.note_block.hat", 1.0f + step * 0.05f);
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void finish() {
        if (finished) {
            return;
        }
        finished = true;
        Messages msg = plugin.messages();
        String rarity = plugin.settings().rarity(reward.rarity()).name();
        show(reward, true);
        sound("ui.toast.challenge_complete", 1.0f);
        viewer.sendTitle(msg.get("crate-title"), reward.name(), 5, 50, 15);
        msg.send(viewer, "crate-won", "name", reward.name(), "rarity", rarity);
    }

    @Override
    protected void click(int slot, ClickType click) {
        if (slot == CENTER && finished) {
            closeLater(() -> { });
        }
    }
}
