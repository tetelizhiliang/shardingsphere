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

package org.apache.shardingsphere.scaling.core.job.task.incremental;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.scaling.core.config.DumperConfiguration;
import org.apache.shardingsphere.scaling.core.config.ImporterConfiguration;
import org.apache.shardingsphere.scaling.core.config.ScalingContext;
import org.apache.shardingsphere.scaling.core.internal.common.channel.DistributionChannel;
import org.apache.shardingsphere.scaling.core.internal.common.datasource.DataSourceManager;
import org.apache.shardingsphere.scaling.core.internal.common.exception.ScalingTaskExecuteException;
import org.apache.shardingsphere.scaling.core.internal.common.record.Record;
import org.apache.shardingsphere.scaling.core.internal.executor.AbstractScalingExecutor;
import org.apache.shardingsphere.scaling.core.internal.executor.ExecuteCallback;
import org.apache.shardingsphere.scaling.core.internal.executor.dumper.Dumper;
import org.apache.shardingsphere.scaling.core.internal.executor.dumper.DumperFactory;
import org.apache.shardingsphere.scaling.core.internal.executor.importer.Importer;
import org.apache.shardingsphere.scaling.core.internal.executor.importer.ImporterFactory;
import org.apache.shardingsphere.scaling.core.job.position.PlaceholderPosition;
import org.apache.shardingsphere.scaling.core.job.task.ScalingTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Incremental task.
 */
@Slf4j
public final class IncrementalTask extends AbstractScalingExecutor implements ScalingTask {
    
    @Getter
    private final String taskId;
    
    private final int concurrency;
    
    private final DumperConfiguration dumperConfig;
    
    private final ImporterConfiguration importerConfig;
    
    private final DataSourceManager dataSourceManager;
    
    private Dumper dumper;
    
    @Getter
    private IncrementalTaskProgress progress;
    
    public IncrementalTask(final int concurrency, final DumperConfiguration dumperConfig, final ImporterConfiguration importerConfig) {
        this.concurrency = concurrency;
        this.dumperConfig = dumperConfig;
        this.importerConfig = importerConfig;
        dataSourceManager = new DataSourceManager();
        taskId = dumperConfig.getDataSourceName();
        progress = new IncrementalTaskProgress(dumperConfig.getPosition());
    }
    
    @Override
    public void start() {
        dumper = DumperFactory.newInstanceLogDumper(dumperConfig, progress.getPosition());
        Collection<Importer> importers = instanceImporters();
        instanceChannel(importers);
        Future<?> future = ScalingContext.getInstance().getIncrementalDumperExecuteEngine().submitAll(importers, new ExecuteCallback() {
            
            @Override
            public void onSuccess() {
            }
            
            @Override
            public void onFailure(final Throwable throwable) {
                log.error("get an error when migrating the increment data", throwable);
                dumper.stop();
            }
        });
        dumper.start();
        waitForResult(future);
        dataSourceManager.close();
    }
    
    private List<Importer> instanceImporters() {
        List<Importer> result = new ArrayList<>(concurrency);
        for (int i = 0; i < concurrency; i++) {
            result.add(ImporterFactory.newInstance(importerConfig, dataSourceManager));
        }
        return result;
    }
    
    private void instanceChannel(final Collection<Importer> importers) {
        DistributionChannel channel = new DistributionChannel(importers.size(), records -> {
            Record lastHandledRecord = records.get(records.size() - 1);
            if (!(lastHandledRecord.getPosition() instanceof PlaceholderPosition)) {
                progress = new IncrementalTaskProgress(lastHandledRecord.getPosition(),
                        new IncrementalTaskDelay(lastHandledRecord.getCommitTime(), System.currentTimeMillis() - lastHandledRecord.getCommitTime()));
            }
        });
        dumper.setChannel(channel);
        for (Importer each : importers) {
            each.setChannel(channel);
        }
    }
    
    private void waitForResult(final Future<?> future) {
        try {
            future.get();
        } catch (final InterruptedException ignored) {
        } catch (final ExecutionException ex) {
            throw new ScalingTaskExecuteException(String.format("Task %s execute failed ", taskId), ex.getCause());
        }
    }
    
    @Override
    public void stop() {
        if (null != dumper) {
            dumper.stop();
            dumper = null;
        }
    }
}
