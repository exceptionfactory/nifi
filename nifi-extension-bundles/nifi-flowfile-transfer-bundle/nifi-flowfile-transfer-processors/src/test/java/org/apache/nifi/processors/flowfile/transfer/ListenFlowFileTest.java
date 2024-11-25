package org.apache.nifi.processors.flowfile.transfer;

import org.apache.nifi.provenance.ProvenanceEventRecord;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.security.cert.builder.StandardCertificateBuilder;
import org.apache.nifi.security.ssl.StandardSslContextBuilder;
import org.apache.nifi.security.ssl.StandardTrustManagerBuilder;
import org.apache.nifi.security.util.ClientAuth;
import org.apache.nifi.security.util.TlsPlatform;
import org.apache.nifi.ssl.SSLContextService;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.apache.nifi.web.client.StandardWebClientService;
import org.apache.nifi.web.client.api.HttpResponseEntity;
import org.apache.nifi.web.client.api.HttpResponseStatus;
import org.apache.nifi.web.client.ssl.TlsContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

@Timeout(15)
@ExtendWith(MockitoExtension.class)
class ListenFlowFileTest {
    private static final String KEY_ALGORITHM = "RSA";

    private static final String KEY_ALIAS = "localhost";

    private static final X500Principal CERTIFICATE_ISSUER = new X500Principal("CN=localhost");

    private static final String LOCALHOST = "127.0.0.1";

    private static final String RANDOM_PORT = "0";

    private static final String HTTP_URL_FORMAT = "https://localhost:%d%s";

    private static final String ROOT_PATH = "/";

    private static final String FLOW_FILES_PATH = "/flow-files";

    private static final String SERVICE_ID = SSLContextService.class.getSimpleName();

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private static final long RUN_PERIOD = 500;

    private static SSLContext sslContext;

    private static X509TrustManager trustManager;

    private static X509KeyManager keyManager;

    private static StandardWebClientService webClientService;

    @Mock
    private SSLContextService sslContextService;

    private TestRunner runner;

    private ListenFlowFile processor;

    @BeforeAll
    static void setSslContext() throws GeneralSecurityException, IOException {
        final KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
        final KeyPair keyPair = keyPairGenerator.generateKeyPair();
        final X509Certificate certificate = new StandardCertificateBuilder(keyPair, CERTIFICATE_ISSUER, Duration.ofDays(1)).build();

        final KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null);

        trustStore.setCertificateEntry(KEY_ALIAS, certificate);
        trustManager = new StandardTrustManagerBuilder().trustStore(trustStore).build();

        final char[] generated = UUID.randomUUID().toString().toCharArray();
        final KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null);
        keyStore.setKeyEntry(KEY_ALIAS, keyPair.getPrivate(), generated, new Certificate[]{certificate});

        final KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, generated);
        final KeyManager[] keyManagers = keyManagerFactory.getKeyManagers();
        final Optional<KeyManager> firstKeyManager = Arrays.stream(keyManagers).findFirst();
        final KeyManager configuredKeyManager = firstKeyManager.orElse(null);
        keyManager = configuredKeyManager instanceof X509KeyManager ? (X509KeyManager) configuredKeyManager : null;

        sslContext = new StandardSslContextBuilder()
                .keyStore(keyStore)
                .keyPassword(generated)
                .trustStore(trustStore)
                .build();

        webClientService = new StandardWebClientService();
        webClientService.setReadTimeout(READ_TIMEOUT);
        webClientService.setConnectTimeout(CONNECT_TIMEOUT);
        webClientService.setWriteTimeout(READ_TIMEOUT);

        webClientService.setTlsContext(new TlsContext() {
            @Override
            public String getProtocol() {
                return TlsPlatform.getLatestProtocol();
            }

            @Override
            public X509TrustManager getTrustManager() {
                return trustManager;
            }

            @Override
            public Optional<X509KeyManager> getKeyManager() {
                return Optional.ofNullable(keyManager);
            }
        });
    }

    @AfterAll
    static void closeWebClientService() {
        webClientService.close();
    }

    @BeforeEach
    void setRunner() {
        processor = new ListenFlowFile();
        runner = TestRunners.newTestRunner(processor);
    }

    @AfterEach
    void stopRunner() {
        runner.stop();
    }

    @Test
    void testGetMethodNotAllowed() throws Exception {
        startServer();
        final URI uri = getUri(ROOT_PATH);

        try (HttpResponseEntity httpResponseEntity = webClientService.get()
                .uri(uri)
                .retrieve()
        ) {
            assertEquals(HttpResponseStatus.METHOD_NOT_ALLOWED.getCode(), httpResponseEntity.statusCode());
        }
    }

    @Test
    void testPostMethodEmptyBody() throws Exception {
        startServer();
        final URI uri = getUri(ROOT_PATH);

        try (HttpResponseEntity httpResponseEntity = webClientService.post()
                .uri(uri)
                .retrieve()
        ) {
            assertEquals(HttpResponseStatus.BAD_REQUEST.getCode(), httpResponseEntity.statusCode());
        }
    }

    @Test
    void testPostMethodBody() throws Exception {
        startServer();
        final URI uri = getUri(FLOW_FILES_PATH);

        final byte[] bytes = new byte[65536];
        final InputStream inputStream = new ByteArrayInputStream(bytes);

        final CountDownLatch countDownLatch = new CountDownLatch(1);
        startSending(countDownLatch, uri, inputStream);
        startProcessing(countDownLatch);

        final List<MockFlowFile> flowFiles = runner.getFlowFilesForRelationship(ListenFlowFile.SUCCESS);
        assertFalse(flowFiles.isEmpty());

        final MockFlowFile flowFile = flowFiles.getFirst();
        flowFile.assertContentEquals(bytes);

        assertProvenanceRecordFound(uri);
    }

    private void startServer() throws InitializationException {
        runner.setProperty(ListenFlowFile.ADDRESS, LOCALHOST);
        runner.setProperty(ListenFlowFile.PORT, RANDOM_PORT);
        setSslContextService();
        when(sslContextService.createContext()).thenReturn(sslContext);

        runner.run(1, false, true);
    }

    private void startSending(final CountDownLatch countDownLatch, final URI uri, final InputStream inputStream) throws InterruptedException {
        Thread.ofVirtual().start(() -> {
            try (HttpResponseEntity httpResponseEntity = webClientService.post()
                    .uri(uri)
                    .body(inputStream, OptionalLong.empty())
                    .retrieve()
            ) {
                assertEquals(HttpResponseStatus.OK.getCode(), httpResponseEntity.statusCode());
                countDownLatch.countDown();
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private void startProcessing(final CountDownLatch countDownLatch) throws InterruptedException {
        try (ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor()) {
            final Runnable command = () -> runner.run(1, false, false);
            scheduledExecutorService.scheduleAtFixedRate(command, RUN_PERIOD, RUN_PERIOD, TimeUnit.MILLISECONDS);
            countDownLatch.await();
        }
    }

    private void setSslContextService() throws InitializationException {
        when(sslContextService.getIdentifier()).thenReturn(SERVICE_ID);

        runner.addControllerService(SERVICE_ID, sslContextService);
        runner.enableControllerService(sslContextService);

        runner.setProperty(ListenFlowFile.SSL_CONTEXT_SERVICE, SERVICE_ID);
        runner.setProperty(ListenFlowFile.CLIENT_AUTHENTICATION, ClientAuth.WANT.name());
    }

    private URI getUri(final String contextPath) {
        final int httpPort = processor.getPort();

        final String httpUrl = String.format(HTTP_URL_FORMAT, httpPort, contextPath);
        return URI.create(httpUrl);
    }

    private void assertProvenanceRecordFound(final URI transitUri) {
        final List<ProvenanceEventRecord> provenanceEventRecords = runner.getProvenanceEvents();
        assertFalse(provenanceEventRecords.isEmpty());

        final ProvenanceEventRecord provenanceEventRecord = provenanceEventRecords.getFirst();
        assertEquals(transitUri.toString(), provenanceEventRecord.getTransitUri());
    }
}
