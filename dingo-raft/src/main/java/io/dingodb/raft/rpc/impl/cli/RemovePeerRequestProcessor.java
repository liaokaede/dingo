/*
 * Copyright 2021 DataCanvas
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.dingodb.raft.rpc.impl.cli;

import com.google.protobuf.Message;
import io.dingodb.raft.rpc.CliRequests;
import io.dingodb.raft.rpc.RpcRequestClosure;
import io.dingodb.raft.entity.PeerId;
import io.dingodb.raft.error.RaftError;
import io.dingodb.raft.util.RpcFactoryHelper;

import java.util.List;
import java.util.concurrent.Executor;

// Refer to SOFAJRaft: <A>https://github.com/sofastack/sofa-jraft/<A/>
public class RemovePeerRequestProcessor extends BaseCliRequestProcessor<CliRequests.RemovePeerRequest> {
    public RemovePeerRequestProcessor(Executor executor) {
        super(executor, CliRequests.RemovePeerResponse.getDefaultInstance());
    }

    @Override
    protected String getPeerId(final CliRequests.RemovePeerRequest request) {
        return request.getLeaderId();
    }

    @Override
    protected String getGroupId(final CliRequests.RemovePeerRequest request) {
        return request.getGroupId();
    }

    @Override
    protected Message processRequest0(final CliRequestContext ctx, final CliRequests.RemovePeerRequest request, final RpcRequestClosure done) {
        final List<PeerId> oldPeers = ctx.node.listPeers();
        final String removingPeerIdStr = request.getPeerId();
        final PeerId removingPeer = new PeerId();
        if (removingPeer.parse(removingPeerIdStr)) {
            LOG.info("Receive RemovePeerRequest to {} from {}, removing {}", ctx.node.getNodeId(), done.getRpcCtx()
                .getRemoteAddress(), removingPeerIdStr);
            ctx.node.removePeer(removingPeer, status -> {
                if (!status.isOk()) {
                    done.run(status);
                } else {
                    final CliRequests.RemovePeerResponse.Builder rb = CliRequests.RemovePeerResponse.newBuilder();
                    for (final PeerId oldPeer : oldPeers) {
                        rb.addOldPeers(oldPeer.toString());
                        if (!oldPeer.equals(removingPeer)) {
                            rb.addNewPeers(oldPeer.toString());
                        }
                    }
                    done.sendResponse(rb.build());
                }
            });
        } else {
            return RpcFactoryHelper //
                .responseFactory() //
                .newResponse(defaultResp(), RaftError.EINVAL, "Fail to parse peer id %s", removingPeerIdStr);
        }

        return null;
    }

    @Override
    public String interest() {
        return CliRequests.RemovePeerRequest.class.getName();
    }
}
