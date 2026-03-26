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
import com.dremio.connector.metadata.DatasetHandle;
import com.dremio.connector.metadata.DatasetHandleListing;
import com.dremio.connector.metadata.DatasetMetadata;
import com.dremio.connector.metadata.EntityPath;
import com.dremio.connector.metadata.GetDatasetOption;
import com.dremio.connector.metadata.GetMetadataOption;
import com.dremio.connector.metadata.ListPartitionChunkOption;
import com.dremio.connector.metadata.PartitionChunkListing;
import com.dremio.connector.metadata.extensions.SupportsListingDatasets;
import com.dremio.exec.catalog.PluginSabotContext;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.exec.store.StoragePluginRulesFactory;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.SourceState;
import com.dremio.service.namespace.capabilities.SourceCapabilities;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.google.common.base.Preconditions;
import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Provider;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightSql;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.TlsCredentials;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ClickHouse storage plugin using Arrow Flight SQL protocol.
 * 
 * ClickHouse supports Arrow Flight SQL starting from version 22.3.
 * The default port is 18130 for Arrow Flight SQL.
 */
public class ClickHouseStoragePlugin implements StoragePlugin, SupportsListingDatasets {

  private static final Logger logger = LoggerFactory.getLogger(ClickHouseStoragePlugin.class);

  private final ClickHousePluginConfig config;
  private final PluginSabotContext context;
  private final String name;
  private final Provider<StoragePluginId> pluginIdProvider;
  private final BufferAllocator allocator;
  private final Set<EntityPath> tableList = new HashSet<>();
  private volatile FlightClient flightClient;
  private volatile ManagedChannel channel;

  public ClickHouseStoragePlugin(
      ClickHousePluginConfig config,
      PluginSabotContext context,
      String name,
      Provider<StoragePluginId> pluginIdProvider) {
    this.config = config;
    this.context = context;
    this.name = name;
    this.pluginIdProvider = pluginIdProvider;
    this.allocator = context.getAllocator().newChildAllocator(
        ClickHouseStoragePlugin.class.getName(), 0, Long.MAX_VALUE);
  }

  @Override
  public boolean hasAccessPermission(String user, NamespaceKey key, DatasetConfig datasetConfig) {
    return true;
  }

  @Override
  public SourceState getState() {
    try {
      getFlightClient();
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
    logger.info("ClickHouse Arrow Flight storage plugin started for source: {}", name);
  }

  @Override
  public void close() throws Exception {
    AutoCloseables.close(flightClient, allocator);
  }

  public FlightClient getFlightClient() {
    if (flightClient == null) {
      synchronized (this) {
        if (flightClient == null) {
          createFlightClient();
        }
      }
    }
    return flightClient;
  }

  private void createFlightClient() {
    String host = config.host;
    int port = config.port;
    
    Location location;
    if (config.useSsl) {
      location = Location.forGrpcTls(host, port);
    } else {
      location = Location.forGrpcInsecure(host, port);
    }

    FlightClient.Builder clientBuilder = FlightClient.builder()
        .allocator(allocator)
        .location(location);

    if (config.useSsl) {
      // For TLS, we need to configure certificate validation
      // In production, you should configure the trusted certificates properly
      clientBuilder = clientBuilder
          .credential(TlsCredentials.fromPem(null, null));
    }

    channel = clientBuilder.build();
    flightClient = channel;
    
    logger.debug("Created Flight client to {}:{}", host, port);
  }

  public BufferAllocator getAllocator() {
    return allocator;
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

  @Override
  public DatasetHandleListing listDatasetHandles(GetDatasetOption... options) {
    Set<DatasetHandle> handles = new HashSet<>();
    
    try {
      FlightClient client = getFlightClient();
      FlightSql.SqlClient sqlClient = new FlightSql.SqlClient(client);

      // Get list of tables from the default database
      String query = "SHOW TABLES FROM " + config.database;
      FlightInfo flightInfo = sqlClient.executeQuery(query);
      
      VectorSchemaRoot root = flightInfo.getSchemaRoot();
      if (root != null) {
        List<FieldVector> vectors = root.getFieldVectors();
        if (!vectors.isEmpty()) {
          FieldVector nameVector = vectors.get(0);
          for (int i = 0; i < nameVector.getValueCount(); i++) {
            Object value = nameVector.getObject(i);
            if (value != null) {
              String tableName = value.toString();
              EntityPath tablePath = getTablePath(tableName);
              handles.add(new ClickHouseDatasetHandle(tablePath, config.database, tableName));
              tableList.add(tablePath);
            }
          }
        }
      }
    } catch (Exception e) {
      logger.error("Failed to list datasets from ClickHouse", e);
    }

    return () -> handles.iterator();
  }

  @Override
  public Optional<DatasetHandle> getDatasetHandle(EntityPath datasetPath, GetDatasetOption... options) {
    Preconditions.checkArgument(!datasetPath.isEmpty(), "Dataset path cannot be empty");

    if (datasetPath.size() == 1) {
      // Database reference
      String database = datasetPath.getComponents().get(0);
      return Optional.of(new ClickHouseDatasetHandle(datasetPath, database, null));
    } else if (datasetPath.size() == 2) {
      // Table reference
      String database = datasetPath.getComponents().get(0);
      String table = datasetPath.getComponents().get(1);
      return Optional.of(new ClickHouseDatasetHandle(datasetPath, database, table));
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
    return false;
  }

  public FlightSql.SqlClient getSqlClient() {
    return new FlightSql.SqlClient(getFlightClient());
  }

  private EntityPath getTablePath(String tableName) {
    List<String> components = new ArrayList<>();
    components.add(name); // source name
    if (tableName.contains(".")) {
      components.addAll(Arrays.asList(tableName.split("\\.")));
    } else {
      components.add(tableName);
    }
    return new EntityPath(components);
  }

  public static EntityPath canonicalize(EntityPath entityPath) {
    return new EntityPath(
        entityPath.getComponents().stream().map(String::toLowerCase).collect(Collectors.toList()));
  }
}
