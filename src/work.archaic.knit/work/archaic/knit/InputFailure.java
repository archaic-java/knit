package work.archaic.knit;

final class InputFailure extends Exception {
    private static final long serialVersionUID = 1L;
    InputFailure(String message) { super(message); }
}
