package io.example.skeletonserver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelProgressiveFuture;
import io.netty.channel.ChannelProgressiveFutureListener;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.stream.ChunkedInput;
import io.netty.handler.stream.ChunkedWriteHandler;
import java.io.File;
import java.io.FileInputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 *
 */

class Fetcher implements Runnable {

  public static final int BUFSIZE = 1024*1024;
  private File file;
  private ByteBuf buf;
  private ChannelHandlerContext ctx;
  private AtomicBoolean done;
  private AtomicBoolean suspended;
  private byte[] b;

  public Fetcher(ChannelHandlerContext ctx, File file, ByteBuf buf) {
    this.file = file;
    this.done = new AtomicBoolean(false);
    this.suspended = new AtomicBoolean(false);
    this.buf = buf;
    this.ctx = ctx;
    this.b = new byte[BUFSIZE];
  }

  public void run() {
    try {
      long bytes = 0;
      ChunkedWriteHandler chunker = (ChunkedWriteHandler) ctx.pipeline().get("chunker");
      FileInputStream fip = new FileInputStream(file);

      while (true) {
        if (!buf.isWritable(BUFSIZE)) {
          Thread.sleep(10); //so we don't hog the cpu
          continue;
        }

        int sz = fip.read(b);
        if (sz == -1) {
          break;
        }

        buf.writeBytes(b, 0, sz);
        bytes += sz;
        System.out.println("Wrote " + bytes + " bytes");

        if (suspended.get()) {
          suspended.set(false);
          System.out.println("Resuming transfer");
          chunker.resumeTransfer();
        }
      }

      System.out.println("Done reading");
      done.set(true);

      if (suspended.get()) {
        chunker.resumeTransfer();
      }
    } catch (Exception e) {
      e.printStackTrace(System.out);
      System.out.println("Caught Exception");
    }
  }

  public void suspend() {
    suspended.set(true);
  }

  public boolean isDone() {
    return done.get();
  }

  public ByteBuf getBuf() {
    return buf;
  }
}

public class FileRequestHandler extends RequestHandler {

  public FileRequestHandler(ChannelHandlerContext ctx) {
    this.ctx = ctx;
  }

  private Boolean vetRequest(HttpRequest request){
    if (request.getMethod() != HttpMethod.GET) {
      RequestDelegator.sendError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED);
      return false;
    }
    return true;
  }

  private Boolean vetPath(String path){
    if (path == null) {
      RequestDelegator.sendError(ctx, HttpResponseStatus.FORBIDDEN);
      return false;
    }
    return true;
  }

  private Boolean vetFile(File file){
    if (file.isHidden() || !file.exists()) {
      RequestDelegator.sendError(ctx, HttpResponseStatus.NOT_FOUND);
      return false;
    }

    if (!file.isFile()) {
      RequestDelegator.sendError(ctx, HttpResponseStatus.FORBIDDEN);
      return false;
    }
    return true;
  }


  @Override
  public void handleRead(HttpObject msg) throws Exception {
    if (msg instanceof HttpRequest) {
      HttpRequest request = (HttpRequest) msg;
      if(!vetRequest(request)){
        return;
      }

      final String path = sanitizeUri(request.getUri());
      if(!vetPath(path)){
        return;
      }

      File file = new File(path);
      if(!vetFile(file)){
        return;
      }

      serveFile(request, file);
    }
  }

  private void serveFile(HttpRequest request, File file) {
    try {
      final ExecutorService executor = Executors.newFixedThreadPool(1);
      Fetcher f = new Fetcher(ctx, file, Unpooled.buffer(Fetcher.BUFSIZE));
      executor.execute(f);

      HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
      HttpHeaders.setContentLength(response, file.length());
      setContentTypeHeader(response, "application/octet-stream");
      response.headers().set(HttpHeaders.Names.TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED);
      if (HttpHeaders.isKeepAlive(request)) {
        response.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
      }
      ctx.write(response);

      ChannelFuture sendFileFuture;
      ChannelFuture lastContentFuture;
      sendFileFuture = ctx.writeAndFlush(new FileChunkedInput(f), ctx.newProgressivePromise());
      // HttpChunkedInput will write the end marker (LastHttpContent) for us.
      lastContentFuture = sendFileFuture;

      sendFileFuture.addListener(new ChannelProgressiveFutureListener() {
        @Override
        public void operationProgressed(ChannelProgressiveFuture future, long progress, long total) {
          if (total < 0) { // total unknown
            System.out.println(future.channel() + " Transfer progress: " + progress);
          } else {
            System.out.println(future.channel() + " Transfer progress: " + progress + " / " + total);
          }
        }


        @Override
        public void operationComplete(ChannelProgressiveFuture future) {
          System.out.println(future.channel() + " Transfer complete." +
              "Shutting down executor");
          executor.shutdown();
        }
      });

      if (!HttpHeaders.isKeepAlive(request)) {
        lastContentFuture.addListener(ChannelFutureListener.CLOSE);
      }
    } catch(Exception e){
      System.out.println("Caught Exception");
      e.printStackTrace(System.out);
    }
  }

  private static String sanitizeUri(String uri) {
    QueryStringDecoder query = new QueryStringDecoder(uri);
    String path = query.parameters().get("path").get(0);

    if (path.isEmpty() || path.charAt(0) != '/') {
      return null;
    }

    path = path.replace('/', File.separatorChar);
    return SkeletonServer.getSourceDir() + File.separator + path;
  }
}

class FileChunkedInput implements ChunkedInput<HttpContent> {

  Fetcher fetcher;
  private final LastHttpContent lastHttpContent;
  private boolean sentLastChunk;

  FileChunkedInput(Fetcher f) {
    this.fetcher = f;
    this.lastHttpContent = LastHttpContent.EMPTY_LAST_CONTENT;
  }

  @Override
  public void close() throws Exception {
  }

  @Override
  public HttpContent readChunk(ChannelHandlerContext ctx) throws Exception {
    if (fetcher.isDone()) {
      if (sentLastChunk) {
        System.out.println("Returned eof");
        return null;
      } else {
        sentLastChunk = true;
        System.out.println("Returned lastHttpContent");
        return lastHttpContent;
      }
    }
    ByteBuf buf = fetcher.getBuf();
    buf.discardReadBytes();
    if (buf.readableBytes() > 0) {
      ByteBuf slice = buf.readBytes(buf.readableBytes());
      System.out.println("Returning slice");
      return new DefaultHttpContent(slice);
    } else {
      System.out.println("Returning null");
      fetcher.suspend();
      return null;
    }
  }

  @Override
  public boolean isEndOfInput() throws Exception {
    boolean ret = fetcher.isDone() && sentLastChunk;
    return ret;
  }
}
