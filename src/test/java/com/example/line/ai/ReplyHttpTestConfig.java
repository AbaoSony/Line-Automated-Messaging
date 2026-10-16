package com.example.line.ai;

import com.example.line.client.LineMessagingTransport;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;

/** 测试仍使用生产传输策略和官方 SDK，仅把目的地址换为本地可控服务器。 */
@TestConfiguration(proxyBeanMethods=false)
class ReplyHttpTestConfig {
    @Bean(destroyMethod="close") ReplyHttpStub replyHttpStub() throws IOException {return new ReplyHttpStub();}
    @Bean(destroyMethod="close") LineMessagingTransport stubReplyTransport(ReplyHttpStub stub) {
        return new LineMessagingTransport("fictional-line-http-access",stub.endpoint());
    }
    @Bean @Primary MessagingApiClient localReplyClient(@Qualifier("stubReplyTransport") LineMessagingTransport transport) {return transport.client();}
}
