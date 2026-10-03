package work.archaic.knit.dns.test;

import java.nio.file.Files;
import java.util.Collection;
import java.util.List;
import work.archaic.knit.dns.SystemdResolver;
import work.archaic.service.test.v02.*;

public record ResolverSuite() implements TestSuite {
    @Override public void cases(Collection<TestCase> cases) {
        for (String mode : List.of("authenticated", "unsigned", "synthetic", "zone", "multicast", "truncated", "owner", "record-type", "exit", "extra", "strings"))
            cases.add(new ResolverReply(mode));
    }
}

record ResolverReply(String mode) implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        var root = Files.createTempDirectory("knit-resolver-test-");
        try {
            var raw = new java.io.ByteArrayOutputStream();
            var data = new java.io.DataOutputStream(raw);
            for (String label : (mode.equals("owner") ? "_knit.example.org" : "_knit.archaic.work").split("\\.")) {
                data.writeByte(label.length()); data.writeBytes(label);
            }
            data.writeByte(0); data.writeShort(mode.equals("record-type") ? 1 : 16); data.writeShort(1); data.writeInt(300);
            String text = "v=knit1 alg=ed25519 id=test state=active key=placeholder";
            data.writeShort(text.length() + (mode.equals("strings") ? 2 : 1));
            if (mode.equals("strings")) {
                data.writeByte(7); data.writeBytes(text.substring(0, 7));
                data.writeByte(text.length() - 7); data.writeBytes(text.substring(7));
            } else { data.writeByte(text.length()); data.writeBytes(text); }
            long flags = switch (mode) {
                case "unsigned" -> 1;
                case "synthetic" -> 513 | (1L << 19);
                case "zone" -> 513 | (1L << 21);
                case "multicast" -> 512 | 8;
                default -> 513;
            };
            var reply = new StringBuilder("a(iqqay)t 1 0 1 16 " + raw.size());
            for (byte value : raw.toByteArray()) reply.append(' ').append(Byte.toUnsignedInt(value));
            if (!mode.equals("truncated")) reply.append(' ').append(flags);
            if (mode.equals("extra")) reply.append(" 0");
            var executable = root.resolve("busctl");
            String script = "#!/bin/sh\n[ \"$1\" = --system ] || exit 9\n[ \"$9\" = isqqt ] || exit 9\n";
            script += "[ \"${11}\" = _knit.archaic.work ] || exit 9\n";
            script += mode.equals("exit") ? "exit 1\n" : "printf '%s\\n' '" + reply + "'\n";
            Files.writeString(executable, script);
            Files.setPosixFilePermissions(executable, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            try {
                var answer = new SystemdResolver(executable).resolve("archaic.work");
                assert !List.of("truncated", "owner", "record-type", "exit", "extra").contains(mode) : "Malformed resolver replies must fail closed";
                assert answer.authenticated() == List.of("authenticated", "strings").contains(mode) : "Only authenticated unicast DNS data establishes DNSSEC evidence";
                assert answer.records().equals(List.of(text)) : "TXT character-strings must concatenate without inserted whitespace";
            } catch (java.io.IOException expected) {
                trail.note(expected.getMessage());
                assert List.of("truncated", "owner", "record-type", "exit", "extra").contains(mode) : "Valid replies must be accepted: " + mode;
            }
        } finally {
            try (var files = Files.walk(root)) { for (var path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path); }
        }
    }
}
