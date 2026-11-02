---
name: verify
description: 모여타 기사 앱 변경을 빌드·에뮬레이터에서 실제 구동해 확인하는 레시피. 검증, 실기 확인, 스크린샷 요청 시 사용할 것.
---

# 모여타 기사 앱 검증 레시피

## 빌드 & 유닛 테스트
```bash
./gradlew :app:assembleDebug :data:testDebugUnitTest --console=plain
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## 백엔드 (서버 연동 화면 검증 시)
- 백엔드 레포: `../backend` (Spring, localhost:8080). 이미 떠 있는지 먼저 확인:
  `curl -s http://localhost:8080/actuator/health || curl -s http://localhost:8080/api/matching/rooms`
- 기사용 API가 백엔드에 없으면 더미 데이터 상태로 화면 품질만 검증하고 리포트에 명시
- 에뮬레이터에서 호스트 접근 주소는 `http://10.0.2.2:8080`
- 서버는 사용자가 띄운 프로세스일 수 있으니 함부로 죽이지 말 것

## 에뮬레이터
```bash
EMU=~/Library/Android/sdk/emulator/emulator; ADB=~/Library/Android/sdk/platform-tools/adb
$EMU -list-avds                       # Pixel_6 존재
nohup $EMU -avd Pixel_6 -no-snapshot-load -no-audio > /tmp/emulator.log 2>&1 &   # 콜드부트 필수 (사용자 지침)
$ADB wait-for-device                  # 이후 sys.boot_completed=1 폴링
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
$ADB shell am start -n com.moyeota.driver/com.moyeota.driver.app.MainActivity
$ADB exec-out screencap -p > /tmp/scr.png
```

## 화면 이동 (1080x2400 기준)
- 시작 화면: D01 기사 로그인 → 「로그인」 → D06 홈(휴무)
- 하단탭(홈·콜·정산·마이): D06·D07·D09·D12·D17·D19·D20에서만 노출 — 다른 화면에 보이면 결함
- 운행 플로우(D11→D13→D14→D15→D16)는 뒤로가기가 차단되어야 함 — 뒤로가기로 이탈되면 결함
- 네트워크 에러 프로브: `adb shell cmd connectivity airplane-mode enable|disable`

## 피그마 대조
- `docs/FIGMA-SCREENS.md`의 노드 ID로 `get_screenshot`(fileKey `nOqpTUmzAhjBW1SDLVd24A`)을 호출해 원본과 실기 스크린샷을 비교
- 다크 모드 고정 위반(흰 배경·검정 텍스트), 하드코딩 hex, 터치 타깃 60dp 미만을 중점 확인

## 주의
- 다크 모드가 기본이므로 스크린샷 판독 시 어두운 배경이 정상이다.
- 검증만 하고 직접 수정하지 않는다 — 결함은 담당 에이전트에게 파일:라인으로 요청.
