# studio-service v2 (관리자 서비스)

> **상태: 구현 완료 · 첫 커밋 (2026-11-13).** `studio-service` 저장소의 `main` · `develop` 에 올렸습니다 (기본 브랜치 `develop`).
> 테스트 19건 통과 (건너뜀 0 · Docker 필요). **다른 저장소는 하나도 고치지 않았습니다.** Studio 가 부르는 내부 API 중 아직 없는 것은 9장에 모았습니다.
> KT 배포 · 실제 GPU 서버 · 실제 AWS 서비스 연동은 확인하지 못했습니다 (10장 · 12장).

## 0. 한눈에 보기

- **관리자 전용 백엔드입니다.** KT Cloud 에서 돌고 DB 는 MySQL 8.4 (`studio` 스키마) 입니다. 포트 8085 (관리 8095).
- **로그인은 2단계입니다.** 1차 아이디 · 비밀번호(DB · bcrypt) → 2차 OTP(TOTP) → 대시보드. 첫 로그인 때 OTP 를 등록합니다.
- **계정마다 역할이 하나입니다.** `MASTER` · `STAGE`(무대) · `CONCERT`(공연) · `GOODS`(굿즈 · 피지컬 앨범) · `CS`.
- **하위 관리자는 서로를 모릅니다.** 탭은 `/me` 가 내려준 것만 그리고, 권한 밖 API 는 없는 경로와 똑같은 404 가 나갑니다.
- **업무 데이터는 Studio 에 없습니다.** 공연 · 상품 · 회원 · 결제는 AWS 의 Concert · Catalog · Member · Payment 내부 API 로 읽고 씁니다 (`X-Internal-Token`, iss=`studio`).
- **무대 그리기:** 화면에서 그린 값 + 도면 사진 → Studio(KT) → 개인 GPU 서버(WireGuard) 검증 → 사진과 값이 화면으로 → 무대 관리자가 확인 → Concert 로 전송. **S3 는 거치지 않습니다.** 사진은 Studio DB 에 둡니다.
- **관리자의 요청은 전부 `audit_log` 에 남습니다** (조회 · 권한 밖 시도 포함). 검색어 · 본문 · 개인정보 값은 남기지 않습니다.
- 테이블은 6개입니다: `admin_account` · `admin_login_challenge` · `admin_session` · `stage_job` · `stage_job_image` · `audit_log`.
- **이전 설계는 폐기했습니다.** `docs/22` · `docs/24` · `docs/34_관리자_서비스_ERD.html` 은 이 문서와 다릅니다 (12장 ⑨).

## 1. 배경과 범위

- 11/13 에 관리자 서비스를 새로 정했습니다. 기준은 그날 받은 기능 설명이고, 거기에 없는 것(코드 구조 · 응답 형식 · 내부 토큰 규약 · 문서 형식)은 concert · catalog · member 의 v2 를 따랐습니다.
- 포함: Studio 백엔드 전체 (계정 · 로그인 · 역할 · 무대 그리기 · 공연 · 상품 · CS · 작업 기록), 로컬 실행 환경, CI(테스트)
- 제외
  - 관리자 화면(프론트) — API 와 화면이 알아야 할 것만 정리했습니다 (11장)
  - 다른 서비스의 수정 — 필요한 것을 목록으로만 남겼습니다 (9장)
  - GPU 서버 구현 — 요청 · 응답 모양까지만 정했습니다 (5.3)
  - KT 배포 (매니페스트 · MySQL · WireGuard · 시크릿) — 12장
  - 수집기(크롤링 · LLM 전처리)

## 2. 테이블 정의

id 는 모두 `BIGINT AUTO_INCREMENT`, 시각은 UTC `DATETIME(3)`, enum 은 `VARCHAR` + CHECK 입니다. 스키마는 Flyway `V1__studio_schema.sql` 한 파일이고 `ddl-auto: validate` 라 엔티티와 어긋나면 기동이 실패합니다.

| 테이블 | 컬럼 |
|---|---|
| `admin_account` | `id` · `login_id`(UNIQUE) · `name` · `password_hash`(bcrypt) · `role` · `status` · `totp_secret_enc` · `totp_last_step` · `failed_count` · `locked_until` · `last_login_at` · `created_at` · `updated_at` |
| `admin_login_challenge` | `id` · `token_hash`(UNIQUE) · `admin_id`(FK) · `enroll_secret_enc` · `client_ip` · `attempts` · `expires_at` · `used_at` · `created_at` |
| `admin_session` | `id` · `token_hash`(UNIQUE) · `admin_id`(FK) · `csrf_token` · `client_ip` · `created_at` · `expires_at` · `last_seen_at` · `revoked_at` |
| `stage_job` | `id` · `venue_id` · `venue_name` · `status` · `drawing_json` · `result_json` · `sent_json` · `error_message` · `requested_by`(FK) · `sent_by`(FK) · `created_at` · `analyzed_at` · `sent_at` |
| `stage_job_image` | `id` · `job_id`(FK · CASCADE) · `kind` · `content_type` · `data`(LONGBLOB) — UNIQUE(`job_id`, `kind`) |
| `audit_log` | `id` · `occurred_at` · `admin_id`(FK) · `role` · `method` · `path` · `status` · `result` · `client_ip` · `duration_ms` |

- **`admin_account` 의 행은 지우지 않습니다.** 작업 기록 · 무대 작업 · AWS 쪽 기록이 이 id 를 가리킵니다. 퇴사 · 업무 종료는 `DISABLED` 입니다. 삭제 API 가 없습니다.
- **DB 에는 원문이 없습니다.** 비밀번호는 bcrypt(강도 12), OTP 비밀키는 AES-256-GCM 암호문, 세션 · 2단계 대기 쿠키는 SHA-256 해시만 둡니다.
- `totp_secret_enc` 가 NULL 이면 OTP 미등록입니다. 다음 로그인 때 등록합니다.
- `stage_job.venue_id` 는 Concert 의 공연장 id 입니다 (다른 DB 라 FK 없음).
- 사진은 목록 조회에 딸려 오지 않게 `stage_job_image` 로 나눴습니다. `kind` 는 `ORIGINAL`(올린 사진) · `RESULT`(GPU 가 돌려준 사진).
- `audit_log.path` 는 쿼리스트링을 뺀 경로입니다. `role` 은 그 시점의 역할입니다.

| enum | 값 |
|---|---|
| `admin_account.role` | `MASTER` · `STAGE` · `CONCERT` · `GOODS` · `CS` |
| `admin_account.status` | `ACTIVE` · `DISABLED` |
| `stage_job.status` | `PENDING` · `ANALYZING` · `REVIEW` · `SENT` · `FAILED` |
| `stage_job_image.kind` | `ORIGINAL` · `RESULT` |
| `audit_log.result` | `OK` · `FAILED` · `DENIED` |

값 목록은 DB CHECK 와 Java enum 양쪽에 있고 `StudioSchemaTest` 가 둘을 대조합니다.

## 3. 역할 · 권한 · 탭

### 3.1 역할

| 역할 | 하는 일 | 권한 | 탭 (`/me.menus` 의 key) |
|---|---|---|---|
| `MASTER` | 모든 기능 접근 · 조회 · 수정 + 관리자 계정 · 작업 기록 | 전부 | `venues` · `concerts` · `products` · `cs` · `accounts` · `audit` |
| `STAGE` | 공연장(무대장) 추가 · 무대 그리기 | `VENUE` · `STAGE_DRAW` | `venues` |
| `CONCERT` | 공연 상세 · 약관 · 이미지 · 요구사항 · 좌석별 가격 | `CONCERT` · `SEAT_PRICE` | `concerts` |
| `GOODS` | 굿즈 · 피지컬 앨범 | `PRODUCT` · `STOCK` | `products` |
| `CS` | 회원의 티켓팅 · 굿즈 · 피지컬 앨범 구매 문의 | `CS_READ` · `CS_CANCEL` | `cs` |

모든 역할은 `SELF`(내 정보 · 비밀번호 변경 · 로그아웃)를 가집니다. 역할 → 권한 표는 코드(`Role`)에 있고 DB 에는 역할 이름만 있습니다.

### 3.2 서로 모르게 하는 방법

1. **탭:** `GET /api/studio/me` 가 내 역할의 탭만 내려줍니다. 화면은 이 목록에 없는 탭을 그리지 않습니다.
2. **API:** 모든 핸들러에 `@RequirePermission` 이 붙어 있고, 내 역할에 그 권한이 없으면 **없는 경로와 같은 404 `NOT_FOUND`** 가 나갑니다. 무대 관리자는 굿즈 API 가 있는지조차 알 수 없습니다. 본문 검증 · CSRF 검사보다 먼저 막고, 대상 서비스에는 아무것도 가지 않습니다.
3. **선언 누락:** 권한을 선언하지 않은 핸들러도 404 입니다 (기본이 거부). `RbacTest` 가 `/api/studio/**` 핸들러 전부에 선언이 있는지 확인합니다.
4. **선택 목록:** 공연 관리자가 고르는 공연장 · 아티스트 목록은 공연 탭 아래의 별도 경로(`/api/studio/concerts/options/**`)로 냅니다. 무대 탭 · 상품 탭의 API 를 빌려 쓰지 않습니다.
5. **즉시 반영:** 세션은 요청마다 DB 에서 계정 상태를 다시 봅니다. 역할 변경 · 비활성화 · 비밀번호 초기화 · OTP 초기화는 그 계정의 세션을 전부 끊습니다.
6. **기록:** 권한 밖 시도는 `audit_log` 에 `DENIED` 로 남고 마스터만 볼 수 있습니다.

## 4. 로그인 · 세션

```
① POST /api/studio/auth/login   { loginId, password }
     → next: "OTP"         OTP 코드만 입력
     → next: "OTP_ENROLL"  otpauthUri(QR) · secret 을 OTP 앱에 등록한 뒤 코드 입력 (첫 로그인 · OTP 초기화 뒤)
     쿠키 STUDIO_2FA (5분 · 1회용) — 아직 로그인된 것이 아닙니다
② POST /api/studio/auth/otp     { code: "123456" }
     → 쿠키 STUDIO_SESSION + 내 정보(/me 와 같은 모양)
③ GET  /api/studio/me           → role · menus · permissions · csrfToken
```

| 항목 | 값 |
|---|---|
| OTP | RFC 6238 TOTP (SHA-1 · 6자리 · 30초). 앞뒤 1단계(±30초) 허용. 같은 코드는 한 번만 통과 |
| 잠금 | 비밀번호 · OTP 연속 5회 실패 → 15분 잠금. 2단계 대기 하나에서 OTP 5회 실패 → 처음부터 다시 |
| 세션 | 로그인 후 최대 8시간, 30분 동안 요청이 없으면 만료. 불투명 토큰(JWT 아님) |
| 쿠키 | `STUDIO_SESSION`(path `/api/studio`) · `STUDIO_2FA`(path `/api/studio/auth`). HttpOnly · Secure · SameSite=Strict (환경변수로 변경) |
| CSRF | 상태를 바꾸는 요청(POST · PUT · PATCH · DELETE)은 `X-CSRF-Token` = `/me.csrfToken` 이어야 함 → 아니면 403 `CSRF_INVALID` |
| 비밀번호 | 12자 이상 · 72바이트 이하 · 아이디 포함 불가 |

- 아이디가 없든 · 비밀번호가 틀리든 · 잠겼든 · 비활성이든 응답은 모두 401 `LOGIN_FAILED` 로 같습니다. 없는 아이디도 bcrypt 를 한 번 돌려 응답 시간으로 가릴 수 없게 했습니다.
- **첫 마스터 계정:** 계정이 하나도 없을 때만, 환경변수 `STUDIO_MASTER_LOGIN_ID` 와 시크릿 `bootstrap.master-password` 로 만듭니다. 이후 계정은 마스터가 화면에서 만듭니다 (가입 API 없음).
- 마스터는 다른 계정의 이름 · 역할 · 상태 변경, 비밀번호 초기화, OTP 초기화(기기 분실)를 할 수 있습니다. 자기 계정의 역할 · 상태는 못 바꿉니다 (`CANNOT_CHANGE_SELF`) — 마스터가 0명이 되는 것을 막습니다.
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
| `POST /api/studio/auth/login` | 1차 — `{ loginId, password }` → `{ next, otpauthUri?, secret? }` |
| `POST /api/studio/auth/otp` | 2차 — `{ code }` → 내 정보 |
| `POST /api/studio/auth/logout` | 세션 종료 |
| `GET /api/studio/me` | `{ id, loginId, name, role, menus[{key, label}], permissions[], csrfToken }` |
| `PUT /api/studio/me/password` | `{ currentPassword, newPassword }` |

### 6.2 마스터 전용

| 메서드 · 경로 | 내용 |
|---|---|
| `GET /api/studio/accounts` | 계정 목록 (`otpEnrolled` · `lockedUntil` · `lastLoginAt` 포함) |
| `POST /api/studio/accounts` | `{ loginId, name, role, password }` — `loginId` 는 영문 소문자 · 숫자 · 점 4~32자 |
| `PATCH /api/studio/accounts/{id}` | `{ name?, role?, status? }` — 보낸 값만 변경 |
| `POST /api/studio/accounts/{id}/password-reset` | `{ password }` |
| `POST /api/studio/accounts/{id}/otp-reset` | 다음 로그인 때 OTP 재등록 |
| `GET /api/studio/audit-logs` | `?adminId=&beforeId=&size=` — 최신순 |

### 6.3 무대 (`STAGE`)

| 메서드 · 경로 | 권한 | 보내는 곳 |
|---|---|---|
| `GET /api/studio/venues` · `/{venueId}` | `VENUE` | Concert `GET /api/concerts/venues` · `/{id}` |
| `POST /api/studio/venues` | `VENUE` | Concert `POST /internal/admin/concerts/venues` |
| `GET /api/studio/venues/{venueId}/layout` | `VENUE` | Concert `GET /internal/admin/concerts/venues/{id}/layout` 🔴 없음 |
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
| `GET /api/studio/concerts/options/venues/{venueId}/layout` | `SEAT_PRICE` | Concert `GET /internal/admin/concerts/venues/{id}/layout` 🔴 없음 |
| `GET /api/studio/concerts/options/artists` | `CONCERT` | Catalog `GET /api/catalog/artists` |

🔴 **약관 · 이미지 · 상세 · 요구사항은 Concert 에 아직 필드가 없습니다.** Studio 는 본문을 그대로 넘기므로, Concert 가 필드를 추가하면 Studio 는 고칠 것이 없습니다 (9장).

### 6.5 굿즈 · 피지컬 앨범 (`GOODS`)

| 메서드 · 경로 | 권한 | 보내는 곳 |
|---|---|---|
| `GET /api/studio/products/goods` | `PRODUCT` | Catalog `GET /api/catalog/goods` |
| `GET /api/studio/products/physical-albums` | `PRODUCT` | Catalog `GET /api/catalog/physical-albums` |
| `GET /api/studio/products/{id}` | `PRODUCT` | Catalog `GET /api/catalog/products/{id}` |
| `POST /api/studio/products` | `PRODUCT` | Catalog `POST /internal/admin/catalog/products` 🔴 없음 |
| `PATCH /api/studio/products/{id}` | `PRODUCT` | Catalog `PATCH /internal/admin/catalog/products/{id}` 🔴 없음 |
| `PUT /api/studio/products/{id}/options` | `PRODUCT` | Catalog `PUT /internal/admin/catalog/products/{id}/options` 🔴 없음 |
| `POST /api/studio/products/options/{optionId}/stock` | `STOCK` | Catalog `POST /internal/admin/catalog/options/{optionId}/stock` 🔴 없음 |
| `GET /api/studio/products/options/artists` · `/{artistId}` | `PRODUCT` | Catalog `GET /api/catalog/artists` · `/{id}` |

### 6.6 CS (`CS`)

| 메서드 · 경로 | 권한 | 보내는 곳 |
|---|---|---|
| `GET /api/studio/cs/members` | `CS_READ` | Member `GET /internal/admin/members` 🔴 없음 |
| `GET /api/studio/cs/members/{userId}` | `CS_READ` | Member `GET /internal/admin/members/{id}` 🔴 없음 |
| `GET /api/studio/cs/members/{userId}/bookings` | `CS_READ` | Concert `GET /internal/admin/concerts/users/{userId}/bookings` 🔴 없음 |
| `GET /api/studio/cs/members/{userId}/orders` | `CS_READ` | Catalog `GET /internal/admin/catalog/users/{userId}/orders` 🔴 없음 |
| `GET /api/studio/cs/members/{userId}/payments` | `CS_READ` | Payment `GET /internal/admin/payments/users/{userId}` 🔴 없음 |
| `POST /api/studio/cs/bookings/{bookingId}/cancel` | `CS_CANCEL` | Concert `POST /internal/admin/concerts/bookings/{id}/cancel` 🔴 없음 |
| `POST /api/studio/cs/orders/{orderId}/cancel` | `CS_CANCEL` | Catalog `POST /internal/admin/catalog/orders/{id}/cancel` 🔴 없음 |

### 6.7 다른 서비스를 부를 때

- 헤더: `X-Internal-Token`(HS256 · `{iss: studio, aud: <대상>, exp: +300초}`) · `X-Admin-Id`(누가 시켰는지 — 대상 서비스의 기록용) · `X-Correlation-Id`
- 쿼리스트링과 본문은 그대로 넘기고, 응답의 `result` 만 꺼내 Studio 의 응답에 담습니다.
- 대상의 4xx 는 `code` · `message` 를 그대로 내보냅니다 (예: 409 `SECTION_IN_USE`).
- 대상의 401 · 403 · 5xx 는 502 `UPSTREAM_ERROR` 입니다. 🔴 401 을 그대로 내보내면 화면이 관리자를 로그아웃시키기 때문입니다.
- 연결 실패 · 시간 초과(연결 2초 · 응답 10초)는 503 `UPSTREAM_UNAVAILABLE` 입니다.
- 공개 조회(`/api/concerts/**` · `/api/catalog/**`)도 Gateway 가 아니라 서비스를 직접 부릅니다.

## 7. 오류 코드

| HTTP | code | 언제 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 본문 · 파라미터 검증 실패 |
| 400 | `PASSWORD_POLICY` · `PASSWORD_MISMATCH` | 비밀번호 규칙 위반 · 현재 비밀번호 틀림 |
| 400 | `STAGE_IMAGE_INVALID` · `STAGE_DRAWING_INVALID` · `STAGE_VALUES_INVALID` | 사진 형식 · 크기 / 그린 값 JSON / 보낼 구역 없음 |
| 401 | `UNAUTHENTICATED` | 세션 없음 · 만료 |
| 401 | `LOGIN_FAILED` · `OTP_INVALID` · `OTP_CHALLENGE_EXPIRED` | 1차 실패 / OTP 틀림 / 2단계 대기 만료 (아이디 · 비밀번호부터 다시) |
| 403 | `CSRF_INVALID` | `X-CSRF-Token` 없음 · 틀림 |
| 404 | `NOT_FOUND` | 없는 경로 **또는 권한 밖** (구분 불가) |
| 404 | `ACCOUNT_NOT_FOUND` · `STAGE_JOB_NOT_FOUND` · `STAGE_IMAGE_NOT_FOUND` | |
| 405 | `METHOD_NOT_ALLOWED` | |
| 409 | `LOGIN_ID_TAKEN` · `CANNOT_CHANGE_SELF` · `STAGE_JOB_NOT_REVIEWABLE` · `CONFLICT` | |
| 413 | `PAYLOAD_TOO_LARGE` | 업로드 10MB 초과 |
| 500 | `INTERNAL_ERROR` | |
| 502 | `UPSTREAM_ERROR` | 대상 서비스의 401 · 403 · 5xx |
| 503 | `UPSTREAM_UNAVAILABLE` | 대상 서비스에 닿지 못함 |

## 8. 설정 · 시크릿 · 로컬 실행

### 8.1 코드 구조

```
com.indie.studio
├─ global    config · common(응답 · 예외 · 필터) · auth(역할 · 세션 필터 · 권한 인터셉터) · audit · client(내부 토큰 · 서비스 호출)
└─ domain    account · auth · stage · concert · product · cs
             (concert · product · cs 는 컨트롤러만 — 대상 서비스로 넘기는 얇은 층)
```

Spring Boot 3.3.5 · Java 21 · Gradle 8.10.2 · Flyway · MySQL 8.4. Spring Security 는 쓰지 않고 `spring-security-crypto`(bcrypt)만 씁니다 — 인증은 `SessionFilter`, 권한은 `PermissionInterceptor` 두 곳에 있습니다.

### 8.2 환경변수

| 이름 | 내용 | 기본값 |
|---|---|---|
| `DB_HOST` | MySQL 호스트 | (필수) |
| `CONCERT_BASE_URL` · `CATALOG_BASE_URL` · `MEMBER_BASE_URL` · `PAYMENT_BASE_URL` | AWS 서비스 주소 | `http://concert:8083` 등 |
| `GPU_BASE_URL` | GPU 서버 주소 (WireGuard 피어). 비면 무대 검증이 `FAILED` | 없음 |
| `STUDIO_ALLOWED_ORIGINS` | 관리자 화면 출처 (쉼표 구분). 같은 출처면 비움 | 없음 |
| `STUDIO_COOKIE_SAMESITE` | 관리자 화면이 다른 사이트에 있으면 `None` | `Strict` |
| `STUDIO_MASTER_LOGIN_ID` · `STUDIO_MASTER_NAME` | 첫 마스터 계정 | 없음 · `마스터` |

### 8.3 시크릿 (`/etc/secrets` 의 파일 · 파일 이름이 키)

| 파일 | 내용 |
|---|---|
| `db.password` | `studio_app` 계정 비밀번호 |
| `internal.shared-secret` | 내부 토큰 서명 키 — 🔴 AWS 서비스들과 같은 값이어야 합니다 |
| `totp.encryption-key` | OTP 비밀키 암호화 키 (base64 · 32바이트). 🔴 바꾸면 모든 계정의 OTP 를 다시 등록해야 합니다 |
| `gpu.token` | GPU 서버와 맞춘 토큰 |
| `bootstrap.master-password` | 첫 마스터 비밀번호. 계정이 생긴 뒤에는 쓰이지 않습니다 |

Studio 는 KT 전용 Secret 만 마운트합니다. AWS 서비스들의 `app-secrets` 를 통째로 넘기지 않습니다.

### 8.4 로컬 실행

```bash
./local/setup-local.sh                       # local-secrets/ 생성 (커밋되지 않음) · 첫 마스터 비밀번호 출력
docker compose -f local/compose.yml up -d    # MySQL 8.4 → 127.0.0.1:3307
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

로컬 프로파일은 쿠키 `Secure` 를 끄고 화면 출처를 `http://localhost:5173` 으로 둡니다. 다른 서비스가 떠 있지 않으면 그쪽으로 가는 API 는 503 입니다.

## 9. 다른 서비스 · 저장소가 고칠 것

**이번에 고친 것은 없습니다.** 아래가 되어야 6장의 🔴 표시가 동작합니다. Studio 는 본문 · 응답을 그대로 넘기므로 모양은 각 서비스가 정하면 됩니다.

| 대상 | 수정 | 내용 |
|---|---|---|
| **concert** | 필드 추가 | 공연에 상세(`description`) · 약관(`terms`) · 요구사항(`requirements`) · 이미지(`posterUrl` · `imageUrls`) — 테이블 · 생성/수정 DTO · 공개 상세 응답. 지금은 받는 필드가 없어 저장되지 않습니다 |
| | 신규 API | `GET /internal/admin/concerts/venues/{venueId}/layout` — 저장된 구역 · 좌석 (무대 탭의 현재 배치 · 공연 탭의 좌석 가격 화면) |
| | 신규 API | `GET /internal/admin/concerts/users/{userId}/bookings` · `POST /internal/admin/concerts/bookings/{id}/cancel` (CS) |
| | 확인 | 공개 조회(`/api/concerts/**`)를 Studio 가 Gateway 없이 직접 부릅니다. `X-Admin-Id` 헤더를 기록에 쓸지 |
| | 없음 | 공연장 · 공연 · 라인업 · 구역 저장 · 좌석 가격 내부 API 는 이미 있고 `allowed-issuers` 에 `studio` 도 있습니다 |
| **catalog** | 설정 | `app.internal.allowed-issuers` 에 `studio` 추가 |
| | 신규 API | `POST` · `PATCH /internal/admin/catalog/products(/{id})` · `PUT …/products/{id}/options` · `POST …/options/{optionId}/stock` |
| | 신규 API | `GET /internal/admin/catalog/users/{userId}/orders` · `POST /internal/admin/catalog/orders/{id}/cancel` (CS) |
| **member** | 설정 | `allowed-issuers` 에 `studio` 추가 |
| | 신규 API | `GET /internal/admin/members?q=` · `GET /internal/admin/members/{id}` (CS — 개인정보를 어디까지 내줄지 member 가 정합니다) |
| **payment** | 설정 | `allowed-issuers` 에 `studio` 추가 |
| | 신규 API | `GET /internal/admin/payments/users/{userId}` |
| **manifests** | 신규 | `services/studio` (라벨 `app: studio` — concert 의 NetworkPolicy 가 이미 이 라벨을 허용), KT MySQL + DB `studio` · 계정 `studio_app`, KT 전용 Secret (8.3), catalog · member · payment NetworkPolicy 에 `app: studio` 허용, 관리자 화면 Ingress |
| **인프라** | 신규 | WireGuard: KT ↔ GPU 서버 피어, KT ↔ AWS 서비스 경로. 이미지 레지스트리 |
| **GPU 서버** | 신규 | `POST /v1/stage/verify` (5.3) |
| **Gateway** | 없음 | Studio 는 Gateway 를 거치지 않습니다 |

수정하지 않았지만 낡은 것 (손대지 않고 알려만 둡니다):

- `docs/22_수집검증_관리자권한_감사로그.md` · `docs/24_관리자_서비스.md` · `docs/34_관리자_서비스_ERD.html` — 이전 관리자 설계입니다 (역할 · 테이블 · 로그인 방식이 다름).
- Concert 의 체크인 내부 API (`/internal/admin/concerts/check-in`) — 이번 역할 5개에는 검표 담당이 없어 Studio 가 부르지 않습니다.
- `manifests` 의 `scripts/apply-kt-secrets.sh` — Studio 항목이 TODO 로 남아 있습니다.

## 10. 테스트

`./gradlew test` — 19건 통과 · 건너뜀 0 (2026-11-13). 실제 MySQL 8.4 를 Testcontainers 로 띄우므로 Docker 가 필요합니다. AWS 서비스와 GPU 서버는 테스트 안의 가짜 HTTP 서버입니다.

| 테스트 | 건수 | 확인하는 것 |
|---|---|---|
| `AuthFlowTest` | 4 | 비밀번호 → OTP 등록 → 대시보드 · 같은 코드 재사용 거부 · 5회 실패 잠금 · 30분 무응답 / 8시간 만료 · 내 비밀번호 변경 |
| `RbacTest` | 7 | 역할별 탭과 닿는 API · 권한 밖은 없는 경로와 같은 404 + `DENIED` 기록 · 내부 토큰 `iss` / `aud` · 대상 오류 전달(401 로는 안 나감) · CSRF(막힌 요청도 기록) · 계정 관리와 즉시 반영 · 모든 핸들러의 권한 선언 |
| `StageJobFlowTest` | 3 | GPU 검증 → 확인 → Concert 수신(확인 전에는 0건) · 값 수정 후 전송과 Concert 거절 뒤 재시도 · 잘못된 업로드 거절과 GPU 실패 표시 |
| `StudioSchemaTest` | 2 | CHECK 값 = Java enum · DB 에 해시와 암호문만 있음 |
| `TotpTest` | 3 | RFC 6238 테스트 벡터 · 시계 오차 1단계 · base32 |

로컬에서 직접 확인한 것: 앱 기동(Flyway 적용 · readiness UP) → 첫 마스터 생성 → 로그인 → OTP 등록 → `/me` 에 탭 6개 → 세션 없이 401 · Concert 가 없을 때 503.

검증하지 못한 것: 실제 GPU 서버, 실제 Concert · Catalog · Member · Payment 호출(🔴 표시 API 는 대상에 아직 없음), WireGuard 구간, KT 배포, 관리자 화면과의 연동(쿠키 · CORS).

## 11. 관리자 화면이 알아야 할 것

- 모든 요청에 쿠키를 실어 보냅니다 (`credentials: 'include'`).
- 로그인은 두 화면입니다. `next` 가 `OTP_ENROLL` 이면 `otpauthUri` 로 QR 을 그리고(직접 입력용 `secret` 도 표시) 코드를 받습니다.
- 로그인 뒤 `GET /api/studio/me` 를 부르고, **`menus` 에 있는 탭만** 그립니다. 탭 이름을 화면 코드에 고정해 두고 역할로 숨기는 방식은 쓰지 않습니다 (다른 역할의 탭 이름이 번들에 남습니다 — 12장 ⑧).
- POST · PUT · PATCH · DELETE 에는 `X-CSRF-Token: <me.csrfToken>` 을 붙입니다.
- 401 `UNAUTHENTICATED` → 로그인 화면으로. 404 는 "없는 페이지" 로만 보여 줍니다 (권한 안내 문구 금지).
- 무대 그리기: 검증 요청 뒤 작업을 2~3초마다 조회하고 `REVIEW` 가 되면 `…/image` 와 `values` 를 보여 줍니다. `FAILED` 면 `errorMessage` 를 보여 주고 다시 요청하게 합니다.
- 502 · 503 은 Studio 가 아니라 연결된 서비스 문제입니다. 로그아웃시키지 않습니다.

## 12. 배포 전에 확인할 것 · 아직 정하지 않은 것

**배포 전 확인**

1. **9장의 설정 3건**(catalog · member · payment 의 `allowed-issuers` 에 `studio`)이 없으면 그쪽 호출은 전부 502 입니다.
2. **`internal.shared-secret` 은 AWS 와 같은 값**, 나머지 시크릿 4개는 KT 에서 새로 만듭니다. `totp.encryption-key` 는 잃어버리면 복구할 수 없으니 따로 보관합니다.
3. **KT 에서 AWS 서비스에 닿는 주소**를 정해 `*_BASE_URL` 에 넣어야 합니다. 기본값(`http://concert:8083`)은 같은 클러스터 DNS 를 가정한 값입니다.
4. **관리자 화면의 주소**에 따라 `STUDIO_ALLOWED_ORIGINS` · `STUDIO_COOKIE_SAMESITE` 를 정합니다. 화면이 다른 사이트(예: Vercel)에 있으면 `None` 이어야 쿠키가 갑니다.
5. **Studio 는 1대만 띄웁니다.** 무대 검증이 프로세스 안의 스레드에서 돌아서, 여러 대면 기동할 때 서로의 작업을 `FAILED` 로 닫습니다.
6. CI 는 테스트만 합니다. 이미지 push · 매니페스트 태그 커밋(deploy 잡)은 KT 레지스트리와 `services/studio` 가 정해지면 추가합니다 (`.github/workflows/ci.yml` 의 TODO).
7. 첫 마스터로 로그인해 OTP 를 등록한 뒤 `bootstrap.master-password` 시크릿은 지워도 됩니다.
8. Flyway 가 MySQL 8.4 를 "테스트한 버전(8.1)보다 새 버전" 이라고 경고합니다. 마이그레이션은 정상 적용됩니다.

**정하지 않은 것 (제가 기본값으로 둔 것)**

- ① **계정마다 역할 하나.** 한 사람이 두 업무를 하면 계정을 두 개 만들거나 마스터를 써야 합니다.
- ② **마스터는 여러 명 만들 수 있습니다.** 마스터끼리는 서로의 역할 · 상태를 바꿀 수 있습니다.
- ③ **공연 이미지는 URL 로 받습니다.** Studio 에 파일 저장소가 없어서 업로드 API 를 만들지 않았습니다. 포스터를 어디에 올릴지 정해야 합니다.
- ④ **`drawing` 의 모양**(demo_ui 의 무대 그리기 값)은 화면과 GPU 서버가 정합니다. Studio 는 그대로 넘깁니다.
- ⑤ **CS 취소**는 Concert · Catalog 의 취소 · 환불 경로가 있어야 합니다. 관리자 취소의 환불 규칙(수수료 · 기한 무시 여부)은 그쪽에서 정합니다.
- ⑥ **보관 기간.** `audit_log` · `stage_job` · 사진을 지우는 배치가 없습니다. 개인정보 접근 기록을 얼마나 보관할지와 함께 정해야 합니다.
- ⑦ **작업 기록 쓰기는 실패해도 요청을 막지 않습니다** (실패는 서버 로그). 기록이 없으면 요청도 실패시킬지.
- ⑧ **관리자 화면을 역할별로 번들을 나눌지.** API 는 서로를 모르지만, 화면이 하나의 번들이면 다른 탭의 코드가 내려갑니다.
- ⑨ **이전 문서**(`docs/22` · `24` · `34`)를 지울지 · "폐기" 표시를 할지.
- ⑩ 검표(체크인) 담당 역할은 이번 5개에 없습니다. 필요하면 역할을 추가합니다.

## 13. 작업 기록

| 날짜 | 내용 |
|---|---|
| 2026-11-13 | 관리자 서비스 개념 · ERD 를 HTML 로 정리 (`docs/34`) → 같은 날 폐기하고 기능 설명 기준으로 다시 시작 |
| 2026-11-13 | `backend/studio-service` 구현: 계정 · 로그인(비밀번호 → OTP) · 역할 5개와 격리 · 무대 그리기(GPU 검증 → 확인 → Concert) · 공연 · 상품 · CS · 작업 기록. 테스트 19건 |
| 2026-11-13 | 로컬 기동 확인 (마스터 로그인 → OTP 등록 → 대시보드) |
| 2026-11-13 | 저장소 연결: `main` 첫 커밋 · `develop` 생성 · 기본 브랜치 `develop` |

문서를 쓰다가 찾아 고친 것: CSRF 로 막힌 요청이 작업 기록에 남지 않던 문제 (인터셉터가 요청을 막으면 완료 콜백이 불리지 않음 → 막는 자리에서 직접 기록 · 테스트 추가).

## 부록 A. 관계

```
admin_account 1 ─── N admin_login_challenge
      │ 1 ───────── N admin_session
      │ 1 ───────── N audit_log
      │ 1 ───────── N stage_job (requested_by · sent_by) 1 ─── N stage_job_image (ORIGINAL · RESULT 각 1)

stage_job.venue_id ┄┄▶ Concert 의 venue (다른 DB · FK 없음)
```
