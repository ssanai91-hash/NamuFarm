# 묘목 관리 영상

홈페이지 첫 화면의 큰 `묘목 재배 영상 보기` 버튼 또는 상단의 `재배 영상` / `묘목 재배 영상` 버튼으로 자료실을 엽니다. 영상은 선택할 때만 YouTube 공식 플레이어를 불러오며, 목록으로 돌아가거나 글자가 표시된 `닫기` 버튼을 누르면 플레이어가 제거됩니다. 검색·묘목·주제 필터가 견적 입력 상태를 변경하지 않습니다.

## 운영시트

기존 `나무팜_견적관리_운영` 파일의 새 `콘텐츠자료실` 탭만 사용합니다. 견적·농원·묘목 시트와 기존 공유 설정은 변경하지 않습니다.

| 열 | 입력 |
| --- | --- |
| 콘텐츠ID | 고유한 영문·숫자·하이픈 ID, 예: video-002 |
| 종류 | youtube (blog는 향후 지원을 위해 예약) |
| 제목 | 표시할 영상 제목 |
| 주소 | YouTube 공유·watch·shorts·live 주소 |
| 묘목 | 공통 또는 블루베리 등, 여러 개는 쉼표로 구분 |
| 주제 | 식재·전정·병해충 등, 여러 개는 쉼표로 구분 |
| 게시 | 체크하면 표시, 해제하면 비공개 |
| 순서 | 숫자가 작을수록 먼저 표시 |
| 출처 | 채널 이름 |

첫 영상은 사용자가 제공한 `HgNJrIhC3rk`이며, 제목·채널명은 2026-10-20 YouTube oEmbed 응답으로 확인했습니다. 영상의 실제 재생 가능 여부는 게시자의 외부 재생 설정·지역·네트워크에 따라 달라질 수 있어 원본 YouTube 링크도 제공합니다.

## Apps Script 반영

1. 견적 접수용 기존 Apps Script 프로젝트에 `ContentLibrary.gs`를 추가합니다. 이 파일의 소스는 `server-support/ContentLibrary.gs`입니다. 저장소 루트 소스에서는 `server/quote-requests/ContentLibrary.gs`에 대응합니다.
2. `Code.gs`의 기존 `doGet`만 아래처럼 확장합니다. 기존 POST·인증·견적 동작은 유지합니다.

```js
function doGet(e) {
  if (e && e.parameter && e.parameter.action === 'content_library') {
    return json_(publicContentLibrary_());
  }
  return json_(secureServiceInfo_());
}
```

3. 기존 웹앱 배포를 새 버전으로 갱신합니다. 배포 URL·실행 주체·액세스 설정·Script Properties를 유지합니다. `LEGACY_SPREADSHEET_ID`가 있으면 그 운영 파일을, 없으면 `SPREADSHEET_ID`를 사용합니다. 새 권한이나 스크립트 속성이 필요하지 않습니다.
4. `/exec?action=content_library`가 `{ ok:true, catalogVersion:1, items:[...] }`를 반환하는지 확인합니다. 기본 `/exec` 서비스 응답도 계속 확인합니다. GET은 읽기만 하며, 헤더 오류·시트 누락·1000개 초과에서는 비공개 오류 코드만 반환합니다.

사이트 `/api/content-library`는 기존 `QUOTE_SCRIPT_URL`만 사용합니다. Apps Script 응답에서 공개 필드만 다시 검증하고 YouTube 주소를 정규화합니다. 영상 데이터를 받지 못하면 목록 새로고침 실패를 알리고 이미 표시하던 목록을 유지합니다. 빈 정상 응답은 그대로 빈 목록으로 표시하며 최초 영상으로 되돌리지 않습니다. 최초 로컬 등록 영상은 서버 배포 전에도 사용할 수 있도록 포함되어 있습니다.

시트 변경은 다음 창 열기 또는 `새로고침`에서 반영됩니다. 홈페이지를 다시 배포할 필요는 없습니다. 블로그는 현재 화면·API에서 노출하지 않습니다. 이후 별도 URL 검증과 글 보기 화면을 추가할 때 같은 시트 구조를 확장합니다.

## 검증

`node --experimental-strip-types --test tests/content-library.test.mjs` 및 기존 테스트, `tsc --noEmit`, Sites production build를 수행합니다. 관리형 환경에서 지원되는 control-browser가 없으면 브라우저 조작·실제 YouTube 재생 테스트를 진행할 수 없습니다.
