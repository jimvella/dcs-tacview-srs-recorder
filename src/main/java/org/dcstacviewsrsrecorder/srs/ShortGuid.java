package org.dcstacviewsrsrecorder.srs;

import java.util.Base64;
import java.util.UUID;

public class ShortGuid {

    public static String encode(String uuid) {
        String encoded = new String(Base64.getEncoder().encode(uuid.getBytes()));
            return encoded.replace("/", "_")
                    .replace("+", "-")
                    .substring(0, 22);
    }

    public static UUID decode(String shortGuid) {
        String value = shortGuid.replace("_", "/").replace("-", "+");
        return UUID.nameUUIDFromBytes(Base64.getDecoder().decode(value.getBytes()));
    }
}
