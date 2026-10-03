package work.archaic.knit.signing;

/** Only VERIFIED establishes publisher trust; unavailable authorization is distinguished from rejection. */
public record Verification(Status status, Metadata metadata, String keyState, String detail) {
    public enum Status { UNSIGNED, VERIFIED, REJECTED, UNAVAILABLE }
}
