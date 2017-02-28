/*
 * Copyright © 2016 - 2017 VMware, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the “License”); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an “AS IS” BASIS, without warranties or conditions of any kind,
 * EITHER EXPRESS OR IMPLIED. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.vrg.rapid;

import com.google.common.net.HostAndPort;
import com.google.protobuf.ByteString;
import com.vrg.rapid.pb.GossipMessage;
import com.vrg.rapid.pb.GossipResponse;
import com.vrg.rapid.pb.JoinMessage;
import com.vrg.rapid.pb.JoinResponse;
import com.vrg.rapid.pb.JoinStatusCode;
import com.vrg.rapid.pb.MembershipServiceGrpc;
import com.vrg.rapid.pb.LinkStatus;
import com.vrg.rapid.pb.LinkUpdateMessageWire;
import com.vrg.rapid.pb.Response;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;


/**
 * Membership server class that implements the Rapid protocol.
 */
public class MembershipService extends MembershipServiceGrpc.MembershipServiceImplBase {
    private static final Logger LOG = LoggerFactory.getLogger(MembershipService.class);
    private final MembershipView membershipView;
    private final WatermarkBuffer watermarkBuffer;
    private final HostAndPort myAddr;
    private final boolean logProposals;
    private final IBroadcaster broadcaster;
    private final List<List<WatermarkBuffer.Node>> logProposalList = new ArrayList<>();
    private Server server;

    public static class Builder {
        private final MembershipView membershipView;
        private final WatermarkBuffer watermarkBuffer;
        private final HostAndPort myAddr;
        private final IBroadcaster broadcaster;
        private boolean logProposals;

        public Builder(final HostAndPort myAddr,
                       final WatermarkBuffer watermarkBuffer,
                       final MembershipView membershipView) {
            this.myAddr = Objects.requireNonNull(myAddr);
            this.watermarkBuffer = Objects.requireNonNull(watermarkBuffer);
            this.membershipView = Objects.requireNonNull(membershipView);
            final MessagingClient messagingClient = new MessagingClient(myAddr);
            this.broadcaster = new UnicastToAllBroadcaster(messagingClient);
        }

        public Builder setLogProposals(final boolean logProposals) {
            this.logProposals = logProposals;
            return this;
        }

        public MembershipService build() {
            return new MembershipService(this);
        }
    }

    private MembershipService(final Builder builder) {
        this.myAddr = builder.myAddr;
        this.membershipView = builder.membershipView;
        this.watermarkBuffer = builder.watermarkBuffer;
        this.logProposals = builder.logProposals;
        this.broadcaster = builder.broadcaster;
    }

    void startServer() throws IOException {
        startServer(Collections.emptyList());
    }

    void startServer(final List<ServerInterceptor> interceptors) throws IOException {
        Objects.requireNonNull(interceptors);
        final ServerBuilder builder = NettyServerBuilder.forPort(myAddr.getPort());
        server = builder.addService(ServerInterceptors
                                   .intercept(this, interceptors))
                .build()
                .start();
        // Use stderr here since the logger may have been reset by its JVM shutdown hook.
        Runtime.getRuntime().addShutdownHook(new Thread(this::stopServer));
    }

    void stopServer() {
        if (server != null) {
            server.shutdown();
        }
    }

    void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    /**
     * rpc implementations for methods defined in rapid.proto.
     */
    @Override
    public void gossip(final GossipMessage request,
                       final StreamObserver<GossipResponse> responseObserver) {
        responseObserver.onNext(GossipResponse.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public void receiveLinkUpdateMessage(final LinkUpdateMessageWire request,
                                         final StreamObserver<Response> responseObserver) {
        final LinkUpdateMessage msg = new LinkUpdateMessage(request.getLinkSrc(), request.getLinkDst(),
                                            request.getLinkStatus(), request.getConfigurationId(),
                                            UUID.fromString(request.getUuid()));
        processLinkUpdateMessage(msg);
        responseObserver.onNext(Response.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public void receiveJoinMessage(final JoinMessage joinMessage,
                                   final StreamObserver<JoinResponse> responseObserver) {
        final JoinResponse response = processJoinMessage(joinMessage);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void receiveJoinPhase2Message(final JoinMessage joinMessage,
                                   final StreamObserver<JoinResponse> responseObserver) {
        // This is a request by joinMessage.sender to its monitor.
        final long currentConfiguration = membershipView.getCurrentConfigurationId();

        LOG.trace("Join phase 2 message received during configuration {}", currentConfiguration);
        // TODO: insert some health checks between monitor and client
        final LinkUpdateMessageWire msg = LinkUpdateMessageWire.newBuilder()
                                            .setSender(this.myAddr.toString())
                                            .setLinkSrc(this.myAddr.toString())
                                            .setLinkDst(joinMessage.getSender())
                                            .setLinkStatus(LinkStatus.UP)
                                            .setConfigurationId(currentConfiguration)
                                            .setUuid(joinMessage.getUuid())
                                            .build();

        broadcaster.broadcast(membershipView.viewRing(0), msg);

        final JoinResponse response = JoinResponse.newBuilder()
                .setSender(this.myAddr.toString())
                .setStatusCode(JoinStatusCode.SAFE_TO_JOIN)
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    /**
     * This method receives link update events and delivers them to
     * the watermark buffer to check if it will return a valid
     * proposal.
     *
     * Link update messages that do not affect an ongoing proposal
     * needs to be dropped.
     */
    private void processLinkUpdateMessage(final LinkUpdateMessage msg) {
        Objects.requireNonNull(msg);

        final long currentConfigurationId = membershipView.getCurrentConfigurationId();
        if (currentConfigurationId != msg.getConfigurationId()) {
            return;
        }

        // The invariant we want to maintain is that a node can only go into the
        // membership set once and leave it once.
        if (msg.getStatus().equals(LinkStatus.UP) && membershipView.isPresent(msg.getDst())) {
            return;
        }
        if (msg.getStatus().equals(LinkStatus.DOWN) && !membershipView.isPresent(msg.getDst())) {
            return;
        }

        final List<WatermarkBuffer.Node> proposal = proposedViewChange(msg);
        if (proposal.size() != 0) {
            // Initiate proposal
            if (logProposals) {
                logProposalList.add(proposal);
            }
            // Initiate consensus from here.

            // TODO: for now, we just apply the proposal directly.
            for (final WatermarkBuffer.Node node: proposal) {
                try {
                    membershipView.ringAdd(node.hostAndPort, node.uuid);
                } catch (final MembershipView.NodeAlreadyInRingException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private JoinResponse processJoinMessage(final JoinMessage joinMessage) {
        final HostAndPort joiningHost = HostAndPort.fromString(joinMessage.getSender());
        final UUID uuid = UUID.fromString(joinMessage.getUuid());
        final JoinStatusCode statusCode = membershipView.isSafeToJoin(joiningHost, uuid);
        final JoinResponse.Builder builder = JoinResponse.newBuilder()
                                                   .setSender(this.myAddr.toString())
                                                   .setStatusCode(statusCode);

        if (statusCode.equals(JoinStatusCode.SAFE_TO_JOIN)) {
            // Return a list of monitors for the
            builder.addAllHosts(membershipView.expectedMonitorsOf(joiningHost)
                                .stream()
                                .map(e -> ByteString.copyFromUtf8(e.toString()))
                                .collect(Collectors.toList()));
        }

        return builder.build();
    }

    private List<WatermarkBuffer.Node> proposedViewChange(final LinkUpdateMessage msg) {
        // TODO: temporary solution for the lack of a deterministic expander
        final List<HostAndPort> monitors = membershipView.expectedMonitorsOf(msg.getDst());
        int monitorNumber = 0;
        for (final HostAndPort monitor: monitors) {
            if (msg.getSrc().equals(monitor)) {
                break;
            }
            monitorNumber++;
        }
        return watermarkBuffer.aggregateForProposal(msg, monitorNumber);
    }

    List<List<WatermarkBuffer.Node>> getProposalLog() {
        return Collections.unmodifiableList(logProposalList);
    }

    List<HostAndPort> getMembershipView() {
        return membershipView.viewRing(0);
    }
}