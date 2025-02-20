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

package io.dingodb.store.memory;

import io.dingodb.codec.serial.DingoKeyValueCodec;
import io.dingodb.common.CommonId;
import io.dingodb.common.store.KeyValue;
import io.dingodb.common.table.TableDefinition;
import io.dingodb.common.util.ByteArrayUtils;
import io.dingodb.common.util.Optional;
import io.dingodb.meta.MetaService;
import io.dingodb.store.api.StoreInstance;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;

@Slf4j
public class MemoryStoreInstance implements StoreInstance {
    private final NavigableMap<byte[], byte[]> db = new ConcurrentSkipListMap<>(ByteArrayUtils::compare);

    private DingoKeyValueCodec codec;

    public void initCodec(CommonId tableId) {
        TableDefinition tableDefinition = MetaService.root()
            .getSubMetaService(MetaService.DINGO_NAME)
            .getTableDefinition(tableId);
        codec = new DingoKeyValueCodec(tableDefinition.getDingoType(), tableDefinition.getKeyMapping());
    }

    @Override
    public KeyValue get(byte[] key) {
        return Optional.mapOrNull(db.get(key), v -> new KeyValue(key, v));
    }

    @Override
    public List<KeyValue> get(List<byte[]> keys) {
        List<KeyValue> result = new ArrayList<>(keys.size());
        for (byte[] key : keys) {
            result.add(new KeyValue(key, db.get(key)));
        }
        return result;
    }

    @Override
    public Iterator<KeyValue> scan(Range range) {
        return getTreeMapByRange(db.entrySet().iterator(), range.start, range.end, range.withStart, range.withEnd)
            .entrySet().stream().map(e -> new KeyValue(e.getKey(), e.getValue()))
            .iterator();
    }

    private TreeMap<byte[], byte[]> getTreeMapByRange(Iterator<Map.Entry<byte[], byte[]>> iterator,
                                                      byte[] startPrimaryKey,
                                                      byte[] endPrimaryKey,
                                                      boolean includeStart,
                                                      boolean includeEnd) {
        TreeMap<byte[], byte[]> treeMap = new TreeMap<>(ByteArrayUtils::compareWithoutLen);

        while (iterator.hasNext()) {
            Map.Entry<byte[], byte[]> next = iterator.next();
            boolean start = true;
            boolean end = false;
            if (includeStart) {
                start = ByteArrayUtils.greatThanOrEqual(next.getKey(), startPrimaryKey);
            } else {
                start = ByteArrayUtils.greatThan(next.getKey(), startPrimaryKey);
            }

            if (includeEnd) {
                end = ByteArrayUtils.lessThanOrEqual(next.getKey(), endPrimaryKey);
            } else {
                end = ByteArrayUtils.lessThan(next.getKey(), endPrimaryKey);
            }

            if (start && end) {
                treeMap.put(next.getKey(), next.getValue());
            }
        }
        return treeMap;
    }

    @Override
    public long count(Range range) {
        Set<byte[]> keys = getTreeMapByRange(
            db.entrySet().iterator(), range.start, range.end, range.withStart, range.withEnd
        ).keySet();
        return keys.size();
    }

    @Override
    public long delete(Range range) {
        Set<byte[]> keys = getTreeMapByRange(
            db.entrySet().iterator(), range.start, range.end, range.withStart, range.withEnd
        ).keySet();
        keys.forEach(db::remove);
        return keys.size();
    }

    @Override
    public boolean insert(KeyValue row) {
        db.put(row.getKey(), row.getValue());
        return true;
    }

    @Override
    public boolean update(KeyValue row, KeyValue old) {
        if (Arrays.equals(row.getKey(), old.getKey())) {
            if (Arrays.equals(db.get(row.getKey()), old.getValue())) {
                db.put(row.getKey(), row.getValue());
                return true;
            } else {
                return false;
            }
        }
        throw new IllegalArgumentException();
    }

    @Override
    public boolean delete(byte[] key) {
        db.remove(key);
        return true;
    }

    private KeyValue convertRow(Object[] row) {
        KeyValue keyValue = null;
        try {
            keyValue = codec.encode(row);
            return keyValue;
        } catch (IOException e) {
            log.error("Encode data error: {}", e.getMessage(), e);
        }
        return null;
    }
}
