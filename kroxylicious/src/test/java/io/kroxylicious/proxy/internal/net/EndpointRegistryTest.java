/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.converter.ConvertWith;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

import io.kroxylicious.proxy.HostPortConverter;
import io.kroxylicious.proxy.config.TargetCluster;
import io.kroxylicious.proxy.model.VirtualCluster;
import io.kroxylicious.proxy.service.HostPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EndpointRegistryTest {
    private static final HostPort DOWNSTREAM_BOOTSTRAP = HostPort.parse("downstream-bootstrap:9192");
    private static final HostPort DOWNSTREAM_BOOTSTRAP_DIFF_SNI = new HostPort(DOWNSTREAM_BOOTSTRAP.host() + ".diff.sni", DOWNSTREAM_BOOTSTRAP.port());
    private static final HostPort DOWNSTREAM_BOOTSTRAP_DIFF_PORT = new HostPort(DOWNSTREAM_BOOTSTRAP.host(), DOWNSTREAM_BOOTSTRAP.port() + 1);
    private static final HostPort DOWNSTREAM_BROKER_0 = HostPort.parse("downstream-broker0:9193");
    private static final HostPort DOWNSTREAM_BROKER_1 = HostPort.parse("downstream-broker1:9194");
    private static final HostPort UPSTREAM_BOOTSTRAP = HostPort.parse("upstream-bootstrap:19192");
    private static final HostPort UPSTREAM_BROKER_0 = HostPort.parse("upstream-broker0:19193");
    private static final HostPort UPSTREAM_BROKER_1 = HostPort.parse("upstream-broker1:19194");
    private final TestNetworkBindingOperationProcessor bindingOperationProcessor = new TestNetworkBindingOperationProcessor();
    private final EndpointRegistry endpointRegistry = new EndpointRegistry(bindingOperationProcessor);
    @Mock(strictness = LENIENT)
    private VirtualCluster virtualCluster1;
    @Mock(strictness = LENIENT)
    private VirtualCluster virtualCluster2;

    @Test
    public void registerVirtualCluster() throws Exception {
        configureVirtualClusterMock(virtualCluster1, DOWNSTREAM_BOOTSTRAP, UPSTREAM_BOOTSTRAP, false);

        var rf = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(DOWNSTREAM_BOOTSTRAP.port(), false));
        verifyVirtualClusterRegisterFuture(DOWNSTREAM_BOOTSTRAP.port(), false, rf);

        assertThat(endpointRegistry.isRegistered(virtualCluster1)).isTrue();
        assertThat(endpointRegistry.listeningChannelCount()).isEqualTo(1);
    }

    @Test
    public void registerVirtualClusterTls() throws Exception {
        configureVirtualClusterMock(virtualCluster1, DOWNSTREAM_BOOTSTRAP, UPSTREAM_BOOTSTRAP, true);

        var rf = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(DOWNSTREAM_BOOTSTRAP.port(), true));
        verifyVirtualClusterRegisterFuture(DOWNSTREAM_BOOTSTRAP.port(), true, rf);
    }

    @Test
    public void registerSameVirtualClusterIsIdempotent() throws Exception {
        configureVirtualClusterMock(virtualCluster1, DOWNSTREAM_BOOTSTRAP, UPSTREAM_BOOTSTRAP, false);

        var rf1 = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        var rf2 = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(DOWNSTREAM_BOOTSTRAP.port(), false));
        verifyVirtualClusterRegisterFuture(DOWNSTREAM_BOOTSTRAP.port(), false, rf1);
        verifyVirtualClusterRegisterFuture(DOWNSTREAM_BOOTSTRAP.port(), false, rf2);

        assertThat(endpointRegistry.listeningChannelCount()).isEqualTo(1);
    }

    @Test
    public void registerTwoClustersThatShareSameNetworkEndpoint() throws Exception {
        // Same port..different SNI
        configureVirtualClusterMock(virtualCluster1, DOWNSTREAM_BOOTSTRAP, UPSTREAM_BOOTSTRAP, true);
        configureVirtualClusterMock(virtualCluster2, DOWNSTREAM_BOOTSTRAP_DIFF_SNI, UPSTREAM_BOOTSTRAP, true);

        var rf1 = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        var rf2 = endpointRegistry.registerVirtualCluster(virtualCluster2).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(DOWNSTREAM_BOOTSTRAP.port(), true));
        verifyVirtualClusterRegisterFuture(DOWNSTREAM_BOOTSTRAP.port(), true, rf1);
        verifyVirtualClusterRegisterFuture(DOWNSTREAM_BOOTSTRAP.port(), true, rf2);

        assertThat(endpointRegistry.listeningChannelCount()).isEqualTo(1);
    }

    @Test
    public void registerTwoClustersThatUsesDistinctNetworkEndpoints() throws Exception {
        configureVirtualClusterMock(virtualCluster1, DOWNSTREAM_BOOTSTRAP, UPSTREAM_BOOTSTRAP, false);
        configureVirtualClusterMock(virtualCluster2, DOWNSTREAM_BOOTSTRAP_DIFF_PORT, UPSTREAM_BOOTSTRAP, false);

        var rf1 = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        var rf2 = endpointRegistry.registerVirtualCluster(virtualCluster2).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(DOWNSTREAM_BOOTSTRAP.port(), false),
                createTestNetworkBindRequest(DOWNSTREAM_BOOTSTRAP_DIFF_PORT.port(), false));
        verifyVirtualClusterRegisterFuture(DOWNSTREAM_BOOTSTRAP.port(), false, rf1);
        verifyVirtualClusterRegisterFuture(DOWNSTREAM_BOOTSTRAP_DIFF_PORT.port(), false, rf2);

        assertThat(endpointRegistry.listeningChannelCount()).isEqualTo(2);
    }

    @Test
    public void registerRejectsDuplicatedBinding() throws Exception {
        configureVirtualClusterMock(virtualCluster1, DOWNSTREAM_BOOTSTRAP, UPSTREAM_BOOTSTRAP, false);
        configureVirtualClusterMock(virtualCluster2, DOWNSTREAM_BOOTSTRAP, UPSTREAM_BOOTSTRAP, false);

        var rf1 = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(DOWNSTREAM_BOOTSTRAP.port(), false));
        verifyVirtualClusterRegisterFuture(DOWNSTREAM_BOOTSTRAP.port(), false, rf1);
        assertThat(endpointRegistry.listeningChannelCount()).isEqualTo(1);

        verifyAndProcessNetworkEventQueue();
        var executionException = assertThrows(ExecutionException.class,
                () -> endpointRegistry.registerVirtualCluster(virtualCluster2).toCompletableFuture().get());
        assertThat(executionException).hasCauseInstanceOf(EndpointBindingException.class);

        assertThat(endpointRegistry.isRegistered(virtualCluster1)).isTrue();
        assertThat(endpointRegistry.isRegistered(virtualCluster2)).isFalse();
        assertThat(endpointRegistry.listeningChannelCount()).isEqualTo(1);
    }

    @Test
    public void registerVirtualClusterFailsDueToExternalPortConflict() throws Exception {
        configureVirtualClusterMock(virtualCluster1, DOWNSTREAM_BOOTSTRAP, UPSTREAM_BOOTSTRAP, false);

        var rf = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(
                createTestNetworkBindRequest(Optional.empty(), DOWNSTREAM_BOOTSTRAP.port(), false,
                        CompletableFuture.failedFuture(new IOException("mocked port in use"))));
        assertThat(rf.isDone()).isTrue();
        var executionException = assertThrows(ExecutionException.class, rf::get);
        assertThat(executionException).hasRootCauseInstanceOf(IOException.class);

        assertThat(endpointRegistry.isRegistered(virtualCluster1)).isFalse();
        assertThat(endpointRegistry.listeningChannelCount()).isEqualTo(0);
    }

    @Test
    public void deregisterVirtualCluster() throws Exception {
        configureVirtualClusterMock(virtualCluster1, DOWNSTREAM_BOOTSTRAP, UPSTREAM_BOOTSTRAP, true);

        var rf = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(DOWNSTREAM_BOOTSTRAP.port(), true));
        assertThat(rf.isDone()).isTrue();

        assertThat(endpointRegistry.isRegistered(virtualCluster1)).isTrue();
        assertThat(endpointRegistry.listeningChannelCount()).isEqualTo(1);

        var df = endpointRegistry.deregisterVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkUnbindRequest(DOWNSTREAM_BOOTSTRAP.port(), true));
        assertThat(df.isDone()).isTrue();

        assertThat(endpointRegistry.isRegistered(virtualCluster1)).isFalse();
        assertThat(endpointRegistry.listeningChannelCount()).isEqualTo(0);
    }

    @Test
    public void deregisterSameVirtualClusterIsIdempotent() throws Exception {
        configureVirtualClusterMock(virtualCluster1, DOWNSTREAM_BOOTSTRAP, UPSTREAM_BOOTSTRAP, true);

        var rf = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(DOWNSTREAM_BOOTSTRAP.port(), true));
        verifyVirtualClusterRegisterFuture(DOWNSTREAM_BOOTSTRAP.port(), true, rf);

        var df1 = endpointRegistry.deregisterVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkUnbindRequest(DOWNSTREAM_BOOTSTRAP.port(), true));
        assertThat(df1.isDone()).isTrue();

        var df2 = endpointRegistry.deregisterVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue();
        assertThat(df2.isDone()).isTrue();
    }

    @Test
    public void deregisterClusterThatSharesEndpoint() throws Exception {
        configureVirtualClusterMock(virtualCluster1, DOWNSTREAM_BOOTSTRAP, UPSTREAM_BOOTSTRAP, true);
        configureVirtualClusterMock(virtualCluster2, DOWNSTREAM_BOOTSTRAP_DIFF_SNI, UPSTREAM_BOOTSTRAP, true);

        var rf1 = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        var rf2 = endpointRegistry.registerVirtualCluster(virtualCluster2).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(DOWNSTREAM_BOOTSTRAP.port(), true));
        verifyVirtualClusterRegisterFuture(DOWNSTREAM_BOOTSTRAP.port(), true, rf1);
        verifyVirtualClusterRegisterFuture(DOWNSTREAM_BOOTSTRAP_DIFF_SNI.port(), true, rf2);

        var df1 = endpointRegistry.deregisterVirtualCluster(virtualCluster1).toCompletableFuture();
        // Port 9192 is shared by the second virtualcluster, so it can't be unbound yet
        verifyAndProcessNetworkEventQueue();
        assertThat(df1.isDone()).isTrue();

        var df2 = endpointRegistry.deregisterVirtualCluster(virtualCluster2).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkUnbindRequest(DOWNSTREAM_BOOTSTRAP.port(), true));
        assertThat(df2.isDone()).isTrue();
    }

    @Test
    public void reregisterClusterWhilstDeregisterIsInProgress() throws Exception {
        configureVirtualClusterMock(virtualCluster1, DOWNSTREAM_BOOTSTRAP, UPSTREAM_BOOTSTRAP, true);

        var rf1 = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(DOWNSTREAM_BOOTSTRAP.port(), true));
        assertThat(rf1.isDone()).isTrue();

        var df1 = endpointRegistry.deregisterVirtualCluster(virtualCluster1).toCompletableFuture();
        // The de-registration for cluster1 is queued up so the future won't be completed.
        assertThat(df1.isDone()).isFalse();

        var rereg = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        assertThat(rereg.isDone()).isFalse();

        // we expect an unbind for 9192
        verifyAndProcessNetworkEventQueue(createTestNetworkUnbindRequest(DOWNSTREAM_BOOTSTRAP.port(), true));
        assertThat(df1.isDone()).isTrue();

        // followed by an immediate rebind of the same port.
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(DOWNSTREAM_BOOTSTRAP.port(), true));

        verifyVirtualClusterRegisterFuture(DOWNSTREAM_BOOTSTRAP.port(), true, rereg);
        assertThat(endpointRegistry.listeningChannelCount()).isEqualTo(1);
    }

    @Test
    public void registerClusterWhileAnotherIsDeregistering() throws Exception {
        configureVirtualClusterMock(virtualCluster1, DOWNSTREAM_BOOTSTRAP, UPSTREAM_BOOTSTRAP, true);
        configureVirtualClusterMock(virtualCluster2, DOWNSTREAM_BOOTSTRAP_DIFF_SNI, UPSTREAM_BOOTSTRAP, true);

        var rf1 = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(DOWNSTREAM_BOOTSTRAP.port(), true));
        assertThat(rf1.isDone()).isTrue();

        var df1 = endpointRegistry.deregisterVirtualCluster(virtualCluster1).toCompletableFuture();
        // The de-registration for cluster1 is queued up so the future won't be completed.
        assertThat(df1.isDone()).isFalse();

        var rf2 = endpointRegistry.registerVirtualCluster(virtualCluster2).toCompletableFuture();
        assertThat(rf2.isDone()).isFalse();

        // we expect an unbind for 9192 followed by an immediate rebind of the same port.
        verifyAndProcessNetworkEventQueue(createTestNetworkUnbindRequest(DOWNSTREAM_BOOTSTRAP.port(), true));
        assertThat(df1.isDone()).isTrue();

        // followed by an immediate rebind of the same port.
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(DOWNSTREAM_BOOTSTRAP.port(), true));

        verifyVirtualClusterRegisterFuture(DOWNSTREAM_BOOTSTRAP.port(), true, rf2);
        assertThat(endpointRegistry.listeningChannelCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({ "mycluster1:9192,upstream1:9192,true,true", "mycluster1:9192,upstream1:9192,true,false", "localhost:9192,upstream1:9192,false,false" })
    public void resolveBootstrap(@ConvertWith(HostPortConverter.class) HostPort downstreamBootstrap, @ConvertWith(HostPortConverter.class) HostPort upstreamBootstrap,
                                 boolean tls, boolean sni)
            throws Exception {
        configureVirtualClusterMock(virtualCluster1, HostPort.parse(downstreamBootstrap.toString()), HostPort.parse(upstreamBootstrap.toString()), tls, sni);

        var f = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(downstreamBootstrap.port(), tls));
        assertThat(f.isDone()).isTrue();

        var endpoint = Endpoint.createEndpoint(downstreamBootstrap.port(), tls);
        var binding = endpointRegistry.resolve(endpoint, tls ? downstreamBootstrap.host() : null).toCompletableFuture().get();
        assertThat(binding).isEqualTo(new VirtualClusterBootstrapBinding(virtualCluster1, upstreamBootstrap));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({ "mismatching host,mycluster1:9192,upstream1:9192,mycluster2:9192", "mistmatching port,mycluster1:9192,upstream1:9192,mycluster1:9191" })
    public void resolveBootstrapResolutionFailures(String name,
                                                   @ConvertWith(HostPortConverter.class) HostPort downstreamBootstrap,
                                                   @ConvertWith(HostPortConverter.class) HostPort upstreamBootstrap,
                                                   @ConvertWith(HostPortConverter.class) HostPort resolveAddress)
            throws Exception {
        configureVirtualClusterMock(virtualCluster1, HostPort.parse(downstreamBootstrap.toString()), HostPort.parse(upstreamBootstrap.toString()), true);

        var f = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(downstreamBootstrap.port(), true));
        assertThat(f.isDone()).isTrue();

        var executionException = assertThrows(ExecutionException.class,
                () -> endpointRegistry.resolve(Endpoint.createEndpoint(resolveAddress.port(), true), resolveAddress.host()).toCompletableFuture().get());
        assertThat(executionException).hasCauseInstanceOf(EndpointResolutionException.class);
    }

    @ParameterizedTest
    @CsvSource({ "mycluster1:9192,upstream1:9192,MyClUsTeR1:9192",
            "69.2.0.192.in-addr.arpa:9192,upstream1:9192,69.2.0.192.in-ADDR.ARPA:9192" })
    public void resolveRespectsCaseInsensitivityRfc4343(@ConvertWith(HostPortConverter.class) HostPort downstreamBootstrap,
                                                        @ConvertWith(HostPortConverter.class) HostPort upstreamBootstrap,
                                                        @ConvertWith(HostPortConverter.class) HostPort resolveAddress)
            throws Exception {
        configureVirtualClusterMock(virtualCluster1, HostPort.parse(downstreamBootstrap.toString()), HostPort.parse(upstreamBootstrap.toString()), true);

        var f = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(downstreamBootstrap.port(), true));
        assertThat(f.isDone()).isTrue();

        var binding = endpointRegistry.resolve(Endpoint.createEndpoint(resolveAddress.port(), true), resolveAddress.host()).toCompletableFuture().get();
        assertThat(binding).isEqualTo(new VirtualClusterBootstrapBinding(virtualCluster1, upstreamBootstrap));
    }

    @Test
    public void bindingAddressEndpointSeparation() throws Exception {
        var bindingAddress1 = Optional.of("127.0.0.1");
        configureVirtualClusterMock(virtualCluster1, HostPort.parse("localhost:9192"), HostPort.parse("upstream1:9192"), false);
        when(virtualCluster1.getBindAddress()).thenReturn(bindingAddress1);

        var bindingAddress2 = Optional.of("192.168.0.1");
        configureVirtualClusterMock(virtualCluster2, HostPort.parse("myhost:9192"), HostPort.parse("upstream2:9192"), false);
        when(virtualCluster2.getBindAddress()).thenReturn(bindingAddress2);

        var rf1 = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        var rf2 = endpointRegistry.registerVirtualCluster(virtualCluster2).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(bindingAddress1, 9192, false),
                createTestNetworkBindRequest(bindingAddress2, 9192, false));
        assertThat(CompletableFuture.allOf(rf1, rf2).isDone()).isTrue();

        var rsf1 = endpointRegistry.resolve(Endpoint.createEndpoint(bindingAddress1, 9192, false), null).toCompletableFuture().get();
        assertThat(rsf1).isNotNull();
        assertThat(rsf1.virtualCluster()).isEqualTo(virtualCluster1);

        var rsf2 = endpointRegistry.resolve(Endpoint.createEndpoint(bindingAddress2, 9192, false), null).toCompletableFuture().get();
        assertThat(rsf2).isNotNull();
        assertThat(rsf2.virtualCluster()).isEqualTo(virtualCluster2);

        var executionException = assertThrows(ExecutionException.class,
                () -> endpointRegistry.resolve(Endpoint.createEndpoint(9192, false), null).toCompletableFuture().get());
        assertThat(executionException).hasCauseInstanceOf(EndpointResolutionException.class);
    }

    @Test
    public void reconcileAddsNewBrokerEndpoint() throws Exception {
        configureVirtualClusterMock(virtualCluster1, DOWNSTREAM_BOOTSTRAP, UPSTREAM_BOOTSTRAP, false);

        var regf = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(DOWNSTREAM_BOOTSTRAP.port(), false));
        assertThat(regf.isDone()).isTrue();
        assertThat(endpointRegistry.listeningChannelCount()).isEqualTo(1);

        // Add a new node (1) to the cluster
        when(virtualCluster1.getBrokerAddress(0)).thenReturn(DOWNSTREAM_BROKER_0);

        var recf = endpointRegistry.reconcile(virtualCluster1, Map.of(0, UPSTREAM_BROKER_0), false).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(DOWNSTREAM_BROKER_0.port(), false));
        assertThat(recf.isDone()).isTrue();
        assertThat(recf.get()).isNull();
        assertThat(endpointRegistry.listeningChannelCount()).isEqualTo(2);
    }

    @Test
    public void resolveReconciledBrokerAddress() throws Exception {
        configureVirtualClusterMock(virtualCluster1, DOWNSTREAM_BOOTSTRAP, UPSTREAM_BOOTSTRAP, false);

        var regf = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(DOWNSTREAM_BOOTSTRAP.port(), false));
        assertThat(regf.isDone()).isTrue();
        assertThat(endpointRegistry.listeningChannelCount()).isEqualTo(1);

        // Add a new node (1) to the cluster
        when(virtualCluster1.getBrokerAddress(0)).thenReturn(DOWNSTREAM_BROKER_0);

        var recf = endpointRegistry.reconcile(virtualCluster1, Map.of(0, UPSTREAM_BROKER_0), false).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(DOWNSTREAM_BROKER_0.port(), false));
        assertThat(recf.isDone()).isTrue();
        assertThat(recf.get()).isNull();
        assertThat(endpointRegistry.listeningChannelCount()).isEqualTo(2);

        var binding = endpointRegistry.resolve(Endpoint.createEndpoint(DOWNSTREAM_BROKER_0.port(), false), null).toCompletableFuture().get();
        assertThat(binding).isEqualTo(new VirtualClusterBrokerBinding(virtualCluster1, UPSTREAM_BROKER_0, 0, false));
    }

    @Test
    public void reconcileRemovesBrokerEndpoint() throws Exception {
        configureVirtualClusterMock(virtualCluster1, DOWNSTREAM_BOOTSTRAP, UPSTREAM_BOOTSTRAP, false);

        var regf = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(DOWNSTREAM_BOOTSTRAP.port(), false));
        assertThat(regf.isDone()).isTrue();

        // Add brokers (0,1) to the cluster
        when(virtualCluster1.getBrokerAddress(0)).thenReturn(DOWNSTREAM_BROKER_0);

        var recf1 = endpointRegistry.reconcile(virtualCluster1, Map.of(0, UPSTREAM_BROKER_0), false).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(DOWNSTREAM_BROKER_0.port(), false));
        assertThat(recf1.isDone()).isTrue();

        when(virtualCluster1.getBrokerAddress(1)).thenReturn(DOWNSTREAM_BROKER_1);
        var recf2 = endpointRegistry.reconcile(virtualCluster1, Map.of(0, UPSTREAM_BROKER_0, 1, UPSTREAM_BROKER_1), false).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(DOWNSTREAM_BROKER_1.port(), false));
        assertThat(recf2.isDone()).isTrue();
        assertThat(endpointRegistry.listeningChannelCount()).isEqualTo(3);

        // Removal of node (0) from the cluster
        var recf3 = endpointRegistry.reconcile(virtualCluster1, Map.of(1, UPSTREAM_BROKER_1), false).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkUnbindRequest(DOWNSTREAM_BROKER_0.port(), false));
        assertThat(recf3.isDone()).isTrue();
        assertThat(recf3.get()).isNull();
        assertThat(endpointRegistry.listeningChannelCount()).isEqualTo(2);
    }

    @Test
    public void reconcileChangesTargetClusterBrokerAddress() throws Exception {
        var upstreamBrokerUpdated0 = HostPort.parse("upstreamupd:29193");

        configureVirtualClusterMock(virtualCluster1, DOWNSTREAM_BOOTSTRAP, UPSTREAM_BOOTSTRAP, false);

        var regf = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(DOWNSTREAM_BOOTSTRAP.port(), false));
        assertThat(regf.isDone()).isTrue();

        when(virtualCluster1.getBrokerAddress(0)).thenReturn(DOWNSTREAM_BROKER_0);
        var recf1 = endpointRegistry.reconcile(virtualCluster1, Map.of(0, UPSTREAM_BROKER_0), false).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(DOWNSTREAM_BROKER_0.port(), false));
        assertThat(recf1.isDone()).isTrue();
        assertThat(endpointRegistry.listeningChannelCount()).isEqualTo(2);

        var resolvedBindingBeforeChange = endpointRegistry.resolve(Endpoint.createEndpoint(DOWNSTREAM_BROKER_0.port(), false), null).toCompletableFuture().get();
        assertThat(resolvedBindingBeforeChange).isEqualTo(new VirtualClusterBrokerBinding(virtualCluster1, UPSTREAM_BROKER_0, 0, false));

        // Target cluster updates the address for broker 0
        var recf2 = endpointRegistry.reconcile(virtualCluster1, Map.of(0, upstreamBrokerUpdated0), false).toCompletableFuture();
        verifyAndProcessNetworkEventQueue();
        assertThat(recf2.isDone()).isTrue();
        assertThat(recf2.get()).isNull();
        assertThat(endpointRegistry.listeningChannelCount()).isEqualTo(2);

        var resolvedBindingAfterChange = endpointRegistry.resolve(Endpoint.createEndpoint(DOWNSTREAM_BROKER_0.port(), false), null).toCompletableFuture().get();
        assertThat(resolvedBindingAfterChange).isEqualTo(new VirtualClusterBrokerBinding(virtualCluster1, upstreamBrokerUpdated0, 0, false));
    }

    @Test
    public void reconcileNoOp() throws Exception {
        configureVirtualClusterMock(virtualCluster1, DOWNSTREAM_BOOTSTRAP, UPSTREAM_BOOTSTRAP, false);

        var regf = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(DOWNSTREAM_BOOTSTRAP.port(), false));
        assertThat(regf.isDone()).isTrue();

        // Add broker to the cluster
        when(virtualCluster1.getBrokerAddress(0)).thenReturn(DOWNSTREAM_BROKER_0);
        var recf1 = endpointRegistry.reconcile(virtualCluster1, Map.of(0, UPSTREAM_BROKER_0), false).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(DOWNSTREAM_BROKER_0.port(), false));
        assertThat(recf1.isDone()).isTrue();

        // Add 2nd broker to the cluster
        when(virtualCluster1.getBrokerAddress(1)).thenReturn(DOWNSTREAM_BROKER_1);
        var recf2 = endpointRegistry.reconcile(virtualCluster1, Map.of(0, UPSTREAM_BROKER_0, 1, UPSTREAM_BROKER_1), false).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(DOWNSTREAM_BROKER_1.port(), false));
        assertThat(recf2.isDone()).isTrue();
        assertThat(endpointRegistry.listeningChannelCount()).isEqualTo(3);

        var rcf3 = endpointRegistry.reconcile(virtualCluster1, Map.of(0, UPSTREAM_BROKER_0, 1, UPSTREAM_BROKER_1), false).toCompletableFuture();
        assertThat(rcf3.isDone()).isTrue();
        assertThat(rcf3.get()).isNull();
        assertThat(endpointRegistry.listeningChannelCount()).isEqualTo(3);
    }

    @Test
    public void reconcileDeleteWhilstPreviousAddInFlight() throws Exception {
        configureVirtualClusterMock(virtualCluster1, DOWNSTREAM_BOOTSTRAP, UPSTREAM_BOOTSTRAP, false);
        when(virtualCluster1.getBrokerAddress(0)).thenReturn(DOWNSTREAM_BROKER_0);
        when(virtualCluster1.getBrokerAddress(1)).thenReturn(DOWNSTREAM_BROKER_1);

        var regf = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(DOWNSTREAM_BOOTSTRAP.port(), false));
        assertThat(regf.isDone()).isTrue();
        assertThat(endpointRegistry.listeningChannelCount()).isEqualTo(1);

        // reconcile adds a node
        when(virtualCluster1.getBrokerAddress(0)).thenReturn(DOWNSTREAM_BROKER_0);
        var add1 = endpointRegistry.reconcile(virtualCluster1, Map.of(0, UPSTREAM_BROKER_0), false).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(DOWNSTREAM_BROKER_0.port(), false));
        assertThat(add1.isDone()).isTrue();
        assertThat(endpointRegistry.listeningChannelCount()).isEqualTo(2);

        // reconcile adds a node, but organise so the network event is not processed yet so the future won't complete
        when(virtualCluster1.getBrokerAddress(1)).thenReturn(DOWNSTREAM_BROKER_1);
        var add2 = endpointRegistry.reconcile(virtualCluster1, Map.of(0, UPSTREAM_BROKER_0, 1, UPSTREAM_BROKER_1), false).toCompletableFuture();
        assertThat(add2.isDone()).isFalse();
        assertThat(endpointRegistry.listeningChannelCount()).isEqualTo(3);

        // reconcile now removes a node, it cannot be processed because it is behind the add
        var remove = endpointRegistry.reconcile(virtualCluster1, Map.of(1, UPSTREAM_BROKER_1), false).toCompletableFuture();
        assertThat(remove.isDone()).isFalse();
        assertThat(endpointRegistry.listeningChannelCount()).isEqualTo(3);

        // process add event
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(DOWNSTREAM_BROKER_1.port(), false));
        assertThat(add2.isDone()).isTrue();
        assertThat(add2.get()).isNull();
        assertThat(endpointRegistry.listeningChannelCount()).isEqualTo(3);
        assertThat(remove.isDone()).isFalse();

        // process remove event
        verifyAndProcessNetworkEventQueue(createTestNetworkUnbindRequest(DOWNSTREAM_BROKER_0.port(), false));
        assertThat(remove.isDone()).isTrue();
        assertThat(remove.get()).isNull();
        assertThat(endpointRegistry.listeningChannelCount()).isEqualTo(2);
    }

    @Test
    public void reconcileFailsDueToExternalPortConflict() {
        doReconcileFailsDueToExternalPortConflict(DOWNSTREAM_BROKER_0, UPSTREAM_BROKER_0);
    }

    private VirtualCluster doReconcileFailsDueToExternalPortConflict(HostPort downstreamBroker0, HostPort upstreamBroker0) {
        configureVirtualClusterMock(virtualCluster1, DOWNSTREAM_BOOTSTRAP, UPSTREAM_BOOTSTRAP, false);

        var rgf = endpointRegistry.registerVirtualCluster(virtualCluster1).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(DOWNSTREAM_BOOTSTRAP.port(), false));
        assertThat(rgf.isDone()).isTrue();
        assertThat(endpointRegistry.listeningChannelCount()).isEqualTo(1);

        // Add a new node (1) to the cluster
        when(virtualCluster1.getBrokerAddress(0)).thenReturn(downstreamBroker0);

        var rcf = endpointRegistry.reconcile(virtualCluster1, Map.of(0, upstreamBroker0), false).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(
                createTestNetworkBindRequest(Optional.empty(), downstreamBroker0.port(), false, CompletableFuture.failedFuture(new IOException("mocked port in use"))));
        assertThat(rcf.isDone()).isTrue();
        var executionException = assertThrows(ExecutionException.class, rcf::get);
        assertThat(executionException).hasRootCauseInstanceOf(IOException.class);
        assertThat(endpointRegistry.listeningChannelCount()).isEqualTo(1);

        return virtualCluster1;
    }

    @Test
    public void nextReconcileSucceedsAfterTransientPortConflict() throws Exception {
        var virtualCluster = doReconcileFailsDueToExternalPortConflict(DOWNSTREAM_BROKER_0, UPSTREAM_BROKER_0);

        var rcf = endpointRegistry.reconcile(virtualCluster, Map.of(0, UPSTREAM_BROKER_0), false).toCompletableFuture();
        verifyAndProcessNetworkEventQueue(createTestNetworkBindRequest(DOWNSTREAM_BROKER_0.port(), false));

        assertThat(rcf.isDone()).isTrue();
        assertThat(rcf.get()).isNull();
        assertThat(endpointRegistry.listeningChannelCount()).isEqualTo(2);
    }

    private Channel createMockNettyChannel(int port) {
        var channel = mock(Channel.class);
        var attr = createTestAttribute(EndpointRegistry.CHANNEL_BINDINGS);
        when(channel.attr(EndpointRegistry.CHANNEL_BINDINGS)).thenReturn(attr);
        var localAddress = InetSocketAddress.createUnresolved("localhost", port); // This is lenient because not all tests exercise the unbind path
        lenient().when(channel.localAddress()).thenReturn(localAddress);
        return channel;
    }

    private NetworkBindRequest createTestNetworkBindRequest(int expectedPort, boolean expectedTls) {
        var channelMock = createMockNettyChannel(expectedPort);
        return createTestNetworkBindRequest(Optional.empty(), expectedPort, expectedTls, CompletableFuture.completedFuture(channelMock));
    }

    private NetworkBindRequest createTestNetworkBindRequest(Optional<String> expectedBindingAddress, int expectedPort, boolean expectedTls) {
        Objects.requireNonNull(expectedBindingAddress);
        var channelMock = createMockNettyChannel(expectedPort);
        return createTestNetworkBindRequest(expectedBindingAddress, expectedPort, expectedTls, CompletableFuture.completedFuture(channelMock));
    }

    private NetworkBindRequest createTestNetworkBindRequest(Optional<String> expectedBindingAddress, int expectedPort, boolean expectedTls,
                                                            CompletableFuture<Channel> channelFuture) {
        return new NetworkBindRequest(channelFuture, Endpoint.createEndpoint(expectedBindingAddress, expectedPort, expectedTls));
    }

    private NetworkUnbindRequest createTestNetworkUnbindRequest(int port, final boolean tls) {
        return createTestNetworkUnbindRequest(port, tls, CompletableFuture.completedFuture(null));
    }

    private NetworkUnbindRequest createTestNetworkUnbindRequest(int port, final boolean tls, final CompletableFuture<Void> future) {
        return new NetworkUnbindRequest(tls, null, future) {
            @Override
            public int port() {
                return port;
            }
        };
    }

    private void configureVirtualClusterMock(VirtualCluster cluster, HostPort downstreamBootstrap, HostPort upstreamBootstrap, boolean tls) {
        configureVirtualClusterMock(cluster, downstreamBootstrap, upstreamBootstrap, tls, tls);
    }

    private void configureVirtualClusterMock(VirtualCluster cluster, HostPort downstreamBootstrap, HostPort upstreamBootstrap, boolean tls, boolean sni) {
        when(cluster.getClusterBootstrapAddress()).thenReturn(downstreamBootstrap);
        when(cluster.isUseTls()).thenReturn(tls);
        when(cluster.requiresTls()).thenReturn(sni);
        when(cluster.targetCluster()).thenReturn(new TargetCluster(upstreamBootstrap.toString(), null));
    }

    private void verifyVirtualClusterRegisterFuture(int expectedPort, boolean expectedTls, CompletableFuture<Endpoint> future) throws Exception {
        assertThat(future.isDone()).isTrue();
        assertThat(future.get()).isEqualTo(Endpoint.createEndpoint(expectedPort, expectedTls));
    }

    private void verifyAndProcessNetworkEventQueue(NetworkBindingOperation<?>... expectedEvents) {
        bindingOperationProcessor.verifyAndProcessNetworkEvents(expectedEvents);
    }

    private <U> Attribute<U> createTestAttribute(final AttributeKey<U> key) {
        return new Attribute<>() {
            final AtomicReference<U> map = new AtomicReference<>();

            @Override
            public AttributeKey<U> key() {
                return key;
            }

            @Override
            public U get() {
                return map.get();
            }

            @Override
            public void set(U value) {
                map.set(value);
            }

            @Override
            public U getAndSet(U value) {

                return map.getAndSet(value);
            }

            @Override
            public U setIfAbsent(U value) {
                return map.compareAndExchange(null, value);
            }

            @Override
            public U getAndRemove() {
                return map.compareAndExchange(map.get(), null);
            }

            @Override
            public boolean compareAndSet(U oldValue,
                                         U newValue) {
                return map.compareAndSet(oldValue, newValue);
            }

            @Override
            public void remove() {
                map.set(null);
            }
        };
    }

    private static class TestNetworkBindingOperationProcessor implements NetworkBindingOperationProcessor {
        private final BlockingQueue<NetworkBindingOperation<?>> queue = new LinkedBlockingQueue<>();

        @Override
        public void start(ServerBootstrap plain, ServerBootstrap tls) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void enqueueNetworkBindingEvent(NetworkBindingOperation<?> o) {
            queue.add(o);
        }

        public void verifyAndProcessNetworkEvents(NetworkBindingOperation... expectedEvents) {
            assertThat(queue.size()).as("unexpected number of events").isEqualTo(expectedEvents.length);
            var expectedEventIterator = Arrays.stream(expectedEvents).iterator();
            while (expectedEventIterator.hasNext()) {
                var expectedEvent = expectedEventIterator.next();
                if (queue.isEmpty()) {
                    fail("No network event available, expecting one matching " + expectedEvent);
                }
                var event = queue.poll();
                if (event instanceof NetworkBindRequest bindEvent) {
                    assertThat(bindEvent.getBindingAddress()).isEqualTo(((NetworkBindRequest) expectedEvent).getBindingAddress());
                    assertThat(bindEvent.port()).isEqualTo(expectedEvent.port());
                    assertThat(bindEvent.tls()).isEqualTo(expectedEvent.tls());
                }
                else if (event instanceof NetworkUnbindRequest unbindEvent) {
                    assertThat(unbindEvent.port()).isEqualTo(expectedEvent.port());
                    assertThat(unbindEvent.tls()).isEqualTo(expectedEvent.tls());
                }
                else {
                    fail("unexpected event type received");
                }
                propagateFutureResult(expectedEvent.getFuture(), event.getFuture());
            }
        }

        private <U> void propagateFutureResult(CompletableFuture<U> source, CompletableFuture<U> dest) {
            var unused = source.handle((c, t) -> {
                if (t != null) {
                    dest.completeExceptionally(t);
                }
                else {
                    dest.complete(c);
                }
                return null;
            });
        }

        @Override
        public void close() {

        }
    }
}