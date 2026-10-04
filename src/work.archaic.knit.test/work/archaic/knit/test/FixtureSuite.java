package work.archaic.knit.test;

import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import work.archaic.service.test.v02.TestCase;
import work.archaic.service.test.v02.TestSuite;
import work.archaic.service.test.v02.TestTrail;

/** Regression checks for the lifetime of external test work. */
public record FixtureSuite() implements TestSuite {
    @Override public void cases(Collection<TestCase> cases) {
        cases.add(new InterruptedFixtureProcess());
    }
}

record InterruptedFixtureProcess() implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        var started = new CountDownLatch(1);
        var child = new AtomicReference<Process>();
        var failure = new AtomicReference<Throwable>();
        var worker = Thread.ofPlatform().start(() -> {
            try {
                Fixture.execute(new ProcessBuilder("sleep", "60"), process -> {
                    child.set(process);
                    started.countDown();
                });
            } catch (Throwable cause) { failure.set(cause); }
        });
        try {
            assert started.await(10, TimeUnit.SECONDS) : "The fixture must start its child within the deadline";
            worker.interrupt();
            worker.join(Duration.ofSeconds(10));
            trail.note("Worker failure: " + failure.get());
            assert !worker.isAlive() : "Interrupted fixture execution must finish bounded cleanup";
            assert failure.get() instanceof InterruptedException : "Fixture execution must preserve interruption";
            assert !child.get().isAlive() : "Interruption must terminate the child before the fixture returns";
        } finally {
            worker.interrupt();
            var process = child.get();
            if (process != null && process.isAlive()) process.destroyForcibly();
            worker.join(Duration.ofSeconds(10));
        }
    }
}
