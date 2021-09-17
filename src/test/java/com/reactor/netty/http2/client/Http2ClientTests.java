package com.reactor.netty.http2.client;

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.testng.annotations.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.Http2SslContextSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import java.security.cert.CertificateException;

public class Http2ClientTests {

    private static final DisposableServer http2Server = mkHttp2Server();

    private static final String RESPONSE_STR = "Hello!!!";


    static DisposableServer mkHttp2Server() {
        try {
            final SelfSignedCertificate ssc = new SelfSignedCertificate();
            final Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());

            final DisposableServer server = HttpServer.create()
                    .protocol(HttpProtocol.H2)
                    .secure(sslContextSpec -> sslContextSpec.sslContext(serverCtx))
                    .port(0)
                    //.http2Settings(setting -> setting.maxConcurrentStreams(100))
                    .handle((req, res) -> res.sendString(Mono.just(RESPONSE_STR)))
                    .wiretap(true).bindNow();
            System.out.println("Reactor Netty started on " + server.port());
            return server;
        } catch (CertificateException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testHttp2Client() {
        Http2SslContextSpec clientCtx =
                Http2SslContextSpec.forClient()
                        .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));

        final HttpClient client =  HttpClient.create()
                .secure(sslContextSpec -> sslContextSpec.sslContext(clientCtx))
                .protocol(HttpProtocol.H2)
                .wiretap(true);

        StepVerifier.create(client
                .get()
                .uri("https://localhost:" + http2Server.port())
                .responseContent()
                .aggregate()
                .asString()
        ).expectNext(RESPONSE_STR).verifyComplete();
    }
}
