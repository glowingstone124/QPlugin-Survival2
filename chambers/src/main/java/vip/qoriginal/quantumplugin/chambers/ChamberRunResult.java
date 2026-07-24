package vip.qoriginal.quantumplugin.chambers;

import vip.qoriginal.quantumplugin.registration.MinecraftRegistrationTest;

public record ChamberRunResult(
        MinecraftRegistrationTest.Session registrationSession,
        FinishReason reason,
        int completedChambers,
        int totalChambers
) {
    public boolean passed() {
        return reason == FinishReason.PASSED;
    }

    public enum FinishReason {
        PASSED,
        TIMED_OUT,
        CANCELLED,
        QUIT,
        PLUGIN_DISABLED
    }
}
