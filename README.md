# 갤러리 (chm_album)

갤럭시 기본 갤러리 느낌의 간단한 안드로이드 사진 앱입니다.

- **사진 탭**: 모든 사진을 날짜별(하루 단위)로 묶어서 보여줍니다.
- **앨범 탭**: 사진을 폴더(카메라, 스크린샷, 다운로드 등)별로 모아 보여줍니다.
- **정렬** (오른쪽 위 버튼)
  - 촬영 날짜: 사진 메타데이터(EXIF)에 기록된 날짜 (`MediaStore.DATE_TAKEN`). 메타데이터가 없으면 파일 날짜로 대체합니다.
  - 기기에 저장된 날짜: 이 기기에 파일이 추가된 날짜 (`MediaStore.DATE_ADDED`)
  - 최신순 / 오래된순
- 핀치로 한 줄에 보이는 사진 수(2~7장) 조절
- 사진 뷰어: 좌우로 밀어 이전/다음, 아래로 밀어 닫기, ⓘ 버튼으로 두 날짜와 파일 정보 확인

## 설치

`dist/chm-gallery.apk` 를 폰에 받아 설치합니다 (출처를 알 수 없는 앱 설치 허용 필요).
Android 6.0 이상에서 동작합니다.

## 빌드

Gradle 없이 Android SDK 명령줄 도구로 빌드합니다.

```bash
sudo apt-get install android-sdk-platform-23 android-sdk-build-tools apksigner zipalign
./build.sh   # → dist/chm-gallery.apk
```

`keystore/debug.keystore`(비밀번호 `android`)로 서명합니다. 같은 키로 서명해야 기존 설치 위에 업데이트할 수 있습니다.

## 구조

| 경로 | 내용 |
| --- | --- |
| `app/src/com/chm/album/MainActivity.java` | 사진/앨범 탭, 권한 요청 |
| `app/src/com/chm/album/AlbumActivity.java` | 폴더 안 사진 목록 |
| `app/src/com/chm/album/ViewerActivity.java` | 전체 화면 보기 |
| `app/src/com/chm/album/MediaRepository.java` | MediaStore 조회, 정렬, 폴더 묶기 |
| `app/src/com/chm/album/ThumbnailLoader.java` | 썸네일 비동기 로딩/캐시 |
| `icon/` | 앱 아이콘 원본 SVG |
| `preview/index.html` | 브라우저용 인터랙티브 미리보기 (예시 사진) |
