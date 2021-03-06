// Copyright 2012 Cloudera Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.cloudera.impala.catalog;

import java.util.HashMap;
import java.util.List;

import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.log4j.Logger;

import com.cloudera.impala.analysis.FunctionName;
import com.cloudera.impala.catalog.MetaStoreClientPool.MetaStoreClient;
import com.cloudera.impala.common.ImpalaException;
import com.cloudera.impala.thrift.TCatalogObjectType;
import com.cloudera.impala.thrift.TDatabase;
import com.cloudera.impala.thrift.TFunctionType;
import com.cloudera.impala.thrift.TStatusCode;
import com.cloudera.impala.thrift.TTable;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheLoader;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

/**
 * Internal representation of db-related metadata. Owned by Catalog instance.
 * Not thread safe.
 *
 * The static initialisation method loadDb is the only way to construct a Db
 * object.
 *
 * Tables are stored in a map from the table name to the table object. They may
 * be loaded 'eagerly' at construction or 'lazily' on first reference.
 * Tables are accessed via getTable which may trigger a metadata read in two cases:
 *  * if the table has never been loaded
 *  * if the table loading failed on the previous attempt
 */
public class Db implements CatalogObject {
  private static final Logger LOG = Logger.getLogger(Db.class);
  private final Catalog parentCatalog_;
  private final TDatabase thriftDb_;
  private long catalogVersion_ = Catalog.INITIAL_CATALOG_VERSION;

  // All of the registered user functions. The key is the user facing name (e.g. "myUdf"),
  // and the values are all the overloaded variants (e.g. myUdf(double), myUdf(string))
  // This includes both UDFs and UDAs
  private final HashMap<String, List<Function>> functions_;

  // Table metadata cache.
  private final CatalogObjectCache<Table> tableCache_ = new CatalogObjectCache<Table>(
      new CacheLoader<String, Table>() {
        @Override
        public Table load(String tableName) throws TableNotFoundException,
            TableLoadingException {
          return loadTable(tableName, null);
        }

        @Override
        public ListenableFuture<Table> reload(String tableName, Table oldValue)
            throws ImpalaException {
          SettableFuture<Table> newValue = SettableFuture.create();
          try {
            Table newTable = loadTable(tableName, oldValue);
            newTable.setCatalogVersion(Catalog.incrementAndGetCatalogVersion());
            newValue.set(newTable);
          } catch (ImpalaException e) {
            // Invalidate the table metadata if load fails.
            Db.this.invalidateTable(tableName);
            throw e;
          }
          return newValue;
        }
      });

  private Table loadTable(String tableName, Table oldValue) throws TableLoadingException,
      TableNotFoundException {
    tableName = tableName.toLowerCase();
    MetaStoreClient msClient = parentCatalog_.getMetaStoreClient();
    try {
      // Try to load the table Metadata
      return Table.load(parentCatalog_.getNextTableId(), msClient.getHiveClient(),
          this, tableName, oldValue);
    } finally {
      msClient.release();
    }
  }

  /**
   * Loads all tables in the the table map, ignoring any tables that don't load
   * correctly.
   */
  private void forceLoadAllTables() {
    LOG.info("Force loading all tables for database: " + this.getName());
    for (String tableName: getAllTableNames()) {
      try {
        tableCache_.get(tableName);
      } catch (Exception ex) {
        LOG.warn("Ignoring table: " + tableName + " due to error when loading", ex);
      }
    }
  }

  private Db(String name, Catalog catalog, HiveMetaStoreClient hiveClient)
      throws MetaException {
    this(name, catalog);
    tableCache_.add(hiveClient.getAllTables(name));
    LOG.info("Added " + tableCache_.getAllNames().size() + " " +
             "tables to Db cache: " + this.getName());
  }


  private Db(String name, Catalog catalog) {
    thriftDb_ = new TDatabase(name);
    this.parentCatalog_ = catalog;
    functions_ = new HashMap<String, List<Function>>();
  }

  /**
   * Load the metadata of a Hive database into our own in-memory metadata
   * representation.  Ignore tables with columns of unsupported types (all
   * complex types). Throws an exception if there is an error communicating with
   * the metastore.
   *
   * @param client
   *          HiveMetaStoreClient to communicate with Metastore
   * @param dbName
   * @param lazy
   *          if true, tables themselves are loaded lazily on read, otherwise
   *          they are read eagerly in this method. The set of table names is
   *          always loaded. If false - meaning all tables are read - malformed
   *          tables that do not load are logged and ignored with no exception
   *          thrown.
   * @return non-null Db instance (possibly containing no tables)
   */
  public static Db loadDb(Catalog catalog, HiveMetaStoreClient client, String dbName,
       boolean lazy) {
    try {
      Db db = new Db(dbName, catalog, client);
      // Load all the table metadata
      if (!lazy) db.forceLoadAllTables();
      return db;
    } catch (MetaException e) {
      // turn into unchecked exception
      throw new IllegalStateException(e);
    }
  }

  /**
   * Creates a Db object with no tables based on the given TDatabase thrift struct.
   */
  public static Db fromTDatabase(TDatabase db, Catalog parentCatalog) {
    return new Db(db.getDb_name(), parentCatalog);
  }

  public TDatabase toThrift() { return thriftDb_; }
  public String getName() { return thriftDb_.getDb_name(); }
  public TCatalogObjectType getCatalogObjectType() {
    return TCatalogObjectType.DATABASE;
  }

  public List<String> getAllTableNames() {
    return Lists.newArrayList(tableCache_.getAllNames());
  }

  public boolean containsTable(String tableName) {
    return tableCache_.contains(tableName);
  }

  /**
   * Case-insensitive lookup. Returns null if a table does not exist, throws an
   * exception if the table metadata could not be loaded.
   */
  public Table getTable(String tbl) throws TableLoadingException {
    try {
      return tableCache_.get(tbl);
    } catch (TableNotFoundException e) {
      return null;
    } catch (TableLoadingException e) {
      throw e;
    } catch (ImpalaException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Returns the Table with the given name if present in the table cache, null otherwise.
   */
  public Table getTableIfPresent(String tblName) {
    return tableCache_.getIfPresent(tblName);
  }

  /**
   * Adds a table to the table list. Table cache will be populated on the next
   * getTable().
   */
  public long addTable(String tableName) { return tableCache_.add(tableName); }

  /**
   * Creates a new Table object from the given thrift representation and adds
   * it to the table list, assigning it the given catalog version. If the thrift table
   * has a LoadStatus that is not set to OK, it indicates that the metadata is incomplete
   * and a new IncompleteTable will be added to the table list.
   */
  public void addTable(TTable thriftTable, long catalogVersion)
      throws TableLoadingException {
    // If LoadStatus is not set, or if it is set to OK, it indicates loading of the table
    // was successful.
    if (!thriftTable.isSetLoad_status() ||
        thriftTable.getLoad_status().status_code == TStatusCode.OK) {

      Preconditions.checkState(thriftTable.isSetMetastore_table());
      Table table = Table.fromMetastoreTable(new TableId(thriftTable.getId()), this,
          thriftTable.getMetastore_table());
      table.loadFromThrift(thriftTable);
      table.setCatalogVersion(catalogVersion);
      tableCache_.add(table);
    } else {
      // This table's metadata is incomplete. The error message in the load status
      // should provide details on why. By convention, the final error message should
      // be the remote (Catalog Server) call stack. This shouldn't be displayed to the
      // user under normal circumstances, but needs to be recorded somewhere so append
      // it to the call stack of the local TableLoadingException created here.
      // TODO: Provide a mechanism (query option?) to optionally allow returning more
      // detailed errors (including the full call stack(s)) to the user.
      List<String> errorMsgs = thriftTable.getLoad_status().getError_msgs();
      String callStackStr = "<None available>";
      if (errorMsgs.size() > 1) callStackStr = errorMsgs.remove(errorMsgs.size() - 1);

      TableLoadingException loadingException = new TableLoadingException(
          Joiner.on("\n").join(errorMsgs));
      List<StackTraceElement> stackTrace =
          Lists.newArrayList(loadingException.getStackTrace());
      stackTrace.add(new StackTraceElement("========",
          "<Remote stack trace on catalogd>: " + callStackStr, "", -1));
      loadingException.setStackTrace(
          stackTrace.toArray(new StackTraceElement[stackTrace.size()]));

      // This table's metadata is incomplete. It will show up in the catalog, but
      // if it is accessed it will throw a TableLoadingException. The TableId for
      // the table doesn't matter because can never be sent to the BE, so just assign
      // it an invalid ID.
      IncompleteTable table = new IncompleteTable(TableId.createInvalidId(),
          this, thriftTable.getTbl_name(), loadingException);
      table.setCatalogVersion(catalogVersion);
      tableCache_.add(table);
    }
  }

  /**
   * Removes the table name and any cached metadata from the Table cache.
   */
  public long removeTable(String tableName) {
    return tableCache_.remove(tableName);
  }

  /**
   * Refresh the metadata for the given table name if the table already exists in the
   * cache, or load the table metadata if the table has not been loaded.
   * If refreshing the table metadata failed, no exception will be thrown but the
   * existing metadata will be invalidated.
   */
  public long refreshTable(String tableName) {
    return tableCache_.refresh(tableName);
  }

  /**
   * Returns all the function signatures in this DB that match the specified
   * fuction type. If the function type is null, all function signatures are returned.
   */
  public List<String> getAllFunctionSignatures(TFunctionType type) {
    List<String> names = Lists.newArrayList();
    synchronized (functions_) {
      for (List<Function> fns: functions_.values()) {
        for (Function f: fns) {
          if (type == null || (type == TFunctionType.SCALAR && f instanceof Udf) ||
               type == TFunctionType.AGGREGATE && f instanceof Uda) {
            names.add(f.signatureString());
          }
        }
      }
    }
    return names;
  }

  /**
   * Returns the number of functions in this database.
   */
  public int numFunctions() {
    synchronized (functions_) {
      return functions_.size();
    }
  }

  /**
   * See comment in Catalog.
   */
  public boolean functionExists(FunctionName name) {
    synchronized (functions_) {
      return functions_.get(name.getFunction()) != null;
    }
  }

  /*
   * See comment in Catalog.
   */
  public Function getFunction(Function desc, Function.CompareMode mode) {
    synchronized (functions_) {
      List<Function> fns = functions_.get(desc.functionName());
      if (fns == null) return null;

      // First check for identical
      for (Function f: fns) {
        if (f.compare(desc, Function.CompareMode.IS_IDENTICAL)) return f;
      }
      if (mode == Function.CompareMode.IS_IDENTICAL) return null;

      // Next check for indistinguishable
      for (Function f: fns) {
        if (f.compare(desc, Function.CompareMode.IS_INDISTINGUISHABLE)) return f;
      }
      if (mode == Function.CompareMode.IS_INDISTINGUISHABLE) return null;

      // Finally check for is_subtype
      for (Function f: fns) {
        if (f.compare(desc, Function.CompareMode.IS_SUBTYPE)) return f;
      }
    }
    return null;
  }

  public Function getFunction(String signatureString) {
    synchronized (functions_) {
      for (List<Function> fns: functions_.values()) {
        for (Function f: fns) {
          if (f.signatureString().equals(signatureString)) return f;
        }
      }
    }
    return null;
  }

  /**
   * See comment in Catalog.
   */
  public boolean addFunction(Function fn) {
    // TODO: add this to persistent store
    synchronized (functions_) {
      if (getFunction(fn, Function.CompareMode.IS_INDISTINGUISHABLE) != null) {
        return false;
      }
      List<Function> fns = functions_.get(fn.functionName());
      if (fns == null) {
        fns = Lists.newArrayList();
        functions_.put(fn.functionName(), fns);
      }
      fn.setCatalogVersion(Catalog.incrementAndGetCatalogVersion());
      fns.add(fn);
    }
    return true;
  }

  /**
   * See comment in Catalog.
   */
  public Function removeFunction(Function desc) {
    // TODO: remove this from persistent store.
    synchronized (functions_) {
      Function fn = getFunction(desc, Function.CompareMode.IS_INDISTINGUISHABLE);
      if (fn == null) return null;
      List<Function> fns = functions_.get(desc.functionName());
      Preconditions.checkNotNull(fns);
      fns.remove(fn);
      if (fns.isEmpty()) functions_.remove(desc.functionName());
      return fn;
    }
  }

  /**
   * Removes a Function with the matching signature string. Returns the removed Function
   * if a Function was removed as a result of this call, null otherwise.
   * TODO: Move away from using signature strings and instead use Function IDs.
   */
  public Function removeFunction(String signatureStr) {
    synchronized (functions_) {
      Function targetFn = getFunction(signatureStr);
      if (targetFn != null) return removeFunction(targetFn);
    }
    return null;
  }

  /**
   * Returns a map of functionNames to list of (overloaded) functions with that name.
   */
  public HashMap<String, List<Function>> getAllFunctions() {
    return functions_;
  }

  /**
   * Marks the table as invalid so the next access will trigger a metadata load.
   */
  public long invalidateTable(String tableName) {
    return tableCache_.invalidate(tableName);
  }


  @Override
  public long getCatalogVersion() { return catalogVersion_; }
  @Override
  public void setCatalogVersion(long newVersion) { catalogVersion_ = newVersion; }
  public Catalog getParentCatalog() { return parentCatalog_; }
}
