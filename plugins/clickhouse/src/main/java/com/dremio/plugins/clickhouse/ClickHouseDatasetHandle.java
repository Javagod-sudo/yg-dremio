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

import com.dremio.connector.metadata.DatasetHandle;
import com.dremio.connector.metadata.DatasetMetadata;
import com.dremio.connector.metadata.EntityPath;
import com.dremio.connector.metadata.PartitionChunkListing;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Dataset handle for ClickHouse.
 */
public class ClickHouseDatasetHandle implements DatasetHandle, DatasetMetadata, PartitionChunkListing {

  private final EntityPath path;
  private final String database;
  private final String table;
  private final ClickHouseStoragePlugin plugin;

  public ClickHouseDatasetHandle(EntityPath path, String database, String table, ClickHouseStoragePlugin plugin) {
    this.path = path;
    this.database = database;
    this.table = table;
    this.plugin = plugin;
  }

  @Override
  public EntityPath getDatasetPath() {
    return path;
  }

  @Override
  public <T> T unwrap(Class<T> iface) {
    if (iface.isInstance(this)) {
      return iface.cast(this);
    }
    throw new UnsupportedOperationException("Cannot unwrap to " + iface);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ClickHouseDatasetHandle that = (ClickHouseDatasetHandle) o;
    return path.equals(that.path);
  }

  @Override
  public int hashCode() {
    return path.hashCode();
  }

  public String getDatabase() {
    return database;
  }

  public String getTable() {
    return table;
  }

  public boolean isDatabase() {
    return table == null;
  }

  public boolean isTable() {
    return table != null;
  }

  public ClickHouseStoragePlugin getPlugin() {
    return plugin;
  }

  @Override
  public Iterator<PartitionChunk> iterator() {
    // Return a single partition chunk for the table
    return Collections.singletonList(
        com.dremio.exec.store.dfs.implicit.ImplicitFilesystemUtils
            .createPartitionChunk(path.getComponents(), Collections.emptyList())
    ).iterator();
  }
}
