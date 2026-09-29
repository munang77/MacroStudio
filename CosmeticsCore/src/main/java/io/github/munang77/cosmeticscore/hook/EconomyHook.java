package io.github.munang77.cosmeticscore.hook;

import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Vault 경제 연동. Vault 를 컴파일에 넣지 않고 리플렉션으로 부르므로 Vault 가 없는 서버에서도 그대로 켜진다.
 * 경제 플러그인(Essentials 등)이 Vault 에 늦게 등록될 수 있어서 처음 쓸 때 찾고, 못 찾으면 다음에 다시 찾는다.
 */
public final class EconomyHook {

    private static final String ECONOMY = "net.milkbowl.vault.economy.Economy";
    private static final String RESPONSE = "net.milkbowl.vault.economy.EconomyResponse";

    private final Logger log;
    private Object provider;
    private Method has;
    private Method withdraw;
    private Method balance;
    private Method format;
    private Method success;
    private boolean warned;

    public EconomyHook(Logger log) {
        this.log = log;
    }

    /** 경제 플러그인을 쓸 수 있는지. */
    public boolean available() {
        return provider != null || find();
    }

    private boolean find() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        try {
            Class<?> economy = Class.forName(ECONOMY);
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(economy);
            if (rsp == null) {
                return false;
            }
            Class<?> response = Class.forName(RESPONSE);
            has = economy.getMethod("has", OfflinePlayer.class, double.class);
            withdraw = economy.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            balance = economy.getMethod("getBalance", OfflinePlayer.class);
            format = economy.getMethod("format", double.class);
            success = response.getMethod("transactionSuccess");
            provider = rsp.getProvider();
            log.info("Vault 경제 연동 완료");
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            if (!warned) {
                warned = true;
                log.log(Level.WARNING, "Vault 경제 연동에 실패했습니다.", e);
            }
            return false;
        }
    }

    public boolean has(OfflinePlayer player, double amount) {
        return available() && Boolean.TRUE.equals(call(has, player, amount));
    }

    /** @return 빠져나갔으면 {@code true} */
    public boolean withdraw(OfflinePlayer player, double amount) {
        if (!available()) {
            return false;
        }
        Object response = call(withdraw, player, amount);
        if (response == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(success.invoke(response));
        } catch (ReflectiveOperationException e) {
            log.log(Level.WARNING, "Vault 응답을 읽지 못했습니다.", e);
            return false;
        }
    }

    public double balance(OfflinePlayer player) {
        Object value = available() ? call(balance, player) : null;
        return value instanceof Number n ? n.doubleValue() : 0;
    }

    /** 경제 플러그인의 돈 표기 (예: "5,000원"). 연동 전이면 숫자만. */
    public String format(double amount) {
        if (available()) {
            Object value = call(format, amount);
            if (value instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return new DecimalFormat("#,##0.##").format(amount);
    }

    private Object call(Method method, Object... args) {
        try {
            return method.invoke(provider, args);
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.log(Level.WARNING, "Vault 호출(" + method.getName() + ")에 실패했습니다.", e);
            return null;
        }
    }
}
