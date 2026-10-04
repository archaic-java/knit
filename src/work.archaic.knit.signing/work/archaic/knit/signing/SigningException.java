package work.archaic.knit.signing;

import java.io.IOException;

/** An artifact or signing declaration violates the Knit v1 format. */
public final class SigningException extends IOException {
    private static final long serialVersionUID = 1L;
    /** Describe an invalid signing artifact, identity or key.
     * @param message explanation of the violated signing rule
     */
    public SigningException(String message) { super(message); }
}
