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

package io.dingodb.store.row.cmd.store;

import io.dingodb.raft.util.BytesUtil;
import lombok.Getter;
import lombok.Setter;

// Refer to SOFAJRaft: <A>https://github.com/sofastack/sofa-jraft/<A/>
@Getter
@Setter
public class GetSequenceRequest extends BaseRequest {
    private static final long serialVersionUID = 8409861577121335137L;

    private byte[] seqKey;
    private int step;

    @Override
    public byte magic() {
        return GET_SEQUENCE;
    }

    @Override
    public String toString() {
        return "GetSequenceRequest{" + "seqKey=" + BytesUtil.toHex(seqKey) + ", step=" + step + "} " + super.toString();
    }
}
