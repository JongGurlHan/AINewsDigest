package com.example.ainewsdigest.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** {@code src/test/resources/fixtures/}의 외부 응답 픽스처를 읽는다. */
public final class Fixtures {

	private Fixtures() {
	}

	public static String read(String name) {
		try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
			if (in == null) {
				throw new IllegalArgumentException("픽스처를 찾을 수 없다: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}
}
