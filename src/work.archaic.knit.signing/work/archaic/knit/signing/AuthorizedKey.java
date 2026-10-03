package work.archaic.knit.signing;

import java.security.PublicKey;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

record AuthorizedKey(PublicKey key, String state) {
    static AuthorizedKey select(List<String> records, Metadata metadata) throws SigningException {
        AuthorizedKey selected = null;
        for (String text : records) {
            // Unrelated TXT protocols coexist with Knit. Any malformed knit1 record fails closed.
            if (!java.util.Arrays.asList(text.split(" ", -1)).contains("v=knit1")) continue;
            var fields = new HashMap<String, String>();
            for (String part : text.split(" ", -1)) {
                int equal = part.indexOf('=');
                if (equal <= 0 || equal == part.length() - 1 || part.chars().anyMatch(c -> c < 33 || c > 126)
                        || fields.putIfAbsent(part.substring(0, equal), part.substring(equal + 1)) != null)
                    throw new SigningException("Malformed Knit DNS key record");
            }
            if (!fields.keySet().equals(Set.of("v", "alg", "id", "state", "key")))
                throw new SigningException("Unexpected Knit DNS key fields");
            if (!fields.get("alg").equals("ed25519") || !fields.get("id").matches("[A-Za-z0-9._-]{1,64}")
                    || !Set.of("active", "retired", "revoked").contains(fields.get("state")))
                throw new SigningException("Unsupported Knit DNS key record");
            byte[] raw = base64(fields.get("key"), 32);
            PublicKey key = Keys.decode(raw);
            if (!fields.get("id").equals(metadata.keyId())) continue;
            if (selected != null) throw new SigningException("Duplicate DNS records for signing key " + metadata.keyId());
            selected = new AuthorizedKey(key, fields.get("state"));
        }
        if (selected == null) throw new SigningException("Signing key is not authorized by publisher");
        if (selected.state.equals("revoked")) throw new SigningException("Signing key is revoked");
        return selected;
    }

    static byte[] base64(String text, int length) throws SigningException {
        try {
            byte[] raw = Base64.getDecoder().decode(text);
            if (raw.length != length || !Base64.getEncoder().encodeToString(raw).equals(text))
                throw new IllegalArgumentException();
            return raw;
        } catch (IllegalArgumentException error) { throw new SigningException("Invalid canonical Base64 (expected " + length + " bytes)"); }
    }
}
