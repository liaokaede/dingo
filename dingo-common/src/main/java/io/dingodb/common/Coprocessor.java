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

package io.dingodb.common;

import io.dingodb.common.table.ColumnDefinition;
import io.dingodb.common.util.ByteArrayUtils;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.Collections;
import java.util.List;

@Getter
@Builder
@EqualsAndHashCode
public class Coprocessor {

    private static final SchemaWrapper DEFAULT_WRAPPER = SchemaWrapper.builder().build();

    private int schemaVersion;

    @Builder.Default
    private SchemaWrapper originalSchema = DEFAULT_WRAPPER;

    @Builder.Default
    private SchemaWrapper resultSchema = DEFAULT_WRAPPER;

    @Builder.Default
    private List<Integer> selection = Collections.emptyList();

    @Builder.Default
    private byte[] expression = ByteArrayUtils.EMPTY_BYTES;

    @Builder.Default
    private List<Integer> groupBy = Collections.emptyList();

    @Builder.Default
    private List<AggregationOperator> aggregations = Collections.emptyList();

    @Getter
    @Builder
    public static class SchemaWrapper {
        @Builder.Default
        private List<ColumnDefinition> schemas = Collections.emptyList();
        private long commonId;
    }
}
