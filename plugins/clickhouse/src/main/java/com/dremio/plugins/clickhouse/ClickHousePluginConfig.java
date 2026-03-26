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

import com.dremio.exec.catalog.ConnectionConf;
import com.dremio.exec.catalog.conf.SourceType;
import com.dremio.exec.catalog.conf.Host;
import com.dremio.exec.catalog.conf.Username;
import com.dremio.exec.catalog.conf.Password;
import com.dremio.exec.catalog.conf.AuthenticationType;
import io.protostuff.Tag;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;

/**
 * Configuration for ClickHouse Arrow Flight storage plugin.
 */
@SourceType(value = "CLICKHOUSE", label = "ClickHouse")
public class ClickHousePluginConfig extends ConnectionConf<ClickHousePluginConfig, ClickHouseStoragePlugin> {

  @NotEmpty
  @Tag(1)
  public String host = "localhost";

  @NotNull
  @Tag(2)
  public int port = 18123;  // ClickHouse HTTP interface (mapped from 8123)

  @Tag(3)
  @Username
  public String username = "default";

  @Tag(4)
  @Password
  public String password = "";

  @Tag(5)
  public AuthenticationType authenticationType = AuthenticationType.NONE;

  @Tag(6)
  public String database = "default";

  @Tag(7)
  public boolean useSsl = false;

  @Tag(8)
  public int timeout = 60;

  @Override
  public ClickHouseStoragePlugin newPlugin(
      com.dremio.exec.catalog.PluginSabotContext pluginSabotContext,
      String name,
      javax.inject.Provider<com.dremio.exec.catalog.StoragePluginId> pluginIdProvider) {
    return new ClickHouseStoragePlugin(this, pluginSabotContext, name, pluginIdProvider);
  }
}
