/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.internal.filter;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.ObjIntConsumer;
import java.util.function.ToIntFunction;

import org.apache.kafka.common.message.DescribeClusterResponseData;
import org.apache.kafka.common.message.DescribeClusterResponseData.DescribeClusterBroker;
import org.apache.kafka.common.message.FindCoordinatorResponseData;
import org.apache.kafka.common.message.FindCoordinatorResponseData.Coordinator;
import org.apache.kafka.common.message.MetadataResponseData;
import org.apache.kafka.common.message.MetadataResponseData.MetadataResponseBroker;
import org.apache.kafka.common.message.ResponseHeaderData;
import org.apache.kafka.common.protocol.ApiMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.kroxylicious.proxy.filter.DescribeClusterResponseFilter;
import io.kroxylicious.proxy.filter.FindCoordinatorResponseFilter;
import io.kroxylicious.proxy.filter.KrpcFilterContext;
import io.kroxylicious.proxy.filter.MetadataResponseFilter;
import io.kroxylicious.proxy.internal.net.EndpointReconciler;
import io.kroxylicious.proxy.model.VirtualCluster;
import io.kroxylicious.proxy.service.HostPort;

/**
 * An internal filter that rewrites broker addresses in all relevant responses to the corresponding proxy address. It also
 * is responsible for updating the virtual cluster's cache of upstream broker endpoints.
 */
public class BrokerAddressFilter implements MetadataResponseFilter, FindCoordinatorResponseFilter, DescribeClusterResponseFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(BrokerAddressFilter.class);

    private final VirtualCluster virtualCluster;
    private final EndpointReconciler reconciler;

    public BrokerAddressFilter(VirtualCluster virtualCluster, EndpointReconciler reconciler) {
        this.virtualCluster = virtualCluster;
        this.reconciler = reconciler;
    }

    @Override
    public void onMetadataResponse(short apiVersion, ResponseHeaderData header, MetadataResponseData data, KrpcFilterContext context) {
        var nodeMap = new HashMap<Integer, HostPort>();
        for (MetadataResponseBroker broker : data.brokers()) {
            nodeMap.put(broker.nodeId(), new HostPort(broker.host(), broker.port()));
            apply(context, broker, MetadataResponseBroker::nodeId, MetadataResponseBroker::host, MetadataResponseBroker::port, MetadataResponseBroker::setHost,
                    MetadataResponseBroker::setPort);
        }
        doReconcileThenForwardResponse(header, data, context, nodeMap);
    }

    @Override
    public void onDescribeClusterResponse(short apiVersion, ResponseHeaderData header, DescribeClusterResponseData data, KrpcFilterContext context) {
        var nodeMap = new HashMap<Integer, HostPort>();
        for (DescribeClusterBroker broker : data.brokers()) {
            nodeMap.put(broker.brokerId(), new HostPort(broker.host(), broker.port()));
            apply(context, broker, DescribeClusterBroker::brokerId, DescribeClusterBroker::host, DescribeClusterBroker::port, DescribeClusterBroker::setHost,
                    DescribeClusterBroker::setPort);
        }
        doReconcileThenForwardResponse(header, data, context, nodeMap);
    }

    @Override
    public void onFindCoordinatorResponse(short apiVersion, ResponseHeaderData header, FindCoordinatorResponseData data, KrpcFilterContext context) {
        // Version 4+
        for (Coordinator coordinator : data.coordinators()) {
            // If the coordinator is not yet available, the server returns a nodeId of -1.
            if (coordinator.nodeId() >= 0) {
                apply(context, coordinator, Coordinator::nodeId, Coordinator::host, Coordinator::port, Coordinator::setHost, Coordinator::setPort);
            }
        }
        // Version 3
        if (data.nodeId() >= 0 && data.host() != null && !data.host().isEmpty() && data.port() > 0) {
            apply(context, data, FindCoordinatorResponseData::nodeId, FindCoordinatorResponseData::host, FindCoordinatorResponseData::port,
                    FindCoordinatorResponseData::setHost, FindCoordinatorResponseData::setPort);
        }
        context.forwardResponse(header, data);
    }

    private <T> void apply(KrpcFilterContext context, T broker, Function<T, Integer> nodeIdGetter, Function<T, String> hostGetter, ToIntFunction<T> portGetter,
                           BiConsumer<T, String> hostSetter,
                           ObjIntConsumer<T> portSetter) {
        String incomingHost = hostGetter.apply(broker);
        int incomingPort = portGetter.applyAsInt(broker);

        var downstreamAddress = virtualCluster.getBrokerAddress(nodeIdGetter.apply(broker));

        LOGGER.trace("{}: Rewriting broker address in response {}:{} -> {}", context, incomingHost, incomingPort, downstreamAddress);
        hostSetter.accept(broker, downstreamAddress.host());
        portSetter.accept(broker, downstreamAddress.port());
    }

    private void doReconcileThenForwardResponse(ResponseHeaderData header, ApiMessage data, KrpcFilterContext context, Map<Integer, HostPort> nodeMap) {
        // https://github.com/kroxylicious/kroxylicious/issues/351
        // Using synchronous approach for now
        reconciler.reconcile(virtualCluster, nodeMap, false).toCompletableFuture().join();
        context.forwardResponse(header, data);
        LOGGER.debug("Endpoint reconciliation complete for  virtual cluster {}", virtualCluster);
    }
}
