package com.shop.paymentservice.provider;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class VNPayEncoding {

    private static final String UNRESERVED = "!~'()";

    private VNPayEncoding() {
    }

    public static String encode(String value) {
        String encoded = URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
        StringBuilder result = new StringBuilder(encoded.length());
        for (int index = 0; index < encoded.length(); index++) {
            char character = encoded.charAt(index);
            if (character == '%' && index + 2 < encoded.length()) {
                int decoded = Integer.parseInt(encoded.substring(index + 1, index + 3), 16);
                if (UNRESERVED.indexOf((char) decoded) >= 0) {
                    result.append((char) decoded);
                    index += 2;
                    continue;
                }
            }
            result.append(character);
        }
        return result.toString();
    }
}
