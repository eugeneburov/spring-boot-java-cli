package ru.netty.io.handler;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProxyFrontendHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final String targetHost;
    private final int targetPort;

    public ProxyFrontendHandler() {
        this.targetHost = "localhost"; // Замените на адрес вашего целевого REST API
        this.targetPort = 8090; // Порт целевого REST API
    }

    @Override
	public void channelActive(ChannelHandlerContext ctx) throws Exception {
		log.info("channel active");
		//final Channel inboundChannel = ctx.channel();
	}
	
	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		log.info("Message read: {}", msg);
		super.channelRead(ctx, msg);
	}
	
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
    	log.info("Message read0: {}", request);
    	log.info("body: ", request.content().toString(StandardCharsets.UTF_8));
        URI uri = new URI(request.uri());
        String path = uri.getPath();
        log.info("path={}", path);
        
        // Формируем новый запрос для отправки на целевой сервер
        FullHttpRequest newRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,
                request.method(), path, Unpooled.wrappedBuffer(request.content().retain()));

        // Копируем заголовки
        for (CharSequence name : request.headers().names()) {
            for (CharSequence value : request.headers().getAll(name)) {
                newRequest.headers().set(name, value);
            }
        }

        // Устанавливаем Connection: close, чтобы закрыть соединение после ответа
        newRequest.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

        // Получаем канал соединения с клиентом
        Channel clientChannel = ctx.channel();

        // Открываем новое соединение с целевым сервером
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(clientChannel.eventLoop())
                .channel(ctx.channel().getClass())
                .option(ChannelOption.AUTO_READ, false)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline().addLast(
                        		new LoggingHandler(LogLevel.INFO),
                                new HttpResponseDecoder(),
                                new HttpObjectAggregator(65536),
                                new HttpRequestEncoder(),
                                new ProxyBackendHandler(clientChannel));
                    }
                });

        // Подключаемся к целевому серверу и отправляем запрос
        ChannelFuture future = bootstrap.connect(new InetSocketAddress(targetHost, targetPort));
        future.addListener((ChannelFutureListener) futureChannel -> {
            if (futureChannel.isSuccess()) {
            	log.info("write to backend {}", newRequest);
                futureChannel.channel().writeAndFlush(newRequest);
                futureChannel.channel().read();
            } else {
            	log.error("handle error!");
                ReferenceCountUtil.release(newRequest);
                sendError(ctx, HttpResponseStatus.BAD_GATEWAY);
            }
        });
    }

    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                Unpooled.copiedBuffer("Failure: " + status.toString() + "\r\n", CharsetUtil.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }
}