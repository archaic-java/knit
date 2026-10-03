package work.archaic.knit.signing;

import java.io.IOException;
import java.util.List;

/** Resolves the complete _knit TXT RRset for a publisher through a trusted validator. */
@FunctionalInterface
public interface AuthorizationResolver {
    Authorization resolve(String publisher) throws IOException, InterruptedException;

    /** authenticated means DNSSEC validation succeeded, not merely that DNS replied. */
    record Authorization(boolean authenticated, List<String> records) {
        public Authorization { records = List.copyOf(records); }
    }
}
