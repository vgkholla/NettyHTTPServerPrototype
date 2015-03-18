package io.example.skeletonserver;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;

import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;


/**
 *
 */
public class RequestHandler {
  protected ChannelHandlerContext ctx;


  public RequestHandler(){

  }

  public RequestHandler(ChannelHandlerContext ctx) {
    this.ctx = ctx;
  }

  protected static void setContentTypeHeader(HttpResponse response, String type) {
    response.headers()
        .set(HttpHeaders.Names.CONTENT_TYPE, type);
  }

  public void handleRead(HttpObject msg) throws Exception {

  }

  public void handleChannelInactive() throws Exception {

  }

  public void handleException(Throwable cause) throws Exception{

  }
}
