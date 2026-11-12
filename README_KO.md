# LEDCAR Controller — Android 소스 프로젝트

기존 LED LAMP 없이 LEDCAR-01 계열 BLE 장치의 FFE0/FFE1로 명령을 전송하는 독립 앱의 **소스 코드**입니다. APK가 아닙니다.

## 구현된 기능
- BLE 검색(이름에 LEDCAR 포함) 및 연결/해제
- 전원 ON/OFF
- 빨강, 초록, 파랑 및 사용자 지정 RGB 전송
- 검증된 최대/최소 밝기

## 주의사항
- 빨강/초록/파랑, 전원 ON/OFF, 밝기 최대/최소 패킷은 사용자 기기에서 검증됨.
- 임의 RGB 혼합값, 흰색의 표현 및 밝기 중간값은 아직 검증되지 않음.
- 기존 앱의 음악, 패턴, 타이머 등 모든 기능을 복제한 것은 아님.
- 일부 삼성 장치는 BLE 스캔에 위치 서비스 활성화가 필요할 수 있음.
- BLE 연결이 안 되면 기존 LED LAMP 및 nRF Connect 연결을 먼저 끊으세요.

## 빌드
Android Studio 또는 Android Gradle Plugin 8.6.1과 JDK 17, Android SDK 35가 준비된 환경에서 프로젝트 폴더를 열어 `assembleDebug` 빌드하세요. 스마트폰 단독 빌드에는 별도의 Android 빌드 환경 설정이 필요합니다. 이 압축에는 Gradle wrapper 실행 파일이 들어 있지 않습니다.

기본 패키지: `com.example.ledcar`, 최소 Android 8.0(API 26).
