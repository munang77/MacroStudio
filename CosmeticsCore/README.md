# CosmeticsCore

마인크래프트 **Paper / Spigot 1.21 ~ 26.x** 서버용 코스메틱(꾸미기) 플러그인입니다.
`/cos` 한 번이면 GUI 메뉴가 열리고, 모든 문구·아이템·가격은 설정 파일로 바꿀 수 있습니다.

## 기능

| 카테고리 | 설명 |
|---|---|
| 모자 | 머리 칸에 씌우는 장식 (방어력 없음, 빼내기·버리기·죽을 때 떨어뜨리기 불가) |
| 백팩 | 등에 붙어 다니는 장식 |
| 풍선 | 줄에 매달려 머리 위에서 흔들리는 풍선 (무지개처럼 색이 바뀌는 풍선 가능) |
| 펫 | 어깨 옆에서 둥실둥실 따라다니는 작은 친구 (이름표 가능) |
| 파티클 | 후광·날개·왕관·회오리·하트 등 9가지 모양 |
| 화살 궤적 | 쏜 화살/삼지창을 따라 나오는 파티클 |
| 킬 이펙트 | 처치한 자리에서 번개·폭죽·파티클 폭발 (피해 없음) |
| 킬 메시지 | 처치했을 때의 사망 메시지 |
| 칭호 | 채팅·탭 목록 이름 앞 글자 |
| 채팅 색 | 한 색·그라데이션·무지개 채팅 |
| 입장 효과 | 입장/퇴장 메시지, 소리, 폭죽 |

그 밖에

- **구매** — `price` 를 적으면 메뉴에서 Vault 돈으로 살 수 있습니다 (두 번 눌러 확인)
- **뽑기** — 아직 없는 코스메틱 중 등급(일반/희귀/영웅/전설) 가중치로 하나. 열쇠 또는 돈으로 열고, 전설은 서버 전체에 알림
- **미리보기** — 메뉴에서 우클릭하면 잠긴 코스메틱도 10초 동안 입어 볼 수 있음 (대기 시간 있음)
- **다른 사람 효과 끄기** — 파티클·장식이 거슬리면 본인 화면에서만 숨김
- **저장소** — YAML(기본), SQLite, MySQL(번지코드/벨로시티 여러 서버 공유). `/cos 이전` 으로 YAML 데이터를 옮김
- **리소스팩/머리 텍스처** — `custom-model-data`, `item-model`, `texture` 로 원하는 모양
- **PlaceholderAPI** — `%cosmeticscore_title%` 등으로 다른 플러그인(TAB, 채팅 플러그인 등)에 칭호 표시
- 백팩·풍선·펫은 플레이어에 태우지 않는 디스플레이 엔티티라 **텔레포트·탈것을 막지 않고, 서버에 저장되지 않습니다**
- 투명화·관전자·바니시 상태이거나 `disabled-worlds` 월드에서는 자동으로 숨김

## 설치

1. `CosmeticsCore-2.0.0.jar` 를 서버의 `plugins` 폴더에 넣고 서버를 켭니다.
2. `plugins/CosmeticsCore/` 에 `config.yml`, `cosmetics.yml`, `messages.yml` 이 생깁니다.
3. 고친 뒤 `/cos 리로드` (저장소 종류만은 서버를 다시 켜야 바뀝니다).

선택: 돈으로 사기/뽑기에는 **Vault + 경제 플러그인**(EssentialsX 등), 칭호를 다른 플러그인에 보여 주려면 **PlaceholderAPI**.

## 명령어

| 명령어 | 설명 |
|---|---|
| `/cos` | 메뉴 열기 (별칭 `/꾸미기`, `/코스메틱`, `/cosmetics`) |
| `/cos <카테고리>` | 카테고리 바로 열기 (예: `/cos 풍선`) |
| `/cos 착용 <아이디>` / `/cos 해제 [카테고리\|전체]` | 착용 / 해제 |
| `/cos 구매 <아이디>` | 사기 |
| `/cos 미리보기 <아이디>` | 잠깐 입어 보기 |
| `/cos 뽑기` / `/cos 열쇠` | 뽑기 / 내 열쇠 수 |
| `/cos 목록 [카테고리]` | 목록 |
| `/cos 보기` | 다른 사람 효과 보기 켜기/끄기 |
| `/cos 지급 <플레이어> <아이디>` | (관리자) 지급 — 접속하지 않은 플레이어도 됨 |
| `/cos 회수 <플레이어> <아이디>` | (관리자) 회수 |
| `/cos 열쇠 <플레이어> <개수>` | (관리자) 뽑기 열쇠 지급 (음수면 회수) |
| `/cos 리로드` | (관리자) 설정 다시 불러오기 |
| `/cos 이전` | (관리자) YAML 데이터를 SQLite/MySQL 로 옮기기 |

영어 명령(`equip`, `unequip`, `buy`, `preview`, `crate`, `keys`, `give`, `take`, `reload`, `migrate`)도 됩니다.
웹 상점 연동은 콘솔에서 `cos give {player} crown` 처럼 쓰면 됩니다.

## 권한

| 권한 | 기본 | 설명 |
|---|---|---|
| `cosmeticscore.use` | 모두 | 메뉴와 착용/구매/뽑기 |
| `cosmeticscore.cosmetic.<아이디>` | OP | 해당 코스메틱 사용 (권한 플러그인으로 등급별 지급) |
| `cosmeticscore.cosmetic.all` | OP | 모든 코스메틱 사용 |
| `cosmeticscore.admin` | OP | 지급/회수/열쇠/리로드/이전 |

코스메틱은 **무료(`free: true`) · 권한 · 지급/구매/뽑기** 중 하나라도 있으면 쓸 수 있습니다.

## 코스메틱 추가하기

`cosmetics.yml` 에 항목을 적고 `/cos 리로드`. 예:

```yaml
balloons:
  gold_balloon:
    name: "&6황금 풍선"
    material: GOLD_BLOCK
    rarity: epic
    price: 50000

pets:
  my_pet:
    name: "&d내 펫"
    texture: "http://textures.minecraft.net/texture/<해시>"   # 머리 텍스처
    name-tag: "&d{player}의 펫"

chat-colors:
  mint:
    name: "&#3EECAC민트 채팅"
    gradient: ["#3EECAC", "#EE74E1"]
```

항목별 설명은 `cosmetics.yml` 맨 위 주석에 있습니다.

## PlaceholderAPI

| 플레이스홀더 | 값 |
|---|---|
| `%cosmeticscore_title%` | 칭호 (색 포함) |
| `%cosmeticscore_title_space%` | 칭호 + 띄어쓰기 (없으면 빈칸) |
| `%cosmeticscore_title_plain%` | 색 없는 칭호 |
| `%cosmeticscore_equipped_<카테고리>%` | 착용 이름 (예: `equipped_pet`) |
| `%cosmeticscore_equipped_<카테고리>_id%` | 착용 아이디 |
| `%cosmeticscore_owned%` / `%cosmeticscore_total%` | 보유 수 / 전체 수 |
| `%cosmeticscore_keys%` | 뽑기 열쇠 수 |

## 다른 플러그인에서 쓰기

```java
CosmeticsCore core = CosmeticsCore.getPlugin(CosmeticsCore.class);
core.manager().owns(player, core.registry().get("crown"));
```

`CosmeticEquipEvent` (취소 가능), `CosmeticUnequipEvent` 이벤트를 들을 수 있습니다.

## 여러 서버에서 MySQL 공유

`storage.type: MYSQL` 로 두면 모든 서버가 같은 데이터를 씁니다. 서버를 옮길 때 이전 서버의 저장이
먼저 끝나도록 로그인 때 `login-delay-ms`(기본 300ms) 만큼 기다렸다가 읽습니다. 데이터는 로그인
스레드에서 읽으므로 DB 가 느려도 서버 틱은 멈추지 않습니다.

## 빌드

JDK 21 이 필요합니다. Maven 을 따로 설치하지 않아도 됩니다.

```
mvnw.cmd package        (윈도우)
./mvnw package          (리눅스/맥)
```

`target/CosmeticsCore-2.0.0.jar` 가 만들어집니다.

테스트

```
./mvnw test                                        단위 테스트
./mvnw install && ./mvnw -f integration-tests/pom.xml test    MockBukkit 통합 테스트
```

통합 테스트는 가짜 서버(MockBukkit) 위에서 플러그인을 실제로 켜고 착용·모자 보호·재접속·장식 소환/추적·
보기 설정·미리보기·뽑기·지급/회수·SQLite 이전 등을 끝까지 돌려 봅니다.

## 호환성

- 서버: Paper / Spigot / Purpur 1.21 이상 (26.x 포함), Java 21
- 1.21.4 API 로 컴파일하고, 26.2 API 로도 컴파일해서 바이트코드 참조가 완전히 같은 것을 확인했습니다.
- `item-model` 은 1.21.2 이상에서만 적용됩니다.
- Folia 는 지원하지 않습니다.
