package io.example.skeletonserver;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.CharsetUtil;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.*;


/**
 *
 */
public class RequestDelegator extends SimpleChannelInboundHandler<HttpObject> {
  private RequestHandler handler;
  private int token;
  private AtomicInteger readNo = new AtomicInteger();

  public RequestDelegator(int token){
    this.token = token;
  }

  private Boolean vetRequest(ChannelHandlerContext ctx, HttpRequest request){
    if (!request.getDecoderResult().isSuccess()) {
        sendError(ctx, HttpResponseStatus.BAD_REQUEST);
        return false;
    }

    return true;
  }

  @Override
  public void channelRead0(ChannelHandlerContext ctx, HttpObject msg) throws Exception {
    if (msg instanceof HttpRequest) {
      HttpRequest request = (HttpRequest) msg;
      if(!vetRequest(ctx, request)){
        return;
      }

      QueryStringDecoder query = new QueryStringDecoder(request.getUri());

      if(query.parameters().get("action") != null){
        String action = query.parameters().get("action").get(0);
        if(action.startsWith("file")){
          handler = new FileRequestHandler(ctx);
        } else if (action.startsWith("echo")) {
          handler = new EchoRequestHandler(ctx);
        } else if (action.startsWith("form")){
          handler = new UploadRequestHandler(ctx, token);
          ctx.channel().config().setAutoRead(false);
        } else {
          sendError(ctx, HttpResponseStatus.BAD_REQUEST);
          return;
        }
      } else {
        return; // nothing to do for now
      }
    }

    if(handler != null){
      long startTime = System.nanoTime();
      handler.handleRead(msg);
      if(readNo.incrementAndGet() % 10000 == 0){
        System.out.println("Read time elapsed is " + (System.nanoTime() - startTime) + " for " + token);
      }
    }
  }

  public static void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
    FullHttpResponse response = new DefaultFullHttpResponse(
        HTTP_1_1, status, Unpooled.copiedBuffer("Failure: " + status + "\r\n", CharsetUtil.UTF_8));
    response.headers().set(HttpHeaders.Names.CONTENT_TYPE, "text/plain; charset=UTF-8");

    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    if(handler != null){
      handler.handleException(cause);
    }

    cause.printStackTrace();
    if (ctx.channel().isActive()) {
      sendError(ctx, INTERNAL_SERVER_ERROR);
    }
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx){

  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    if(handler != null){
      handler.handleChannelInactive();
    }
  }
}
