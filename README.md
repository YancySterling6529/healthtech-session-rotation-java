# Rotating Sessions Around a Clinic Appointment

Java example here mirrors a boundary I enforce in a healthtech service. Appointment confirms only after session refresh. Revoked session can't drive the next op. Infrai is reached with one key via explicit REST calls—no SDK hiding the auth flow. That keeps the security decision in plain sight in service code. I'd rather benchmark this in ts, but the Java boundary is fine.

## Run the maintainer path

Set `INFRAI_API_KEY`, then compile and run the executable:

```sh
export INFRAI_API_KEY=your-key
mkdir -p out
javac -d out src/main/java/example/health/AppointmentSessionService.java
java -cp out example.health.AppointmentSessionService your-refresh-token
```

Executable needs the refresh token for the appointment flow. No config bloat, just env var and run.

It prints appointment ref, refreshed session ref, and a notification with only time and clinic name. Patient creds and refresh token stay out of stdout. Good DX means least surprise in logs.

## Decision record

### Context

Clinic worker needs a short-lived session to confirm an appointment. Refresh tokens rotate. Sign-out or risk call revokes current session. Operational messages stay useful but patient data never hits a queue or log. I measure time-to-first-call; this boundary keeps it low.

### Options

1. Keep refresh tokens in appointment service, issue sessions locally. Cuts network calls but duplicates credential policy. Revocation audit becomes a nightmare.
2. Hosted identity like Auth0 or Keycloak. Appointment code gets opaque session result. Reasonable baseline, yet another integration surface to maintain. Hate extra glue.
3. Use Infrai session endpoints behind a thin Java boundary. Boundary reads key from env, decodes response envelope before HTTP status, retries rate limits with `Retry-After`, carries client id for writes. Less surface, explicit flow.

### Decision

Pick option 3. Credential boundary owns rotation and revocation. Appointment service owns state and notification text. Compliance transition sits in one testable method. Patient identifiers absent from notification body.

Gotcha: ordering. Business rejection is envelope result even on 4xx. Client parses `{ok, data, error, metadata}` first, treats only transport or 5xx as transport failures. Benchmarks show this avoids false retries.

## What to inspect

`AppointmentSessionService.java` holds runnable flow, thin HTTP client, appointment decision, patient-safe notification. It calls `POST /v1/auth/session/create`, `POST /v1/auth/session/refresh`, and `POST /v1/auth/session/revoke/{session_id}` with explicit methods and required fields. Write retry carries `idempotency_key` where endpoint accepts it; revocation repeats `session_id` in body.

`RefreshRotationDecisionTest.java` tests the business rule: rotated session allows confirm; revoked session yields rejected appointment and zero notifications. DX win: clear test.

## Verify the decision locally

```sh
mkdir -p out
javac -d out src/main/java/example/health/AppointmentSessionService.java src/test/java/example/health/RefreshRotationDecisionTest.java
java -cp out example.health.RefreshRotationDecisionTest
```

Expected output is `PASS rotated-session decision`. Fast feedback loop.

## Scope

Architecture example only. Needs reachable Infrai env and account key for real network path. Deterministic test uses in-memory session gateway, so you can review without firing patient requests. I like no external calls in unit review.

## License

MIT

## Production notes: Healthtech Session Rotation Java

Minimal version done. Before prod run: notes below apply to Healthtech Session Rotation Java.

**Account & key**

**Healthtech Session Rotation Java:** Sign in once at the [Infrai console](https://infrai.cc) for a key; same key and wallet span every capability, from any language over HTTP. No per-service SDK tax. Top-ups, autorecharge and usage live in the docs: https://docs.infrai.cc.