package work.archaic.knit.signing;

import java.io.IOException;
import java.util.List;

/** Resolves the complete _knit TXT RRset for a publisher through a trusted validator. */
@FunctionalInterface
public interface AuthorizationResolver {
    /** Resolve all TXT records for {@code _knit.<publisher>} through a trusted validator.
     * Do not infer authentication merely from a remote DNS AD bit or a successful reply.
     * @param publisher canonical publisher domain as validated by {@link Metadata}
     * @return complete TXT RRset and evidence of DNSSEC validation
     * @throws IOException if authorization cannot be obtained
     * @throws InterruptedException if lookup is interrupted; callers preserve cancellation
     */
    Authorization resolve(String publisher) throws IOException, InterruptedException;

    /** Validator evidence and complete TXT record text, with TXT character-strings concatenated.
     * Unauthenticated answers cannot establish publisher trust.
     * @param authenticated true only when trusted DNSSEC validation succeeded
     * @param records complete RRset, copied into an immutable list; neither list nor entries may be null
     */
    record Authorization(boolean authenticated, List<String> records) {
        /** Snapshot the RRset without changing its record text.
         * @throws NullPointerException if the list or any record is null
         */
        public Authorization { records = List.copyOf(records); }
    }
}
