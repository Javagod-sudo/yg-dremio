/*
 * Copyright (C) 2017-2019 Dremio Corporation
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
package com.dremio.plugins.clickhouse;

import com.dremio.common.AutoCloseables;
import com.dremio.common.exceptions.UserException;
import com.dremio.connector.metadata.DatasetHandle;
import com.dremio.connector.metadata.DatasetHandleListing;
import com.dremio.connector.metadata.DatasetMetadata;
import com.dremio.connector.metadata.EntityPath;
import com.dremio.connector.metadata.GetDatasetOption;
import com.dremio.connector.metadata.GetMetadataOption;
import com.dremio.connector.metadata.ListPartitionChunkOption;
import com.dremio.connector.metadata.PartitionChunkListing;
import com.dremio.connector.metadata.extensions.SupportsListingDatasets;
import com.dremio.exec.catalog.AuthenticationType;
import com.dremio.exec.catalog.PluginSabotContext;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.exec.store.StoragePluginRulesFactory;
import com.dremio.plugins.clickhouse.execution.ClickHouseRecordReader;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.SourceState;
import com.dremio.service.namespace.capabilities.SourceCapabilities;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.inject.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ClickHouse storage plugin using JDBC.
 * 
 * <p>This plugin allows Dremio to query ClickHouse databases using JDBC.
 * It supports:
 * <ul>
 *   <li>Listing databases and tables</li>
 *   <li>Reading table metadata</li>
 *   <li>Query pushdown for better performance</li>
 * </ul>
 */
public class ClickHouseStoragePlugin implements StoragePlugin, SupportsListingDatasets {

  private static final Logger logger = LoggerFactory.getLogger(ClickHouseStoragePlugin.class);

  private final ClickHousePluginConfig config;
  private final PluginSabotContext context;
  private final String name;
  private final Provider<StoragePluginId> pluginIdProvider;
  private volatile Connection connection;

  public ClickHouseStoragePlugin(
      ClickHousePluginConfig config,
      PluginSabotContext context,
      String name,
      Provider<StoragePluginId> pluginIdProvider) {
    this.config = config;
    this.context = context;
    this.name = name;
    this.pluginIdProvider = pluginIdProvider;
  }

  @Override
  public boolean hasAccessPermission(String user, NamespaceKey key, DatasetConfig datasetConfig) {
    return true;
  }

  @Override
  public SourceState getState() {
    try {
      getConnection();
      return SourceState.GOOD;
    } catch (Exception e) {
      logger.warn("ClickHouse source {} is not accessible: {}", name, e.getMessage());
      return SourceState.badState(e.getMessage());
    }
  }

  @Override
  public SourceCapabilities getSourceCapabilities() {
    return SourceCapabilities.SUPPORTS_QUERY_PUSHDOWN;
  }

  @Override
  public Class<? extends StoragePluginRulesFactory> getRulesFactoryClass() {
    return ClickHouseRulesFactory.class;
  }

  @Override
  public void start() throws IOException {
    try {
      // Register ClickHouse JDBC driver
      DriverManager.registerDriver(new com.clickhouse.jdbc.ClickHouseDriver());
      logger.info("ClickHouse storage plugin started for source: {}", name);
    } catch (SQLException e) {
      throw new IOException("Failed to register ClickHouse JDBC driver", e);
    }
  }

  @Override
  public void close() throws Exception {
    AutoCloseables.close(connection);
  }

  /**
   * Get a JDBC connection to ClickHouse.
   */
  public Connection getConnection() throws SQLException {
    if (connection == null) {
      synchronized (this) {
        if (connection == null) {
          connection = createConnection();
        }
      }
    }
    return connection;
  }

  private Connection createConnection() throws SQLException {
    ClickHousePluginConfig cfg = config;
    
    // Build JDBC URL
    StringBuilder urlBuilder = new StringBuilder();
    urlBuilder.append("jdbc:clickhouse:http://");
    urlBuilder.append(cfg.host);
    urlBuilder.append(":");
    urlBuilder.append(cfg.port);
    urlBuilder.append("/");
    urlBuilder.append(cfg.database);
    
    // Add connection parameters
    urlBuilder.append("?compress=0"); // Disable compression for simplicity
    
    if (cfg.connectionTimeout > 0) {
      urlBuilder.append("&connect_timeout=").append(cfg.connectionTimeout * 1000);
    }
    if (cfg.socketTimeout > 0) {
      urlBuilder.append("&socket_timeout=").append(cfg.socketTimeout * 1000);
    }
    
    String url = urlBuilder.toString();
    logger.debug("Connecting to ClickHouse: {}", url.replaceAll("password=[^&]*", "password=***"));
    
    if (cfg.authenticationType == AuthenticationType.USERNAME_PASSWORD) {
      return DriverManager.getConnection(url, cfg.username, cfg.password);
    } else {
      return DriverManager.getConnection(url);
    }
  }

  @Override
  public DatasetHandleListing listDatasetHandles(GetDatasetOption... options) {
    Set<DatasetHandle> handles = new HashSet<>();
    
    try {
      Connection conn = getConnection();
      DatabaseMetaData metaData = conn.getMetaData();
      
      // Get list of databases/catalogs
      try (ResultSet rs = metaData.getCatalogs()) {
        while (rs.next()) {
          String databaseName = rs.getString("TABLE_CAT");
          if (databaseName != null && !databaseName.isEmpty()) {
            EntityPath databasePath = EntityPath.fromString(databaseName);
            handles.add(new ClickHouseDatasetHandle(
                databasePath, 
                databaseName, 
                null,
                this));
          }
        }
      }
      
      // Get list of tables for each database
      try (ResultSet rs = metaData.getTables(null, null, "%", new String[]{"TABLE", "VIEW"})) {
        while (rs.next()) {
          String tableName = rs.getString("TABLE_NAME");
          String databaseName = rs.getString("TABLE_CAT");
          
          // Skip tables without a valid database
          if (tableName == null || databaseName == null) {
            continue;
          }
          
          EntityPath tablePath = EntityPath.fromString(databaseName + "." + tableName);
          handles.add(new ClickHouseDatasetHandle(
              tablePath,
              databaseName,
              tableName,
              this));
        }
      }
      
    } catch (SQLException e) {
      logger.error("Failed to list datasets from ClickHouse", e);
    }

    return () -> handles.iterator();
  }

  @Override
  public Optional<DatasetHandle> getDatasetHandle(EntityPath datasetPath, GetDatasetOption... options) {
    Preconditions.checkArgument(!datasetPath.isEmpty(), "Dataset path cannot be empty");

    List<String> components = datasetPath.getComponents();
    
    if (components.size() == 1) {
      // Database reference
      String database = components.get(0);
      return Optional.of(new ClickHouseDatasetHandle(datasetPath, database, null, this));
    } else if (components.size() == 2) {
      // Table reference
      String database = components.get(0);
      String table = components.get(1);
      return Optional.of(new ClickHouseDatasetHandle(datasetPath, database, table, this));
    }

    return Optional.empty();
  }

  @Override
  public DatasetMetadata getDatasetMetadata(
      DatasetHandle datasetHandle,
      PartitionChunkListing chunkListing,
      GetMetadataOption... options) {
    return datasetHandle.unwrap(ClickHouseDatasetHandle.class);
  }

  @Override
  public PartitionChunkListing listPartitionChunks(
      DatasetHandle datasetHandle, ListPartitionChunkOption... options) {
    return datasetHandle.unwrap(ClickHouseDatasetHandle.class);
  }

  @Override
  public boolean containerExists(EntityPath containerPath, GetMetadataOption... options) {
    try {
      Connection conn = getConnection();
      DatabaseMetaData metaData = conn.getMetaData();
      
      if (containerPath.size() == 1) {
        // Check if database exists
        String database = containerPath.getComponents().get(0);
        try (ResultSet rs = metaData.getCatalogs()) {
          while (rs.next()) {
            if (database.equals(rs.getString("TABLE_CAT"))) {
              return true;
            }
          }
        }
      } else if (containerPath.size() == 2) {
        // Check if table exists
        String database = containerPath.getComponents().get(0);
        String table = containerPath.getComponents().get(1);
        try (ResultSet rs = metaData.getTables(database, null, table, new String[]{"TABLE", "VIEW"})) {
          return rs.next();
        }
      }
    } catch (SQLException e) {
      logger.warn("Failed to check container existence", e);
    }
    return false;
  }

  public PluginSabotContext getContext() {
    return context;
  }

  public String getName() {
    return name;
  }

  public ClickHousePluginConfig getConfig() {
    return config;
  }
}
