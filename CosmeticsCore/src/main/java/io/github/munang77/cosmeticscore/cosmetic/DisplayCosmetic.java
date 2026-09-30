package io.github.munang77.cosmeticscore.cosmetic;

import java.util.List;

import org.bukkit.Material;

/**
 * 몸에 붙거나 곁에 떠다니는 아이템 (백팩, 날개, 꼬리, 허리, 상체, 풍선, 펫). 서버에 저장되지 않는
 * 디스플레이 엔티티로 그린다.
 */
public final class DisplayCosmetic extends Cosmetic {

    private final ItemSpec item;
    private final List<ItemSpec> cycle;
    private final int cycleTicks;
    private final float scale;
    private final Attachment attachment;
    private final String nameTag;
    private final Material stringBlock;

    /**
     * @param cycle       비어 있지 않으면 {@code cycleTicks} 마다 차례로 바꿔 보여 준다
     * @param attachment  붙는 자리와 움직임 (풍선/펫은 {@code offset} 의 높이만 쓴다)
     * @param nameTag     펫 머리 위 이름 (없으면 {@code null})
     * @param stringBlock 풍선 줄로 쓸 블록 (없으면 {@code null})
     */
    public DisplayCosmetic(Info info, Category category, ItemSpec item, List<ItemSpec> cycle, int cycleTicks,
                           float scale, Attachment attachment, String nameTag, Material stringBlock) {
        super(info, category);
        if (!category.isDisplay()) {
            throw new IllegalArgumentException(category + " 는 디스플레이 카테고리가 아닙니다");
        }
        this.item = item;
        this.cycle = List.copyOf(cycle);
        this.cycleTicks = Math.max(1, cycleTicks);
        this.scale = scale;
        this.attachment = attachment;
        this.nameTag = nameTag;
        this.stringBlock = stringBlock;
    }

    /** {@code tick} 에 보여 줄 아이템. */
    public ItemSpec itemAt(long tick) {
        if (cycle.isEmpty()) {
            return item;
        }
        return cycle.get((int) Math.floorMod(tick / cycleTicks, (long) cycle.size()));
    }

    public boolean cycles() {
        return !cycle.isEmpty();
    }

    public int cycleTicks() {
        return cycleTicks;
    }

    public float scale() {
        return scale;
    }

    public Attachment attachment() {
        return attachment;
    }

    /** 몸 장식이 좌우 한 쌍인지. */
    public boolean mirrored() {
        return category().isBody() && attachment.mirror();
    }

    public String nameTag() {
        return nameTag;
    }

    public Material stringBlock() {
        return stringBlock;
    }
}
