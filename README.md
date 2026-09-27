# 갤러리 (chm_album)

## 📥 다운로드

<p align="center">
  <a href="https://github.com/kiri190-me/chm_album/releases/latest/download/chm-gallery.apk">
    <img src="https://img.shields.io/badge/APK%20%EB%8B%A4%EC%9A%B4%EB%A1%9C%EB%93%9C-chm--gallery.apk-E8264F?style=for-the-badge&logo=android&logoColor=white" alt="APK 다운로드">
  </a>
</p>

<p align="center">
  <img src="docs/download-qr.png" width="180" alt="APK 다운로드 QR 코드"><br>
  <sub>폰 카메라로 QR 코드를 찍으면 바로 받을 수 있습니다</sub>
</p>

- 폰에서 위 버튼을 누르거나 QR 코드를 찍으면 최신 버전 APK 가 바로 내려받아집니다.
- 받은 파일을 열어 설치합니다. 처음이면 **"출처를 알 수 없는 앱 설치"** 를 허용해야 합니다
  (갤럭시: 설치 창에서 *설정* → 이 브라우저/내 파일 앱 *허용* → 뒤로 가서 *설치*).
- 새 버전도 같은 방법으로 받아 설치하면 기존 앱 위에 업데이트됩니다.
- 이전 버전과 변경 내역: [릴리스 목록](https://github.com/kiri190-me/chm_album/releases) · [CHANGELOG](CHANGELOG.md)

갤럭시 기본 갤러리 느낌의 간단한 안드로이드 사진/동영상 앱입니다.

- **사진 탭**: 모든 사진과 동영상을 날짜별(하루 단위)로 묶어서 보여줍니다. 동영상은 썸네일에 길이가 표시됩니다.
- **동영상 탭**: 동영상만 날짜별로 모아 보여줍니다.
- **앨범 탭**: 사진과 동영상을 폴더(카메라, 스크린샷, 다운로드 등)별로 모아 보여줍니다.
- **정렬** (오른쪽 위 버튼)
  - 촬영 날짜: 사진 메타데이터(EXIF)에 기록된 날짜 (`MediaStore.DATE_TAKEN`). 메타데이터가 없으면 파일 날짜로 대체합니다.
  - 기기에 저장된 날짜: 이 기기에 파일이 추가된 날짜 (`MediaStore.DATE_ADDED`)
  - 최신순 / 오래된순
- 핀치로 한 줄에 보이는 사진 수(2~7장) 조절
- 뷰어: 두 손가락/두 번 탭으로 확대·축소, 확대한 채로 끌어 이동, 좌우로 밀어 이전/다음, 아래로 밀어 닫기, ⓘ 버튼으로 두 날짜와 파일 정보 확인, 동영상 재생
- **공유**: 뷰어의 공유 버튼, 또는 격자에서 길게 눌러 여러 개 선택한 뒤 공유
- **사진 편집**: 자르기(자유/원본/1:1/4:3/3:4/16:9/9:16), 기울기(-45°~45°), 90° 회전, 좌우 반전, 저장 크기(100/75/50/25%, 긴 변 2048/1280px)
- **동영상 자르기**: 시작/끝 손잡이로 구간을 골라 다시 인코딩 없이 잘라 저장 (키프레임 단위라 시작점이 조금 앞당겨질 수 있음)
- 편집 결과는 원본을 그대로 두고 같은 폴더에 사본(`_edit`, `_trim`)으로 저장합니다. 사진 편집본에는 원래 촬영 날짜를 EXIF 에 기록해 '촬영 날짜' 정렬에서 원본 옆에 옵니다.

Android 6.0 이상에서 동작합니다.

## 빌드

Gradle 없이 Android SDK 명령줄 도구로 빌드합니다.

```bash
sudo apt-get install android-sdk-platform-23 android-sdk-build-tools apksigner zipalign
./build.sh   # → dist/chm-gallery.apk
```

`keystore/debug.keystore`(비밀번호 `android`)로 서명합니다. 같은 키로 서명해야 기존 설치 위에 업데이트할 수 있습니다.

### 자동 릴리스

`app/` 등의 코드를 푸시하면 GitHub Actions(`.github/workflows/release.yml`)가 APK 를 빌드해
`AndroidManifest.xml` 의 `versionName` 이름(예: `v1.1`)의 릴리스에 올립니다.
새 버전을 내려면 `versionCode`/`versionName` 을 올리고 `CHANGELOG.md` 에 그 버전 항목을 추가한 뒤 푸시하면 됩니다.

## 구조

| 경로 | 내용 |
| --- | --- |
| `app/src/com/chm/album/MainActivity.java` | 사진/동영상/앨범 탭, 선택 모드, 권한 요청 |
| `app/src/com/chm/album/AlbumActivity.java` | 폴더 안 사진/동영상 목록 |
| `app/src/com/chm/album/ViewerActivity.java` | 전체 화면 보기, 동영상 재생 |
| `app/src/com/chm/album/PhotoEditorActivity.java`, `CropView.java` | 사진 편집 |
| `app/src/com/chm/album/VideoTrimActivity.java`, `RangeTrimView.java`, `VideoTrimmer.java` | 동영상 자르기 |
| `app/src/com/chm/album/ShareHelper.java` | 공유 |
| `app/src/com/chm/album/MediaSaver.java` | 편집한 사본 저장 |
| `app/src/com/chm/album/MediaRepository.java` | MediaStore 조회, 정렬, 폴더 묶기 |
| `app/src/com/chm/album/ThumbnailLoader.java` | 썸네일 비동기 로딩/캐시 |
| `icon/` | 앱 아이콘 원본 SVG |
| `preview/index.html` | 브라우저용 인터랙티브 미리보기 (예시 사진) |
