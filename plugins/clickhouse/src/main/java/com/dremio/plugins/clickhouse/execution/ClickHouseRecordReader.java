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
package com.dremio.plugins.clickhouse.execution;

import com.dremio.common.exceptions.ExecutionSetupException;
import com.dremio.common.expression.SchemaPath;
import com.dremio.exec.physical.base.GroupScan;
import com.dremio.exec.store.AbstractRecordReader;
import com.dremio.plugins.clickhouse.ClickHouseStoragePlugin;
import com.dremio.plugins.clickhouse.ClickHouseSubScan;
import com.dremio.sabot.exec.context.OperatorContext;
import io.grpc.ManagedChannel;
import java.util.ArrayList;
import java.util.List;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightSql;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.Location;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VectorLoader;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RecordReader for ClickHouse using Arrow Flight SQL.
 */
public class ClickHouseRecordReader extends AbstractRecordReader {

  private static final Logger logger = LoggerFactory.getLogger(ClickHouseRecordReader.class);

  private final ClickHouseStoragePlugin plugin;
  private final ClickHouseSubScan subScan;
  private final OperatorContext context;
  private final List<SchemaPath> columns;
  
  private BufferAllocator allocator;
  private FlightClient flightClient;
  private ManagedChannel channel;
  private FlightInfo flightInfo;
  private VectorSchemaRoot root;
  private VectorLoader loader;
  private int currentRow = 0;
  private boolean initialized = false;

  public ClickHouseRecordReader(
      ClickHouseStoragePlugin plugin,
      ClickHouseSubScan subScan,
      OperatorContext context) {
    super(context, subScan.getColumns());
    this.plugin = plugin;
    this.subScan = subScan;
    this.context = context;
    this.columns = subScan.getColumns();
  }

  @Override
  public void setup(org.apache.arrow.vector.VectorContainer container) throws ExecutionSetupException {
    allocator = context.getAllocator();
    
    try {
      // Create Flight client connection
      createFlightClient();
      
      // Execute query and get Flight info
      FlightSql.SqlClient sqlClient = new FlightSql.SqlClient(flightClient);
      
      // Build the query
      String query = buildQuery();
      logger.debug("Executing ClickHouse query: {}", query);
      
      flightInfo = sqlClient.executeQuery(query);
      root = flightInfo.getSchemaRoot();
      
      // Setup the output container with the schema
      for (org.apache.arrow.vector.types.pojo.Field field : root.getSchema().getFields()) {
        container.addOrGet(field.createVector(allocator));
      }
      container.build();
      
      loader = new VectorLoader(container);
      initialized = true;
      
    } catch (Exception e) {
      logger.error("Failed to setup ClickHouse record reader", e);
      throw new ExecutionSetupException("Failed to setup ClickHouse record reader", e);
    }
  }

  private void createFlightClient() {
    ClickHousePluginConfig config = plugin.getConfig();
    
    Location location;
    if (config.useSsl) {
      location = Location.forGrpcTls(config.host, config.port);
    } else {
      location = Location.forGrpcInsecure(config.host, config.port);
    }

    channel = FlightClient.builder()
        .allocator(allocator)
        .location(location)
        .build();
    
    flightClient = channel;
  }

  private String buildQuery() {
    StringBuilder query = new StringBuilder("SELECT ");
    
    if (columns == null || columns.isEmpty() || columns.contains(GroupScan.ALL_COLUMNS)) {
      query.append("*");
    } else {
      List<String> columnNames = new ArrayList<>();
      for (SchemaPath path : columns) {
        columnNames.add(path.getAsUnescapedPath());
      }
      query.append(String.join(", ", columnNames));
    }
    
    query.append(" FROM ");
    if (subScan.getDatabase() != null && !subScan.getDatabase().isEmpty()) {
      query.append(subScan.getDatabase()).append(".");
    }
    query.append(subScan.getTableName());
    
    return query.toString();
  }

  @Override
  public org.apache.arrow.vector.VectorSchemaRoot next() throws ExecutionSetupException {
    if (!initialized || root == null) {
      return null;
    }

    try {
      if (root.getRowCount() > currentRow) {
        loader.load(root.getRowCount());
        currentRow = root.getRowCount();
        return root;
      }
      
      // Try to read next batch
      if (flightInfo != null && flightInfo.getBatches() != null) {
        for (org.apache.arrow.flight.FlightStream stream : flightInfo.getBatches()) {
          while (stream.next()) {
            org.apache.arrow.vector.VectorSchemaRoot batchRoot = stream.getRoot();
            if (batchRoot.getRowCount() > 0) {
              currentRow = batchRoot.getRowCount();
              return batchRoot;
            }
          }
        }
      }
    } catch (Exception e) {
      logger.error("Error reading next batch from ClickHouse", e);
    }
    
    return null;
  }

  @Override
  public void close() throws Exception {
    if (flightClient != null) {
      flightClient.close();
    }
    if (channel != null && !channel.isShutdown()) {
      channel.shutdown();
    }
    if (allocator != null) {
      allocator.close();
    }
  }

  @Override
  public void allocate() throws ExecutionSetupException {
    // Vectors are allocated by the VectorContainer
  }
}
