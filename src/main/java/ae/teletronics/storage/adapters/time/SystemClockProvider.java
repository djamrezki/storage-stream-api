package ae.teletronics.storage.adapters.time;

import ae.teletronics.storage.ports.ClockProvider;

import java.time.Instant;

/** Production clock based on system time. */
public class SystemClockProvider implements ClockProvider {
    @Override public Instant now() { return Instant.now(); }
}
