# CosmeticsCore

마인크래프트 **Paper / Spigot 1.21 ~ 26.x** 서버용 코스메틱(꾸미기) 플러그인입니다.
`/cos` 한 번이면 GUI 메뉴가 열리고, 모든 문구·아이템·가격은 설정 파일로 바꿀 수 있습니다.
날개·꼬리 같은 **3D 모델 몸 장식**, 마네킹에 입혀 보는 **옷장**, 모델이 든 **리소스팩**까지 들어 있습니다.

## 기능

| 카테고리 | 설명 |
|---|---|
| 모자 | 머리 칸에 씌우는 장식 (방어력 없음, 빼내기·버리기·죽을 때 떨어뜨리기 불가) |
| 날개 | 등 뒤에서 퍼덕이는 한 쌍 (한 짝 모델을 거울처럼 양쪽에) |
| 백팩 | 등에 메는 장식 (배낭, 펄럭이는 망토) |
| 꼬리 | 엉덩이 뒤에서 살랑이는 꼬리 |
| 허리 장식 | 벨트, 허리에 찬 검집 |
| 상체 장식 | 목도리, 메달 |
| 풍선 | 줄에 매달려 머리 위에서 흔들리는 풍선 (색이 바뀌는 풍선 가능) |
| 펫 | 어깨 옆에서 둥실둥실 따라다니는 작은 친구 (이름표 가능) |
| 파티클 | 후광·날개·왕관·회오리·하트 등 9가지 모양 |
| 화살 궤적 | 쏜 화살/삼지창을 따라 나오는 파티클 |
| 킬 이펙트 | 처치한 자리에서 번개·폭죽·파티클 폭발 (피해 없음) |
| 킬 메시지 | 처치했을 때의 사망 메시지 |
| 칭호 | 채팅·탭 목록 이름 앞 글자 |
| 채팅 색 | 한 색·그라데이션·무지개 채팅 |
| 입장 효과 | 입장/퇴장 메시지, 소리, 폭죽 |

기본 코스메틱 74개, 그중 16개는 플러그인에 들어 있는 3D 모델(천사·악마·나비 날개, 여우·고양이·용 꼬리,
망토, 모험가 배낭, 벨트, 허리 검, 목도리, 메달, 신사 모자, 마녀 모자, 토끼 귀, 하트 풍선)입니다.

그 밖에

- **옷장** — 본인에게만 보이는 마네킹(1.21.9+ 는 내 스킨)에 입혀 보며 고르기. 마우스 휠로 넘기고, 떠 있는 버튼이나 마네킹 우클릭으로 착용·구매
- **몸 장식 움직임** — 몸 방향을 따라 돌고, 웅크리면 같이 숙이고, 날개는 퍼덕이고 꼬리는 살랑이고 망토는 걸을 때 펄럭임. 겉날개로 날거나 수영·잠잘 때는 숨김
- **리소스팩** — 모델을 합친 zip 을 켤 때마다 만들고, 내장 웹 서버나 직접 올린 주소로 접속한 플레이어에게 보냄
- **블록벤치** — 모델 원본(`resourcepack/blockbench/*.bbmodel`)을 블록벤치에서 열어 고칠 수 있음
- **구매** — `price` 를 적으면 메뉴에서 Vault 돈으로 살 수 있습니다 (두 번 눌러 확인)
- **뽑기** — 아직 없는 코스메틱 중 등급(일반/희귀/영웅/전설) 가중치로 하나. 열쇠 또는 돈으로 열고, 전설은 서버 전체에 알림
- **미리보기** — 메뉴에서 우클릭하면 잠긴 코스메틱도 10초 동안 입어 볼 수 있음 (대기 시간 있음)
- **다른 사람 효과 끄기** — 파티클·장식이 거슬리면 본인 화면에서만 숨김
- **저장소** — YAML(기본), SQLite, MySQL(번지코드/벨로시티 여러 서버 공유). `/cos 이전` 으로 YAML 데이터를 옮김
- **PlaceholderAPI** — `%cosmeticscore_title%` 등으로 다른 플러그인(TAB, 채팅 플러그인 등)에 칭호 표시
- 몸 장식·풍선·펫은 플레이어에 태우지 않는 디스플레이 엔티티라 **텔레포트·탈것을 막지 않고, 서버에 저장되지 않으며**, 제자리에 있으면 위치를 다시 보내지 않아 통신량이 적습니다
- 투명화·관전자·바니시 상태이거나 `disabled-worlds` 월드에서는 자동으로 숨김

## 설치

1. `CosmeticsCore-2.1.0.jar` 를 서버의 `plugins` 폴더에 넣고 서버를 켭니다.
2. `plugins/CosmeticsCore/` 에 `config.yml`, `cosmetics.yml`, `messages.yml` 과 `resourcepack/CosmeticsCore-pack.zip` 이 생깁니다.
3. 3D 모델을 보이게 하려면 아래 [리소스팩](#리소스팩) 을 설정합니다.
4. 고친 뒤 `/cos 리로드` (저장소 종류만은 서버를 다시 켜야 바뀝니다).

선택: 돈으로 사기/뽑기에는 **Vault + 경제 플러그인**(EssentialsX 등), 칭호를 다른 플러그인에 보여 주려면 **PlaceholderAPI**.

## 명령어

| 명령어 | 설명 |
|---|---|
| `/cos` | 메뉴 열기 (별칭 `/꾸미기`, `/코스메틱`, `/cosmetics`) |
| `/cos <카테고리>` | 카테고리 바로 열기 (예: `/cos 날개`) |
| `/cos 옷장 [카테고리]` | 옷장 열기/닫기 |
| `/cos 착용 <아이디>` / `/cos 해제 [카테고리\|전체]` | 착용 / 해제 |
| `/cos 구매 <아이디>` | 사기 |
| `/cos 미리보기 <아이디>` | 잠깐 입어 보기 |
| `/cos 뽑기` / `/cos 열쇠` | 뽑기 / 내 열쇠 수 |
| `/cos 목록 [카테고리]` | 목록 |
| `/cos 보기` | 다른 사람 효과 보기 켜기/끄기 |
| `/cos 리소스팩` | 코스메틱 리소스팩 다시 받기 |
| `/cos 옷장 설정` / `/cos 옷장 삭제` | (관리자) 지금 서 있는 곳을 옷장 자리로 정하기 / 지우기 |
| `/cos 지급 <플레이어> <아이디>` | (관리자) 지급 — 접속하지 않은 플레이어도 됨 |
| `/cos 회수 <플레이어> <아이디>` | (관리자) 회수 |
| `/cos 열쇠 <플레이어> <개수>` | (관리자) 뽑기 열쇠 지급 (음수면 회수) |
| `/cos 리로드` | (관리자) 설정 다시 불러오기 (리소스팩도 다시 만듦) |
| `/cos 이전` | (관리자) YAML 데이터를 SQLite/MySQL 로 옮기기 |

영어 명령(`equip`, `unequip`, `buy`, `preview`, `wardrobe`, `crate`, `keys`, `resourcepack`, `give`, `take`,
`reload`, `migrate`)도 됩니다. 웹 상점 연동은 콘솔에서 `cos give {player} feather_wings` 처럼 쓰면 됩니다.

## 권한

| 권한 | 기본 | 설명 |
|---|---|---|
| `cosmeticscore.use` | 모두 | 메뉴, 옷장, 착용/구매/뽑기 |
| `cosmeticscore.cosmetic.<아이디>` | OP | 해당 코스메틱 사용 (권한 플러그인으로 등급별 지급) |
| `cosmeticscore.cosmetic.all` | OP | 모든 코스메틱 사용 |
| `cosmeticscore.admin` | OP | 지급/회수/열쇠/리로드/이전/옷장 자리 |

코스메틱은 **무료(`free: true`) · 권한 · 지급/구매/뽑기** 중 하나라도 있으면 쓸 수 있습니다.

## 옷장

`/cos 옷장` 이나 메인 메뉴의 갑옷 거치대를 누르면 앞에 **나에게만 보이는 마네킹**이 섭니다.

- **마우스 휠**: 지금 카테고리의 코스메틱 넘기기 (맨 앞은 "벗은 모습")
- **떠 있는 버튼 우클릭**: ◀ ▶ 코스메틱, « 종류 » 카테고리, ✔ 착용/구매, ⟳ 회전, ✖ 나가기
- **마네킹 우클릭**: 지금 보이는 코스메틱 착용 (안 가진 것은 5초 안에 두 번 눌러 구매 후 착용)
- **웅크리기**: 나가기. 맞거나, 다른 곳으로 이동하거나, `wardrobe.max-seconds` 가 지나도 닫힙니다

옷장에 있는 동안은 제자리에 서 있고(고개는 돌릴 수 있음) 블록·아이템을 쓸 수 없습니다.
관리자가 `/cos 옷장 설정` 으로 자리를 정하면 모두 그 자리로 옮겨 가서 옷장을 열고, 닫으면 원래 자리로
돌아옵니다 (같은 자리의 다른 사람은 서로 안 보임). 정하지 않으면 서 있는 곳 앞 3칸이 비어 있을 때 바로 열립니다.
1.21.9 이상은 내 스킨을 입은 바닐라 마네킹, 그 아래는 내 머리와 갑옷을 입은 갑옷 거치대가 섭니다.

## 리소스팩

플러그인은 켤 때마다 `plugins/CosmeticsCore/resourcepack/CosmeticsCore-pack.zip` 을 만듭니다.
리소스팩이 없는 플레이어에게는 3D 모델 코스메틱이 원래 아이템 모양(깃털, 종이 등)으로 보입니다.

**방법 1 — 플러그인이 직접 보내기** (가장 쉬움)

```yaml
resource-pack:
  send: true
  self-host:
    enabled: true
    port: 8163                  # 방화벽/호스팅 패널에서 이 포트를 열어 주세요
    address: "play.myserver.com" # 플레이어가 접속하는 서버 주소 (공인 IP 나 도메인)
```

내장 웹 서버는 리소스팩 파일 하나만 내어 주고 다른 요청은 모두 거절합니다.

**방법 2 — 직접 올린 주소**: zip 을 드롭박스·깃허브 릴리스 등에 올리고 `url` 에 직접 받기 주소를 적습니다
(내용을 바꾸면 해시가 바뀌므로 다시 올려야 합니다).

**방법 3 — 다른 리소스팩과 합치기**: ItemsAdder·Nexo·Oraxen 이나 서버 리소스팩을 이미 쓰면 zip 안의
`assets` 폴더를 그 리소스팩에 합치고 `send: false` 로 둡니다. 모델은 `feather`, `rabbit_foot`, `leather`,
`string`, `gold_nugget`, `paper`, `red_dye` 의 `custom_model_data` 7130001~7130016 을 씁니다.

리소스팩은 1.21~1.21.3(`overrides`)과 1.21.4 이상(`items/` 정의)을 둘 다 넣어 두었습니다.
`resourcepack/extra/` 폴더에 넣은 파일은 zip 에 함께 합쳐집니다 (같은 경로면 extra 가 이김).

## 코스메틱 추가하기

`cosmetics.yml` 에 항목을 적고 `/cos 리로드`. 예:

```yaml
balloons:
  gold_balloon:
    name: "&6황금 풍선"
    material: GOLD_BLOCK
    rarity: epic
    price: 50000

wings:
  my_wings:
    name: "&b얼음 날개"
    material: PAPER
    custom-model-data: 1001      # 내 리소스팩의 오른쪽 날개 한 짝 모델
    scale: 0.9
    animation-speed: 1.2         # 퍼덕이는 빠르기
    animation-angle: 20          # 퍼덕이는 각도
    spread: 15                   # 뒤로 젖힌 기본 각도

tails:
  my_tail:
    material: PAPER
    custom-model-data: 1002
    animation: SWAY              # NONE, FLAP, SWAY, SWING, BOB, SPIN

chat-colors:
  mint:
    name: "&#3EECAC민트 채팅"
    gradient: ["#3EECAC", "#EE74E1"]
```

몸 장식(날개·백팩·꼬리·허리·상체)에는 `offset`(자리 옮기기), `rotation`(돌리기), `pivot`(흔들릴 때 제자리인 점),
`mirror`(거울 한 쌍), `animation` 을 쓸 수 있습니다. 항목별 설명은 `cosmetics.yml` 맨 위 주석에 있습니다.

### 내 모델 만들기 (블록벤치)

1. [블록벤치](https://www.blockbench.net)에서 **Java Block/Item** 모델을 만듭니다. 기본 모델을 고치려면
   `resourcepack/blockbench/*.bbmodel` 을 열면 됩니다 (텍스처 포함).
2. 좌표 약속: **북쪽(-Z)이 몸 앞, 동쪽(+X)이 몸 오른쪽**, 모델 가운데 (8, 8, 8) 이 붙는 자리이자 돌아가는 중심입니다.
   허리·상체·백팩 모델은 16픽셀 = 1칸, 플레이어 몸통은 x 4~12, z 6~10 입니다. 날개는 **오른쪽 한 짝**만 만들면
   왼쪽은 플러그인이 거울처럼 붙입니다. 모자는 머리 위(y 14.4 부터)에 올리면 됩니다.
3. 모델과 텍스처를 `resourcepack/extra/assets/<내이름>/...` 에 넣고, 원하는 아이템의 `custom_model_data`
   로 연결한 뒤 `cosmetics.yml` 에 `material` 과 `custom-model-data` 를 적습니다.

기본 모델 전체는 `python3 resourcepack/generate.py` 로 다시 만들 수 있습니다 (표준 파이썬만 필요).

## PlaceholderAPI

| 플레이스홀더 | 값 |
|---|---|
| `%cosmeticscore_title%` | 칭호 (색 포함) |
| `%cosmeticscore_title_space%` | 칭호 + 띄어쓰기 (없으면 빈칸) |
| `%cosmeticscore_title_plain%` | 색 없는 칭호 |
| `%cosmeticscore_equipped_<카테고리>%` | 착용 이름 (예: `equipped_wings`) |
| `%cosmeticscore_equipped_<카테고리>_id%` | 착용 아이디 |
| `%cosmeticscore_owned%` / `%cosmeticscore_total%` | 보유 수 / 전체 수 |
| `%cosmeticscore_keys%` | 뽑기 열쇠 수 |

## 다른 플러그인에서 쓰기

```java
CosmeticsCore core = CosmeticsCore.getPlugin(CosmeticsCore.class);
core.manager().owns(player, core.registry().get("feather_wings"));
core.wardrobe().open(player, null);
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

`target/CosmeticsCore-2.1.0.jar` 가 만들어집니다.

테스트

```
./mvnw test                                        단위 테스트
./mvnw install && ./mvnw -f integration-tests/pom.xml test    MockBukkit 통합 테스트
```

통합 테스트는 가짜 서버(MockBukkit) 위에서 플러그인을 실제로 켜고 착용·모자 보호·재접속·장식 소환/추적·
날개 한 쌍과 웅크리기/비행·옷장(마네킹, 휠, 착용, 옷장 자리 이동과 복귀)·리소스팩(zip, 내장 웹 서버)·
보기 설정·미리보기·뽑기·지급/회수·SQLite 이전 등을 끝까지 돌려 봅니다.

## 호환성

- 서버: Paper / Spigot / Purpur 1.21 이상 (26.x 포함), Java 21
- 1.21.4 API 로 컴파일하고, 26.2 API 로도 컴파일해서 바이트코드 참조가 같은 것을 확인했습니다 (Paper 전용 API 는 쓰지 않음).
- 스킨을 입은 마네킹은 1.21.9 이상, `item-model` 은 1.21.2 이상에서만 적용됩니다.
- 실제 서버와 게임 화면에서는 아직 시험하지 못했습니다. 모델 위치는 게임의 표시 규칙을 따라 만든 미리보기로 확인했습니다.
- Folia 는 지원하지 않습니다.
