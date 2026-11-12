# 갤럭시에서 GitHub Actions로 APK 자동 빌드

프로젝트에는 `.github/workflows/build-apk.yml`이 포함되어 있습니다.

## 권장 업로드 절차
1. GitHub에서 새 저장소를 만들고 기본 브랜치를 `main`으로 설정합니다.
2. 이 ZIP을 휴대전화에서 압축 해제합니다.
3. 압축 해제된 **폴더 안의 파일과 하위 폴더 구조**를 저장소에 업로드합니다. `.github/workflows/build-apk.yml`이 GitHub 저장소에서도 같은 위치에 있어야 합니다. ZIP 자체만 올리면 빌드되지 않습니다.
4. 저장소의 **Actions** 탭에서 `Build LEDCAR APK`를 선택합니다. `Run workflow`를 누르거나 main 브랜치에 파일을 올려 자동 실행되도록 합니다.
5. 성공한 실행의 **Artifacts**에서 `LEDCAR-Controller-debug-APK`를 다운로드합니다.
6. 받은 아티팩트 ZIP의 압축을 풀고 `app-debug.apk`를 설치합니다.

## 스마트폰에서 폴더 업로드가 어려울 때
GitHub 웹의 파일 업로드 UI는 모바일 브라우저에서 폴더 구조를 그대로 업로드하기 어려울 수 있습니다. 데스크톱 사이트 모드를 시도하거나 Termux의 git 명령으로 프로젝트 폴더 전체를 push하세요. 워크플로 YAML을 저장소의 `.github/workflows/`에 별도로 생성하는 방법도 있습니다.

## 주의
- GitHub Actions가 외부 서비스에서 컴파일하므로 GitHub 계정과 네트워크 연결이 필요합니다.
- GitHub 저장소가 공개이면 소스 코드가 공개됩니다. 원치 않으면 Private 저장소를 선택하세요.
- debug APK는 테스트용 서명으로 빌드됩니다.
- 블루투스 권한을 허용하고 기존 앱/nRF Connect 연결을 해제한 뒤 테스트하세요.
