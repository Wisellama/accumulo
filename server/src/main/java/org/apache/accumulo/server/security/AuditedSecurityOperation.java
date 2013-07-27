/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.server.security;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.impl.Tables;
import org.apache.accumulo.core.client.impl.Translator;
import org.apache.accumulo.core.client.impl.thrift.ThriftSecurityException;
import org.apache.accumulo.core.data.Column;
import org.apache.accumulo.core.data.KeyExtent;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.thrift.IterInfo;
import org.apache.accumulo.core.data.thrift.TColumn;
import org.apache.accumulo.core.data.thrift.TKeyExtent;
import org.apache.accumulo.core.data.thrift.TRange;
import org.apache.accumulo.core.master.thrift.TableOperation;
import org.apache.accumulo.core.metadata.MetadataTable;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.security.Credentials;
import org.apache.accumulo.core.security.SystemPermission;
import org.apache.accumulo.core.security.TablePermission;
import org.apache.accumulo.core.security.thrift.TCredentials;
import org.apache.accumulo.core.util.ByteBufferUtil;
import org.apache.accumulo.server.client.HdfsZooInstance;
import org.apache.accumulo.server.security.handler.Authenticator;
import org.apache.accumulo.server.security.handler.Authorizor;
import org.apache.accumulo.server.security.handler.PermissionHandler;
import org.apache.hadoop.io.Text;
import org.apache.log4j.Logger;

/**
 *
 */
public class AuditedSecurityOperation extends SecurityOperation {
  
  public static final String AUDITLOG = "Audit";
  public static final Logger audit = Logger.getLogger(AUDITLOG);
  
  public AuditedSecurityOperation(Authorizor author, Authenticator authent, PermissionHandler pm, String instanceId) {
    super(author, authent, pm, instanceId);
  }
  
  public static synchronized SecurityOperation getInstance() {
    String instanceId = HdfsZooInstance.getInstance().getInstanceID();
    return getInstance(instanceId, false);
  }
  
  public static synchronized SecurityOperation getInstance(String instanceId, boolean initialize) {
    if (instance == null) {
      instance = new AuditedSecurityOperation(getAuthorizor(instanceId, initialize), getAuthenticator(instanceId, initialize), getPermHandler(instanceId,
          initialize), instanceId);
    }
    return instance;
  }
  
  private static String getTableName(String tableId) {
    try {
      return Tables.getTableName(HdfsZooInstance.getInstance(), tableId);
    } catch (TableNotFoundException e) {
      return "Unknown Table with ID " + tableId;
    }
  }
  
  public static StringBuilder getAuthString(List<ByteBuffer> authorizations) {
    StringBuilder auths = new StringBuilder();
    for (ByteBuffer bb : authorizations) {
      auths.append(ByteBufferUtil.toString(bb)).append(",");
    }
    return auths;
  }
  
  private static boolean shouldAudit(TCredentials credentials, String tableId) {
    return !tableId.equals(MetadataTable.ID) && shouldAudit(credentials);
  }
  
  // Is INFO the right level to check? Do we even need that check?
  private static boolean shouldAudit(TCredentials credentials) {
    return !SystemCredentials.get().getToken().getClass().getName().equals(credentials.getTokenClassName());
  }
  
  /*
   * Three auditing methods try to capture the 4 states we might have here. audit is in response to a thrown exception, the operation failed (perhaps due to
   * insufficient privs, or some other reason) audit(credentials, template, args) is a successful operation audit(credentials, permitted, template, args) is a
   * privileges check that is either permitted or denied. We don't know if the operation went on to be successful or not at this point, we would have to go
   * digging through loads of other code to find it.
   */
  private void audit(TCredentials credentials, ThriftSecurityException ex, String template, Object... args) {
    audit.warn("operation: failed; user: " + credentials.getPrincipal() + "; " + String.format(template, args) + "; exception: " + ex.toString());
  }
  
  private void audit(TCredentials credentials, String template, Object... args) {
    if (shouldAudit(credentials)) {
      audit.info("operation: success; user: " + credentials.getPrincipal() + ": " + String.format(template, args));
    }
  }
  
  private void audit(TCredentials credentials, boolean permitted, String template, Object... args) {
    if (shouldAudit(credentials)) {
      String prefix = permitted ? "permitted" : "denied";
      audit.info("operation: " + prefix + "; user: " + credentials.getPrincipal() + "; " + String.format(template, args));
    }
  }
  
  public static final String CAN_SCAN_AUDIT_TEMPLATE = "action: scan; targetTable: %s; authorizations: %s; range: %s; columns: %s; iterators: %s; iteratorOptions: %s;";
  
  @Override
  public boolean canScan(TCredentials credentials, String tableId, TRange range, List<TColumn> columns, List<IterInfo> ssiList,
      Map<String,Map<String,String>> ssio, List<ByteBuffer> authorizations) throws ThriftSecurityException {
    if (shouldAudit(credentials, tableId)) {
      Range convertedRange = new Range(range);
      List<Column> convertedColumns = Translator.translate(columns, new Translator.TColumnTranslator());
      String tableName = getTableName(tableId);
      
      try {
        boolean canScan = super.canScan(credentials, tableId);
        audit(credentials, canScan, CAN_SCAN_AUDIT_TEMPLATE, tableName, getAuthString(authorizations), convertedRange, convertedColumns, ssiList, ssio);
        
        return canScan;
      } catch (ThriftSecurityException ex) {
        audit(credentials, ex, CAN_SCAN_AUDIT_TEMPLATE, getAuthString(authorizations), tableId, convertedRange, convertedColumns, ssiList, ssio);
        throw ex;
      }
    } else {
      return super.canScan(credentials, tableId);
    }
  }
  
  public static final String CAN_SCAN_BATCH_AUDIT_TEMPLATE = "action: scan; targetTable: %s; authorizations: %s; range: %s; columns: %s; iterators: %s; iteratorOptions: %s;";
  
  @Override
  public boolean canScan(TCredentials credentials, String tableId, Map<TKeyExtent,List<TRange>> tbatch, List<TColumn> tcolumns, List<IterInfo> ssiList,
      Map<String,Map<String,String>> ssio, List<ByteBuffer> authorizations) throws ThriftSecurityException {
    if (shouldAudit(credentials, tableId)) {
      @SuppressWarnings({"unchecked", "rawtypes"})
      Map<KeyExtent,List<Range>> convertedBatch = Translator.translate(tbatch, new Translator.TKeyExtentTranslator(), new Translator.ListTranslator(
          new Translator.TRangeTranslator()));
      List<Column> convertedColumns = Translator.translate(tcolumns, new Translator.TColumnTranslator());
      String tableName = getTableName(tableId);
      
      try {
        boolean canScan = super.canScan(credentials, tableId);
        audit(credentials, canScan, CAN_SCAN_BATCH_AUDIT_TEMPLATE, tableName, getAuthString(authorizations), convertedBatch, convertedColumns, ssiList, ssio);
        
        return canScan;
      } catch (ThriftSecurityException ex) {
        audit(credentials, ex, CAN_SCAN_BATCH_AUDIT_TEMPLATE, getAuthString(authorizations), tableId, convertedBatch, convertedColumns, ssiList, ssio);
        throw ex;
      }
    } else {
      return super.canScan(credentials, tableId);
    }
  }
  
  public static final String CHANGE_AUTHORIZATIONS_AUDIT_TEMPLATE = "action: changeAuthorizations; targetUser: %s; authorizations: %s";
  
  @Override
  public void changeAuthorizations(TCredentials credentials, String user, Authorizations authorizations) throws ThriftSecurityException {
    try {
      super.changeAuthorizations(credentials, user, authorizations);
      audit(credentials, CHANGE_AUTHORIZATIONS_AUDIT_TEMPLATE, user, authorizations);
    } catch (ThriftSecurityException ex) {
      audit(credentials, ex, CHANGE_AUTHORIZATIONS_AUDIT_TEMPLATE, user, authorizations);
      throw ex;
    }
  }
  
  public static final String CHANGE_PASSWORD_AUDIT_TEMPLATE = "action: changePassword; targetUser: %s;";
  
  @Override
  public void changePassword(TCredentials credentials, Credentials newInfo) throws ThriftSecurityException {
    try {
      super.changePassword(credentials, newInfo);
      audit(credentials, CHANGE_PASSWORD_AUDIT_TEMPLATE, newInfo.getPrincipal());
    } catch (ThriftSecurityException ex) {
      audit(credentials, ex, CHANGE_PASSWORD_AUDIT_TEMPLATE, newInfo.getPrincipal());
      throw ex;
    }
  }
  
  public static final String CREATE_USER_AUDIT_TEMPLATE = "action: createUser; targetUser: %s; Authorizations: %s;";
  
  @Override
  public void createUser(TCredentials credentials, Credentials newUser, Authorizations authorizations) throws ThriftSecurityException {
    try {
      super.createUser(credentials, newUser, authorizations);
      audit(credentials, CREATE_USER_AUDIT_TEMPLATE, newUser.getPrincipal(), authorizations);
    } catch (ThriftSecurityException ex) {
      audit(credentials, ex, CREATE_USER_AUDIT_TEMPLATE, newUser.getPrincipal(), authorizations);
      throw ex;
    }
  }
  
  public static final String CAN_CREATE_TABLE_AUDIT_TEMPLATE = "action: createTable; targetTable: %s;";
  
  @Override
  public boolean canCreateTable(TCredentials c, String tableName) throws ThriftSecurityException {
    try {
      boolean result = super.canCreateTable(c);
      audit(c, result, CAN_CREATE_TABLE_AUDIT_TEMPLATE, tableName);
      return result;
    } catch (ThriftSecurityException ex) {
      audit(c, ex, CAN_CREATE_TABLE_AUDIT_TEMPLATE, tableName);
      throw ex;
    }
  }
  
  public static final String CAN_DELETE_TABLE_AUDIT_TEMPLATE = "action: deleteTable; targetTable: %s;";
  
  @Override
  public boolean canDeleteTable(TCredentials c, String tableId) throws ThriftSecurityException {
    String tableName = getTableName(tableId);
    try {
      boolean result = super.canDeleteTable(c, tableId);
      audit(c, result, CAN_DELETE_TABLE_AUDIT_TEMPLATE, tableName, tableId);
      return result;
    } catch (ThriftSecurityException ex) {
      audit(c, ex, CAN_DELETE_TABLE_AUDIT_TEMPLATE, tableName, tableId);
      throw ex;
    }
  }
  
  public static final String CAN_RENAME_TABLE_AUDIT_TEMPLATE = "action: renameTable; targetTable: %s; newTableName: %s;";
  
  @Override
  public boolean canRenameTable(TCredentials c, String tableId, String oldTableName, String newTableName) throws ThriftSecurityException {
    try {
      boolean result = super.canRenameTable(c, tableId, oldTableName, newTableName);
      audit(c, result, CAN_RENAME_TABLE_AUDIT_TEMPLATE, oldTableName, newTableName);
      return result;
    } catch (ThriftSecurityException ex) {
      audit(c, ex, CAN_RENAME_TABLE_AUDIT_TEMPLATE, oldTableName, newTableName);
      throw ex;
    }
  }
  
  public static final String CAN_CLONE_TABLE_AUDIT_TEMPLATE = "action: cloneTable; targetTable: %s; newTableName: %s";
  
  @Override
  public boolean canCloneTable(TCredentials c, String tableId, String tableName) throws ThriftSecurityException {
    String oldTableName = getTableName(tableId);
    try {
      boolean result = super.canCloneTable(c, tableId, tableName);
      audit(c, result, CAN_CLONE_TABLE_AUDIT_TEMPLATE, oldTableName, tableName);
      return result;
    } catch (ThriftSecurityException ex) {
      audit(c, ex, CAN_CLONE_TABLE_AUDIT_TEMPLATE, oldTableName, tableName);
      throw ex;
    }
  }
  
  public static final String CAN_DELETE_RANGE_AUDIT_TEMPLATE = "action: deleteData; targetTable: %s; startRange: %s; endRange: %s;";
  
  @Override
  public boolean canDeleteRange(TCredentials c, String tableId, String tableName, Text startRow, Text endRow) throws ThriftSecurityException {
    try {
      boolean result = super.canDeleteRange(c, tableId, tableName, startRow, endRow);
      audit(c, result, CAN_DELETE_RANGE_AUDIT_TEMPLATE, tableName, startRow.toString(), endRow.toString());
      return result;
    } catch (ThriftSecurityException ex) {
      audit(c, ex, CAN_DELETE_RANGE_AUDIT_TEMPLATE, tableName, startRow.toString(), endRow.toString());
      throw ex;
    }
  }
  
  public static final String CAN_BULK_IMPORT_AUDIT_TEMPLATE = "action: bulkImport; targetTable: %s; dataDir: %s; failDir: %s;";
  
  @Override
  public boolean canBulkImport(TCredentials c, String tableId, String tableName, String dir, String failDir) throws ThriftSecurityException {
    try {
      boolean result = super.canBulkImport(c, tableId);
      audit(c, result, CAN_BULK_IMPORT_AUDIT_TEMPLATE, tableName, dir, failDir);
      return result;
    } catch (ThriftSecurityException ex) {
      audit(c, ex, CAN_BULK_IMPORT_AUDIT_TEMPLATE, tableName, dir, failDir);
      throw ex;
    }
  }
  
  public static final String CAN_IMPORT_AUDIT_TEMPLATE = "action: import; targetTable: %s; dataDir: %s;";
  
  @Override
  public boolean canImport(TCredentials credentials, String tableName, String importDir) throws ThriftSecurityException {
    
    try {
      boolean result = super.canImport(credentials, tableName, importDir);
      audit(credentials, result, CAN_IMPORT_AUDIT_TEMPLATE, tableName, importDir);
      return result;
    } catch (ThriftSecurityException ex) {
      audit(credentials, ex, CAN_IMPORT_AUDIT_TEMPLATE, tableName, importDir);
      throw ex;
    }
  }
  
  public static final String CAN_EXPORT_AUDIT_TEMPLATE = "action: export; targetTable: %s; dataDir: %s;";
  
  @Override
  public boolean canExport(TCredentials credentials, String tableId, String tableName, String exportDir) throws ThriftSecurityException {
    
    try {
      boolean result = super.canExport(credentials, tableId, tableName, exportDir);
      audit(credentials, result, CAN_EXPORT_AUDIT_TEMPLATE, tableName, exportDir);
      return result;
    } catch (ThriftSecurityException ex) {
      audit(credentials, ex, CAN_EXPORT_AUDIT_TEMPLATE, tableName, exportDir);
      throw ex;
    }
  }
  
  public static final String DROP_USER_AUDIT_TEMPLATE = "action: dropUser; targetUser: %s;";
  
  @Override
  public void dropUser(TCredentials credentials, String user) throws ThriftSecurityException {
    try {
      super.dropUser(credentials, user);
      audit(credentials, DROP_USER_AUDIT_TEMPLATE, user);
    } catch (ThriftSecurityException ex) {
      audit(credentials, ex, DROP_USER_AUDIT_TEMPLATE, user);
      throw ex;
    }
  }
  
  public static final String GRANT_SYSTEM_PERMISSION_AUDIT_TEMPLATE = "action: grantSystemPermission; permission: %s; targetUser: %s;";
  
  @Override
  public void grantSystemPermission(TCredentials credentials, String user, SystemPermission permission) throws ThriftSecurityException {
    try {
      super.grantSystemPermission(credentials, user, permission);
      audit(credentials, GRANT_SYSTEM_PERMISSION_AUDIT_TEMPLATE, permission, user);
    } catch (ThriftSecurityException ex) {
      audit(credentials, ex, GRANT_SYSTEM_PERMISSION_AUDIT_TEMPLATE, permission, user);
      throw ex;
    }
  }
  
  public static final String GRANT_TABLE_PERMISSION_AUDIT_TEMPLATE = "action: grantTablePermission; permission: %s; targetTable: %s; targetUser: %s;";
  
  @Override
  public void grantTablePermission(TCredentials credentials, String user, String tableId, TablePermission permission) throws ThriftSecurityException {
    String tableName = getTableName(tableId);
    try {
      super.grantTablePermission(credentials, user, tableId, permission);
      audit(credentials, GRANT_TABLE_PERMISSION_AUDIT_TEMPLATE, permission, tableName, user);
    } catch (ThriftSecurityException ex) {
      audit(credentials, ex, GRANT_TABLE_PERMISSION_AUDIT_TEMPLATE, permission, tableName, user);
      throw ex;
    }
  }
  
  public static final String REVOKE_SYSTEM_PERMISSION_AUDIT_TEMPLATE = "action: revokeSystemPermission; permission: %s; targetUser: %s;";
  
  @Override
  public void revokeSystemPermission(TCredentials credentials, String user, SystemPermission permission) throws ThriftSecurityException {
    
    try {
      super.revokeSystemPermission(credentials, user, permission);
      audit(credentials, REVOKE_SYSTEM_PERMISSION_AUDIT_TEMPLATE, permission, user);
    } catch (ThriftSecurityException ex) {
      audit(credentials, ex, REVOKE_SYSTEM_PERMISSION_AUDIT_TEMPLATE, permission, user);
      throw ex;
    }
  }
  
  public static final String REVOKE_TABLE_PERMISSION_AUDIT_TEMPLATE = "action: revokeTablePermission; permission: %s; targetTable: %s; targetUser: %s;";
  
  @Override
  public void revokeTablePermission(TCredentials credentials, String user, String tableId, TablePermission permission) throws ThriftSecurityException {
    String tableName = getTableName(tableId);
    try {
      super.revokeTablePermission(credentials, user, tableId, permission);
      audit(credentials, REVOKE_TABLE_PERMISSION_AUDIT_TEMPLATE, permission, tableName, user);
    } catch (ThriftSecurityException ex) {
      audit(credentials, ex, REVOKE_TABLE_PERMISSION_AUDIT_TEMPLATE, permission, tableName, user);
      throw ex;
    }
  }
  
  public static final String CAN_ONLINE_OFFLINE_TABLE_AUDIT_TEMPLATE = "action: %s; targetTable: %s;";
  
  @Override
  public boolean canOnlineOfflineTable(TCredentials credentials, String tableId, TableOperation op) throws ThriftSecurityException {
    String tableName = getTableName(tableId);
    String operation = null;
    if (op == TableOperation.ONLINE)
      operation = "onlineTable";
    if (op == TableOperation.OFFLINE)
      operation = "offlineTable";
    try {
      boolean result = super.canOnlineOfflineTable(credentials, tableId, op);
      audit(credentials, result, CAN_ONLINE_OFFLINE_TABLE_AUDIT_TEMPLATE, operation, tableName, tableId);
      return result;
    } catch (ThriftSecurityException ex) {
      audit(credentials, ex, CAN_ONLINE_OFFLINE_TABLE_AUDIT_TEMPLATE, operation, tableName, tableId);
      throw ex;
    }
  }
}
