package ae.teletronics.storage.ports;

import java.time.Instant;

/**
 * Testable clock abstraction.
 */
public interface ClockProvider {
    Instant now();
}
