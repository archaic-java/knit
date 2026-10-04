package work.archaic.knit.signing;

/** Verification outcome; only {@link Status#VERIFIED} establishes current publisher trust.
 * This is a result value, not proof of historical authorization or artifact safety.
 * @param status trust outcome
 * @param metadata parsed identity when available, otherwise null
 * @param keyState authorized key state for a verified result, otherwise an empty string
 * @param detail human-readable outcome explanation
 */
public record Verification(Status status, Metadata metadata, String keyState, String detail) {
    /** Distinguishes missing signatures, established trust, rejection and unavailable evidence. */
    public enum Status {
        /** Both signing entries are absent. */ UNSIGNED,
        /** Signature is valid and its active or retired key is currently DNSSEC-authorized. */ VERIFIED,
        /** Artifact/signature or publisher authorization violates verification rules. */ REJECTED,
        /** Artifact or authorization data could not be obtained/read. */ UNAVAILABLE
    }
}
