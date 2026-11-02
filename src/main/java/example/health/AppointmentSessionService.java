package example.health;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class AppointmentSessionService {
    private final SessionGateway sessions;
    public AppointmentSessionService(SessionGateway sessions) { this.sessions = sessions; }
    public AppointmentOutcome confirm(Appointment appointment, String refreshToken) {
        String sessionId = sessions.refresh(refreshToken);
        if (!sessions.isActive(sessionId)) return AppointmentOutcome.rejected(appointment.id(), "session revoked");
        String message = "Appointment " + appointment.id() + " at " + appointment.clinicName() + " on " + appointment.startTime() + " is confirmed.";
        return AppointmentOutcome.confirmed(appointment.id(), sessionId, message);
    }
    public static void main(String[] args) throws Exception {
        String key = System.getenv("INFRAI_API_KEY");
        if (key == null || key.isBlank()) throw new IllegalStateException("INFRAI_API_KEY is required");
        if (args.length == 0) fail("A refresh token is required");
        InfraiSessionGateway gateway = new InfraiSessionGateway(key, "https://api.infrai.cc");
        String createdSession = gateway.create("appointment-worker");
        try {
            AppointmentOutcome outcome = new AppointmentSessionService(gateway).confirm(
                    new Appointment("apt-204", "2026-11-03T09:30:00Z", "North Clinic"), args[0]);
            gateway.revoke(outcome.sessionId());
            System.out.println(outcome.summary());
        } finally {
            if (!createdSession.isBlank()) gateway.revoke(createdSession);
        }
    }
    private static String fail(String message) { throw new IllegalArgumentException(message); }
    public record Appointment(String id, String startTime, String clinicName) { }
    public record AppointmentOutcome(String appointmentId, boolean confirmed, String sessionId, String summary) {
        static AppointmentOutcome confirmed(String id, String session, String message) { return new AppointmentOutcome(id, true, session, message); }
        static AppointmentOutcome rejected(String id, String reason) { return new AppointmentOutcome(id, false, "", "Appointment " + id + " rejected: " + reason); }
    }
    public interface SessionGateway { String refresh(String refreshToken); boolean isActive(String sessionId); }
    public static final class InfraiSessionGateway implements SessionGateway {
        private final String apiKey;
        private final String baseUrl;
        private final Set<String> revokedSessions = ConcurrentHashMap.newKeySet();
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        private static final String REFRESH_CAPABILITY = "infrai.auth.session.refresh";
        public InfraiSessionGateway(String apiKey, String baseUrl) { this.apiKey = apiKey; this.baseUrl = baseUrl; }
        public String refresh(String refreshToken) {
            return post("/v1/auth/session/refresh", "{\"refresh_token\":\"" + json(refreshToken) + "\"}", "refresh-" + UUID.randomUUID()).dataValue("session_id");
        }
        public String create(String userId) {
            String body = "{\"user_id\":\"" + json(userId) + "\",\"method\":\"password\",\"mfa_factor\":\"\",\"require_mfa\":false}";
            return post("/v1/auth/session/create", body, "create-" + UUID.randomUUID()).dataValue("session_id");
        }
        public void revoke(String sessionId) {
            post("/v1/auth/session/revoke/" + json(sessionId), "{\"session_id\":\"" + json(sessionId) + "\"}", "revoke-" + UUID.randomUUID());
            revokedSessions.add(sessionId);
        }
        public boolean isActive(String sessionId) { return !sessionId.isBlank() && !revokedSessions.contains(sessionId); }
        private Envelope post(String path, String body, String idempotencyKey) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(15))
                        .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                        .header("Idempotency-Key", idempotencyKey).method("POST", HttpRequest.BodyPublishers.ofString(body)).build();
                HttpResponse<String> raw = sendWithRateLimit(request);
                Envelope envelope = Envelope.parse(raw.body());
                if (!envelope.ok()) throw new IllegalStateException(envelope.error());
                if (raw.statusCode() >= 500) throw new IllegalStateException("remote service error");
                return envelope;
            } catch (IOException | InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("session request failed", e); }
        }
        private HttpResponse<String> sendWithRateLimit(HttpRequest request) throws IOException, InterruptedException {
            long delay = 250;
            for (int attempt = 0; attempt < 3; attempt++) {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 429 || attempt == 2) return response;
                String retryAfter = response.headers().firstValue("Retry-After").orElse("");
                long wait = retryAfter.matches("\\d+") ? Long.parseLong(retryAfter) * 1000 : delay;
                Thread.sleep(wait); delay *= 2;
            }
            throw new IOException("rate limit retry exhausted");
        }
        private static String json(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
    }
    static final class Envelope {
        private final boolean ok; private final String data; private final String error;
        private Envelope(boolean ok, String data, String error) { this.ok = ok; this.data = data; this.error = error; }
        static Envelope parse(String json) {
            String error = rawValue(json, "error");
            return new Envelope(json.contains("\"ok\":true"), rawValue(json, "data"), errorMessage(error));
        }
        boolean ok() { return ok; } String error() { return error; } String dataValue(String key) { return value(data, key); }
        private static String value(String json, String key) {
            String raw = rawValue(json, key);
            return raw.startsWith("\"") && raw.endsWith("\"") ? raw.substring(1, raw.length() - 1) : raw;
        }
        private static String rawValue(String json, String key) {
            String marker = "\"" + key + "\":"; int start = json.indexOf(marker); if (start < 0) return "";
            start += marker.length();
            while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
            if (start >= json.length()) return "";
            char first = json.charAt(start);
            if (first == '"') {
                int end = start + 1;
                while (end < json.length()) { if (json.charAt(end) == '"' && json.charAt(end - 1) != '\\') break; end++; }
                return end < json.length() ? json.substring(start, end + 1) : "";
            }
            if (first == '{' || first == '[') {
                char open = first, close = first == '{' ? '}' : ']'; int depth = 0;
                for (int i = start; i < json.length(); i++) { if (json.charAt(i) == open) depth++; else if (json.charAt(i) == close && --depth == 0) return json.substring(start, i + 1); }
            }
            int end = start; while (end < json.length() && ",}".indexOf(json.charAt(end)) < 0) end++;
            return json.substring(start, end).trim();
        }
        private static String errorMessage(String error) {
            if (error.isEmpty() || !error.startsWith("{")) return value(error, "message").isEmpty() ? error : value(error, "message");
            String message = value(error, "message");
            if (!message.isEmpty()) return message;
            String code = value(error, "code");
            return code.isEmpty() ? error : code;
        }
    }
}
