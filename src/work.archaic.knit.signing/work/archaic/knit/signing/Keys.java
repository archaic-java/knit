package work.archaic.knit.signing;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.Arrays;

/** Raw RFC 8032 Ed25519 public-key encoding used in DNS. */
public final class Keys {
    private Keys() {}
    public static byte[] encode(PublicKey key) throws SigningException {
        if (!(key instanceof EdECPublicKey ed) || !ed.getParams().getName().equals("Ed25519"))
            throw new SigningException("Expected an Ed25519 public key");
        byte[] big = ed.getPoint().getY().toByteArray();
        byte[] raw = new byte[32];
        for (int i = 0; i < Math.min(big.length, 32); i++) raw[i] = big[big.length - 1 - i];
        if (ed.getPoint().isXOdd()) raw[31] |= (byte) 128;
        return raw;
    }
    public static PublicKey decode(byte[] raw) throws SigningException {
        if (raw.length != 32) throw new SigningException("Ed25519 public key must contain 32 bytes");
        byte[] big = raw.clone();
        boolean odd = (big[31] & 128) != 0;
        big[31] &= 127;
        for (int i = 0; i < 16; i++) { byte value = big[i]; big[i] = big[31 - i]; big[31 - i] = value; }
        BigInteger y = new BigInteger(1, big);
        if (y.compareTo(BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))) >= 0)
            throw new SigningException("Noncanonical Ed25519 public key");
        try {
            var key = KeyFactory.getInstance("Ed25519").generatePublic(new EdECPublicKeySpec(NamedParameterSpec.ED25519, new EdECPoint(odd, y)));
            if (!Arrays.equals(raw, encode(key))) throw new SigningException("Noncanonical Ed25519 public key");
            return key;
        } catch (GeneralSecurityException error) { throw new SigningException("Invalid Ed25519 public key: " + error.getMessage()); }
    }
}
