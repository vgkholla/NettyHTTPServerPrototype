package io.example.skeletonserver;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.handler.codec.http.Cookie;
import io.netty.handler.codec.http.CookieDecoder;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.ServerCookieEncoder;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.DiskAttribute;
import io.netty.handler.codec.http.multipart.DiskFileUpload;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.EndOfDataDecoderException;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.ErrorDataDecoderException;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.codec.http.multipart.InterfaceHttpData.HttpDataType;
import io.netty.util.CharsetUtil;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static io.netty.buffer.Unpooled.*;

/**
 *
 */

public class UploadRequestHandler extends RequestHandler {

  private HttpRequest request;
  private final StringBuilder responseContent = new StringBuilder();

  private static final HttpDataFactory factory =
      new DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE); // Disk if size exceed

  private HttpPostRequestDecoder decoder;
  static {
    DiskFileUpload.deleteOnExitTemporaryFile = true;
    DiskFileUpload.baseDirectory = null; // temp directory by default
    DiskAttribute.deleteOnExitTemporaryFile = true;
    DiskAttribute.baseDirectory = null; // temp directory by default
  }

  private final BlockingQueue<HttpContent> chunkQueue = new LinkedBlockingQueue<HttpContent>();
  private ExecutorService executor = Executors.newSingleThreadExecutor();
  ChunkDecoder chunkDecoder;

  public UploadRequestHandler(ChannelHandlerContext ctx) {
    this.ctx = ctx;
    suspendChannelAutoRead();
  }

  private void suspendChannelAutoRead(){
    ctx.channel().config().setAutoRead(false);
    ctx.channel().config().setMaxMessagesPerRead(1);
  }

  @Override
  public void handleChannelInactive() throws Exception {
    if (decoder != null) {
      decoder.cleanFiles();
    }
  }

  @Override
  public void handleRead(HttpObject msg) throws Exception {
    if (msg instanceof HttpRequest) {
      HttpRequest request = this.request = (HttpRequest) msg;
      QueryStringDecoder query = new QueryStringDecoder(request.getUri());
      String action = query.parameters().get("action").get(0);
      if (action.startsWith("formShow")) {
        // Write Menu
        writeMenu(ctx);
        return;
      }
      responseContent.setLength(0);

      if (request.getMethod().equals(HttpMethod.GET)) {
        return;
      }

      try {
        decoder = new HttpPostRequestDecoder(factory, request);
        chunkDecoder = new ChunkDecoder(responseContent, decoder, ctx, chunkQueue);
        executor.execute(chunkDecoder);
      } catch (Exception e) {
        bailOutOnException(e);
        return;
      }
    }

    if (decoder != null) {
      // this is not a get
      if (msg instanceof HttpContent) {
        HttpContent chunk = (HttpContent) msg;
        try {
          chunk.retain();
          chunkQueue.add(chunk);
          if(chunkQueue.size() > 9){
            System.out.println("Queue size: " + chunkQueue.size());
          }
        } catch (ErrorDataDecoderException e) {
          bailOutOnException(e);
          return;
        }

        if (chunk instanceof LastHttpContent) {
          executor.shutdown();
          try {
            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
          } catch (InterruptedException e) {
            e.printStackTrace();
          }
          writeResponse();
        }
      } else {
        if(chunkQueue.isEmpty()){
          ctx.read();
        }
      }
    } else {
      writeResponse();
      ctx.read();
    }
  }

  private void bailOutOnException(Exception e) {
    e.printStackTrace();
    responseContent.append(e.getMessage());
    bailOut();
  }

  private void bailOut(){
    writeResponse();
    ctx.channel().close();
  }

  private void reset() {
    decoder.destroy();
    decoder = null;
    request = null;
  }

  private void writeResponse() {
    Channel channel = ctx.channel();
    ByteBuf buf = copiedBuffer(responseContent.toString(), CharsetUtil.UTF_8);
    responseContent.setLength(0);

    boolean close = request.headers().contains(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE, true)
        || request.getProtocolVersion().equals(HttpVersion.HTTP_1_0)
        && !request.headers().contains(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE, true);

    FullHttpResponse response = new DefaultFullHttpResponse(
        HttpVersion.HTTP_1_1, HttpResponseStatus.OK, buf);
    response.headers().set(HttpHeaders.Names.CONTENT_TYPE, "text/plain; charset=UTF-8");

    if (!close) {
      response.headers().set(HttpHeaders.Names.CONTENT_LENGTH, buf.readableBytes());
    }

    Set<Cookie> cookies;
    String value = request.headers().get(HttpHeaders.Names.COOKIE);
    if (value == null) {
      cookies = Collections.emptySet();
    } else {
      cookies = CookieDecoder.decode(value);
    }
    if (!cookies.isEmpty()) {
      for (Cookie cookie : cookies) {
        response.headers().add(HttpHeaders.Names.SET_COOKIE, ServerCookieEncoder.encode(cookie));
      }
    }

    ChannelFuture future = channel.writeAndFlush(response);
    if (close) {
      future.addListener(ChannelFutureListener.CLOSE);
    }
  }

  private void writeMenu(ChannelHandlerContext ctx) {
    responseContent.setLength(0);

    // create Pseudo Menu
    responseContent.append("<html>");
    responseContent.append("<head>");
    responseContent.append("<title>Netty Test Form</title>\r\n");
    responseContent.append("</head>\r\n");
    responseContent.append("<body bgcolor=white><style>td{font-size: 12pt;}</style>");

    responseContent.append("<table border=\"0\">");
    responseContent.append("<tr>");
    responseContent.append("<td>");
    responseContent.append("<h1>Netty Test Form</h1>");
    responseContent.append("Choose one FORM");
    responseContent.append("</td>");
    responseContent.append("</tr>");
    responseContent.append("</table>\r\n");

    // GET
    responseContent.append("<CENTER>GET FORM<HR WIDTH=\"75%\" NOSHADE color=\"blue\"></CENTER>");
    responseContent.append("<FORM ACTION=\"/?action=formGet\" METHOD=\"GET\">");
    responseContent.append("<input type=hidden name=getform value=\"GET\">");
    responseContent.append("<table border=\"0\">");
    responseContent.append("<tr><td>Fill with value: <br> <input type=text name=\"info\" size=10></td></tr>");
    responseContent.append("<tr><td>Fill with value: <br> <input type=text name=\"secondinfo\" size=20>");
    responseContent
        .append("<tr><td>Fill with value: <br> <textarea name=\"thirdinfo\" cols=40 rows=10></textarea>");
    responseContent.append("</td></tr>");
    responseContent.append("<tr><td><INPUT TYPE=\"submit\" NAME=\"Send\" VALUE=\"Send\"></INPUT></td>");
    responseContent.append("<td><INPUT TYPE=\"reset\" NAME=\"Clear\" VALUE=\"Clear\" ></INPUT></td></tr>");
    responseContent.append("</table></FORM>\r\n");
    responseContent.append("<CENTER><HR WIDTH=\"75%\" NOSHADE color=\"blue\"></CENTER>");

    // POST
    responseContent.append("<CENTER>POST FORM<HR WIDTH=\"75%\" NOSHADE color=\"blue\"></CENTER>");
    responseContent.append("<FORM ACTION=\"/?action=formPost\" METHOD=\"POST\">");
    responseContent.append("<input type=hidden name=getform value=\"POST\">");
    responseContent.append("<table border=\"0\">");
    responseContent.append("<tr><td>Fill with value: <br> <input type=text name=\"info\" size=10></td></tr>");
    responseContent.append("<tr><td>Fill with value: <br> <input type=text name=\"secondinfo\" size=20>");
    responseContent
        .append("<tr><td>Fill with value: <br> <textarea name=\"thirdinfo\" cols=40 rows=10></textarea>");
    responseContent.append("<tr><td>Fill with file (only file name will be transmitted): <br> "
        + "<input type=file name=\"myfile\">");
    responseContent.append("</td></tr>");
    responseContent.append("<tr><td><INPUT TYPE=\"submit\" NAME=\"Send\" VALUE=\"Send\"></INPUT></td>");
    responseContent.append("<td><INPUT TYPE=\"reset\" NAME=\"Clear\" VALUE=\"Clear\" ></INPUT></td></tr>");
    responseContent.append("</table></FORM>\r\n");
    responseContent.append("<CENTER><HR WIDTH=\"75%\" NOSHADE color=\"blue\"></CENTER>");

    // POST with enctype="multipart/form-data"
    responseContent.append("<CENTER>POST MULTIPART FORM<HR WIDTH=\"75%\" NOSHADE color=\"blue\"></CENTER>");
    responseContent.append("<FORM ACTION=\"/?action=formPostMultipart\" ENCTYPE=\"multipart/form-data\" METHOD=\"POST\">");
    responseContent.append("<input type=hidden name=getform value=\"POST\">");
    responseContent.append("<table border=\"0\">");
    responseContent.append("<tr><td>Fill with value: <br> <input type=text name=\"info\" size=10></td></tr>");
    responseContent.append("<tr><td>Fill with value: <br> <input type=text name=\"secondinfo\" size=20>");
    responseContent
        .append("<tr><td>Fill with value: <br> <textarea name=\"thirdinfo\" cols=40 rows=10></textarea>");
    responseContent.append("<tr><td>Fill with file: <br> <input type=file name=\"myfile\">");
    responseContent.append("</td></tr>");
    responseContent.append("<tr><td><INPUT TYPE=\"submit\" NAME=\"Send\" VALUE=\"Send\"></INPUT></td>");
    responseContent.append("<td><INPUT TYPE=\"reset\" NAME=\"Clear\" VALUE=\"Clear\" ></INPUT></td></tr>");
    responseContent.append("</table></FORM>\r\n");
    responseContent.append("<CENTER><HR WIDTH=\"75%\" NOSHADE color=\"blue\"></CENTER>");

    responseContent.append("</body>");
    responseContent.append("</html>");

    ByteBuf buf = copiedBuffer(responseContent.toString(), CharsetUtil.UTF_8);
    FullHttpResponse response = new DefaultFullHttpResponse(
        HttpVersion.HTTP_1_1, HttpResponseStatus.OK, buf);

    response.headers().set(HttpHeaders.Names.CONTENT_TYPE, "text/html; charset=UTF-8");
    response.headers().set(HttpHeaders.Names.CONTENT_LENGTH, buf.readableBytes());

    ctx.channel().writeAndFlush(response);
  }

  @Override
  public void handleException(Throwable cause) throws Exception {
    ctx.channel().close();
  }
}

class ChunkDecoder implements Runnable {
  private BlockingQueue<HttpContent> chunkQueue;
  private Boolean done = false;
  private StringBuilder responseContent;
  private HttpPostRequestDecoder decoder;
  private ChannelHandlerContext ctx;

  public ChunkDecoder(StringBuilder responseContent, HttpPostRequestDecoder decoder, ChannelHandlerContext ctx,
      BlockingQueue<HttpContent> chunkQueue){
    this.responseContent = responseContent;
    this.decoder = decoder;
    this.ctx = ctx;
    this.chunkQueue = chunkQueue;
  }

  public void run() {
    while(!done){
      try{
        decode(chunkQueue.take());
      } catch (Exception e) {
        e.printStackTrace();
        break;
      }
    }
  }

   private void decode(HttpContent chunk){
     decoder.offer(chunk);
     readHttpDataChunkByChunk();
     if(chunk instanceof LastHttpContent){
       done = true;
     }
     chunk.release();
     if(chunkQueue.isEmpty()) {
      ctx.read();
     }
   }

  private void readHttpDataChunkByChunk() {
    try {
      while (decoder.hasNext()) {
        InterfaceHttpData data = decoder.next();
        if (data != null) {
          try {
            writeHttpData(data);
          } finally {
            data.release();
          }
        }
      }
    } catch (EndOfDataDecoderException e1) {
      //done
    }
  }

  private void writeHttpData(InterfaceHttpData data) {
    if (data.getHttpDataType() == HttpDataType.FileUpload) {
      FileUpload fileUpload = (FileUpload) data;
      if (fileUpload.isCompleted()) {
        responseContent.append("\tFile length:" + fileUpload.length() + "\r\n");
        if (fileUpload.length() < 10000) {
          System.out.println("Content of file: ");
          try {
            System.out.println(fileUpload.getString(fileUpload.getCharset()));
          } catch (IOException e) {
            // error reading file
            e.printStackTrace();
          }
        }
        try{
          //File dest = new File("/tmp/uploads/" + System.nanoTime());
          //fileUpload.renameTo(dest);
        } catch(Exception e) {
          e.printStackTrace();
        }
      } else {
        responseContent.append("\tFile to be continued but should not!\r\n");
      }
    }
  }
}
