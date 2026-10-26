# 홈페이지 작업 지침

루트 AGENTS.md와 함께 적용한다. 기존 API·입력 검증·접수/중복/결과불명 처리·개인정보 문구는 보존한다.

## 팝업 배치 회귀 방지 (2026-10-26)

- 위치 배치는 `components/ui/dialog.tsx`의 `placement`와 `app/globals.css`의 공통 Dialog 규칙에서만 관리한다. 중앙 창은 기본 `center`, PC 중앙/모바일 하단 시트는 `responsive-sheet`를 사용한다.
- 개별 팝업 CSS에는 크기·여백·색만 지정한다. 모바일 시트에 `inset: 0`이나 0 기준 좌표를 추가하면서 중앙 배치의 -50% 이동을 남기지 않는다. Tailwind translate 유틸리티·CSS translate·transform을 중복 조합하지 않는다.
- 중앙 이동은 `translate: var(--dialog-offset-x, -50%) var(--dialog-offset-y, -50%)`로 유지한다. 하단 시트는 공통 규칙에서 두 변수를 `0px`로 설정한다. `translate: none` 또는 `translate: 0`만으로 중앙 이동을 지우지 않는다. 빌드 최적화가 이를 transform으로 바꾸면 독립 translate가 남아 창 절반이 잘릴 수 있다.
- 시트 진입/종료 애니메이션이 translate·transform·scale로 위치를 다시 덮어쓰지 않게 한다. 기본 시트는 위치 애니메이션을 사용하지 않는다. 새 애니메이션을 넣으면 최종 게시용 CSS에서도 배치가 유지되는지 확인한다.
- 팝업 배치를 바꾸면 게시용 빌드 후 `dist/client/_next/static/css/`의 실제 결과를 읽어 공통 translate 변수가 유지되고 시트의 두 offset이 0으로 남는지 확인한다. 소스의 `translate: none`만 보고 해결됐다고 판단하지 않는다.
- 화면 확인이 가능하면 초성·용도별·심는 시기와 내부 식재 안내의 열기/닫기·탭 전환·스크롤·초점 복귀를 PC와 모바일에서 확인한다. 짧은 화면·큰 글씨·키보드도 고려한다. 미리보기 경로가 막히면 확인하지 못한 화면을 보고하고 실기기 검증 완료로 기록하지 않는다.
- 실제 견적 접수·문자/메일 발송·운영 고객자료로 시험하지 않는다. 광범위 자동시험이나 추가 컴파일은 사용자가 요청한 경우에만 진행한다.
