package ae.teletronics.storage.ports;

import java.io.IOException;
import java.util.Objects;

/**
 * Pluggable antivirus check. Current implementation can be NoOp.
 */
public interface VirusScanner {

    ScanReport scan(StreamSource source) throws IOException;

    enum Verdict { CLEAN, INFECTED, ERROR }

    final class ScanReport {
        private final Verdict verdict;
        private final String engine;   // e.g., "NoOp", "ClamAV"
        private final String details;  // optional extra info

        public static ScanReport clean(String engine) {
            return new ScanReport(Verdict.CLEAN, engine, null);
        }
        public static ScanReport infected(String engine, String details) {
            return new ScanReport(Verdict.INFECTED, engine, details);
        }
        public static ScanReport error(String engine, String details) {
            return new ScanReport(Verdict.ERROR, engine, details);
        }

        public ScanReport(Verdict verdict, String engine, String details) {
            this.verdict = Objects.requireNonNull(verdict, "verdict");
            this.engine = engine;
            this.details = details;
        }

        public Verdict getVerdict() { return verdict; }
        public String getEngine() { return engine; }
        public String getDetails() { return details; }
    }
}
