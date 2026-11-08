# 내 차 루틴

운전자가 "이럴 때, 이렇게"를 한 번 만들어 두면, 차가 상황에 맞춰 창문·공조·조명을 스스로 움직이는 Pleos Connect용 규칙 앱입니다.

현대자동차그룹 차량용 앱 개발 공모전 출품용 시제품이며, 앱 이름은 가칭입니다.

## 주요 기능

- 조건과 동작을 골라 규칙 만들기 (기어가 P일 때만 편집 가능)
- 조건이 맞으면 음성 안내 후 자동 실행
- 직접 정한 말로 규칙 실행
- 실행 기록 확인

조건과 동작은 자유롭게 조합할 수 있습니다.

- 조건: 와이퍼가 켜지면, 기어를 P에 넣으면, 뒷좌석에 사람이 타면, 정해 둔 말을 하면
- 동작: 창문 닫기, 뒷창문 잠그기, 내기순환 켜기, 와이퍼 끄기, 무드등 끄기

주행 중 실행될 수 있으므로 닫기, 잠그기, 켜기, 끄기 동작만 제공합니다. 창문이나 문을 여는 동작은 규칙으로 만들 수 없습니다.

## APK 위치

`apk/` 폴더의 release APK를 사용합니다. 시연 영상은 `demo/` 폴더에 있습니다.

## 테스트 환경

- Pleos Connect Emulator v2.1.0 (Android 14, API 34)
- Vehicle SDK 2.3.2, SpeechToText SDK 3.3.1, TextToSpeech SDK 3.3.1
- 실차에서는 시험하지 않았습니다.

## 실행 방법

### APK 설치

```bash
adb install apk/<APK 파일 이름>
```

앱을 설치하거나 업데이트한 뒤에는 에뮬레이터를 재부팅해 주세요. 재설치 직후에는 뒷좌석 탑승 신호 응답이 오지 않는 경우가 있었습니다.

### 조건 바꾸기

Pleos Connect Virtual Console에서 와이퍼, 기어, 좌석 탑승 상태를 바꾸면 규칙이 실행됩니다.

```bash
adb forward tcp:8088 tcp:8088
```

포워딩 후 브라우저에서 `http://127.0.0.1:8088/`을 엽니다. 콘솔 창이 열려 있어야 값이 전달됩니다.

### 소스에서 빌드

JDK 17과 Android SDK Platform 35가 필요합니다.

```bash
./gradlew assembleDebug
```

release 빌드의 서명 키는 저장소에 포함하지 않았습니다. 서명 설정 파일이 없으면 서명되지 않은 APK가 만들어집니다.

## 필요한 설정

- 마이크로 말해서 실행하려면 에뮬레이터의 Extended Controls > Microphone에서 오디오 입력을 켜야 합니다.
- 음성 기능은 ON_DEVICE 모드를 사용하므로 Client ID와 Client Secret이 필요 없습니다.

## 사용한 구성 요소

- Pleos Connect SDK: Vehicle, SpeechToText, TextToSpeech (42dot SDK 라이선스 적용)
- Kotlin 표준 라이브러리와 해당 SDK가 의존하는 라이브러리(각 라이선스 적용)
- Android Automotive OS `android.car` API

## 확인된 제한 사항

- 에뮬레이터 시험에서 음성 실행은 음성 파일 입력으로 확인했습니다. 실제 마이크 입력과 안내 소리는 확인하지 못했습니다.
- 에뮬레이터에서 Vehicle SDK의 좌석 탑승 값이 빈 좌석은 `true`, 탑승은 `false`로 전달되어 반대로 해석합니다. 실차에서 다시 확인해야 합니다.
- 무드등 끄기와 뒷창문 잠금의 결과는 SDK 재조회로 확인했으며, Virtual Console 화면에는 반영되지 않을 수 있습니다.
- 주행 중에는 앱 화면이 시스템에 의해 가려지지만 규칙은 계속 실행됩니다.
