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

package io.dingodb.server.executor.service;

import com.google.auto.service.AutoService;
import io.dingodb.calcite.runtime.DingoResource;
import io.dingodb.codec.KeyValueCodec;
import io.dingodb.common.CommonId;
import io.dingodb.common.config.DingoConfiguration;
import io.dingodb.common.partition.RangeDistribution;
import io.dingodb.common.privilege.PrivilegeDefinition;
import io.dingodb.common.privilege.PrivilegeDict;
import io.dingodb.common.privilege.PrivilegeGather;
import io.dingodb.common.privilege.SchemaPrivDefinition;
import io.dingodb.common.privilege.TablePrivDefinition;
import io.dingodb.common.privilege.UserDefinition;
import io.dingodb.common.store.KeyValue;
import io.dingodb.common.table.ColumnDefinition;
import io.dingodb.common.table.TableDefinition;
import io.dingodb.common.util.Optional;
import io.dingodb.store.api.StoreInstance;
import io.dingodb.verify.plugin.AlgorithmPlugin;
import io.dingodb.verify.service.UserServiceProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

import static io.dingodb.calcite.runtime.DingoResource.DINGO_RESOURCE;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

@Slf4j
public class UserService implements io.dingodb.verify.service.UserService {
    public static final UserService INSTANCE = new UserService();

    @AutoService(UserServiceProvider.class)
    public static class Provider implements UserServiceProvider {
        @Override
        public io.dingodb.verify.service.UserService get() {
            return INSTANCE;
        }
    }

    private UserService() {
    }

    public static final String userTable = "USER";
    public static final String dbPrivilegeTable = "DB";
    public static final String tablePrivilegeTable = "TABLES_PRIV";

    private final MetaService metaService = MetaService.ROOT.getSubMetaService("mysql");
    private final StoreService storeService = StoreService.DEFAULT_INSTANCE;

    private final CommonId userTblId = metaService.getTableId(userTable);
    private final CommonId dbPrivTblId = metaService.getTableId(dbPrivilegeTable);
    private final CommonId tablePrivTblId = metaService.getTableId(tablePrivilegeTable);

    private final TableDefinition userTd = metaService.getTableDefinition(userTable);
    private final TableDefinition dbPrivTd = metaService.getTableDefinition(dbPrivilegeTable);
    private final TableDefinition tablePrivTd = metaService.getTableDefinition(tablePrivilegeTable);

    private final KeyValueCodec userCodec = CodecService.INSTANCE.createKeyValueCodec(userTblId, userTd);
    private final KeyValueCodec dbPrivCodec = CodecService.INSTANCE.createKeyValueCodec(dbPrivTblId, dbPrivTd);
    private final KeyValueCodec tablePrivCodec = CodecService.INSTANCE.createKeyValueCodec(tablePrivTblId, tablePrivTd);

    private final StoreInstance userStore = storeService.getInstance(userTblId, getRegionId(userTblId));
    private final StoreInstance dbPrivStore = storeService.getInstance(dbPrivTblId, getRegionId(dbPrivTblId));
    private final StoreInstance tablePrivStore = storeService.getInstance(tablePrivTblId, getRegionId(tablePrivTblId));

    @Override
    public boolean existsUser(UserDefinition userDefinition) {
        Object[] keys = getUserKeys(userDefinition);
        Object[] values = get(userStore, userCodec, keys);
        return values != null;
    }

    @Override
    public void createUser(UserDefinition userDefinition) {
        try {
            Object[] userRow = createUserRow(userDefinition);
            userStore.insert(userCodec.encode(userRow));
            log.info("create user: {}", userDefinition);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void dropUser(UserDefinition userDefinition) {
        try {
            Object[] key = getUserKeys(userDefinition);
            boolean result = userStore.delete(userCodec.encodeKey(key));
            if (result) {
                Object[] dbPrivKeys = getDbPrivilegeKeys(userDefinition, null);
                deletePrefix(dbPrivStore, dbPrivCodec, dbPrivKeys);
                Object[] tablePrivKeys = getTablePrivilegeKeys(
                    userDefinition, null, null
                );
                deletePrefix(tablePrivStore, tablePrivCodec, tablePrivKeys);
            }
        } catch (Exception e) {
            throw new RuntimeException();
        }
    }

    @Override
    public void updateUser(UserDefinition userDefinition) {
        try {
            Object[] key = getUserKeys(userDefinition);
            KeyValue old = userStore.get(userCodec.encodeKey(key));
            Object[] values = userCodec.decode(old);
            if (values == null) {
                throw new RuntimeException("user not exists");
            }
            if (userDefinition.getPassword() != null) {
                String digestPwd;
                if (StringUtils.isEmpty(userDefinition.getPassword())) {
                    digestPwd = "";
                } else {
                    String plugin = (String) values[39];
                    digestPwd = AlgorithmPlugin.digestAlgorithm(userDefinition.getPassword(), plugin);
                }
                values[40] = digestPwd;
                values[42] = new Timestamp(System.currentTimeMillis());
                values[41] = "N";
            }
            if ("NONE".equalsIgnoreCase(userDefinition.getRequireSsl())) {
                values[31] = "";
            } else if ("SSL".equalsIgnoreCase(userDefinition.getRequireSsl())) {
                values[31] = "ANY";
            }
            if (StringUtils.isNotBlank(userDefinition.getLock())) {
                values[44] = userDefinition.getLock();
            }
            if (userDefinition.getExpireDays() != null) {
                int expireDays = Integer.parseInt(userDefinition.getExpireDays().toString());
                if (expireDays == 0) {
                    values[41] = "Y";
                    values[43] = null;
                } else if (expireDays >= 0) {
                    values[41] = "N";
                    values[43] = expireDays;
                }
            }

            KeyValue row = userCodec.encode(values);
            userStore.update(row, old);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void grant(PrivilegeDefinition privilegeDefinition) {
        if (privilegeDefinition instanceof UserDefinition) {
            grantUser((UserDefinition) privilegeDefinition);
        } else if (privilegeDefinition instanceof SchemaPrivDefinition) {
            grantDbPrivilege((SchemaPrivDefinition) privilegeDefinition);
        } else if (privilegeDefinition instanceof TablePrivDefinition) {
            grantTablePrivilege((TablePrivDefinition) privilegeDefinition);
        }
    }

    @Override
    public void revoke(PrivilegeDefinition privilegeDefinition) {
        if (privilegeDefinition instanceof UserDefinition) {
            revokeUser(privilegeDefinition,
                privilegeDefinition.getPrivilegeList());
        } else if (privilegeDefinition instanceof SchemaPrivDefinition) {
            SchemaPrivDefinition schemaPrivDefinition = (SchemaPrivDefinition) privilegeDefinition;
            revokeDbPrivilege(privilegeDefinition,
                schemaPrivDefinition.getSchemaName(), privilegeDefinition.getPrivilegeList());
        } else if (privilegeDefinition instanceof TablePrivDefinition) {
            TablePrivDefinition tablePrivDefinition = (TablePrivDefinition) privilegeDefinition;
            revokeTablePrivilege(privilegeDefinition,
                tablePrivDefinition.getSchemaName(), tablePrivDefinition.getTableName(),
                privilegeDefinition.getPrivilegeList());
        }
    }

    @Override
    public PrivilegeGather getPrivilegeDef(String user, String host) {
        UserDefinition userDefinition = getUserDefinition(user, host);
        if (userDefinition == null) {
            return null;
        }
        List<Object[]> dpValues = getSchemaPrivilegeList(userDefinition);
        List<Object[]> tpValues = getTablePrivilegeList(userDefinition);
        Map<String, SchemaPrivDefinition> schemaPrivDefMap = new HashMap<>();
        if (dpValues != null) {
            dpValues.forEach(dbValue -> {
                String schemaName = (String) dbValue[2];
                SchemaPrivDefinition schemaPrivDefinition = new SchemaPrivDefinition();
                schemaPrivDefinition.setSchemaName(schemaName);
                schemaPrivDefinition.setPrivileges(spMapping(dbValue));
                schemaPrivDefMap.put(schemaName, schemaPrivDefinition);
            });
        }

        Map<String, TablePrivDefinition> tablePrivDefMap = new HashMap<>();
        if (tpValues != null) {
            tpValues.forEach(tpValue -> {
                String schemaName = (String) tpValue[2];
                String tableName = (String) tpValue[3];

                TablePrivDefinition tablePrivDefinition = new TablePrivDefinition();
                tablePrivDefinition.setSchemaName(schemaName);
                tablePrivDefinition.setTableName(tableName);
                tablePrivDefinition.setPrivileges(tpMapping(tpValue));
                tablePrivDefMap.put(tableName, tablePrivDefinition);
            });
        }

        return PrivilegeGather.builder()
            .user(user)
            .host(userDefinition.getHost())
            .userDef(userDefinition)
            .schemaPrivDefMap(schemaPrivDefMap)
            .tablePrivDefMap(tablePrivDefMap)
            .build();
    }

    @Override
    public UserDefinition getUserDefinition(String user, String host) {
        Object[] keys = new Object[userTd.getColumnsCount()];
        keys[0] = host;
        keys[1] = user;
        Object[] userPrivilege = get(userStore, userCodec, keys);
        if (userPrivilege == null) {
            keys[0] = "%";
            userPrivilege = get(userStore, userCodec, keys);
            if (userPrivilege == null) {
                keys[0] = DingoConfiguration.host();
                userPrivilege = get(userStore, userCodec, keys);
                if (userPrivilege == null) {
                    return null;
                }
            }
        }

        UserDefinition userDefinition = new UserDefinition();
        userDefinition.setUser(userPrivilege[1].toString());
        userDefinition.setPassword((String) userPrivilege[40]);
        userDefinition.setPlugin((String) userPrivilege[39]);
        userDefinition.setRequireSsl((String) userPrivilege[31]);
        userDefinition.setHost(userPrivilege[0].toString());
        userDefinition.setLock((String) userPrivilege[44]);
        userDefinition.setPasswordExpire(userPrivilege[41]);
        userDefinition.setExpireDays(userPrivilege[43]);
        userDefinition.setPwdLastChange((Timestamp) userPrivilege[42]);
        userDefinition.setPrivileges(upMapping(userPrivilege));
        return userDefinition;
    }

    @Override
    public CommonId getSchemaId(String schema) {
        return null;
    }

    @Override
    public CommonId getTableId(String schemaName, String table) {
        return null;
    }

    @Override
    public void flushPrivileges() {
        log.info("flush privileges");
    }

    private Object[] createUserRow(UserDefinition user) {
        Object[] row = new Object[userTd.getColumnsCount()];
        for (int i = 0; i < userTd.getColumns().size(); i++) {
            ColumnDefinition column = userTd.getColumn(i);
            switch (column.getName()) {
                case "USER":
                    row[i] = user.getUser();
                    break;
                case "HOST":
                    row[i] = user.getHost();
                    break;
                case "AUTHENTICATION_STRING":
                    row[i] = user.getPassword();
                    break;
                case "SSL_TYPE":
                    if (StringUtils.isNotBlank(user.getRequireSsl())
                        && !"NONE".equalsIgnoreCase(user.getRequireSsl())) {
                        row[i] = "ANY";
                    }
                    break;
                case "SSL_CIPHER":
                case "X509_ISSUER":
                case "X509_SUBJECT":
                    row[i] = "";
                    break;
                case "PASSWORD_EXPIRE":
                    if (user.getExpireDays() != null) {
                        int expireDays = Integer.parseInt(user.getExpireDays().toString());
                        if (expireDays == 0) {
                            row[i] = "Y";
                        } else if (expireDays > 0) {
                            row[i] = "N";
                        }
                    }
                case "PASSWORD_LIFETIME":
                    if (user.getExpireDays() != null) {
                        int expireDays = Integer.parseInt(user.getExpireDays().toString());
                        if (expireDays > 0) {
                            row[i] = expireDays;
                        }
                    }
                    break;
                case "MAX_QUESTIONS":
                case "MAX_UPDATES":
                case "MAX_CONNECTIONS":
                case "MAX_USER_CONNECTIONS":
                    row[i] = 0;
                    break;
                case "PLUGIN":
                    row[i] = user.getPlugin();
                    break;
                case "PASSWORD_LAST_CHANGED":
                    row[i] = new Timestamp(System.currentTimeMillis());
                    break;
                case "ACCOUNT_LOCKED":
                    row[i] = user.getLock();
                    break;
                default:
                    row[i] = "N";
            }
        }
        return row;
    }

    private void grantUser(UserDefinition userDefinition) {
        try {
            KeyValue old = userStore.get(userCodec.encodeKey(getUserKeys(userDefinition)));
            Object[] userValues = userCodec.decode(old);
            userDefinition.getPrivilegeList().forEach(privilege -> {
                Integer index = PrivilegeDict.userPrivilegeIndex.get(privilege.toLowerCase());
                if (index != null) {
                    userValues[index] = "Y";
                }
            });
            KeyValue row = userCodec.encode(userValues);
            userStore.update(row, old);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void grantDbPrivilege(SchemaPrivDefinition schemaPrivDefinition) {
        boolean exist = true;
        KeyValue old = getKeyValue(
            dbPrivStore, dbPrivCodec, getDbPrivilegeKeys(schemaPrivDefinition, schemaPrivDefinition.getSchemaName())
        );
        Object[] dbValues = decode(dbPrivCodec, old);
        if (dbValues == null) {
            log.info("db privilege is empty");
            dbValues = getDbPrivilege(schemaPrivDefinition.getUser(), schemaPrivDefinition.getHost(),
                schemaPrivDefinition.getSchemaName());
            exist = false;
        }
        Object[] finalDbValues = dbValues;
        schemaPrivDefinition.getPrivilegeList().forEach(privilege ->
            finalDbValues[PrivilegeDict.dbPrivilegeIndex.get(privilege.toLowerCase())] = "Y");

        if (exist) {
            update(dbPrivStore, dbPrivCodec, finalDbValues, old);
        } else {
            insert(dbPrivStore, dbPrivCodec, finalDbValues);
        }
    }

    private void grantTablePrivilege(TablePrivDefinition tablePrivDefinition) {
        boolean exist = true;
        String schemaName = tablePrivDefinition.getSchemaName();
        String tableName = tablePrivDefinition.getTableName();
        KeyValue old = getKeyValue(
            tablePrivStore, tablePrivCodec, getTablePrivilegeKeys(tablePrivDefinition, schemaName, tableName)
        );
        Object[] tpValues = decode(tablePrivCodec, old);
        if (tpValues == null) {
            tpValues = getTablePrivilege(
                tablePrivDefinition.getUser(), tablePrivDefinition.getHost(), schemaName, tableName
            );
            exist = false;
        }
        String tp = (String) tpValues[6];
        String[] privileges = tp.split(",");
        List<String> privilegeList = new ArrayList<>(tablePrivDefinition.getPrivilegeList());
        for (String privilege : privileges) {
            if (!tablePrivDefinition.getPrivilegeList().contains(privilege.toLowerCase()) && !privilege.isEmpty()) {
                privilegeList.add(privilege);
            }
        }

        tpValues[6] = String.join(",", privilegeList);
        if (exist) {
            update(tablePrivStore, tablePrivCodec, tpValues, old);
        } else {
            insert(tablePrivStore, tablePrivCodec, tpValues);
        }
    }

    private Object[] getDbPrivilege(String user, String host, String db) {
        Object[] dbValues = new Object[dbPrivTd.getColumnsCount()];
        dbValues[0] = host;
        dbValues[1] = user;
        dbValues[2] = db;
        for (int i = 3; i < dbValues.length; i++) {
            dbValues[i] = "N";
        }
        return dbValues;
    }

    private List<Object[]> getTablePrivilegeList(UserDefinition user) {
        Object[] keys = getTablePrivilegeKeys(user, "", "");
        return scan(tablePrivStore, tablePrivCodec, keys);
    }

    private Object[] getTablePrivilege(String user, String host, String db, String tableName) {
        Object[] tpValues = new Object[tablePrivTd.getColumnsCount()];
        tpValues[0] = host;
        tpValues[1] = user;
        tpValues[2] = db;
        tpValues[3] = tableName;
        tpValues[4] = "";
        tpValues[5] = new Timestamp(System.currentTimeMillis());
        tpValues[6] = "";
        tpValues[7] = "";
        return tpValues;
    }

    public void revokeUser(PrivilegeDefinition privilege, List<String> privilegeList) {
        KeyValue old = getKeyValue(userStore, userCodec, getUserKeys(privilege));
        Object[] userValues = decode(userCodec, old);
        if (userValues == null) {
            return;
        }
        privilegeList.forEach(priv -> userValues[PrivilegeDict.userPrivilegeIndex.get(priv.toLowerCase())] = "N");
        update(userStore, userCodec, userValues, old);
    }

    public void revokeDbPrivilege(PrivilegeDefinition privilege, String schema, List<String> privilegeList) {
        KeyValue old = getKeyValue(dbPrivStore, dbPrivCodec, getDbPrivilegeKeys(privilege, schema));
        Object[] dbValues = decode(dbPrivCodec, old);
        if (dbValues == null) {
            throw DINGO_RESOURCE.noDbGrantsForRevoke(privilege.getUser(), privilege.getHost()).ex();
        }
        privilegeList.forEach(priv -> dbValues[PrivilegeDict.dbPrivilegeIndex.get(priv.toLowerCase())] = "N");

        int n = 0;
        for (int i = 3; i < dbValues.length; i++) {
            if (dbValues[i].equals("N")) {
                n++;
            }
        }
        if (n == 19) {
            delete(dbPrivStore, dbPrivCodec, dbValues);
        } else {
            update(dbPrivStore, dbPrivCodec, dbValues, old);
        }
    }

    public void revokeTablePrivilege(
        PrivilegeDefinition privilege, String schemaName, String tableName, List<String> privilegeList
    ) {
        KeyValue old = getKeyValue(
            tablePrivStore, tablePrivCodec, getTablePrivilegeKeys(privilege, schemaName, tableName)
        );
        Object[] tablesPrivValues = decode(tablePrivCodec, old);
        if (tablesPrivValues == null) {
            throw DINGO_RESOURCE.noTableGrantsForRevoke(privilege.getUser(), privilege.getHost(), tableName).ex();
        }
        String tablePriv = (String) tablesPrivValues[6];
        String[] privileges = tablePriv.split(",");
        StringBuilder tpBuilder = new StringBuilder();
        for (String priv : privileges) {
            if (!privilegeList.contains(priv.toLowerCase())) {
                tpBuilder.append(priv);
                tpBuilder.append(",");
            }
        }
        if (tpBuilder.length() > 0) {
            tpBuilder.deleteCharAt(tpBuilder.length() - 1);
        }
        tablePriv = tpBuilder.toString();
        tablesPrivValues[6] = tablePriv;
        if (StringUtils.isBlank(tablePriv)) {
            delete(tablePrivStore, tablePrivCodec, tablesPrivValues);
        } else {
            update(tablePrivStore, tablePrivCodec, tablesPrivValues, old);
        }
    }

    private List<Object[]> getSchemaPrivilegeList(PrivilegeDefinition user) {
        Object[] keys = getDbPrivilegeKeys(user, "");
        return scan(dbPrivStore, dbPrivCodec, keys);
    }

    private static Boolean[] tpMapping(Object[] tpValues) {
        Boolean[] tablePrivileges = new Boolean[35];
        Arrays.fill(tablePrivileges, false);
        String[] tpList = String.valueOf(tpValues[6]).split(",");
        for (String tp : tpList) {
            Integer index = PrivilegeDict.privilegeIndexDict.get(tp.toLowerCase());
            if (index != null) {
                tablePrivileges[index] = true;
            }
        }
        return tablePrivileges;
    }

    private static Boolean[] upMapping(Object[] userValues) {
        Boolean[] userPrivileges = new Boolean[35];
        Arrays.fill(userPrivileges, false);
        PrivilegeDict.privilegeIndexDict.forEach((k, v) -> {
            Integer index = PrivilegeDict.userPrivilegeIndex.get(k);
            if (index != null) {
                userPrivileges[v] = isTrue(userValues[index]);
            }
        });
        return userPrivileges;
    }

    private static Boolean[] spMapping(Object[] dbValues) {
        Boolean[] schemaPrivileges = new Boolean[35];
        Arrays.fill(schemaPrivileges, false);
        PrivilegeDict.privilegeIndexDict.forEach((k, v) -> {
            Integer index = PrivilegeDict.dbPrivilegeIndex.get(k);
            if (index != null) {
                schemaPrivileges[v] = isTrue(dbValues[index]);
            }
        });
        return schemaPrivileges;
    }

    private static Boolean isTrue(Object value) {
        return "Y".equalsIgnoreCase(value.toString());
    }

    private CommonId getRegionId(CommonId tableId) {
        return Optional.ofNullable(metaService.getRangeDistribution(tableId))
            .map(NavigableMap::firstEntry)
            .map(Map.Entry::getValue)
            .map(RangeDistribution::getId)
            .orElseThrow("Cannot get region for " + tableId);
    }

    private static void insert(StoreInstance store, KeyValueCodec codec, Object[] row) {
        try {
            store.insert(codec.encode(row));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void update(StoreInstance store, KeyValueCodec codec, Object[] row, KeyValue old) {
        try {
            KeyValue keyValue = codec.encode(row);
            store.update(keyValue, old);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<Object[]> scan(StoreInstance store, KeyValueCodec codec, Object[] startKey) {
        try {
            byte[] prefix = codec.encodeKeyPrefix(startKey, 2);
            Iterator<KeyValue> iterator = store.scan(
                new StoreInstance.Range(prefix, prefix, true, true)
            );
            if (iterator == null) {
                return null;
            }
            List<Object[]> list = new ArrayList<>();
            while (iterator.hasNext()) {
                list.add(codec.decode(iterator.next()));
            }
            return list;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Object[] get(StoreInstance store, KeyValueCodec codec, Object[] key) {
        try {
            KeyValue keyValue = store.get(codec.encodeKey(key));
            if (keyValue.getValue() == null || keyValue.getValue().length == 0) {
                return null;
            }
            return codec.decode(keyValue);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Object[] decode(KeyValueCodec codec, KeyValue keyValue) {
        try {
            if (keyValue.getValue() == null || keyValue.getValue().length == 0) {
                return null;
            }
            return codec.decode(keyValue);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static KeyValue getKeyValue(StoreInstance store, KeyValueCodec codec, Object[] key) {
        try {
            return store.get(codec.encodeKey(key));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void delete(StoreInstance store, KeyValueCodec codec, Object[] key) {
        try {
            store.delete(codec.encodeKey(key));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void deletePrefix(StoreInstance store, KeyValueCodec codec, Object[] key) {
        try {
            byte[] prefix = codec.encodeKeyPrefix(key, 2);
            store.delete(new StoreInstance.Range(prefix, prefix, true, true));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Object[] getUserKeys(PrivilegeDefinition user) {
        Object[] values = new Object[userTd.getColumnsCount()];
        values[0] = user.getHost();
        values[1] = user.getUser();
        return values;
    }

    private Object[] getDbPrivilegeKeys(PrivilegeDefinition user, String db) {
        Object[] values = new Object[dbPrivTd.getColumnsCount()];
        values[0] = user.getHost();
        values[1] = user.getUser();
        if (isNotBlank(db)) {
            values[2] = db;
        }
        return values;
    }

    private Object[] getTablePrivilegeKeys(PrivilegeDefinition user, String db, String table) {
        Object[] values = new Object[tablePrivTd.getColumnsCount()];
        values[0] = user.getHost();
        values[1] = user.getUser();
        if (isNotBlank(db)) {
            values[2] = db;
        }
        if (isNotBlank(table)) {
            values[3] = table;
        }
        return values;
    }

}
