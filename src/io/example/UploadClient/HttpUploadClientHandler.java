/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.example.UploadClient;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.CharsetUtil;

/**
 * Handler that just dumps the contents of the response from the server
 */
public class HttpUploadClientHandler extends SimpleChannelInboundHandler<HttpObject> {

  private boolean readingChunks;

  @Override
  public void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
    if (msg instanceof HttpResponse) {
      HttpResponse response = (HttpResponse) msg;

      //printMsg("STATUS: " + response.getStatus());
      //printMsg("VERSION: " + response.getProtocolVersion());

      if (!response.headers().isEmpty()) {
        for (CharSequence name : response.headers().names()) {
          for (CharSequence value : response.headers().getAll(name)) {
            //printMsg("HEADER: " + name + " = " + value);
          }
        }
      }

      if (response.getStatus().code() == 200 && HttpHeaders.isTransferEncodingChunked(response)) {
        readingChunks = true;
        //printMsg("CHUNKED CONTENT {");
      } else {
        //printMsg("CONTENT {");
      }
    }
    if (msg instanceof HttpContent) {
      HttpContent chunk = (HttpContent) msg;
      printMsg(chunk.content().toString(CharsetUtil.UTF_8));

      if (chunk instanceof LastHttpContent) {
        if (readingChunks) {
          //printMsg("} END OF CHUNKED CONTENT");
        } else {
          //printMsg("} END OF CONTENT");
        }
        readingChunks = false;
      } else {
        //printMsg(chunk.content().toString(CharsetUtil.UTF_8));
      }
    }
  }

  private void printMsg(String msg){
    System.err.println(msg);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    cause.printStackTrace();
    ctx.channel().close();
  }
}