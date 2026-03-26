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

import com.dremio.common.exceptions.ExecutionSetupException;
import com.dremio.exec.store.RecordReader;
import com.dremio.exec.store.parquet.RecordReaderIterator;
import com.dremio.plugins.clickhouse.execution.ClickHouseRecordReader;
import com.dremio.sabot.op.scan.ScanOperator;
import com.dremio.sabot.op.spi.ProducerOperator;
import com.google.common.base.Preconditions;

/**
 * Creator for ClickHouse scan operators.
 */
public class ClickHouseScanCreator implements ProducerOperator.Creator<ClickHouseSubScan> {

  private static final org.slf4j.Logger logger = 
      org.slf4j.LoggerFactory.getLogger(ClickHouseScanCreator.class);

  @Override
  public ProducerOperator create(
      com.dremio.sabot.exec.fragment.FragmentExecutionContext fec,
      com.dremio.sabot.exec.context.OperatorContext context,
      ClickHouseSubScan subScan) throws ExecutionSetupException {
    
    final ClickHouseStoragePlugin plugin = fec.getStoragePlugin(subScan.getPluginId());
    
    Preconditions.checkNotNull(plugin, "ClickHouse plugin not found");
    Preconditions.checkNotNull(subScan.getTableName(), "Table name cannot be null");

    // Create the record reader
    ClickHouseRecordReader recordReader = new ClickHouseRecordReader(plugin, subScan, context);
    
    // Wrap it in a RecordReaderIterator
    RecordReaderIterator readerIterator = RecordReaderIterator.from(recordReader);

    return new ScanOperator(fec, subScan, context, readerIterator);
  }
}
