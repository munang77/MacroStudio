package io.github.munang77.cosmeticscore.crate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.ToIntFunction;

import io.github.munang77.cosmeticscore.CosmeticsCore;
import io.github.munang77.cosmeticscore.Messages;
import io.github.munang77.cosmeticscore.Settings;
import io.github.munang77.cosmeticscore.cosmetic.Cosmetic;
import io.github.munang77.cosmeticscore.data.PlayerData;
import io.github.munang77.cosmeticscore.hook.EconomyHook;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * 코스메틱 뽑기. 열쇠가 있으면 열쇠를 쓰고, 없으면 설정된 값을 낸다.
 * 아직 없는 코스메틱 중에서 먼저 등급을 가중치로 고르고, 그 등급 안에서 하나를 고른다 (중복 없음).
 */
public final class CrateService {

    /** 결과 종류. */
    public enum Outcome {
        OK, DISABLED, COMPLETE, NO_KEY, NO_MONEY, ECONOMY_MISSING
    }

    /** 뽑기 결과. {@code reward} 는 성공했을 때만 있다. */
    public record Result(Outcome outcome, Cosmetic reward, List<Cosmetic> pool) {
    }

    private final CosmeticsCore plugin;

    public CrateService(CosmeticsCore plugin) {
        this.plugin = plugin;
    }

    /** 아직 없는 코스메틱 중 뽑기에 나오는 것. */
    public List<Cosmetic> candidates(Player player) {
        List<Cosmetic> pool = new ArrayList<>();
        for (Cosmetic c : plugin.registry().all()) {
            if (c.inCrate() && !plugin.manager().owns(player, c)) {
                pool.add(c);
            }
        }
        return pool;
    }

    /** 뽑는다. 보상은 애니메이션 전에 바로 지급하므로 창을 닫아도 잃지 않는다. */
    public Result open(Player player) {
        Settings settings = plugin.settings();
        PlayerData data = plugin.store().get(player.getUniqueId());
        if (!settings.crateEnabled() || data == null || data.readOnly()) {
            return new Result(Outcome.DISABLED, null, List.of());
        }
        List<Cosmetic> pool = candidates(player);
        if (pool.isEmpty()) {
            return new Result(Outcome.COMPLETE, null, pool);
        }
        if (!data.takeKey()) {
            double price = settings.cratePrice();
            if (price <= 0) {
                return new Result(Outcome.NO_KEY, null, pool);
            }
            EconomyHook economy = plugin.economy();
            if (!economy.available()) {
                return new Result(Outcome.ECONOMY_MISSING, null, pool);
            }
            if (!economy.has(player, price) || !economy.withdraw(player, price)) {
                return new Result(Outcome.NO_MONEY, null, pool);
            }
        }
        Cosmetic reward = pick(pool, key -> settings.rarity(key).weight(), ThreadLocalRandom.current());
        data.unlock(reward.id());
        plugin.store().save(data);
        if (settings.broadcastCrate(reward.rarity())) {
            Messages msg = plugin.messages();
            String text = msg.get("crate-broadcast", "player", player.getName(), "name", reward.name(),
                    "rarity", settings.rarity(reward.rarity()).name());
            for (Player online : Bukkit.getOnlinePlayers()) {
                online.sendMessage(msg.prefix() + text);
            }
        }
        return new Result(Outcome.OK, reward, pool);
    }

    /**
     * 등급을 가중치로 고른 뒤 그 등급 안에서 고르게 하나를 뽑는다. 모든 가중치가 0 이면 전체에서 고르게 뽑는다.
     */
    public static Cosmetic pick(List<Cosmetic> pool, ToIntFunction<String> weightOf, Random random) {
        Map<String, List<Cosmetic>> byRarity = new LinkedHashMap<>();
        for (Cosmetic c : pool) {
            byRarity.computeIfAbsent(c.rarity(), k -> new ArrayList<>()).add(c);
        }
        long total = 0;
        for (String rarity : byRarity.keySet()) {
            total += Math.max(0, weightOf.applyAsInt(rarity));
        }
        if (total <= 0) {
            return pool.get(random.nextInt(pool.size()));
        }
        long roll = (long) (random.nextDouble() * total);
        for (Map.Entry<String, List<Cosmetic>> entry : byRarity.entrySet()) {
            roll -= Math.max(0, weightOf.applyAsInt(entry.getKey()));
            if (roll < 0) {
                List<Cosmetic> list = entry.getValue();
                return list.get(random.nextInt(list.size()));
            }
        }
        List<Cosmetic> last = byRarity.values().stream().reduce((a, b) -> b).orElse(pool);
        return last.get(random.nextInt(last.size()));
    }
}
