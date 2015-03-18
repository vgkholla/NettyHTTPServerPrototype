package io.example.skeletonserver;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;


/**
 *
 */

public class SkeletonServer {
  private int port;
  private static final String sourceDir = "/tmp/server/";
  private AtomicInteger token = new AtomicInteger();

  public static String getSourceDir() {
    return sourceDir;
  }

  public static void main(String[] args)
      throws Exception {
    SkeletonServer server = new SkeletonServer(8088);
    server.start();
  }

  public SkeletonServer(int port) {
    this.port = port;
    File dir = new File(sourceDir);
    if (!dir.exists()) {
      dir.mkdir();
    }
  }

  public void start() {

    EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    EventLoopGroup workerGroup = new NioEventLoopGroup(1);

    try {
      ServerBootstrap b = new ServerBootstrap();
      b.group(bossGroup, workerGroup)
          .channel(NioServerSocketChannel.class)
          .option(ChannelOption.SO_BACKLOG, 100)
          .handler(new LoggingHandler(LogLevel.INFO))
          .childHandler(new ChannelInitializer<SocketChannel>() {
            @Override
            public void initChannel(SocketChannel ch)
                throws Exception {
              ch.pipeline()
                  .addLast("codec", new HttpServerCodec())
                  .addLast("chunker", new ChunkedWriteHandler())
                  .addLast("delegator", new RequestDelegator(token.incrementAndGet()));
            }
          });

      ChannelFuture f = b.bind(port).sync();
      System.out.println("Open your web browser and navigate to " +
          "http://127.0.0.1:" + port + '/');

      f.channel().closeFuture().sync();
    } catch (Exception e) {
    }finally {
      workerGroup.shutdownGracefully();
      bossGroup.shutdownGracefully();
    }
  }
}