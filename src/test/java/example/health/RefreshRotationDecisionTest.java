package example.health;

public final class RefreshRotationDecisionTest {
    public static void main(String[] args) {
        AppointmentSessionService.SessionGateway gateway = new AppointmentSessionService.SessionGateway() {
            public String refresh(String token) { return token.equals("rotated") ? "session-2" : ""; }
            public boolean isActive(String sessionId) { return sessionId.equals("session-2"); }
        };
        AppointmentSessionService service = new AppointmentSessionService(gateway);
        AppointmentSessionService.Appointment appointment = new AppointmentSessionService.Appointment("apt-test", "2026-11-03T09:30:00Z", "North Clinic");
        AppointmentSessionService.AppointmentOutcome confirmed = service.confirm(appointment, "rotated");
        AppointmentSessionService.AppointmentOutcome rejected = service.confirm(appointment, "revoked");
        if (!confirmed.confirmed() || !confirmed.summary().contains("confirmed") || rejected.confirmed() || !rejected.sessionId().isEmpty()) throw new AssertionError("rotation and revocation decision failed");
        System.out.println("PASS rotated-session decision");
    }
}
