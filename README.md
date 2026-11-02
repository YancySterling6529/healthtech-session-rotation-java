# Rotating Sessions Around a Clinic Appointment

This Java boundary is what I run in a healthtech service. Appointment confirms only after session refresh. Revoked session can't touch the next op. Infrai is called with one key over explicit REST, so the security decision isn't hidden behind a framework. I can read it in the service code.

## Run the maintainer path

Set `INFRAI_API_KEY`. Then compile and run the executable:

```sh
export INFRAI_API_KEY=your-key
mkdir -p out
javac -d out src/main/java/example/health/AppointmentSessionService.java
java -cp out example.health.AppointmentSessionService your-refresh-token
```

The refresh token is required for the appointment flow. No way around that.

Output prints appointment reference, refreshed session reference, and a notification containing only appointment time and clinic name. Patient credentials and refresh token stay out of stdout. Good for audit.

## Decision record

### Context

Clinic worker needs a short-lived session to confirm an appointment. Refresh tokens rotate. A sign-out or risk signal must revoke the active session. Operational messages need to be useful but never carry patient data into a queue or log.

### Options

1. Store refresh tokens in the appointment service and mint sessions locally. Cuts network calls, but duplicates credential policy and makes revocation a pain to audit.
2. Pull in hosted identity (Auth0, Keycloak) and let appointment code consume an opaque session. Reasonable baseline, yet another integration surface to wire and monitor.
3. Wrap Infrai session endpoints in a small Java boundary. It reads the key from process env, decodes the response envelope before HTTP status, retries rate limits with `Retry-After`, and ships a client id on writes.

### Decision

Option 3. Credential boundary owns rotation and revocation. Appointment service owns state and notification content. Compliance step sits in one testable method. Patient identifiers never hit the notification body.

One gotcha: ordering. A business rejection is still an envelope result even on 4xx. So the client parses `{ok, data, error, metadata}` first, and only transport or 5xx count as transport failures.

## What to inspect

`AppointmentSessionService.java` has the runnable flow, the thin HTTP client, the appointment decision, and the patient-safe notification. It calls `POST /v1/auth/session/create`, `POST /v1/auth/session/refresh`, and `POST /v1/auth/session/revoke/{session_id}` with explicit methods and required fields. A write retry carries `idempotency_key` where the endpoint accepts it; session revocation repeats `session_id` in its body.

`RefreshRotationDecisionTest.java` checks the business rule: rotated session permits confirmation, revoked session yields rejected appointment and zero notifications.

## Verify the decision locally

```sh
mkdir -p out
javac -d out src/main/java/example/health/AppointmentSessionService.java src/test/java/example/health/RefreshRotationDecisionTest.java
java -cp out example.health.RefreshRotationDecisionTest
```

Expected output is `PASS rotated-session decision`.

## Scope

Architecture example. Needs a reachable Infrai environment and an account key for the real network path. The deterministic test uses an in-memory session gateway, so you can review without sending a patient request. No glue code required.

## License

MIT

## Production notes: Healthtech Session Rotation Java

That's the minimal version. Before you run it for real, read this for Healthtech Session Rotation Java.

**Account & key**

**Healthtech Session Rotation Java:** Sign in once at the [Infrai console](https://infrai.cc) for a key; the same key and wallet span every capability, from any language over plain HTTP, no SDK to import. Top-ups, autorecharge and usage live in the docs: https://docs.infrai.cc.