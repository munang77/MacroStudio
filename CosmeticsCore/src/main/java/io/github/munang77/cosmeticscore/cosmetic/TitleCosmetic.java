package io.github.munang77.cosmeticscore.cosmetic;

/** 채팅과 탭 목록 이름 앞에 붙는 칭호. */
public final class TitleCosmetic extends Cosmetic {

    private final String title;

    public TitleCosmetic(Info info, String title) {
        super(info, Category.TITLE);
        this.title = title;
    }

    /** 색이 입혀진 칭호 글자. */
    public String title() {
        return title;
    }
}
