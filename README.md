# Poke Item (포켓몬 아이템)

파티의 포켓몬을 아이템으로 바꿔 상자에 보관하거나 다른 사람에게 줄 수 있게 하는 모드입니다.

- 버전: 1.0.0
- Minecraft 1.21.1 / Fabric
- 필요한 모드: Cobblemon, Fabric API
- 설치: 서버에만 넣으면 됩니다. 접속하는 사람(클라이언트)은 이 모드가 없어도 됩니다.

## 다운로드

**[⬇ poke-item-1.0.0.jar 다운로드](https://github.com/minerapai/poke-item/releases/download/v1.0.0/poke-item-1.0.0.jar)**

받은 jar 파일을 마인크래프트(또는 서버) 폴더의 `mods` 폴더에 넣으면 됩니다. 다른 버전은 [Releases](https://github.com/minerapai/poke-item/releases) 에 있습니다.

## 기능

- 포켓몬 → 아이템 (이름, 성별, 이로치 표시)
- 아이템을 들고 우클릭하면 다시 내 포켓몬으로 받기
- 인벤토리가 꽉 차면 발밑에 떨어뜨림

## 사용법

| 명령어 | 설명 |
|---|---|
| `/pokeitem <번호> (/포켓몬아이템)` | 파티 <번호>번째 포켓몬을 아이템으로 바꾸기 |
| 아이템 들고 우클릭 | 포켓몬으로 되돌리기 |

(OP) 표시가 있는 명령어는 서버 관리자(OP)만 쓸 수 있습니다.
괄호 안의 한글 명령어는 [Korean Commands](https://github.com/minerapai/korean-commands) 모드가 있을 때만 동작합니다. 없으면 영어 명령어를 쓰세요.

## 빌드

```bash
./gradlew build
```

`build/libs/` 에 만들어진 jar 파일을 서버의 `mods` 폴더에 넣습니다.

## 라이선스

MIT License. 출처(minerapai)만 밝히면 누구나 자유롭게 쓰고 고치고 배포할 수 있습니다. 자세한 내용은 [LICENSE](LICENSE) 파일을 보세요.
