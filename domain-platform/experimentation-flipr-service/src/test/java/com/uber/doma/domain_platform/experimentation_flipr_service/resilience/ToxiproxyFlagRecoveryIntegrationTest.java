package com.uber.doma.domain_platform.experimentation_flipr_service.resilience;

import eu.rekawek.toxiproxy.model.Toxic;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToxiproxyFlagRecoveryIntegrationTest {

    private static final Network NETWORK = Network.newNetwork();
    private GenericContainer<?> upstream;
    private ToxiproxyContainer toxiproxy;
    private ToxiproxyContainer.ContainerProxy proxy;

    @BeforeAll
    void startInfrastructure() {
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            return;
        }
        upstream = new GenericContainer<>(DockerImageName.parse("alpine:3.20"))
                .withNetwork(NETWORK)
                .withCommand("sh", "-c",
                        "while true; do printf 'treatment\\n' | nc -l -p 9099; done")
                .withExposedPorts(9099)
                .waitingFor(Wait.forListeningPort())
                .withStartupTimeout(Duration.ofMinutes(2));
        upstream.start();
        toxiproxy = new ToxiproxyContainer(
                DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.9.0"))
                .withNetwork(NETWORK);
        toxiproxy.start();
        proxy = toxiproxy.getProxy(upstream, 9099);
    }

    @AfterAll
    void stopInfrastructure() {
        if (toxiproxy != null) {
            toxiproxy.stop();
        }
        if (upstream != null) {
            upstream.stop();
        }
        NETWORK.close();
    }

    @Test
    void latencyPacketLossAndRecoveryPreserveFlagEvaluation() throws IOException {
        assumeTrue(proxy != null, "Docker unavailable; skipping Toxiproxy chaos test");
        final ResilientFlagEvaluator evaluator = new ResilientFlagEvaluator(
                new TcpFlagUpstream(proxy.getContainerIpAddress(), proxy.getProxyPort(), 2_000),
                Map.of("booking-flow", "control"));

        proxy.toxics().latency("latency-500", ToxicDirection.DOWNSTREAM, 500);
        final long started = System.nanoTime();
        final FlagDecision delayed = evaluator.evaluate("booking-flow", "rider-42");
        final long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        assertEquals(FlagDecision.Source.UPSTREAM, delayed.source());
        assertTrue(elapsedMillis >= 400, "500ms toxic should add observable latency");

        clearToxics();
        proxy.toxics().resetPeer("drop-20-percent", ToxicDirection.DOWNSTREAM, 0)
                .setToxicity(0.20f);
        for (int i = 0; i < 50; i++) {
            final int subject = i;
            final FlagDecision decision = assertDoesNotThrow(
                    () -> evaluator.evaluate("booking-flow", "rider-" + subject));
            assertTrue(decision.variant().equals("treatment")
                    || decision.variant().equals("control"));
        }

        clearToxics();
        proxy.toxics().resetPeer("partition", ToxicDirection.DOWNSTREAM, 0);
        final FlagDecision fallback = assertDoesNotThrow(
                () -> evaluator.evaluate("booking-flow", "rider-42"));
        assertEquals(FlagDecision.Source.LAST_KNOWN, fallback.source());

        clearToxics();
        final FlagDecision recovered = evaluator.evaluate("booking-flow", "rider-42");
        assertEquals(FlagDecision.Source.UPSTREAM, recovered.source());
        assertEquals("treatment", recovered.variant());
    }

    private void clearToxics() throws IOException {
        for (final Toxic toxic : proxy.toxics().getAll()) {
            toxic.remove();
        }
    }

    private record TcpFlagUpstream(String host, int port, int timeoutMillis) implements FlagUpstream {
        @Override
        public String evaluate(final String flagKey, final String subjectId) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), timeoutMillis);
                socket.setSoTimeout(timeoutMillis);
                socket.getOutputStream().write(
                        (flagKey + ":" + subjectId + "\n").getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
                final byte[] response = new byte[32];
                final int count = socket.getInputStream().read(response);
                if (count < 1) {
                    throw new IOException("upstream closed without a response");
                }
                return new String(response, 0, count, StandardCharsets.UTF_8).trim();
            } catch (final IOException failure) {
                throw new IllegalStateException("flag upstream unavailable", failure);
            }
        }
    }
}
