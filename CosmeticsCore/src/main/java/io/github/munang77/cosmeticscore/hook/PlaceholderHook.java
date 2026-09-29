package io.github.munang77.cosmeticscore.hook;

import io.github.munang77.cosmeticscore.CosmeticsCore;

/**
 * PlaceholderAPI 가 있을 때만 불린다. 이 클래스를 거쳐서 확장을 만들기 때문에
 * PlaceholderAPI 가 없는 서버에서는 {@link CosmeticsExpansion} 이 아예 로드되지 않는다.
 */
public final class PlaceholderHook {

    private PlaceholderHook() {
    }

    public static boolean register(CosmeticsCore plugin) {
        return new CosmeticsExpansion(plugin).register();
    }
}
