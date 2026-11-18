# studio-service v2 (관리자 서비스)

> **상태: 구현 완료 · main 머지 (2026-11-13, `6c1d371`) · 배포 안 됨.** 같은 날 검표 역할을 추가하고 마이그레이션을 두 파일(V1 구조 · V2 값)로 정리했습니다.
> 테스트 28건 통과 (건너뜀 0 · Docker 필요 · ✏️ 11/17 크롤러 받는 쪽 7건 — 아티스트 · 앨범도 승인 대기). Studio 가 부르는 내부 API 는 concert · catalog · member · payment 에 모두 만들었습니다 (9장).
> ✏️ **11/16 KT Cloud 를 받아 배포 설정을 만들었습니다** — KT 는 AWS · PC 클러스터에 붙지 않고 **VM 한 대의 docker compose** 로 돌며(MySQL · Redis · Studio · Prometheus · Grafana), AWS 또는 온프레미스(PC)를 Cloudflare 관리자 터널로 부릅니다 (`SW-Team07-2026-2/manifests-kt` README · 9장). 같은 날 **로그인 2단계 대기 · 세션을 MySQL 에서 Redis 로** 옮겼습니다 (2장 · 4장). 실제 KT VM · GPU 서버 · 서비스 연동은 아직 확인하지 못했습니다 (10장).
> 전 서비스 공통의 남은 일은 `indie-msa/docs/v2_후속_작업.md`, 전체 API 는 `indie-msa/docs/v2_API_명세서.md`, 관리자 화면 확인 사항은 `indie-msa/docs/v2_프론트_체크사항.md` 에 있습니다.

## 0. 한눈에 보기

- **관리자 전용 백엔드입니다.** KT Cloud 에서 돌고 DB 는 MySQL 8.4 (`studio` 스키마), 로그인 2단계 대기 · 세션은 Redis 7.4 입니다. 포트 8085 (관리 8095).
- **로그인은 2단계입니다.** 1차 아이디 · 비밀번호(MySQL · bcrypt) → 2차 메일로 받은 인증 코드 6자리 → 대시보드. 2단계 대기(5분)와 세션은 Redis 에 둡니다. (11/17 변경: OTP 앱(TOTP · QR) 대신 메일 코드 — 4장)
- **계정마다 역할이 하나입니다.** `MASTER` · `STAGE`(무대) · `CONCERT`(공연) · `GOODS`(굿즈 · 피지컬 앨범) · `CS` · `CHECKER`(검표).
- **하위 관리자는 서로를 모릅니다.** 탭은 `/me` 가 내려준 것만 그리고, 권한 밖 API 는 없는 경로와 똑같은 404 가 나갑니다.
- **업무 데이터는 Studio 에 없습니다.** 공연 · 상품 · 회원 · 결제는 Concert · Catalog · Member · Payment 내부 API 로 읽고 씁니다 (`X-Internal-Token`, iss=`studio`). 서비스는 AWS 또는 온프레미스(PC)에 있고, KT 에서 `https://link-aws.indig.co.kr` · `https://link-onprem.indig.co.kr` 중 하나를 부릅니다 — 전환은 `manifests-kt` 의 `compose.yml` 한 줄 (`x-link-base-url` · 6.8).
- **무대 그리기:** 화면에서 그린 값 + 도면 사진 → Studio(KT) → 개인 GPU 서버(WireGuard) 검증 → 사진과 값이 화면으로 → 무대 관리자가 확인 → Concert 로 전송. **S3 는 거치지 않습니다.** 사진은 Studio DB 에 둡니다.
- **관리자의 요청은 전부 `audit_log` 에 남습니다** (조회 · 권한 밖 시도 포함). 검색어 · 본문 · 개인정보 값은 남기지 않습니다.
- 테이블은 4개입니다: `admin_account` · `stage_job` · `stage_job_image` · `audit_log`. ✏️ 11/16 `admin_login_challenge` · `admin_session` 은 Redis 키로 옮겼습니다 (2장).
- **이전 설계는 폐기했습니다.** `docs/22`(§2 · §3) · `docs/24` · `docs/34_관리자_서비스_ERD.html` 은 이 문서와 다릅니다. 파일은 남겨 두고 각 문서 머리에 "폐기" 를 표시했습니다 (12장 ⑨).
- **이미지는 URL 만 다룹니다.** 크롤링 이미지는 수집기가 S3 에 올리고 URL 을 넘기며, 그 데이터를 Concert 에 실제로 저장하는 것은 Studio 입니다 (6.4 · 12장 ③).

## 1. 배경과 범위

- 11/13 에 관리자 서비스를 새로 정했습니다. 기준은 그날 받은 기능 설명이고, 거기에 없는 것(코드 구조 · 응답 형식 · 내부 토큰 규약 · 문서 형식)은 concert · catalog · member 의 v2 를 따랐습니다.
- 포함: Studio 백엔드 전체 (계정 · 로그인 · 역할 · 무대 그리기 · 공연 · 상품 · CS · 작업 기록), 로컬 실행 환경, CI(테스트)
- 제외
  - 관리자 화면(프론트) — API 와 화면이 알아야 할 것만 정리했습니다 (11장)
  - 다른 서비스의 수정 — 처음에는 목록만 남겼고, 같은 날 각 서비스에서 만들었습니다 (9장)
  - GPU 서버 구현 — 요청 · 응답 모양까지만 정했습니다 (5.3)
  - ~~KT 배포~~ — ✏️ 11/16 만들었습니다 (`manifests-kt` 의 docker compose · `manifests` 의 `services/studio-link` · 9장)
  - 수집기(크롤링 · LLM 전처리) — 사용자가 직접 만듭니다 (11/17). Studio 가 받는 쪽은 만들었습니다 (6.9)

## 2. 테이블 정의

id 는 모두 `BIGINT AUTO_INCREMENT`, 시각은 UTC `DATETIME(3)`, enum 은 `VARCHAR` + CHECK 입니다. `ddl-auto: validate` 라 엔티티와 어긋나면 기동이 실패합니다.

마이그레이션은 Flyway 두 파일만 둡니다 (다른 서비스와 같은 규칙).

| 파일 | 내용 |
|---|---|
| `V1__studio_schema.sql` | 구조 — 테이블 · 키 · 인덱스 |
| `V2__studio_values.sql` | 값 — enum 값 목록 CHECK 6개 (`ck_admin_role` · `ck_admin_status` · `ck_stage_job_status` · `ck_stage_job_image_kind` · `ck_crawl_concert_status` · `ck_crawl_catalog_type` · `ck_crawl_catalog_status` · `ck_audit_result`) |

- 🔴 구조나 값을 바꾸면 **파일을 고치고 DB 스키마를 비운 뒤 다시 올립니다.** 적용된 DB 를 두고 파일만 고치면 Flyway 체크섬 검증에 걸려 서비스가 뜨지 않습니다. 실제 데이터가 쌓인 뒤에는 이 규칙을 쓸 수 없고 `V3` 부터 추가해야 합니다.
- 이전 `V1`(CHECK 포함)로 만든 로컬 DB 는 지우고 다시 만듭니다 (`docker compose -f local/compose.yml down -v`).

| 테이블 | 컬럼 |
|---|---|
| `admin_account` | `id` · `login_id`(UNIQUE) · `name` · `password_hash`(bcrypt) · `role` · `status` · `otp_emails` · `failed_count` · `locked_until` · `last_login_at` · `created_at` · `updated_at` |
| `stage_job` | `id` · `venue_id` · `venue_name` · `status` · `drawing_json` · `result_json` · `sent_json` · `error_message` · `requested_by`(FK) · `sent_by`(FK) · `created_at` · `analyzed_at` · `sent_at` |
| `stage_job_image` | `id` · `job_id`(FK · CASCADE) · `kind` · `content_type` · `data`(LONGBLOB) — UNIQUE(`job_id`, `kind`) |
| `crawl_concert` | `id` · `source` · `external_id` — UNIQUE(`source`, `external_id`) · `source_hash` · `payload_json`(크롤러가 준 값 그대로) · `status` · `received_at` · `reviewed_by`(FK) · `reviewed_at` · `concert_id` (11/17 · 6.9) |
| `crawl_catalog` | `id` · `type`(ARTIST · ALBUM) · `source` · `external_id` — UNIQUE(`type`, `source`, `external_id`) · `source_hash` · `payload_json`(Catalog 수집 계약 한 항목 · 앨범은 `artist_ref`) · `status` · `received_at` · `reviewed_by`(FK) · `reviewed_at` · `catalog_id` · `last_error` (11/17 · 6.9) |
| `audit_log` | `id` · `occurred_at` · `admin_id`(FK) · `role` · `method` · `path` · `status` · `result` · `client_ip` · `duration_ms` |

- **`admin_account` 의 행은 지우지 않습니다.** 작업 기록 · 무대 작업 · AWS 쪽 기록이 이 id 를 가리킵니다. 퇴사 · 업무 종료는 `DISABLED` 입니다. 삭제 API 가 없습니다.
- **DB 에는 원문이 없습니다.** 비밀번호는 bcrypt(강도 12), 세션 · 2단계 대기 쿠키는 SHA-256 해시만 (Redis 키), 메일로 보낸 인증 코드도 해시만 (Redis) 둡니다.
- `otp_emails` 는 인증 코드를 받을 메일 1~5개입니다 (쉼표 구분 · 소문자). 역할별 공용 계정이면 담당자 메일을 모두 넣고, 같은 코드가 모두에게 갑니다.
- `stage_job.venue_id` 는 Concert 의 공연장 id 입니다 (다른 DB 라 FK 없음).
- 사진은 목록 조회에 딸려 오지 않게 `stage_job_image` 로 나눴습니다. `kind` 는 `ORIGINAL`(올린 사진) · `RESULT`(GPU 가 돌려준 사진).
- `audit_log.path` 는 쿼리스트링을 뺀 경로입니다. `role` 은 그 시점의 역할입니다.

**Redis 키** — ✏️ 11/16 로그인 2단계 대기 · 세션을 MySQL 테이블에서 옮겼습니다 (`LoginChallengeStore` · `SessionStore`)

| 키 | 모양 · 만료 | 내용 |
|---|---|---|
| `studio:auth:challenge:<SHA-256(쿠키)>` | 해시 · 5분 | `adminId` · `code`(인증 코드 해시 — SHA-256(쿠키 값 + 코드) · 쿠키 값은 Redis 에 없어 Redis 만 봐서는 코드를 되짚을 수 없음) · `ip` · `attempts` · `expiresAt` |
| `studio:auth:session:<SHA-256(쿠키)>` | 해시 · 8시간 | `adminId` · `csrf` · `ip` · `createdAt` · `lastSeenAt` · `expiresAt` |
| `studio:auth:admin-sessions:<adminId>` | 집합 · 마지막 로그인 + 8시간 | 그 계정의 세션 해시들 — 역할 · 상태 · 비밀번호가 바뀔 때 한꺼번에 끊으려고 |

- 만료(5분 · 8시간 · 30분 무요청) 판정은 저장한 시각과 앱 시계로 하고, Redis 만료는 청소용입니다 (예전의 1시간마다 지우던 작업은 없앴습니다).
- 2단계 대기는 인증 코드가 맞으면 키를 지워서 1회용입니다 (먼저 지운 요청만 통과). 시도 횟수 · 마지막 접속 시각은 키가 있을 때만 고칩니다 (방금 지워진 키를 만료 없이 되살리지 않게).
- 주의: **Redis 가 데이터를 잃으면(재시작) 관리자가 모두 로그아웃될 뿐입니다.** 계정 · 잠금 · 감사 로그는 MySQL 에 있습니다. 코드 입력 중이던 사람은 아이디 · 비밀번호부터 다시 합니다. KT 의 Redis 는 디스크에 쓰지 않습니다.
- 세션 끊기는 MySQL 트랜잭션 밖입니다 — 계정 변경이 롤백돼도 세션은 끊긴 채입니다 (다시 로그인하면 됨). 요청마다 계정을 MySQL 에서 다시 보므로 반대 방향(끊겨야 할 세션이 남음)의 권한 문제는 없습니다.

| enum | 값 |
|---|---|
| `admin_account.role` | `MASTER` · `STAGE` · `CONCERT` · `GOODS` · `CS` · `CHECKER` |
| `admin_account.status` | `ACTIVE` · `DISABLED` |
| `stage_job.status` | `PENDING` · `ANALYZING` · `REVIEW` · `SENT` · `FAILED` |
| `stage_job_image.kind` | `ORIGINAL` · `RESULT` |
| `crawl_concert.status` | `PENDING`(확인 대기) · `APPROVED`(Concert 에 등록) · `REJECTED`(등록 안 함) |
| `crawl_catalog.type` · `status` | `ARTIST` · `ALBUM` / `PENDING`(승인 대기) · `APPROVED`(Catalog 에 적재) · `REJECTED`(적재 안 함) |
| `audit_log.result` | `OK` · `FAILED` · `DENIED` |

값 목록은 DB CHECK(`V2`)와 Java enum 양쪽에 있고 `StudioSchemaTest` 가 둘을 대조합니다.

## 3. 역할 · 권한 · 탭

### 3.1 역할

| 역할 | 하는 일 | 권한 | 탭 (`/me.menus` 의 key) |
|---|---|---|---|
| `MASTER` | 모든 기능 접근 · 조회 · 수정 + 관리자 계정 · 작업 기록 | 전부 | `venues` · `concerts` · `products` · `cs` · `checkin` · `accounts` · `audit` |
| `STAGE` | 공연장(무대장) 추가 · 무대 그리기 | `VENUE` · `STAGE_DRAW` | `venues` |
| `CONCERT` | 공연 상세 · 약관 · 이미지 · 요구사항 · 좌석별 가격 | `CONCERT` · `SEAT_PRICE` | `concerts` |
| `GOODS` | 굿즈 · 피지컬 앨범 | `PRODUCT` · `STOCK` | `products` |
| `CS` | 회원의 티켓팅 · 굿즈 · 피지컬 앨범 구매 문의 | `CS_READ` · `CS_CANCEL` | `cs` |
| `CHECKER` | 공연장 입구에서 입장 QR 확인 (검표) | `CHECK_IN` | `checkin` |

모든 역할은 `SELF`(내 정보 · 비밀번호 변경 · 로그아웃)를 가집니다. 역할 → 권한 표는 코드(`Role`)에 있고 DB 에는 역할 이름만 있습니다.

### 3.2 서로 모르게 하는 방법

1. **탭:** `GET /api/studio/me` 가 내 역할의 탭만 내려줍니다. 화면은 이 목록에 없는 탭을 그리지 않습니다.
2. **API:** 모든 핸들러에 `@RequirePermission` 이 붙어 있고, 내 역할에 그 권한이 없으면 **없는 경로와 같은 404 `NOT_FOUND`** 가 나갑니다. 무대 관리자는 굿즈 API 가 있는지조차 알 수 없습니다. 본문 검증 · CSRF 검사보다 먼저 막고, 대상 서비스에는 아무것도 가지 않습니다.
3. **선언 누락:** 권한을 선언하지 않은 핸들러도 404 입니다 (기본이 거부). `RbacTest` 가 `/api/studio/**` 핸들러 전부에 선언이 있는지 확인합니다.
4. **선택 목록:** 공연 관리자가 고르는 공연장 · 아티스트 목록은 공연 탭 아래의 별도 경로(`/api/studio/concerts/options/**`)로 냅니다. 무대 탭 · 상품 탭의 API 를 빌려 쓰지 않습니다.
5. **즉시 반영:** 세션은 요청마다 DB 에서 계정 상태를 다시 봅니다. 역할 변경 · 비활성화 · 비밀번호 초기화는 그 계정의 세션을 전부 끊습니다.
6. **기록:** 권한 밖 시도는 `audit_log` 에 `DENIED` 로 남고 마스터만 볼 수 있습니다.

## 4. 로그인 · 세션

```
① POST /api/studio/auth/login   { loginId, password }
     → 계정에 등록된 메일(otp_emails 전부)로 인증 코드 6자리를 보냅니다 (보내는 사람 indig <noreply@indig.co.kr>)
     → { next: "OTP", sentTo: ["ab***@gmail.com"] }   sentTo 는 가린 주소 (화면 안내용) · 코드는 응답에 없습니다
     쿠키 STUDIO_2FA (5분 · 1회용) — 아직 로그인된 것이 아닙니다
② POST /api/studio/auth/otp     { code: "123456" }   ← 메일로 받은 코드
     → 쿠키 STUDIO_SESSION + 내 정보(/me 와 같은 모양)
③ GET  /api/studio/me           → role · menus · permissions · csrfToken
```

| 항목 | 값 |
|---|---|
| 인증 코드 | 무작위 6자리 · 로그인(1차 통과)마다 새로 만듦 · 5분 · 한 번만 통과. 코드를 못 받았으면 1차부터 다시 (새 코드 발송 · 재전송 API 는 없음) |
| 메일 | SMTP — 운영은 Gmail(`smtp.gmail.com:587` STARTTLS · 앱 비밀번호). 보내는 주소 `noreply@indig.co.kr` 은 그 Gmail 계정의 "다른 주소에서 메일 보내기"에 등록돼 있어야 그대로 나갑니다. 주소마다 한 통 (받는 사람끼리 주소가 안 보임). 못 보내면 503 `OTP_MAIL_FAILED` 이고 2단계 대기를 만들지 않습니다 |
| 잠금 | 비밀번호 · 인증 코드 연속 5회 실패 → 15분 잠금. 2단계 대기 하나에서 코드 5회 실패 → 처음부터 다시 |
| 세션 | 로그인 후 최대 8시간, 30분 동안 요청이 없으면 만료. 불투명 토큰(JWT 아님) |
| 쿠키 | `STUDIO_SESSION`(path `/api/studio`) · `STUDIO_2FA`(path `/api/studio/auth`). HttpOnly · Secure · SameSite=Strict (환경변수로 변경) |
| CSRF | 상태를 바꾸는 요청(POST · PUT · PATCH · DELETE)은 `X-CSRF-Token` = `/me.csrfToken` 이어야 함 → 아니면 403 `CSRF_INVALID` |
| 비밀번호 | 12자 이상 · 72바이트 이하 · 아이디 포함 불가 |

- 아이디가 없든 · 비밀번호가 틀리든 · 잠겼든 · 비활성이든 응답은 모두 401 `LOGIN_FAILED` 로 같습니다. 없는 아이디도 bcrypt 를 한 번 돌려 응답 시간으로 가릴 수 없게 했습니다.
- **계정 심기 (11/17 변경):** 기동할 때 시크릿 파일 `bootstrap.admins` 의 줄마다 **DB 에 없는 아이디만** 만듭니다 (`AdminSeeder`). 한 줄은 `아이디,이름,역할,메일(여러 개면 ;),bcrypt 해시` 이고 `#` 줄 · 빈 줄은 무시합니다. 이미 있는 계정은 파일을 고쳐도 바뀌지 않습니다 (비밀번호 · 메일 · 역할은 화면에서). 형식이 틀리면 기동을 멈춥니다. 운영 파일은 맥의 `~/indie-secrets/kt/admins.csv`(비밀번호 원문 · 맥에만) 를 `manifests-kt` 의 `scripts/make-secrets.sh` 가 bcrypt 로 바꿔 만듭니다. 가입 API 는 없습니다.
- **운영 계정은 역할별 공용 계정 5개입니다 (11/17 결정):** MASTER · CONCERT · GOODS · CS · CHECKER 에 하나씩. STAGE 전용 계정은 없고 마스터가 맡습니다. 아이디 · 수신 메일은 이 레포(공개)에 적지 않습니다 — 맥의 `~/indie-secrets/kt/관리자_계정.md`. 주의: 한 계정을 두 사람이 쓰면 작업 기록에는 계정 id 만 남아 누가 했는지 구분되지 않습니다.
- 마스터는 다른 계정의 이름 · 역할 · 상태 · 인증 코드 받을 메일 변경, 비밀번호 초기화를 할 수 있습니다. 자기 계정의 역할 · 상태는 못 바꿉니다 (`CANNOT_CHANGE_SELF`) — 마스터가 0명이 되는 것을 막습니다.
- 내 비밀번호를 바꾸면 다른 기기의 세션은 끊기고 지금 세션만 남습니다.
- 로그인 실패는 `audit_log` 가 아니라 서버 로그에 남습니다 (`admin_locked` · `admin_login`). `audit_log` 는 로그인한 뒤의 요청만 담습니다.

## 5. 무대 그리기

### 5.1 흐름

```
관리자 화면 ──①──▶ Studio (KT) ──②──▶ GPU 서버 (개인 · WireGuard)
     ▲                 │  ◀──③── 사진 + 값
     └───④ 조회(폴링)──┘
     ──⑤ 확인(수정) ──▶ Studio ──⑥──▶ Concert (AWS) 구역 저장
```

| 단계 | 내용 | 상태 |
|---|---|---|
| ① | `POST /api/studio/venues/{venueId}/stage-jobs` — multipart `image`(도면 사진) + `drawing`(그린 값 JSON). DB 에 저장하고 바로 응답 | `PENDING` |
| ② | 백그라운드 스레드가 GPU 서버에 사진 + 값을 보냄 | `ANALYZING` |
| ③ | GPU 가 돌려준 사진 · 값을 DB 에 저장 | `REVIEW` (실패하면 `FAILED` + 사유) |
| ④ | 화면이 `GET /api/studio/stage-jobs/{jobId}` 를 2~3초마다 다시 부름. 사진은 `…/image?kind=RESULT` | |
| ⑤ | 관리자가 사진과 값을 보고 `POST …/confirm`. 본문이 있으면 고친 값, 없으면 GPU 값 그대로 | |
| ⑥ | 구역마다 Concert `PUT /internal/admin/concerts/venues/{venueId}/sections` | `SENT` |

- 🔴 **관리자가 확인하기 전에는 Concert 에 아무것도 가지 않습니다.**
- 값은 구역 하나(객체) · 구역 배열 · `{ "sections": [...] }` 모두 받습니다. 구역 하나의 모양은 Concert 의 `SectionLayoutRequest`(`venueId` · `venueName` · `floor` · `section{name, shape, angle, rows, seatCount}` · `seats[{seatId, row, number, x, y, angle}]`) 입니다.
- 중간에 Concert 가 거절하면(예: 409 `SECTION_IN_USE`) 작업은 `REVIEW` 로 남습니다. 고쳐서 다시 보내면 Concert 가 같은 구역을 덮어씁니다.
- 검증은 한 번에 하나씩 돕니다 (스레드 1개 · 나머지는 줄을 섬). GPU 가 느려도 요청 스레드와 DB 커넥션을 붙잡지 않습니다. 제한 시간은 3분입니다.
- Studio 가 꺼졌다 켜지면 돌던 검증은 사라집니다. 기동할 때 `PENDING` · `ANALYZING` 작업을 `FAILED` 로 닫아 화면이 계속 기다리지 않게 합니다.
- 사진은 PNG · JPEG · WEBP, 10MB 이하입니다. 확장자가 아니라 파일 앞부분(매직 넘버)으로 판별합니다.

### 5.2 사진을 DB 에 두는 이유

S3 경유를 없애기로 했고(11/13) 느려도 된다고 했습니다. Studio 에는 다른 저장소가 없어서 MySQL `LONGBLOB` 에 둡니다. 작업 1건에 사진이 최대 2장(각 10MB 이하)이고, 지우는 배치는 아직 없습니다 (12장 ⑥).

### 5.3 GPU 서버가 구현할 것

```
POST {GPU_BASE_URL}/v1/stage/verify      multipart/form-data
헤더  X-Gpu-Token: <시크릿 gpu.token>
파트  image      도면 사진 (PNG · JPEG · WEBP)
      drawing    관리자가 그린 값 (JSON 문자열 — 화면이 보낸 그대로)
      venueId · venueName

200  { "image": "<base64>", "imageContentType": "image/png", "values": { … } }
```

- `image` 는 없어도 됩니다. `values` 는 필수이고 5.1 의 구역 모양(하나 · 배열 · `{sections}`)이어야 그대로 Concert 로 보낼 수 있습니다.
- 200 이 아니거나 `values` 가 없으면 작업은 `FAILED` 입니다.
- GPU 서버는 WireGuard 피어 주소로만 열고, `X-Gpu-Token` 을 확인해야 합니다.
- `drawing` 의 모양은 Studio 가 정하지 않습니다 (JSON 객체 · 배열이면 통과). 화면과 GPU 서버가 맞춥니다 (12장 ④).

## 6. API

공통: 응답은 `{ isSuccess, code, message, result, correlationId }` 입니다. `/api/studio/**` 는 로그인 2개 경로를 빼고 전부 세션이 필요합니다 (없으면 401 `UNAUTHENTICATED`).

### 6.1 로그인 · 내 정보 (전 역할)

| 메서드 · 경로 | 내용 |
|---|---|
| `POST /api/studio/auth/login` | 1차 — `{ loginId, password }` → 인증 코드 메일 발송 · `{ next: "OTP", sentTo[] }` (가린 주소) |
| `POST /api/studio/auth/otp` | 2차 — `{ code }`(메일로 받은 6자리) → 내 정보 |
| `POST /api/studio/auth/logout` | 세션 종료 |
| `GET /api/studio/me` | `{ id, loginId, name, role, menus[{key, label}], permissions[], csrfToken }` |
| `PUT /api/studio/me/password` | `{ currentPassword, newPassword }` |

### 6.2 마스터 전용

| 메서드 · 경로 | 내용 |
|---|---|
| `GET /api/studio/accounts` | 계정 목록 (`otpEmails[]` · `lockedUntil` · `lastLoginAt` 포함) |
| `POST /api/studio/accounts` | `{ loginId, name, role, otpEmails[], password }` — `loginId` 는 영문 소문자 · 숫자 · 점 4~32자 · `otpEmails` 는 1~5개 |
| `PATCH /api/studio/accounts/{id}` | `{ name?, role?, status?, otpEmails? }` — 보낸 값만 변경 |
| `POST /api/studio/accounts/{id}/password-reset` | `{ password }` |
| `GET /api/studio/audit-logs` | `?adminId=&beforeId=&size=` — 최신순 |

### 6.3 무대 (`STAGE`)

| 메서드 · 경로 | 권한 | 보내는 곳 |
|---|---|---|
| `GET /api/studio/venues` · `/{venueId}` | `VENUE` | Concert `GET /api/concerts/venues` · `/{id}` |
| `POST /api/studio/venues` | `VENUE` | Concert `POST /internal/admin/concerts/venues` |
| `GET /api/studio/venues/{venueId}/layout` | `VENUE` | Concert `GET /internal/admin/concerts/venues/{id}/layout` |
| `POST /api/studio/venues/{venueId}/stage-jobs` | `STAGE_DRAW` | (Studio) 검증 요청 |
| `GET /api/studio/venues/{venueId}/stage-jobs` | `STAGE_DRAW` | (Studio) 최근 50건 — 값 · 사진 제외 |
| `GET /api/studio/stage-jobs/{jobId}` | `STAGE_DRAW` | (Studio) `drawing` · `values` · `sent` · `resultImage` 포함 |
| `GET /api/studio/stage-jobs/{jobId}/image` | `STAGE_DRAW` | (Studio) `?kind=RESULT`(기본) \| `ORIGINAL` |
| `POST /api/studio/stage-jobs/{jobId}/confirm` | `STAGE_DRAW` | Concert `PUT /internal/admin/concerts/venues/{id}/sections` (구역마다) |

### 6.4 공연 (`CONCERT`)

| 메서드 · 경로 | 권한 | 보내는 곳 |
|---|---|---|
| `GET /api/studio/concerts` · `/{id}` | `CONCERT` | Concert `GET /api/concerts` · `/{id}` |
| `POST /api/studio/concerts` | `CONCERT` | Concert `POST /internal/admin/concerts` |
| `PATCH /api/studio/concerts/{id}` | `CONCERT` | Concert `PATCH /internal/admin/concerts/{id}` |
| `PUT /api/studio/concerts/{id}/lineup` | `CONCERT` | Concert `PUT /internal/admin/concerts/{id}/lineup` |
| `GET /api/studio/concerts/{id}/seats` | `SEAT_PRICE` | Concert `GET /api/concerts/{id}/seats` |
| `POST /api/studio/concerts/{id}/seats` | `SEAT_PRICE` | Concert `POST /internal/admin/concerts/{id}/seats` — `{ seats: [{ seatId, price }] }` |
| `GET /api/studio/concerts/options/venues` | `CONCERT` | Concert `GET /api/concerts/venues` |
| `GET /api/studio/concerts/options/venues/{venueId}/layout` | `SEAT_PRICE` | Concert `GET /internal/admin/concerts/venues/{id}/layout` |
| `GET /api/studio/concerts/options/artists` | `CONCERT` | Catalog `GET /api/catalog/artists` |
| ✏️ 11/18 `GET`/`POST /api/studio/concerts/{id}/notices` · `PATCH`/`DELETE …/notices/{noticeId}` | `CONCERT` | Concert `/internal/admin/concerts/{id}/notices(/{noticeId})` — 변경 공지 `{ kind: SCHEDULE·VENUE·LINEUP·BOOKING·CANCEL·ETC, title, body }`. 올리면 Concert 가 `concert.notice_posted` → Member 가 라인업 팔로워 + 확정 예매자에게 알림. 고치기 · 지우기는 알림 없음 |
| ✏️ 11/18 `POST /api/studio/concerts/images` | `CONCERT` | (Studio 안) 이미지 업로드 주소 — 6.10 |

- 공연 생성 · 수정 본문에 상세(`description`) · 약관(`terms`) · 요구사항(`requirements`) · 포스터(`posterUrl`) · 이미지(`imageUrls` — 배열)를 넣을 수 있습니다. Concert 가 저장하고 공개 상세 응답에 담습니다. Studio 는 본문을 그대로 넘깁니다.
- `genre` 는 Catalog 의 장르 목록(23개)과 같은 값만 받습니다. 목록에 없는 값이면 Concert 가 400 을 냅니다.
- ✏️ 11/18 `posterUrl` · `imageUrls` 는 우리 버킷 주소만 받습니다 (6.10 · 저장 전에 파일이 있는지 확인).
- **크롤링한 공연은 Studio 를 거쳐 저장합니다 (2026-11-13 결정).** ~~수집기는 포스터 · 이미지를 S3 에 올리고 URL 을 넘깁니다.~~ ✏️ 11/18 수집기는 KOPIS 포스터 · 소개 이미지 주소를 넘기고, 승인할 때 Studio 가 받아 우리 버킷에 올립니다 (6.9 · 6.10). 공연 관리자가 확인한 뒤 Studio 가 `POST /api/studio/concerts` 로 Concert 에 저장합니다 — `posterUrl` · `imageUrls` 에 수집기가 준 S3 URL, `source` · `externalId` 에 수집 출처를 넣습니다 (Concert 는 `(source, externalId)` 가 이미 있으면 409 `CONFLICT` + `concertId` 를 냅니다). 라인업 아티스트는 Concert 가 Catalog 에서 복제해 둔 아티스트여야 하므로 아티스트가 Catalog 에 먼저 있어야 합니다. 단독 공연(`SOLO`)은 `genre` 를 보내지 않습니다 (아티스트 장르를 씀). Gateway 의 공연 수집 경로는 없습니다 (관리자 검수 후에만 등록).
- ✏️ 11/17: 수집기가 넘긴 공연은 Studio 가 후보(`crawl_concert`)로 받아 두고, 공연 관리자가 "수집 대기" 목록에서 확인 · 보완해 등록합니다 (6.9).

### 6.5 굿즈 · 피지컬 앨범 (`GOODS`)

Catalog 의 관리자 API 는 수집(ingest)과 같은 규칙으로 저장하고 같은 이벤트를 냅니다. 옵션은 `id` 를 보내면 이름을 바꾸고, 재고는 늘리거나 줄이는 양으로 보냅니다.

| 메서드 · 경로 | 권한 | 보내는 곳 |
|---|---|---|
| `GET /api/studio/products/goods` | `PRODUCT` | Catalog `GET /api/catalog/goods` |
| `GET /api/studio/products/physical-albums` | `PRODUCT` | Catalog `GET /api/catalog/physical-albums` |
| `GET /api/studio/products/{id}` | `PRODUCT` | Catalog `GET /api/catalog/products/{id}` |
| `POST /api/studio/products` | `PRODUCT` | Catalog `POST /internal/admin/catalog/products` |
| `PATCH /api/studio/products/{id}` | `PRODUCT` | Catalog `PATCH /internal/admin/catalog/products/{id}` |
| `PUT /api/studio/products/{id}/options` | `PRODUCT` | Catalog `PUT /internal/admin/catalog/products/{id}/options` |
| `POST /api/studio/products/options/{optionId}/stock` | `STOCK` | Catalog `POST /internal/admin/catalog/options/{optionId}/stock` |
| ✏️ 11/18 `PUT /api/studio/products/options/{optionId}/stock` `{ qty, reason }` | `STOCK` | Catalog `PUT /internal/admin/catalog/options/{optionId}/stock` — 재고를 qty 개로 설정 |
| `GET /api/studio/products/options/artists` · `/{artistId}` | `PRODUCT` | Catalog `GET /api/catalog/artists` · `/{id}` |
| ✏️ 11/18 `PATCH /api/studio/products/artists/{artistId}` `{ profileUrl }` | `PRODUCT` | Catalog `PATCH /internal/admin/catalog/artists/{id}` — 아티스트 사진 |
| ✏️ 11/18 `GET /api/studio/products/albums/{albumId}` | `PRODUCT` | Catalog `GET /api/catalog/albums/{id}` |
| ✏️ 11/18 `PATCH /api/studio/products/albums/{albumId}` `{ thumbnailUrl?, tracks?: [{ trackNo, genres?, tempo?, moods?, emotions?, styles? }] }` | `PRODUCT` | Catalog `PATCH /internal/admin/catalog/albums/{id}` — 표지 · 트랙 태그 (null 은 그대로 · `[]` 은 비움) |
| ✏️ 11/18 `POST /api/studio/products/images` | `PRODUCT` | (Studio 안) 이미지 업로드 주소 — 6.10 |

- ✏️ 11/18 상품 등록 · 수정의 `thumbnailUrl`, 아티스트 `profileUrl`, 앨범 `thumbnailUrl` 은 우리 버킷 주소만 받습니다 (6.10). 크롤러가 다시 보내도 관리자가 넣은 이미지 · 트랙 태그 · 판매가는 지워지지 않습니다 (Catalog 가 null 이면 유지).

### 6.6 CS (`CS`)

| 메서드 · 경로 | 권한 | 보내는 곳 |
|---|---|---|
| `GET /api/studio/cs/members` | `CS_READ` | Member `GET /internal/admin/members` |
| `GET /api/studio/cs/members/{userId}` | `CS_READ` | Member `GET /internal/admin/members/{id}` |
| `GET /api/studio/cs/members/{userId}/bookings` | `CS_READ` | Concert `GET /internal/admin/concerts/users/{userId}/bookings` |
| `GET /api/studio/cs/members/{userId}/orders` | `CS_READ` | Catalog `GET /internal/admin/catalog/users/{userId}/orders` |
| `GET /api/studio/cs/members/{userId}/payments` | `CS_READ` | Payment `GET /internal/admin/payments/users/{userId}` |
| `POST /api/studio/cs/bookings/{bookingId}/cancel` | `CS_CANCEL` | Concert `POST /internal/admin/concerts/bookings/{id}/cancel` |
| `POST /api/studio/cs/orders/{orderId}/cancel` | `CS_CANCEL` | Catalog `POST /internal/admin/catalog/orders/{id}/cancel` |

- 회원 목록(`?q=&size=`)은 회원 id · 이메일(전체 일치) · 닉네임(부분 일치)으로 찾고 **이메일을 가려서**(`ab***@…`) 내려줍니다. 상세는 이메일 전체를 내려줍니다. 어디까지 내줄지는 Member 가 정합니다.
- 취소 대행은 회원이 직접 취소할 때와 **같은 규칙**(기한 · 상태)을 따르고 사유만 `ADMIN_CANCEL` 로 남습니다. 환불은 Concert · Catalog 가 Payment 로 요청합니다.

### 6.7 검표 (`CHECKER`)

| 메서드 · 경로 | 권한 | 보내는 곳 |
|---|---|---|
| `GET /api/studio/check-in/concerts` | `CHECK_IN` | Concert `GET /internal/admin/concerts/check-in` — 지금 검표할 수 있는 공연 |
| `POST /api/studio/check-in/concerts/{id}` | `CHECK_IN` | Concert `POST /internal/admin/concerts/{id}/check-in` — `{ token }` → `{ bookingId, seats[], checkedInAt }` |

- `token` 은 찍은 QR 값 그대로입니다 (최대 1000자).
- 🔴 **검표자(`checkerId`)는 Studio 가 세션의 관리자 id 로 채웁니다.** 화면이 보낸 값은 쓰지 않습니다.
- 오류는 Concert 의 것을 그대로 내보냅니다: `WRONG_CONCERT`(다른 공연의 QR) · `ALREADY_CHECKED_IN`(+ `checkedInAt`) · `ENTRY_NOT_AVAILABLE`(취소된 예매) · `ENTRY_QR_INVALID`.
- 공연 목록은 검표 탭 아래의 경로로만 냅니다. 검표 관리자는 공연 탭 API 를 볼 수 없습니다 (3.2 ④).

### 6.8 다른 서비스를 부를 때

- 헤더: `X-Internal-Token`(HS256 · `{iss: studio, aud: <대상>, exp: +300초}`) · `X-Admin-Id`(누가 시켰는지 — 대상 서비스의 기록용) · `X-Correlation-Id`
- 쿼리스트링과 본문은 그대로 넘기고, 응답의 `result` 만 꺼내 Studio 의 응답에 담습니다.
- 대상의 4xx 는 `code` · `message` 를 그대로 내보냅니다 (예: 409 `SECTION_IN_USE`).
- 대상의 401 · 403 · 5xx 는 502 `UPSTREAM_ERROR` 입니다. 🔴 401 을 그대로 내보내면 화면이 관리자를 로그아웃시키기 때문입니다.
- 연결 실패 · 시간 초과(연결 2초 · 응답 10초)는 503 `UPSTREAM_UNAVAILABLE` 입니다.
- 공개 조회(`/api/concerts/**` · `/api/catalog/**`)도 Gateway 가 아니라 서비스를 직접 부릅니다.
- ✏️ 11/16 운영에서는 4개 주소가 모두 `manifests-kt` `compose.yml` 의 `x-link-base-url` 하나입니다 (`https://link-<환경>.indig.co.kr`). 그 환경의 cloudflared(`studio-link`)가 경로로 서비스를 나눕니다 — `/internal/admin/concerts` · `/api/concerts` → concert, `…/catalog` → catalog, `/internal/admin/members` → member, `/internal/admin/payments` → payment. 그 밖의 경로는 터널이 404 로 끝내고, KT 공인 IP 밖에서 온 요청은 Cloudflare WAF 가 막습니다. 🔴 Studio 가 새 경로 앞부분을 부르면 터널 규칙(`manifests-kt` README "Cloudflare")에도 더해야 합니다.

### 6.9 크롤러 → Studio (11/17 결정)

크롤러(Python · AI + 크롤링 + 전처리 · 사용자 담당)는 같은 KT compose 안에서 Studio 만 부릅니다. Gateway `/ingest` 로는 보내지 않습니다 — 관리자 터널(Cloudflare)이 붙이는 `CF-Ray` · `CF-Connecting-IP` · `X-Forwarded-For` 때문에 Gateway G1 이 404 로 막습니다.

| 경로 (크롤러가 부름) | 하는 일 |
|---|---|
| `POST /internal/crawl/concerts` | 공연 후보 받기 — `{ items: [{ source, external_id, source_hash, concert: {...} }] }` 최대 100건 → 항목별 `CREATED` · `UPDATED` · `UNCHANGED`(같은 해시) · `REVIEWED`(등록 안 함으로 처리한 후보라 그대로) · ✏️ 11/18 `CHANGED`(등록한 공연이 바뀌어 옴 — 아래) |
| `POST /internal/crawl/catalog/artists` · `/albums` | ✏️ 11/17 오후 변경: **바로 넘기지 않고** 후보(`crawl_catalog`)로 받음 — `{ items: [...] }` 최대 500건 · 항목은 Catalog 수집 계약(catalog 문서 7.3 · snake_case) 그대로, 앨범만 `artist_id` 대신 `artist_ref: { source, external_id }`. Studio 는 열쇠(`source` · `external_id` · 앨범의 `artist_ref`)만 검사 → 항목별 `CREATED` · `UPDATED` · `UNCHANGED` · `REVIEWED`(적재 안 함으로 처리된 것) |
| `POST /internal/crawl/catalog/diff` | 조회라 그대로 Catalog `POST /internal/admin/catalog/ingest/diff` 로 (Studio 토큰 · `X-Admin-Id` 없음) |

| 경로 (관리자 화면) | 권한 | 하는 일 |
|---|---|---|
| `GET /api/studio/crawl/concerts?status=&beforeId=&size=` | `CONCERT` | 수집 대기 목록 (기본 `PENDING` · 최신순 · 커서) — `{ id, source, externalId, status, concert, receivedAt, reviewedBy, reviewedAt, concertId }` |
| `POST /api/studio/crawl/concerts/{id}/approve` | `CONCERT` | 등록 — 본문은 `POST /api/studio/concerts` 와 같음 (화면이 후보 값으로 채우고 공연장 · 라인업 id 등을 보완). Studio 가 `source` · `externalId` 를 후보 값으로 넣어 Concert 에 보내고 `concertId` 를 남김 |
| `POST /api/studio/crawl/concerts/{id}/reject` | `CONCERT` | 등록 안 함 — 같은 후보가 다시 와도 대기 목록에 다시 올리지 않음 |
| ✏️ 11/18 `POST /api/studio/crawl/concerts/{id}/change-checked` | `CONCERT` | 바뀐 공연을 반영했음 — `CHANGED` → `APPROVED` · `previous` 비움 (공연 수정 · 변경 공지는 관리자가 따로) · `CHANGED` 가 아니면 409 `CRAWL_CONCERT_REVIEWED` |
| `GET /api/studio/crawl/catalog?status=&type=&beforeId=&size=` | `PRODUCT` | 아티스트 · 앨범 수집 대기 (기본 `PENDING` · `type` 없으면 둘 다) — `{ id, type, source, externalId, status, item, receivedAt, reviewedBy, reviewedAt, catalogId, lastError }`. `catalogId` 가 있으면 이미 Catalog 에 있는 것의 변경 |
| `POST /api/studio/crawl/catalog/{id}/approve` | `PRODUCT` | 승인 → 그 한 건을 Catalog `/internal/admin/catalog/ingest/{artists·albums}` 로 (`X-Admin-Id` 붙음). 본문을 비우면 받은 값, 넣으면 고친 항목으로 저장해 보냄 (`source` · `external_id` 는 후보 값). 앨범은 `artist_ref` 를 승인된 아티스트의 `catalog_id` 로 바꿔 `artist_id` 로 → 아직이면 409 `CRAWL_ARTIST_NOT_APPROVED`. Catalog 가 항목을 거절하면 422 `CRAWL_CATALOG_FAILED`(사유 = message) · 후보는 `PENDING` + `lastError`. 11/18: 앨범의 `physical_albums[].options[].price` · 아티스트의 `goods[].options[].price` 가 0 이하이면 422 `CRAWL_PRICE_REQUIRED`. ✏️ 11/18 오후 판매가는 관리자가 넣는 값이라 크롤러는 null 로 보냄 → null 은 통과해 Catalog 가 판단 (기존 옵션은 저장된 값 유지 · 새 옵션은 판매가가 없으면 `CRAWL_CATALOG_FAILED` "새 옵션 'X' 의 판매가가 필요합니다" → 고친 항목으로 가격을 넣거나 `physical_albums` · `goods` 를 null 로). 이미지 주소(`profile_url` · `thumbnail_url` · `goods[]` · `physical_albums[]`)가 있으면 우리 버킷 파일인지 확인 (6.10). 출처 `evidence` 는 목록 · 상세에 보이고 Catalog 로는 빼고 보냄 |
| `POST /api/studio/crawl/catalog/approve` | `PRODUCT` | 여러 건 승인 `{ ids }` (최대 100) — 아티스트부터 · 실패해도 계속 → `[{ id, approved, catalogId, code, message }]` |
| `POST /api/studio/crawl/catalog/{id}/reject` | `PRODUCT` | 적재 안 함 — 다시 와도 대기 목록에 올리지 않음 |

- **인증:** `X-Internal-Token` HS256 `{ iss: crawler, aud: studio, exp }` (시계 오차 30초). 서명 키는 시크릿 `crawler.shared-secret` (8.3) — 🔴 서비스 공유 키(`internal.shared-secret`)와 다른 값입니다. 같으면 크롤러가 `iss=studio` 토큰을 만들어 Studio 를 건너뛸 수 있습니다. 파일이 없으면 `/internal/crawl/**` 는 전부 401 입니다.
- `/internal/crawl/**` 은 관리자 세션 · 작업 기록 대상이 아니고 KT compose 안에서만 부릅니다 (`http://studio:8085`). 밖에서는 `admin.indig.co.kr` 의 WAF ④ 가 `/api/studio/` 밖 경로를 막습니다.
- ~~공연: 확인한 뒤(`APPROVED` · `REJECTED`)에 크롤러가 바뀐 값을 보내도 후보는 그대로입니다.~~ ✏️ 11/18 공연: `REJECTED` 는 그대로(`REVIEWED`). **등록한(`APPROVED`) 공연이 다른 해시로 오면 `CHANGED`** — 처음 바뀔 때 그때까지의 값을 `previous_json` 에 남기고(V1 · 계속 바뀌어도 첫 기준 유지) 새 값으로 바꿈. 목록 · 상세에 `previous` 로 보여 줘 화면이 비교. 관리자는 공연 수정(6.4) · 변경 공지를 하고 `change-checked`.
- ✏️ 11/18 공연 승인 때 이미지: 본문의 `posterUrl` · `imageUrls` 중 KOPIS 주소(`app.image.import-hosts`)는 Studio 가 받아 우리 버킷(`poster/` · `concert/`)에 올리고 그 주소로 바꿔 Concert 에 보냅니다. 못 받으면 502 `IMAGE_IMPORT_FAILED`(관리자가 직접 올림). 그 밖의 주소는 우리 버킷 파일만.
- 아티스트 · 앨범: 승인된 것이 다른 `source_hash` 로 다시 오면 **다시 `PENDING`** 입니다 (새 굿즈 · 트랙 · 피지컬도 승인을 거침 · `catalogId` 유지). `REJECTED` 는 그대로입니다.
- ✏️ 11/17 오후 결정: Catalog 에 적재되는 순간 팔로워 알림 이벤트(`album.released` 등)가 나가므로 크롤러가 보낸 것은 **전부 관리자 승인 뒤** 사용자 서비스로 갑니다.
- 같은 후보를 두 관리자가 동시에 등록하면 Concert 의 `(source, externalId)` 유일 조건 때문에 뒤쪽이 409 `CONFLICT` 를 받습니다.
- 아티스트 · 앨범 · 상품을 바로 넘기는 이유: 11/17 결정 (B1) — 공연만 관리자가 확인합니다. 잘못 들어간 상품은 상품 관리(6.5)에서 고칩니다.

### 6.10 이미지 (11/18 결정 · 구현)

상품 · 공연 이미지는 관리자가 **파일로 올립니다** (주소 붙여넣기 없음). 파일은 Studio 를 거치지 않고 브라우저 → S3 로 바로 갑니다.

| 메서드 · 경로 | 권한 | 하는 일 |
|---|---|---|
| `POST /api/studio/products/images` | `PRODUCT` | `{ kind: ARTIST·ALBUM·GOODS·PHYSICAL, contentType, size }` → `{ uploadUrl, method: "PUT", headers, imageUrl, expiresAt }` |
| `POST /api/studio/concerts/images` | `CONCERT` | `{ kind: POSTER·CONCERT, contentType, size }` → 같음 |

- 키 `{kind}/{yyyy}/{MM}/{uuid}.{ext}` · 공개 주소 `app.image.public-base-url`(기본 `https://img.indig.co.kr`) + 키. 형식 `image/jpeg` · `png` · `gif` · `webp` · 최대 `app.image.max-bytes`(5MB) · 업로드 주소 `upload-ttl-seconds`(300초). 업로드 주소는 Content-Type · Content-Length 까지 서명하므로 다른 종류 · 크기로는 S3 가 403.
- 저장할 때(`ImageGuard`): 이미지 주소가 우리 공개 주소로 시작하고 키 모양이 맞는지 → S3 `HeadObject` 로 실제 파일이 있는지. 아니면 400 `IMAGE_URL_NOT_ALLOWED` · `IMAGE_NOT_UPLOADED`. 관리자 API(상품 · 아티스트 · 앨범 · 공연 등록/수정)와 크롤러 후보 승인 모두.
- KOPIS 가져오기(`importFrom`): `app.image.import-hosts`(www.kopis.or.kr · kopis.or.kr)만 · http/https · 리다이렉트 안 따라감 · 크기 상한 · **앞 바이트로 jpg/png/gif/webp 확인**(응답 Content-Type 을 믿지 않음).
- 설정: `IMAGE_BUCKET`(비우면 `DisabledImageStore` → 503 `IMAGE_STORE_DISABLED` · 주소 확인도 거절) · `IMAGE_PUBLIC_BASE_URL` · `IMAGE_S3_ENDPOINT`(로컬 · 테스트용 · path-style) · 시크릿 `image.access-key` · `image.secret-key` (configtree). AWS SDK v2 2.57.1 (`s3` · JDK `url-connection-client` — Spring Boot 3.3 의 httpclient5 와 맞지 않아 apache5 · netty 는 뺌).
- 인프라(남음 · `docs/v2_후속_작업.md` 2-1 · 2-2): 버킷 · CloudFront `img.indig.co.kr` · CORS(`https://admin.indig.co.kr` 에서 PUT) · IAM(버킷 6경로 Put · Get).
- 테스트: `S3ImageStoreTest`(LocalStack 3.8.1 S3 · 서명 검사 켬 — 업로드 주소로 PUT · 다른 Content-Type 403 · HeadObject · 남의 주소 · 경로 조작 · 크기 · 형식 · KOPIS 가져오기 · 거짓 Content-Type · 리다이렉트) · `ImageAndNoticeTest` · `CrawlIntakeTest`(가짜 저장소). MinIO 공식 이미지는 받을 수 없어 LocalStack 을 씀.

## 7. 오류 코드

| HTTP | code | 언제 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 본문 · 파라미터 검증 실패 |
| 400 | `PASSWORD_POLICY` · `PASSWORD_MISMATCH` | 비밀번호 규칙 위반 · 현재 비밀번호 틀림 |
| 400 | `STAGE_IMAGE_INVALID` · `STAGE_DRAWING_INVALID` · `STAGE_VALUES_INVALID` | 사진 형식 · 크기 / 그린 값 JSON / 보낼 구역 없음 |
| 401 | `UNAUTHENTICATED` | 세션 없음 · 만료 |
| 401 | `LOGIN_FAILED` · `OTP_INVALID` · `OTP_CHALLENGE_EXPIRED` | 1차 실패 / 인증 코드 틀림 / 2단계 대기 만료 (아이디 · 비밀번호부터 다시) |
| 503 | `OTP_MAIL_FAILED` | 인증 코드 메일을 못 보냄 (SMTP) — 잠시 뒤 1차부터 다시 |
| 403 | `CSRF_INVALID` | `X-CSRF-Token` 없음 · 틀림 |
| 404 | `NOT_FOUND` | 없는 경로 **또는 권한 밖** (구분 불가) |
| 404 | `ACCOUNT_NOT_FOUND` · `STAGE_JOB_NOT_FOUND` · `STAGE_IMAGE_NOT_FOUND` · `CRAWL_CONCERT_NOT_FOUND` · `CRAWL_CATALOG_NOT_FOUND` | |
| 405 | `METHOD_NOT_ALLOWED` | |
| 409 | `LOGIN_ID_TAKEN` · `CANNOT_CHANGE_SELF` · `STAGE_JOB_NOT_REVIEWABLE` · `CRAWL_CONCERT_REVIEWED` · `CRAWL_CATALOG_REVIEWED` · `CRAWL_ARTIST_NOT_APPROVED` · `CONFLICT` | |
| 413 | `PAYLOAD_TOO_LARGE` | 업로드 10MB 초과 |
| 400 | `IMAGE_TYPE_NOT_ALLOWED` · `IMAGE_TOO_LARGE` · `IMAGE_URL_NOT_ALLOWED` · `IMAGE_NOT_UPLOADED` | ✏️ 11/18 이미지 — 형식 · 5MB · 우리 주소 아님 · 버킷에 파일 없음 |
| 502 | `IMAGE_IMPORT_FAILED` | ✏️ 11/18 KOPIS 포스터 · 이미지를 받지 못함 |
| 503 | `IMAGE_STORE_DISABLED` | ✏️ 11/18 이 환경에 이미지 버킷이 없음 |
| 422 | `CRAWL_CATALOG_FAILED` · `CRAWL_PRICE_REQUIRED` | 수집 후보 승인 — Catalog 거절 · 옵션 판매가 0 이하 |
| 500 | `INTERNAL_ERROR` | |
| 502 | `UPSTREAM_ERROR` | 대상 서비스의 401 · 403 · 5xx |
| 503 | `UPSTREAM_UNAVAILABLE` | 대상 서비스에 닿지 못함 |

## 8. 설정 · 시크릿 · 로컬 실행

### 8.1 코드 구조

```
com.indie.studio
├─ global    config · common(응답 · 예외 · 필터) · auth(역할 · 세션 필터 · 권한 인터셉터) · audit · client(내부 토큰 · 서비스 호출)
└─ domain    account · auth · stage · concert · product · cs · checkin
             (concert · product · cs · checkin 은 컨트롤러만 — 대상 서비스로 넘기는 얇은 층)
```

Spring Boot 3.3.5 · Java 21 · Gradle 8.10.2 · Flyway · MySQL 8.4 · Redis 7.4 (Lettuce · `StringRedisTemplate`). Spring Security 는 쓰지 않고 `spring-security-crypto`(bcrypt)만 씁니다 — 인증은 `SessionFilter`, 권한은 `PermissionInterceptor` 두 곳에 있습니다.

### 8.2 환경변수

| 이름 | 내용 | 기본값 |
|---|---|---|
| `DB_HOST` | MySQL 호스트 | (필수) |
| `REDIS_HOST` | Redis 호스트 (포트 6379) | (필수) |
| `CONCERT_BASE_URL` · `CATALOG_BASE_URL` · `MEMBER_BASE_URL` · `PAYMENT_BASE_URL` | 서비스 주소. ✏️ 11/16 운영은 4개 모두 같은 값 (`manifests-kt` `compose.yml` 의 `x-link-base-url`) | `http://concert:8083` 등 |
| `GPU_BASE_URL` | GPU 서버 주소 (WireGuard 피어). 비면 무대 검증이 `FAILED` | 없음 |
| `STUDIO_ALLOWED_ORIGINS` | 관리자 화면 출처 (쉼표 구분). 같은 출처면 비움 | 없음 |
| `STUDIO_COOKIE_SAMESITE` | 관리자 화면이 다른 사이트에 있으면 `None` | `Strict` |
| `STUDIO_MAIL_FROM` | 인증 코드 메일의 보내는 사람 | `indig <noreply@indig.co.kr>` |
| `SMTP_HOST` · `SMTP_PORT` | 메일 서버 | `smtp.gmail.com` · `587` |

### 8.3 시크릿 (`/etc/secrets` 의 파일 · 파일 이름이 키)

| 파일 | 내용 |
|---|---|
| `db.password` | `studio_app` 계정 비밀번호 |
| `redis.password` | Redis 비밀번호 (KT 의 Redis 는 `--requirepass`) |
| `internal.shared-secret` | 내부 토큰 서명 키 — 🔴 AWS 서비스들과 같은 값이어야 합니다 |
| `gpu.token` | GPU 서버와 맞춘 토큰 |
| `crawler.shared-secret` | 크롤러 토큰 검증 키 (6.9) — 크롤러 컨테이너와 같은 값 · 🔴 `internal.shared-secret` 과 다른 값. 없으면 크롤러 경로가 401 (Studio 는 뜸) |
| `smtp.username` · `smtp.password` | 메일 서버 로그인 — Gmail 주소와 앱 비밀번호(16자) |
| `bootstrap.admins` | 관리자 계정 목록 (4장 "계정 심기") — 비밀번호는 bcrypt 해시. DB 에 없는 아이디만 만들어집니다 |

Studio 는 KT 전용 Secret 만 마운트합니다. AWS 서비스들의 `app-secrets` 를 통째로 넘기지 않습니다.

### 8.4 로컬 실행

```bash
./local/setup-local.sh                       # local-secrets/ 생성 (커밋되지 않음) · 첫 마스터 비밀번호 출력
docker compose -f local/compose.yml up -d    # MySQL 8.4 → 127.0.0.1:3307 · Redis 7.4 → 127.0.0.1:6382 (✏️ 11/16)
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

로컬 프로파일은 쿠키 `Secure` 를 끄고 화면 출처를 `http://localhost:5173` 으로 둡니다. 다른 서비스가 떠 있지 않으면 그쪽으로 가는 API 는 503 입니다.

## 9. 다른 서비스 · 저장소가 고칠 것

6장의 API 가 부르는 내부 API 는 11/13 에 각 서비스에서 만들었습니다. Studio 는 본문 · 응답을 그대로 넘기므로 모양은 각 서비스가 정합니다.
관리자 경로(`/internal/admin/**`)는 각 서비스의 `InternalTokenFilter` 가 `admin-issuers`(`studio`)의 토큰만 받습니다. 다른 서비스의 토큰으로는 부를 수 없고, Studio 토큰으로 그 밖의 내부 경로를 부를 수도 없습니다.

**만든 것 (2026-11-13)**

| 대상 | 내용 |
|---|---|
| **concert** | 공연 필드 `description` · `terms` · `requirements` · `poster_url` · `image_urls` — 테이블 · 생성/수정 DTO · 공개 상세 응답. `genre` 는 Catalog 장르 목록(23개) |
| | `GET /internal/admin/concerts/venues/{venueId}/layout` — 저장된 구역 · 좌석 |
| | `GET /internal/admin/concerts/users/{userId}/bookings` · `POST /internal/admin/concerts/bookings/{id}/cancel` (CS) |
| | 공연장 · 공연 · 라인업 · 구역 저장 · 좌석 가격 · 체크인 내부 API 는 이미 있었고 `allowed-issuers` 에 `studio` 도 있었습니다 |
| **catalog** | `app.internal.admin-issuers: [studio]` (쓰지 않던 `assistant` 삭제) |
| | `POST` · `PATCH /internal/admin/catalog/products(/{id})` · `PUT …/products/{id}/options` · `POST …/options/{optionId}/stock` · ✏️ 11/18 `PUT …/options/{optionId}/stock` |
| | `GET /internal/admin/catalog/users/{userId}/orders` · `POST /internal/admin/catalog/orders/{id}/cancel` (CS) |
| **member** | `admin-issuers: [studio]` · `GET /internal/admin/members?q=&size=` · `GET /internal/admin/members/{id}` |
| **payment** | `admin-issuers: [studio]` · `GET /internal/admin/payments/users/{userId}` |
| **Gateway** | 없음 — Studio 는 Gateway 를 거치지 않습니다 |

**KT 배포 — ✏️ 2026-11-16 KT Cloud 를 받아 만든 것** (KT 는 VM 한 대의 docker compose · 맥 docker 로 확인 · 실제 VM 에서는 아직 돌려 보지 않음)

| 대상 | 내용 |
|---|---|
| ☑ **manifests-kt** | `compose.yml` — mysql · redis · studio · prometheus · grafana · kt-tunnel. Studio 는 1대 · 비밀 값은 VM 의 `/opt/indig/secrets/studio` → `/etc/secrets`. 전환 한 줄 `x-link-base-url`. 배포는 `deploy` 워크플로(KT 의 self-hosted runner · `docker compose up --wait`) |
| ☑ **manifests** | `services/studio-link` — 관리자 터널 cloudflared (AWS `apps/studio-link.yaml` · PC 는 manifests-onprem). concert · catalog · member · payment NetworkPolicy 에 `app: studio-link` 인그레스 (concert 의 `app: studio` 규칙은 이것으로 바꿈) |
| ☑ **manifests-kt** | MySQL 8.4 (`studio` · `studio_app` — 이미지가 첫 기동 때 만듦) · Redis 7.4 · KT 터널(`admin` · `grafana` · `metrics.indig.co.kr`) · 비밀 값 스크립트(`make-secrets.sh` · `send.sh`) · VM 설치(`setup.sh` — Docker · runner) · GPU WireGuard(`wireguard.sh`) |
| ☑ **manifests** | `services/prometheus-agent` — AWS · PC 의 지표를 KT Prometheus 로 (Studio 와는 별개 · 같은 KT VM). ✏️ 11/16 `services/studio` 는 지웠습니다 (compose 로 옮김) |
| ☑ **CI** | deploy 잡 — Docker Hub push(amd64 · arm64) · `manifests-kt` 의 `compose.yml` 태그 커밋. ☐ Environment `deploy` 에 `DOCKERHUB_TOKEN` · `MANIFESTS_KT_DEPLOY_KEY` 등록 (`DOCKERHUB_USERNAME` 변수는 등록함) |
| ☐ **Cloudflare** | 터널 3개(`indig-kt` · `indig-link-aws` · `indig-link-onprem`) · 경로 규칙 · WAF ② 호스트 조건 + ③ · ④ (`manifests-kt` README "Cloudflare") |
| ☐ **KT VM** | 방화벽(들어오는 22 · 51820/udp · 나가는 443 · 7844 등) · `scripts/send.sh` → 첫 배포 → 첫 마스터 로그인 |
| ☐ **GPU** | WireGuard 피어 (`manifests-kt scripts/wireguard.sh`) · `compose.yml` 의 `GPU_BASE_URL` |

옛 계획(KT 를 AWS 클러스터에 agent 노드로 붙이고 NAT ↔ KT WireGuard)은 쓰지 않습니다 — 노드는 한 클러스터에만 붙을 수 있어 AWS ↔ PC 전환이 안 됩니다.

**대기 — 그 밖**

| 대상 | 할 일 |
|---|---|
| ☐ **수집기** | 사용자가 직접 만듭니다 (11/17 · Python · AI + 크롤링 + 전처리). 정해진 것: 이미지는 **수집기가 S3 에 올리고 URL 을 넘김** · 공연은 Studio 후보 → 관리자 등록 · 아티스트 · 앨범 · 상품도 Studio 후보 → 상품 관리자 승인 → Catalog 관리자 적재 (6.9 · 11/17 오후 변경). Gateway `/ingest` 는 쓰지 않습니다. 보내는 모양은 `docs/v2_크롤러_전송_계약.md` |
| ☑ **수집기 ↔ Studio** | ✏️ 11/17 만들었습니다 (6.9) — 받는 API `/internal/crawl/**` · 토큰 `iss=crawler` · 후보 테이블 `crawl_concert` · `crawl_catalog` · 관리자 API `/api/studio/crawl/concerts` · `/api/studio/crawl/catalog`. 남은 것: 관리자 화면의 "수집 대기" 목록 · KT 에 `crawler.shared-secret` 파일 (`manifests-kt` README "크롤러") |
| ☐ **인프라 (S3)** | 크롤링 이미지용 버킷 · 공개 방식(CloudFront 등) · 수집기의 쓰기 자격 증명. 지금 Terraform 의 S3 4개(`stage-images` · `crawl-raw` · `audit` · `server-logs`)에는 공개 이미지용 버킷이 없습니다. `stage-images` 버킷과 `studio-presign` 유저는 무대 사진을 MySQL 에 두기로 해서 쓰이지 않습니다 |
| ☐ **GPU 서버** | `POST /v1/stage/verify` (5.3) |
| ☐ **concert** | 공개 조회(`/api/concerts/**`)를 Studio 가 Gateway 없이 직접 부릅니다. `X-Admin-Id` 헤더를 기록에 쓸지 |

이전 관리자 설계 문서(`docs/22` §2 · §3 · `docs/24` · `docs/34`)는 **지우지 않고 남겨 두고, 각 문서 머리에 폐기를 표시했습니다** (11/13 결정). `docs/22` 의 §1(수집 데이터 이중 검증)은 지금 코드(Gateway · Catalog)의 기준이라 폐기 대상이 아닙니다.

## 10. 테스트

`./gradlew test` — 28건 통과 · 건너뜀 0 (2026-11-13 · ✏️ 11/16 Redis 로 옮긴 뒤 다시 통과 · ✏️ 11/17 `CrawlIntakeTest` 7건). 실제 MySQL 8.4 · Redis 7.4(비밀번호 있음)를 Testcontainers 로 띄우므로 Docker 가 필요합니다. AWS 서비스와 GPU 서버는 테스트 안의 가짜 HTTP 서버입니다.

| 테스트 | 건수 | 확인하는 것 |
|---|---|---|
| `AuthFlowTest` | 7 | 비밀번호 → 메일 인증 코드 → 대시보드(응답에 코드 없음 · 쓴 코드 재사용 거부) · 공용 계정은 모든 주소에 같은 코드(`bootstrap.admins` 로 심은 계정) · 5분 만료와 코드 5회 실패 · 메일 실패 시 503 이고 대기 없음 · 5회 실패 잠금 · 30분 무응답 / 8시간 만료 · 내 비밀번호 변경 |
| `RbacTest` | 8 | 역할별 탭과 닿는 API(검표 포함 6개 역할) · 권한 밖은 없는 경로와 같은 404 + `DENIED` 기록 · 내부 토큰 `iss` / `aud` · 대상 오류 전달(401 로는 안 나감) · CSRF(막힌 요청도 기록) · 계정 관리와 즉시 반영 · 모든 핸들러의 권한 선언 · 검표 요청의 `checkerId` = 세션의 관리자 id |
| `CrawlIntakeTest` | 7 | 크롤러 토큰만 통과(키 없음 · 서비스 공유 키 · 다른 `iss` / `aud` · 만료 → 401, 크롤러 토큰으로 관리자 API 401) · 공연 후보 CREATED → 같은 해시 UNCHANGED → UPDATED → 공연 관리자만 목록(다른 역할 404) → 등록 때 `source` · `externalId` 를 후보 값으로 덮어 Concert 로 · 처리 뒤 REVIEWED · 두 번 처리 409 · 등록 안 함 · 아티스트 · 앨범은 받기만 하고 Catalog 호출 없음 → 상품 관리자만 목록 → 앨범 먼저 승인 409 → 아티스트 승인(본문 그대로 · Studio 토큰 · `X-Admin-Id`) → 앨범 승인 때 `artist_ref` → `artist_id` · 승인된 것의 변경은 다시 PENDING · Catalog FAILED 는 422 + `lastError` · 고친 값으로 승인 · 적재 안 함 → REVIEWED · 여러 건 승인은 아티스트부터 · 열쇠 없으면 400 · diff 는 그대로 |
| `StageJobFlowTest` | 3 | GPU 검증 → 확인 → Concert 수신(확인 전에는 0건) · 값 수정 후 전송과 Concert 거절 뒤 재시도 · 잘못된 업로드 거절과 GPU 실패 표시 |
| `StudioSchemaTest` | 3 | 마이그레이션이 V1(구조) · V2(값) 두 개뿐 · CHECK 값 = Java enum · DB 에 해시와 암호문만 있음 · ✏️ Redis 키는 `studio:auth:*` + 64자 해시이고 모두 만료가 걸림 · 세션 테이블이 MySQL 에 없음 · 2단계 대기에는 인증 코드 해시만 |

로컬에서 직접 확인한 것: 앱 기동(Flyway 적용 · readiness UP) → 첫 마스터 생성 → 로그인 → OTP 등록 → `/me` 에 탭 6개(검표 추가 전 — 지금은 7개) → 세션 없이 401 · Concert 가 없을 때 503.

11/17 메일 인증 코드로 바꾼 뒤 로컬에서 다시 확인한 것: `htpasswd` 로 만든 해시(`$2y$`)로 심은 계정 로그인 → Mailpit 으로 온 메일(보내는 사람 `indig <noreply@indig.co.kr>` · 한글 본문) → 틀린 코드 401 → 맞는 코드로 세션 · `/me` 200 → 쓴 코드 재사용 401. 같은 앱을 실제 Gmail SMTP(587 · 앱 비밀번호)에 붙여 1차 로그인 200(메일 접수)까지 확인했습니다. 테스트는 가짜 메일함(`MailBox`)을 씁니다.

검증하지 못한 것: 실제 GPU 서버, 실제 Concert · Catalog · Member · Payment 호출(각 서비스의 관리자 API 는 그 서비스의 테스트로만 확인했고 Studio 에서 HTTP 로 이어 부르지는 않았습니다), WireGuard 구간, KT 배포, 관리자 화면과의 연동(쿠키 · CORS).

## 11. 관리자 화면이 알아야 할 것

- 모든 요청에 쿠키를 실어 보냅니다 (`credentials: 'include'`).
- 로그인은 두 화면입니다. 1차가 통과하면 "`sentTo` 주소로 인증 코드를 보냈습니다" 를 보여 주고 6자리 입력 칸을 띄웁니다 (QR · OTP 앱 등록 화면은 없습니다). 메일 발송에 1~3초 걸릴 수 있어 1차 버튼은 응답이 올 때까지 막습니다. "코드 다시 받기" 는 1차를 다시 부르는 것입니다. 401 `OTP_CHALLENGE_EXPIRED` 면 아이디 · 비밀번호 화면으로, 503 `OTP_MAIL_FAILED` 면 잠시 뒤 다시 시도하라고 안내합니다.
- 로그인 뒤 `GET /api/studio/me` 를 부르고, **`menus` 에 있는 탭만** 그립니다. 탭 이름을 화면 코드에 고정해 두고 역할로 숨기는 방식은 쓰지 않습니다 (다른 역할의 탭 이름이 번들에 남습니다 — 12장 ⑧).
- POST · PUT · PATCH · DELETE 에는 `X-CSRF-Token: <me.csrfToken>` 을 붙입니다.
- 401 `UNAUTHENTICATED` → 로그인 화면으로. 404 는 "없는 페이지" 로만 보여 줍니다 (권한 안내 문구 금지).
- 무대 그리기: 검증 요청 뒤 작업을 2~3초마다 조회하고 `REVIEW` 가 되면 `…/image` 와 `values` 를 보여 줍니다. `FAILED` 면 `errorMessage` 를 보여 주고 다시 요청하게 합니다.
- 검표 탭: 카메라로 QR 을 읽어 `token` 만 보냅니다. 409 `ALREADY_CHECKED_IN` 이면 응답의 `checkedInAt`(먼저 입장한 시각)을 보여 줍니다.
- 502 · 503 은 Studio 가 아니라 연결된 서비스 문제입니다. 로그아웃시키지 않습니다.

## 12. 배포 전에 확인할 것 · 아직 정하지 않은 것

**배포 전 확인**

1. **9장의 ☐ 항목**(Cloudflare 터널 · WAF · CI Environment · KT VM)이 먼저입니다. catalog · member · payment 의 `admin-issuers` 에는 `studio` 를 넣었습니다 — 그 버전이 배포돼 있지 않으면 그쪽 호출은 전부 502 입니다.
2. **`internal.shared-secret` 은 AWS 와 같은 값**, 나머지 시크릿은 KT 용으로 새로 만듭니다 (`manifests-kt` `scripts/make-secrets.sh`). 관리자 계정 원본 `~/indie-secrets/kt/admins.csv` 와 Gmail 앱 비밀번호는 맥에만 있으니 `~/indie-secrets` 를 백업할 때 함께 둡니다.
3. ✏️ 11/16 **KT 에서 서비스에 닿는 주소**는 `https://link-aws.indig.co.kr` · `https://link-onprem.indig.co.kr` 입니다 (6.8). 부르는 쪽은 `manifests-kt` 의 `compose.yml` 한 줄(`x-link-base-url`)로 바꿉니다.
4. ✏️ 11/16 **관리자 화면**은 프론트(`https://indig.co.kr` · Vercel) 안에 있고 Studio 는 `https://admin.indig.co.kr` 입니다. 같은 사이트라 쿠키는 기본값 `Strict` 그대로, `STUDIO_ALLOWED_ORIGINS` 는 `https://indig.co.kr,https://www.indig.co.kr` 로 두었습니다. 🔴 로컬 화면(`localhost:5173`)에서 운영 Studio 를 부르면 다른 사이트라 쿠키가 가지 않습니다.
5. **Studio 는 1대만 띄웁니다.** 무대 검증이 프로세스 안의 스레드에서 돌아서, 여러 대면 기동할 때 서로의 작업을 `FAILED` 로 닫습니다.
6. ✏️ 11/16 CI 에 deploy 잡을 넣었습니다 (Docker Hub · `manifests-kt` 의 `compose.yml` 태그 커밋). Environment `deploy` 의 secret 을 등록하기 전이나 manifests-kt main 에 `compose.yml` 이 들어가기 전에 이 레포를 main 에 머지하면 deploy 잡이 실패합니다 (9장).
7. (11/17) KT 방화벽에 나가는 587/TCP 가 열려 있어야 인증 코드 메일이 나갑니다 (`manifests-kt` README). Gmail 무료 계정은 하루 발송 한도(약 500통)가 있습니다 — 관리자 5계정 로그인에는 충분합니다.
8. Flyway 가 MySQL 8.4 를 "테스트한 버전(8.1)보다 새 버전" 이라고 경고합니다. 마이그레이션은 정상 적용됩니다.

**정하지 않은 것 (제가 기본값으로 둔 것)**

- ① **계정마다 역할 하나.** 한 사람이 두 업무를 하면 계정을 두 개 쓰거나 마스터를 써야 합니다. 11/17 결정으로 운영은 역할별 공용 계정이고, 한 사람이 여러 계정의 메일 수신자로 들어갑니다 (4장).
- ② **마스터는 여러 명 만들 수 있습니다.** 마스터끼리는 서로의 역할 · 상태를 바꿀 수 있습니다.
- ③ **공연 이미지는 URL 로 받습니다 (11/13 결정).** 크롤링 이미지는 수집기가 S3 에 올리고 URL 을 넘기며, Concert 저장은 Studio 가 합니다. 그래서 Studio 에는 업로드 API · S3 자격 증명이 없습니다. 관리자가 수집되지 않은 포스터를 직접 올려야 하는 경우는 아직 없다고 보고 만들지 않았습니다 (제 해석 — 필요해지면 업로드 API 를 추가합니다). 무대 도면 사진은 그대로 MySQL 에 둡니다 (5.2).
- ④ **`drawing` 의 모양**(demo_ui 의 무대 그리기 값)은 화면과 GPU 서버가 정합니다. Studio 는 그대로 넘깁니다.
- ⑤ **CS 취소**는 회원이 직접 취소할 때와 같은 규칙으로 만들었습니다 (6.6). 기한이 지난 건을 관리자가 취소할 수 있게 할지 · 수수료를 뺄지는 정하지 않았습니다.
- ⑥ **보관 기간.** `audit_log` · `stage_job` · 사진을 지우는 배치가 없습니다. 개인정보 접근 기록을 얼마나 보관할지와 함께 정해야 합니다.
- ⑦ **작업 기록 쓰기는 실패해도 요청을 막지 않습니다** (실패는 서버 로그). 기록이 없으면 요청도 실패시킬지.
- ⑧ **관리자 화면을 역할별로 번들을 나눌지.** API 는 서로를 모르지만, 화면이 하나의 번들이면 다른 탭의 코드가 내려갑니다.
- ⑨ **이전 문서**(`docs/22` §2 · §3 · `24` · `34`)는 남겨 두고 폐기를 표시했습니다 (11/13 결정 · 9장).

## 13. 작업 기록

| 날짜 | 내용 |
|---|---|
| 2026-11-13 | 관리자 서비스 개념 · ERD 를 HTML 로 정리 (`docs/34`) → 같은 날 폐기하고 기능 설명 기준으로 다시 시작 |
| 2026-11-13 | `backend/studio-service` 구현: 계정 · 로그인(비밀번호 → OTP) · 역할 5개와 격리 · 무대 그리기(GPU 검증 → 확인 → Concert) · 공연 · 상품 · CS · 작업 기록. 테스트 19건 |
| 2026-11-13 | 로컬 기동 확인 (마스터 로그인 → OTP 등록 → 대시보드) |
| 2026-11-13 | 저장소 연결: `main` 첫 커밋 · `develop` 생성 · 기본 브랜치 `develop` |
| 2026-11-13 | 검표 역할(`CHECKER` · 권한 `CHECK_IN` · 탭 `checkin`) 추가. 마이그레이션을 `V1`(구조) · `V2`(값) 두 파일로 정리. 테스트 21건 |
| 2026-11-13 | concert · catalog · member · payment 에 Studio 가 부르는 관리자 API 와 `admin-issuers` 추가 (9장). KT 배포 · 수집기는 대기로 기록 |
| 2026-11-13 | 결정 반영: 크롤링 이미지는 수집기가 S3 에 올리고 URL 을 넘기며 Concert 저장은 Studio 가 (6.4 · 12장 ③). 이전 설계 문서는 남기고 폐기 표시 (9장). 대기 문서 `docs/pending_studio_work.md` 는 할 일이 모두 정리되어 지움 (남은 일은 9장 · `indie-msa/docs/v2_후속_작업.md`) |
| 2026-11-16 | KT 배포 설정: KT 는 독립 클러스터 · AWS / 온프레미스는 Cloudflare 관리자 터널(`link-aws` · `link-onprem`) · 전환은 `manifests-kt` 한 줄 · 입구 `admin.indig.co.kr` 도 터널 · CI deploy 잡 (9장 · 6.8) |
| 2026-11-16 | KT 를 k3s 대신 docker compose 로 (MySQL · Redis · Studio · Prometheus · Grafana). 로그인 2단계 대기 · 세션을 MySQL 테이블에서 Redis 로 (`LoginChallengeStore` · `SessionStore` · V1 에서 테이블 2개 뺌 · 테스트에 Redis 컨테이너). CI 태그 커밋 대상을 `manifests-kt` 의 `compose.yml` 로. 테스트 21건 통과 · 맥 docker 로 compose 전체 기동 확인 |
| 2026-11-17 | 로그인 2차를 OTP 앱(TOTP · QR)에서 **메일 인증 코드**로 (결정: SMTP · Gmail · 보내는 주소 `noreply@indig.co.kr`). `Totp` · `SecretCipher` · `totp.encryption-key` · `otp-reset` · 첫 마스터 부트스트랩을 없애고, `otp_emails` 컬럼(V1 수정 → 스키마 비우고 다시) · `OtpMailer` · `AdminSeeder`(`bootstrap.admins`) 를 넣음. 운영 계정은 역할별 공용 5개. 테스트 21건 통과 (`TotpTest` 3건 삭제 · `AuthFlowTest` 3건 추가) · 로컬 Mailpit 과 실제 Gmail SMTP 로 발송 확인 |
| 2026-11-17 | 운영 배포 (main · KT): `studio` 스키마를 비우고 V1 · V2 다시 적용 → 공용 계정 5개 생성 → `admin.indig.co.kr` 에서 비밀번호 → 메일 코드 → 세션 · `/me` · 계정 목록 · 로그아웃(CSRF)까지 실제 확인 |
| 2026-11-17 | 크롤러 받는 쪽 (결정 A1 · B1 · C1): `/internal/crawl/concerts` → 후보 `crawl_concert` → 공연 관리자 등록 · 등록 안 함 (`/api/studio/crawl/concerts`), `/internal/crawl/catalog/*` → Catalog `/internal/admin/catalog/ingest/*` 로 바로. 크롤러 토큰 `iss=crawler` · 키 `crawler.shared-secret` (6.9). V1 에 테이블 1개 · V2 에 CHECK 1개 → 🔴 KT 운영 반영 때 studio 스키마 비우기. 테스트 25건 |
| 2026-11-17 | 결정 변경(사용자): 아티스트 · 앨범 · 상품도 Catalog 로 바로 보내지 않고 후보 `crawl_catalog` → 상품 관리자 승인(`/api/studio/crawl/catalog`). 앨범은 `artist_ref` → 승인된 아티스트 `catalog_id`. 승인된 것의 변경은 다시 대기. V1 에 테이블 1개 · V2 에 CHECK 2개 (PR #11 과 함께 main 에 올리면 스키마 비우기는 한 번). 테스트 28건 |

문서를 쓰다가 찾아 고친 것: CSRF 로 막힌 요청이 작업 기록에 남지 않던 문제 (인터셉터가 요청을 막으면 완료 콜백이 불리지 않음 → 막는 자리에서 직접 기록 · 테스트 추가).

## 부록 A. 관계

```
admin_account 1 ─── N audit_log
      │           (2단계 대기 · 세션은 Redis — studio:auth:* · adminId 로만 가리킴)
      │ 1 ───────── N stage_job (requested_by · sent_by) 1 ─── N stage_job_image (ORIGINAL · RESULT 각 1)

stage_job.venue_id ┄┄▶ Concert 의 venue (다른 DB · FK 없음)
```
