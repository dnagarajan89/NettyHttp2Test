package com.reactor.netty.http2.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.jmx.JmxConfig;
import io.micrometer.jmx.JmxMeterRegistry;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;
import reactor.netty.channel.MicrometerChannelMetricsRecorder;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class HttpConnectionTest {

    private static final int WIREMOCK_PORT = 8888;

    private static final String CONNECTION_RESET_W_DELAY_PATH = "/connection-reset-with-delay";
    private static final String CONNECTION_RESET_PATH = "/connection-reset";
    private static final String MALFORMED_RESPONSE_PATH = "/malformed-response";
    private static final String HAPPY_PATH = "/happy-path";

    private final WireMockServer wireMockServer = new WireMockServer(WIREMOCK_PORT);


    private static ConnectionProvider connectionProvider = ConnectionProvider.builder("capped_conn_pool")
            .maxConnections(10)
            .maxIdleTime(Duration.ofMillis(2000))
            .maxLifeTime(Duration.ofMillis(2000))
            .pendingAcquireTimeout(Duration.ofMillis(50))
            .build();
    private static final HttpClient.ResponseReceiver<?> testHttpClient = HttpClient
            .create(connectionProvider)
            .baseUrl("http://localhost:" + WIREMOCK_PORT).metrics(true, HttpClientMetricsRecorder::new)
            .get();

    static class HttpClientMetricsRecorder extends MicrometerChannelMetricsRecorder {
        HttpClientMetricsRecorder() {
            super("http.client.testHttpClient", "tcp");
        }
    }

    @BeforeTest
    public void init() {
        Metrics.globalRegistry.add(new JmxMeterRegistry(JmxConfig.DEFAULT, Clock.SYSTEM));
        wireMockServer.start();
        WireMock.configureFor("localhost", WIREMOCK_PORT);
        WireMock.stubFor(
                WireMock.get(CONNECTION_RESET_W_DELAY_PATH)
                        .willReturn(WireMock.aResponse().withFixedDelay(100).withFault(Fault.CONNECTION_RESET_BY_PEER))
        );

        WireMock.stubFor(
                WireMock.get(CONNECTION_RESET_PATH)
                        .willReturn(WireMock.aResponse()//.withUniformRandomDelay(100, 500)
                                .withFault(Fault.CONNECTION_RESET_BY_PEER))
        );

        WireMock.stubFor(
                WireMock.get(MALFORMED_RESPONSE_PATH)
                        .willReturn(WireMock.aResponse().withFault(Fault.MALFORMED_RESPONSE_CHUNK))
        );

        WireMock.stubFor(
                WireMock.get(HAPPY_PATH)
                        .willReturn(WireMock.aResponse().withFixedDelay(100).withBody("Hello!!!"))
        );
    }

    @Test
    public void reproduceConnectionLeak() {
        IntStream.range(0, 3000)
                .parallel()
                .mapToObj(index ->  {
                    mkHttpCall(CONNECTION_RESET_W_DELAY_PATH);
                    mkHttpCall(MALFORMED_RESPONSE_PATH);
                    return mkHttpCall(MALFORMED_RESPONSE_PATH);
                }).collect(Collectors.toList());

        Metrics.globalRegistry.find("reactor.netty.connection.provider.total.connections")
                .meter()
                .measure().forEach(measurement -> System.out.println("Total connections : " + measurement.getValue()));
        Metrics.globalRegistry.find("reactor.netty.connection.provider.active.connections")
                .meter()
                .measure().forEach(measurement -> System.out.println("Active connections : " + measurement.getValue()));
        Metrics.globalRegistry.find("reactor.netty.connection.provider.idle.connections")
                .meter()
                .measure().forEach(measurement -> System.out.println("Idle connections : " + measurement.getValue()));
    }

    private String mkHttpCall(final String path) {
        try {
            return testHttpClient.uri(path)
                    .responseContent()
                    .aggregate()
                    .asString()
                    .block();
        } catch (Exception e) {
            e.printStackTrace();
            return e.getMessage();
        }
    }

    @AfterTest
    public void stop() {
        wireMockServer.stop();
    }

}
