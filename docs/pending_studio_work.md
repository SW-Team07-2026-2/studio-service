# studio-service 작업 대기

> **상태: 1번 완료 · 2번 · 3번 대기 (2026-11-13).** 1번(검표 역할)은 같은 날 끝냈습니다. 2번 · 3번은 확인받을 것이 남아 **아무것도 바꾸지 않았습니다** (조사만 함).
> 관리자 서비스(Studio) 작업을 시작하면 **이 문서를 먼저 읽고 여기 있는 것부터 끝낸 뒤** 새로 받은 작업을 합니다 (11/13 지시).
> 다 끝나면 이 파일과 메모리의 `studio-pending-work` 항목을 지웁니다.

## 0. 받은 요청 (11/13 · 원문)

> "검표 관리자도 만들어줘 s3 써야겠네 크롤링해서 나온 데이터들의 이미지는 s3에 올리는걸로 가자, 폐기 내용은 지워"

앞선 보고(`v2_studio_service_re.md` 12장의 미정 항목)에 대한 답입니다.

| # | 작업 | 상태 | 시작 전에 물어볼 것 |
|---|---|---|---|
| 1 | 검표 관리자 역할 추가 | ✅ **완료 (11/13)** | — |
| 2 | 크롤링 데이터의 이미지를 S3 에 | **확인 필요** | 2장의 질문 3개 |
| 3 | 폐기 내용 삭제 | **일부 확인 필요** | 3장 — `docs/22` 는 통째로 지우면 안 됨 |

저장소 상태: 1번은 `refactor/flyway-v1-v2` 브랜치에 있습니다 (마이그레이션 두 파일 정리와 같은 커밋).

## 1. 검표 관리자 역할 — ✅ 완료 (2026-11-13)

> 아래 1.2 의 계획대로 만들었고 **4번만 다르게 했습니다.** 같은 날 "마이그레이션은 V1(구조) · V2(값) 두 파일만" 으로 정해져서
> `V2__checker_role.sql` 을 따로 만들지 않고 `V2__studio_values.sql` 의 `ck_admin_role` 에 `CHECKER` 를 넣었습니다 (`V1` 의 CHECK 는 V2 로 옮김).
> Studio 는 아직 어디에도 배포되지 않아 비울 DB 가 없습니다. 이전 `V1` 로 만든 로컬 DB 만 지우고 다시 만들면 됩니다.
> 결과는 `v2_studio_service_re.md` 2장 · 3.1 · 6.7 · 10장 · 13장에 있습니다. 테스트 21건 통과.

### 1.1 Concert 에 이미 있는 것 (확인함 — 고칠 것 없음)

`InternalCheckInController` · `allowed-issuers` 에 `studio` 포함.

| Concert API | 내용 |
|---|---|
| `GET /internal/admin/concerts/check-in` | 지금 검표할 수 있는 공연 목록 (`List<ConcertRef>`) |
| `POST /internal/admin/concerts/{id}/check-in` | 본문 `{ token, checkerId }` — `token` 은 찍은 QR 값 그대로(최대 1000자), `checkerId` 는 검표자 계정 id → `{ bookingId, seats[], checkedInAt }` |

- 오류: `WRONG_CONCERT`(다른 공연의 QR) · `ALREADY_CHECKED_IN`(+ `checkedInAt`) · `ENTRY_NOT_AVAILABLE`(취소된 예매) · `ENTRY_QR_INVALID`. Studio 의 `ServiceClient` 가 4xx 의 `code` · `message` 를 그대로 내보냅니다.
- 🔴 `checkerId` 는 **Studio 가 세션의 관리자 id 로 채웁니다.** 화면이 보낸 값을 쓰지 않습니다 (Concert 주석: "X-User-Id 를 신뢰하지 않습니다").

### 1.2 Studio 에서 할 일

1. `Permission` 에 `CHECK_IN` 추가
2. `Role` 에 검표 역할 추가 — 이름은 `CHECKER` 로 할 생각입니다 (`SELF` + `CHECK_IN`). `MASTER` 는 `EnumSet.allOf` 라 자동으로 포함
3. `Menu` 에 `CHECKIN("checkin", "검표", Permission.CHECK_IN)`
4. **`V2__checker_role.sql`** — `ck_admin_role` 을 지우고 새 값 목록으로 다시 만듭니다. 🔴 `V1` 은 이미 푸시했으니 고치지 않습니다 (Flyway 체크섬)
   ```sql
   ALTER TABLE admin_account DROP CHECK ck_admin_role;
   ALTER TABLE admin_account ADD CONSTRAINT ck_admin_role CHECK (role IN ('MASTER', 'STAGE', 'CONCERT', 'GOODS', 'CS', 'CHECKER'));
   ```
   `role` 은 `VARCHAR(16)` 이라 길이는 충분합니다. `Role.java` 의 주석("V1 의 ck_admin_role 도 함께")도 고칩니다
5. `domain/checkin/controller/CheckInController`
   - `GET /api/studio/check-in/concerts` → Concert `GET /internal/admin/concerts/check-in`
   - `POST /api/studio/check-in/concerts/{id}` — 본문 `{ token }` → Studio 가 `checkerId` 를 붙여 Concert `POST /internal/admin/concerts/{id}/check-in`
   - 공연 목록은 검표 탭 아래의 경로로만 냅니다 (공연 탭 API 를 빌려 쓰지 않음 — 격리 규칙 3.2 ④)
6. 테스트
   - `RbacTest` 의 역할별 영역 목록에 검표 추가 (다른 역할은 404 · 검표는 다른 탭 404 · 마스터 탭 7개)
   - 검표 요청의 `checkerId` 가 세션의 관리자 id 인지 (화면이 다른 값을 보내도 무시)
   - `StudioSchemaTest` 는 CHECK ↔ enum 을 대조하므로 V2 가 맞으면 통과
7. `v2_studio_service_re.md` — 0장(역할 6개) · 2장 enum 표 · 3.1 표 · 6장(검표 절 추가) · 9장 "낡은 것"의 체크인 줄 삭제 · 10장 건수 · 12장 ⑩ 삭제 · 13장 기록

화면 쪽: 검표 탭은 QR 을 읽어(카메라) `token` 을 보냅니다. 이미 입장한 QR 이면 `checkedInAt` 을 보여 줍니다 (11장에 추가).

## 2. 크롤링 데이터의 이미지 → S3 (확인 필요)

**정해진 것:** S3 를 씁니다. 크롤링해서 나온 데이터의 이미지(공연 포스터 · 상품 썸네일 · 아티스트 사진 등)는 S3 에 올립니다. → `v2_studio_service_re.md` 12장 ③("포스터를 어디에 올릴지")의 답입니다.

**시작하기 전에 물어볼 것** — 답에 따라 Studio 가 할 일이 달라집니다:

1. **누가 올리나요?** 수집기가 크롤링하면서 올리고 URL 만 넘기는지(이러면 Studio 는 지금처럼 URL 만 다룸 · 코드 변경 없음), Studio 가 올리는지(업로드 API · KT 에서 AWS S3 로 가는 자격 증명 필요).
2. **관리자가 직접 올리는 이미지**(공연 관리자가 포스터를 바꿀 때)도 S3 인가요? 그러면 Studio 에 업로드 API 가 필요합니다.
3. **무대 도면 사진**은 그대로 MySQL 에 두나요? 무대 그리기는 "s3 경유는 없애줘" 가 먼저 있었고 이번 말은 크롤링 데이터에 한정돼 있어서, **그대로 둔다고 이해했습니다.**

참고

- 수집기(크롤링 · LLM 전처리)는 사용자 범위 밖입니다 — 인터페이스 계약까지만 정합니다.
- 기존 S3 설계는 `docs/19_Terraform_구성.md` 에 있습니다 (이미지용 버킷이 이미 정의돼 있는지는 아직 확인하지 않았습니다 — 시작할 때 먼저 확인).
- Studio 가 올리게 되면: 버킷 · 경로 규칙 · 공개 방식(CloudFront?) · KT 에 둘 자격 증명(시크릿 추가) · 허용 형식/크기 · 테스트(LocalStack 또는 가짜 서버)를 정해야 합니다.
- 최소한 할 일(답과 무관): `v2_studio_service_re.md` 12장 ③ 을 "S3 로 결정" 으로 고치고 9장에 필요한 인프라를 적습니다.

## 3. 폐기 내용 삭제

`indie-msa/` 와 `docs/` 는 git 저장소가 아니라서 **지우면 되돌릴 수 없습니다.** `rm` 대신 휴지통(`~/.Trash`)으로 옮길 생각입니다.

| 대상 | 줄 수 | 조사 결과 | 계획 |
|---|---|---|---|
| `docs/34_관리자_서비스_ERD.html` | 767 | 전부 이전 관리자 설계. 참조하는 곳은 Studio 문서뿐 | 파일 삭제 |
| `docs/24_관리자_서비스.md` | 488 | 전부 이전 관리자 설계 (역할 5종 · 시크릿 계정 · 엑셀 입고 · 수집 자동화 · 관리자 AI) | 파일 삭제 |
| `docs/22_수집검증_관리자권한_감사로그.md` | 533 | 🔴 **섞여 있습니다.** §1 수집 데이터 이중 검증은 지금 도는 코드의 기준입니다 | §2 · §3 만 삭제 (아래) |

**`docs/22` 를 통째로 지우면 안 되는 이유:** Gateway(`IngestFilter` · `IngestRoute` · `IngestSignatureVerifier` 등) · Catalog(`InternalTokenFilter` · `application.yml`) · `backend/README.md` · `backend/local/*.sh` 가 `docs/22 §1-4 G1~G9` · `S1 · S2` 로 이 문서의 §1 을 가리킵니다.
→ §2(관리자 권한 · 로그인 · 계정 관리) · §3(관리자 감사 로그) 과 §4 · §5 의 해당 줄만 지우고, 제목과 머리말을 "수집 데이터 이중 검증" 으로 고칩니다. **이렇게 일부만 지워도 되는지는 시작할 때 한 줄로 확인받습니다.**

함께 고칠 참조

- `backend/studio-service/docs/v2_studio_service_re.md` — 0장 마지막 줄 · 9장 "낡은 것" 첫 줄 · 12장 ⑨ · 13장 첫 행 (폐기 언급 삭제)
- `docs/22` 안의 `[24](24_관리자_서비스.md)` 링크 (12 · 92 · 214행 부근)
- `docs/19_Terraform_구성.md` 297 · 415 · 721행 — `22 §3`(감사 로그 S3 사본 · 감사 릴레이)을 가리킵니다. 이전 설계의 인프라라 내용 자체가 낡았습니다. 지울지 확인 필요
- `backend/README.md` 253행 — 옛 Studio 설명 (역할 3종 · Member `admin_account`)
- Concert `InternalCheckInController` 주석의 "24 §1 · §9" · `TICKET_CHECKER` — 다른 저장소라 손대지 않고 알려만 둡니다
- 메모리 `studio-service-v2` 의 "docs/22 · 24 · 34 는 낡음" 줄

## 4. 순서와 마무리

1. ~~1번(검표)~~ — 완료
2. **3번(삭제)** — `docs/34` · `docs/24` 삭제와 `docs/22` 일부 삭제. `docs/` 는 git 밖이라 커밋 없음. Studio 문서의 폐기 언급 삭제는 별도 `docs/` 브랜치로
3. **2번(S3)** — 질문 3개의 답을 받은 뒤
4. 끝나면 이 파일을 지우고 메모리 `studio-pending-work` 를 지웁니다. `frontend/` 는 커밋 · 푸시 · 머지하지 않습니다
