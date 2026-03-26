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

import com.dremio.common.expression.SchemaPath;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.physical.base.AbstractSubScan;
import com.dremio.exec.physical.base.OpProps;
import com.dremio.exec.record.BatchSchema;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import java.util.List;

/**
 * SubScan for ClickHouse - holds information needed to read data from ClickHouse.
 */
@JsonTypeName("clickhouse-sub-scan")
public class ClickHouseSubScan extends AbstractSubScan {

  private final List<String> datasetPath;
  private final StoragePluginId pluginId;
  private final BatchSchema schema;
  private final List<SchemaPath> columns;
  private final String tableName;
  private final String database;
  private final String query;

  @JsonCreator
  public ClickHouseSubScan(
      @JsonProperty("props") OpProps props,
      @JsonProperty("datasetPath") List<String> datasetPath,
      @JsonProperty("schema") BatchSchema schema,
      @JsonProperty("columns") List<SchemaPath> columns,
      @JsonProperty("pluginId") StoragePluginId pluginId,
      @JsonProperty("tableName") String tableName,
      @JsonProperty("database") String database,
      @JsonProperty("query") String query) {
    super(props, schema, datasetPath);
    this.datasetPath = datasetPath;
    this.schema = schema;
    this.columns = columns;
    this.pluginId = pluginId;
    this.tableName = tableName;
    this.database = database;
    this.query = query;
  }

  public List<String> getDatasetPath() {
    return datasetPath;
  }

  public StoragePluginId getPluginId() {
    return pluginId;
  }

  public BatchSchema getSchema() {
    return schema;
  }

  public List<SchemaPath> getColumns() {
    return columns;
  }

  public String getTableName() {
    return tableName;
  }

  public String getDatabase() {
    return database;
  }

  public String getQuery() {
    return query;
  }
}
