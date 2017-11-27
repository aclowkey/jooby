package io.jooby.internal.netty;

import io.jooby.Context;
import io.jooby.Route;
import io.jooby.spi.BaseContext;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import static io.netty.buffer.Unpooled.copiedBuffer;
import static io.netty.buffer.Unpooled.wrappedBuffer;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import static io.netty.channel.ChannelFutureListener.CLOSE;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import static io.netty.handler.codec.http.LastHttpContent.EMPTY_LAST_CONTENT;

import javax.annotation.Nonnull;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.function.Consumer;

public final class NettyContext extends BaseContext {
  private final HttpHeaders setHeaders = new DefaultHttpHeaders(false);
  private final ChannelHandlerContext ctx;
  private final HttpRequest req;
  private final String path;
  private HttpResponseStatus status = HttpResponseStatus.OK;
  private boolean keepAlive;
  private boolean committed;
  private int chunk;

  public NettyContext(ChannelHandlerContext ctx, HttpRequest req, boolean keepAlive, String path,
      Iterator<Route> router) {
    super(router);
    this.path = path;
    this.ctx = ctx;
    this.req = req;
    if (keepAlive) {
      setHeaders.set(CONNECTION, HttpHeaderValues.KEEP_ALIVE);
    }
    this.keepAlive = keepAlive;
  }

  @Override public int status() {
    return this.status.code();
  }

  @Override public Context status(int status) {
    this.status = HttpResponseStatus.valueOf(status);
    return this;
  }

  @Nonnull @Override public final String path() {
    return path;
  }

  @Override public final boolean isInIoThread() {
    return ctx.executor().inEventLoop();
  }

  @Override public final Context type(String contentType) {
    setHeaders.set(CONTENT_TYPE, contentType);
    return this;
  }

  @Override public Context length(long length) {
    setHeaders.set(CONTENT_LENGTH, length);
    return this;
  }

  @Override public final Context write(byte[] chunk) {
    writeChunk(wrappedBuffer(chunk));
    return this;
  }

  @Override public final Context write(ByteBuffer chunk) {
    writeChunk(wrappedBuffer(chunk));
    return this;
  }

  @Override public final Context write(String chunk, Charset charset) {
    writeChunk(copiedBuffer(chunk, charset));
    return this;
  }

  @Override public final Context send(String response, Charset charset) {
    return send(copiedBuffer(response, charset));
  }

  @Override public final Context send(byte[] response) {
    return send(wrappedBuffer(response));
  }

  @Override public final Context send(ByteBuffer response) {
    return send(wrappedBuffer(response));
  }

  @Override public boolean committed() {
    return committed;
  }

  @Override public Context end() {
    committed = true;
    if (chunk > 0) {
      if (!keepAlive) {
        ctx.writeAndFlush(EMPTY_LAST_CONTENT).addListener(CLOSE);
      }
    }
    return this;
  }

  private Context send(ByteBuf buff) {
    if (chunk > 0) {
      throw new IllegalStateException("Response already started via write methods");
    }
    int len = buff.readableBytes();
    setHeaders.set(CONTENT_LENGTH, len);
    if (len > 0) {
      HttpResponse rsp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, buff);
      rsp.headers().set(setHeaders);
      if (keepAlive) {
        ctx.writeAndFlush(rsp, ctx.voidPromise());
      } else {
        ctx.writeAndFlush(rsp).addListener(CLOSE);
      }
    } else {
      // empty response
      setHeaders.remove(CONNECTION);
      setHeaders.set(CONTENT_LENGTH, 0);
      HttpResponse rsp = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status, setHeaders);
      ctx.writeAndFlush(rsp).addListener(CLOSE);
    }
    committed = true;
    return this;
  }

  private void writeChunk(ByteBuf buff) {
    if (chunk == 0) {
      if (!setHeaders.contains(HttpHeaderNames.CONTENT_LENGTH)) {
        setHeaders.set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        setHeaders.remove(HttpHeaderNames.CONNECTION);
        keepAlive = false;
      }
      HttpResponse rsp = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status, setHeaders);
      // TODO: group writes and run inside eventloop? we must do the same with tail chunk.
      ctx.write(rsp, ctx.voidPromise());
    }
    ctx.writeAndFlush(new DefaultHttpContent(buff), ctx.voidPromise());
    chunk += 1;
  }
}