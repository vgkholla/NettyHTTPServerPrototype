package io.example.skeletonclient;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.stream.ChunkedWriteHandler;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;


/**
 *
 */
public class EchoClient {

  public static void main(String[] args)
      throws Exception {

    EchoClient client = new EchoClient();
    client.begin();
    System.out.println("Done");
  }

  public EchoClient() {
  }

  public void begin() {
    EventLoopGroup group = new NioEventLoopGroup();

    try {
      Bootstrap b = new Bootstrap();
      b.group(group)
          .channel(NioSocketChannel.class)
          .option(ChannelOption.SO_KEEPALIVE, false)
          .option(ChannelOption.TCP_NODELAY, false)
          .handler(new ChannelInitializer<SocketChannel>() {
            @Override
            public void initChannel(SocketChannel ch)
                throws Exception {
              ch.pipeline().addLast(new HttpClientCodec())
                  .addLast(new ChunkedWriteHandler())
                  .addLast(new EchoClientHandler());
            }
          });

      ChannelFuture f = b.connect("localhost", 8088).sync();
      f.channel().closeFuture().sync();
    } catch (Exception e) {
      System.out.println("Caught exception");
      e.printStackTrace();
    } finally {
      group.shutdownGracefully();
    }
  }
}

class EchoClientHandler extends SimpleChannelInboundHandler<Object> {

  EchoClientHandler() {
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx)
      throws Exception {
    BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
    System.out.println("Enter a message: ");
    String msg = in.readLine();
    FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, msg);
    ctx.writeAndFlush(request);
  }

  @Override
  public void channelRead0(ChannelHandlerContext ctx, Object in)
      throws Exception {
    if (in instanceof DefaultHttpResponse) {
      DefaultHttpResponse response = (DefaultHttpResponse) in;
      if (!response.getDecoderResult().isSuccess()) {
        System.out.println("Bad response");
        return;
      }
      if (!response.getStatus().equals(HttpResponseStatus.OK)) {
        System.out.println("Not OK");
        return;
      }
    }

    if (in instanceof DefaultHttpContent) {
      DefaultHttpContent response = (DefaultHttpContent) in;
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      response.content().readBytes(out, response.content().readableBytes());
      System.out.println("Server says: " + out);
    }
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) {
    ctx.close();
  }
}
