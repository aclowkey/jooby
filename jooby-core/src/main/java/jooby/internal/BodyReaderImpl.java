package jooby.internal;

import static java.util.Objects.requireNonNull;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;

import jooby.BodyReader;
import jooby.ThrowingSupplier;

public class BodyReaderImpl implements BodyReader {

  private Charset charset;

  private ThrowingSupplier<InputStream> stream;

  public BodyReaderImpl(final Charset charset, final ThrowingSupplier<InputStream> stream) {
    this.charset = requireNonNull(charset, "A charset is required.");
    this.stream = requireNonNull(stream, "An stream  supplier is required.");
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> T text(final Text text) throws Exception {
    try (Reader reader = new InputStreamReader(this.stream.get(), charset)) {
      return (T) text.read(reader);
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> T bytes(final Binary bin) throws Exception {
    try (InputStream in = this.stream.get()) {
      return (T) bin.read(in);
    }
  }
}