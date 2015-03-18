package io.example.skeletonserver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;


/**
 *
 */
@ChannelHandler.Sharable
public class EchoRequestHandler extends RequestHandler{

  EchoRequestHandler(ChannelHandlerContext ctx){
      this.ctx = ctx;
  }

  @Override
  public void handleRead(HttpObject msg) throws Exception {
    if (msg instanceof HttpRequest) {
      HttpRequest request = (HttpRequest) msg;
      QueryStringDecoder query = new QueryStringDecoder(request.getUri());
      String in = query.parameters().get("text").get(0);
      System.out.println("Client says: " + in);

      HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
      setContentTypeHeader(response, "text/plain");
      ctx.write(response);

      ByteBuf answer = Unpooled.buffer(in.length());
      answer.writeBytes(in.getBytes());
      ChannelFuture future = ctx.writeAndFlush(new DefaultHttpContent(answer));
      future.addListener(ChannelFutureListener.CLOSE);
    } else {
      System.out.println("Not a HTTP Request");
      ctx.close();
    }
  }
}
