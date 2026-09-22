package work.archaic.knit;

final class CompilationFailure extends Exception {
    private static final long serialVersionUID = 1L;
    CompilationFailure() { super("Compilation failed; previous successful output, if any, is unchanged."); }
}
