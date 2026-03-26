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
import com.dremio.exec.catalog.AuthenticationType;
import com.dremio.exec.physical.base.GroupScan;
import com.dremio.exec.store.AbstractRecordReader;
import com.dremio.plugins.clickhouse.ClickHouseDatasetHandle;
import com.dremio.plugins.clickhouse.ClickHousePluginConfig;
import com.dremio.plugins.clickhouse.ClickHouseStoragePlugin;
import com.dremio.plugins.clickhouse.ClickHouseSubScan;
import com.dremio.sabot.exec.context.OperatorContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RecordReader for ClickHouse using JDBC.
 */
public class ClickHouseRecordReader extends AbstractRecordReader {

  private static final Logger logger = LoggerFactory.getLogger(ClickHouseRecordReader.class);

  private final ClickHouseStoragePlugin plugin;
  private final ClickHouseSubScan subScan;
  private final OperatorContext context;
  private final List<SchemaPath> columns;
  
  private BufferAllocator allocator;
  private Connection connection;
  private PreparedStatement statement;
  private ResultSet resultSet;
  private ResultSetMetaData metaData;
  private int columnCount = 0;
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
      // Create JDBC connection
      ClickHousePluginConfig cfg = plugin.getConfig();
      
      // Build JDBC URL
      StringBuilder urlBuilder = new StringBuilder();
      urlBuilder.append("jdbc:clickhouse:http://");
      urlBuilder.append(cfg.host);
      urlBuilder.append(":");
      urlBuilder.append(cfg.port);
      urlBuilder.append("/");
      urlBuilder.append(subScan.getDatabase() != null ? subScan.getDatabase() : cfg.database);
      urlBuilder.append("?compress=0");
      
      if (cfg.connectionTimeout > 0) {
        urlBuilder.append("&connect_timeout=").append(cfg.connectionTimeout * 1000);
      }
      
      String url = urlBuilder.toString();
      
      if (cfg.authenticationType == AuthenticationType.USERNAME_PASSWORD) {
        connection = DriverManager.getConnection(url, cfg.username, cfg.password);
      } else {
        connection = DriverManager.getConnection(url);
      }
      
      // Build the query
      String query = buildQuery();
      logger.debug("Executing ClickHouse query: {}", query);
      
      statement = connection.prepareStatement(query);
      resultSet = statement.executeQuery();
      metaData = resultSet.getMetaData();
      columnCount = metaData.getColumnCount();
      
      // Setup output vectors based on result set metadata
      List<Field> fields = new ArrayList<>();
      for (int i = 1; i <= columnCount; i++) {
        String columnName = metaData.getColumnLabel(i);
        int sqlType = metaData.getColumnType(i);
        String typeName = metaData.getColumnTypeName(i);
        ArrowType arrowType = mapSqlTypeToArrowType(sqlType, typeName);
        fields.add(new Field(columnName, org.apache.arrow.vector.types.pojo.FieldType.nullable(arrowType), null));
      }
      
      Schema schema = new Schema(fields);
      setupOutgoingSchema(schema);
      
      initialized = true;
      
    } catch (SQLException e) {
      logger.error("Failed to setup ClickHouse record reader", e);
      throw new ExecutionSetupException("Failed to setup ClickHouse record reader", e);
    }
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

  private ArrowType mapSqlTypeToArrowType(int sqlType, String typeName) {
    // Map SQL types to Arrow types
    switch (sqlType) {
      case java.sql.Types.TINYINT:
      case java.sql.Types.SMALLINT:
        return new ArrowType.Int(16, true);
      case java.sql.Types.INTEGER:
        return new ArrowType.Int(32, true);
      case java.sql.Types.BIGINT:
        return new ArrowType.Int(64, true);
      case java.sql.Types.FLOAT:
      case java.sql.Types.REAL:
        return new ArrowType.FloatingPoint(ArrowType.FloatingPointPrecision.SINGLE);
      case java.sql.Types.DOUBLE:
        return new ArrowType.FloatingPoint(ArrowType.FloatingPointPrecision.DOUBLE);
      case java.sql.Types.DECIMAL:
        return new ArrowType.Decimal(38, 9, 256);
      case java.sql.Types.CHAR:
      case java.sql.Types.VARCHAR:
      case java.sql.Types.LONGVARCHAR:
      case java.sql.Types.NCHAR:
      case java.sql.Types.NVARCHAR:
      case java.sql.Types.LONGNVARCHAR:
        return new ArrowType.Utf8();
      case java.sql.Types.DATE:
      case java.sql.Types.TIME:
      case java.sql.Types.TIMESTAMP:
      case java.sql.Types.TIME_WITH_TIMEZONE:
      case java.sql.Types.TIMESTAMP_WITH_TIMEZONE:
        return new ArrowType.Date(ArrowType.DateUnit.MILLISECOND);
      case java.sql.Types.BOOLEAN:
        return new ArrowType.Bool();
      case java.sql.Types.BINARY:
      case java.sql.Types.VARBINARY:
      case java.sql.Types.LONGVARBINARY:
        return new ArrowType.Binary();
      case java.sql.Types.BLOB:
      case java.sql.Types.CLOB:
        return new ArrowType.Binary();
      default:
        // For ClickHouse-specific types like Array, Map, UUID, etc.
        if (typeName != null) {
          switch (typeName.toLowerCase()) {
            case "uuid":
              return new ArrowType.FixedSizeBinary(16);
            case "ipv4":
              return new ArrowType.FixedSizeBinary(4);
            case "ipv6":
              return new ArrowType.FixedSizeBinary(16);
            case "json":
            case "object":
              return new ArrowType.Utf8();
            default:
              break;
          }
        }
        return new ArrowType.Utf8();
    }
  }

  @Override
  public VectorSchemaRoot next() throws ExecutionSetupException {
    if (!initialized || resultSet == null) {
      return null;
    }

    try {
      if (!resultSet.next()) {
        return null;
      }
      
      // Transfer data to vectors
      for (int i = 0; i < getOutputVectorContainer().getNumberOfColumns(); i++) {
        org.apache.arrow.vector.ValueVector vector = getOutputVectorContainer().getVector(i);
        int rowIndex = getOutputVectorContainer().getRowCount();
        
        // Add value based on type
        Object value = resultSet.getObject(i + 1);
        if (value != null && !resultSet.wasNull()) {
          setValue(vector, rowIndex, value);
        }
      }
      
      getOutputVectorContainer().setRowCount(getOutputVectorContainer().getRowCount() + 1);
      
    } catch (SQLException e) {
      logger.error("Error reading next row from ClickHouse", e);
      throw new ExecutionSetupException("Error reading next row from ClickHouse", e);
    }
    
    return null; // Data is already in the output container
  }

  private void setValue(org.apache.arrow.vector.ValueVector vector, int rowIndex, Object value) {
    // Handle value setting based on vector type
    if (vector instanceof org.apache.arrow.vector.IntVector) {
      ((org.apache.arrow.vector.IntVector) vector).set(rowIndex, ((Number) value).intValue());
    } else if (vector instanceof org.apache.arrow.vector.BigIntVector) {
      ((org.apache.arrow.vector.BigIntVector) vector).set(rowIndex, ((Number) value).longValue());
    } else if (vector instanceof org.apache.arrow.vector.Float4Vector) {
      ((org.apache.arrow.vector.Float4Vector) vector).set(rowIndex, ((Number) value).floatValue());
    } else if (vector instanceof org.apache.arrow.vector.Float8Vector) {
      ((org.apache.arrow.vector.Float8Vector) vector).set(rowIndex, ((Number) value).doubleValue());
    } else if (vector instanceof org.apache.arrow.vector.VarCharVector) {
      byte[] bytes = value.toString().getBytes();
      ((org.apache.arrow.vector.VarCharVector) vector).set(rowIndex, bytes);
    } else if (vector instanceof org.apache.arrow.vector.VarCharVector) {
      byte[] bytes = value.toString().getBytes();
      ((org.apache.arrow.vector.VarCharVector) vector).set(rowIndex, bytes);
    } else if (vector instanceof org.apache.arrow.vector.BitVector) {
      ((org.apache.arrow.vector.BitVector) vector).set(rowIndex, ((Boolean) value) ? 1 : 0);
    } else {
      // Default: try to convert to string
      byte[] bytes = value.toString().getBytes();
      if (vector instanceof org.apache.arrow.vector.VarCharVector) {
        ((org.apache.arrow.vector.VarCharVector) vector).set(rowIndex, bytes);
      }
    }
  }

  @Override
  public void close() throws Exception {
    if (resultSet != null) {
      resultSet.close();
    }
    if (statement != null) {
      statement.close();
    }
    if (connection != null) {
      connection.close();
    }
  }

  @Override
  public void allocate() throws ExecutionSetupException {
    // Vectors are allocated by the VectorContainer
  }
}
