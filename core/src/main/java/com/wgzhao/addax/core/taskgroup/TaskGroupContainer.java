/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.wgzhao.addax.core.taskgroup;

import com.alibaba.fastjson.JSON;
import com.wgzhao.addax.common.constant.PluginType;
import com.wgzhao.addax.common.exception.AddaxException;
import com.wgzhao.addax.common.exception.CommonErrorCode;
import com.wgzhao.addax.common.plugin.RecordSender;
import com.wgzhao.addax.common.plugin.TaskPluginCollector;
import com.wgzhao.addax.common.statistics.PerfRecord;
import com.wgzhao.addax.common.statistics.PerfTrace;
import com.wgzhao.addax.common.statistics.VMInfo;
import com.wgzhao.addax.common.util.Configuration;
import com.wgzhao.addax.core.AbstractContainer;
import com.wgzhao.addax.core.meta.State;
import com.wgzhao.addax.core.statistics.communication.Communication;
import com.wgzhao.addax.core.statistics.communication.CommunicationTool;
import com.wgzhao.addax.core.statistics.communication.LocalTGCommunicationManager;
import com.wgzhao.addax.core.statistics.container.communicator.taskgroup.StandaloneTGContainerCommunicator;
import com.wgzhao.addax.core.statistics.plugin.task.AbstractTaskPluginCollector;
import com.wgzhao.addax.core.statistics.plugin.task.StdoutPluginCollector;
import com.wgzhao.addax.core.taskgroup.runner.AbstractRunner;
import com.wgzhao.addax.core.taskgroup.runner.ReaderRunner;
import com.wgzhao.addax.core.taskgroup.runner.WriterRunner;
import com.wgzhao.addax.core.transport.channel.Channel;
import com.wgzhao.addax.core.transport.channel.memory.MemoryChannel;
import com.wgzhao.addax.core.transport.exchanger.BufferedRecordExchanger;
import com.wgzhao.addax.core.transport.exchanger.BufferedRecordTransformerExchanger;
import com.wgzhao.addax.core.transport.transformer.TransformerExecution;
import com.wgzhao.addax.core.util.ClassUtil;
import com.wgzhao.addax.core.util.FrameworkErrorCode;
import com.wgzhao.addax.core.util.TransformerUtil;
import com.wgzhao.addax.core.util.container.CoreConstant;
import com.wgzhao.addax.core.util.container.LoadUtil;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class TaskGroupContainer
        extends AbstractContainer
{
    private static final Logger LOG = LoggerFactory.getLogger(TaskGroupContainer.class);

    /**
     * ??????taskGroup??????jobId
     */
    private final long jobId;

    /**
     * ??????taskGroupId
     */
    private final int taskGroupId;

    /**
     * ?????????channel???
     */
    private final String channelClazz;

    /**
     * task?????????????????????
     */
    private final String taskCollectorClass;

    private final TaskMonitor taskMonitor = TaskMonitor.getInstance();

    public TaskGroupContainer(Configuration configuration)
    {
        super(configuration);

        initCommunicator(configuration);

        this.jobId = this.configuration.getLong(CoreConstant.CORE_CONTAINER_JOB_ID);
        this.taskGroupId = this.configuration.getInt(CoreConstant.CORE_CONTAINER_TASK_GROUP_ID);
        this.channelClazz = this.configuration.getString(CoreConstant.CORE_TRANSPORT_CHANNEL_CLASS, MemoryChannel.class.getName());
        this.taskCollectorClass = this.configuration.getString(CoreConstant.CORE_STATISTICS_COLLECTOR_PLUGIN_TASK_CLASS, StdoutPluginCollector.class.getName());
    }

    private void initCommunicator(Configuration configuration)
    {
        super.setContainerCommunicator(new StandaloneTGContainerCommunicator(configuration));
    }

    public long getJobId()
    {
        return jobId;
    }

    public int getTaskGroupId()
    {
        return taskGroupId;
    }

    @Override
    public void start()
    {
        try {

            // ??????check????????????????????????????????????????????????????????????channel???
            int sleepIntervalInMillSec = this.configuration.getInt(CoreConstant.CORE_CONTAINER_TASK_GROUP_SLEEP_INTERVAL, 100);

            // ??????????????????????????????????????????????????????
            long reportIntervalInMillSec = this.configuration.getLong(CoreConstant.CORE_CONTAINER_TASK_GROUP_REPORT_INTERVAL, 10000);

            // ??????channel??????
            int channelNumber = this.configuration.getInt(CoreConstant.CORE_CONTAINER_TASK_GROUP_CHANNEL, 1);

            int taskMaxRetryTimes = this.configuration.getInt(CoreConstant.CORE_CONTAINER_TASK_FAIL_OVER_MAX_RETRY_TIMES, 1);

            long taskRetryIntervalInMs = this.configuration.getLong(CoreConstant.CORE_CONTAINER_TASK_FAIL_OVER_RETRY_INTERVAL_IN_MSEC, 10000);

            long taskMaxWaitInMs = this.configuration.getLong(CoreConstant.CORE_CONTAINER_TASK_FAIL_OVER_MAX_WAIT_IN_MSEC, 60000);

            List<Configuration> taskConfigs = this.configuration.getListConfiguration(CoreConstant.JOB_CONTENT);

            if (LOG.isDebugEnabled()) {
                LOG.debug("taskGroup[{}]'s task configs[{}]", this.taskGroupId, JSON.toJSONString(taskConfigs));
            }

            int taskCountInThisTaskGroup = taskConfigs.size();
            LOG.info("taskGroupId=[{}] start [{}] channels for [{}] tasks.", this.taskGroupId, channelNumber, taskCountInThisTaskGroup);

            this.containerCommunicator.registerCommunication(taskConfigs);

            Map<Integer, Configuration> taskConfigMap = buildTaskConfigMap(taskConfigs); //taskId???task??????
            List<Configuration> taskQueue = buildRemainTasks(taskConfigs); //?????????task??????
            Map<Integer, TaskExecutor> taskFailedExecutorMap = new HashMap<>(); //taskId?????????????????????
            List<TaskExecutor> runTasks = new ArrayList<>(channelNumber); //????????????task
            Map<Integer, Long> taskStartTimeMap = new HashMap<>(); //??????????????????

            long lastReportTimeStamp = 0;
            Communication lastTaskGroupContainerCommunication = new Communication();

            while (true) {
                //1.??????task??????
                boolean failedOrKilled = false;
                Map<Integer, Communication> communicationMap = containerCommunicator.getCommunicationMap();
                for (Map.Entry<Integer, Communication> entry : communicationMap.entrySet()) {
                    Integer taskId = entry.getKey();
                    Communication taskCommunication = entry.getValue();
                    if (!taskCommunication.isFinished()) {
                        continue;
                    }
                    TaskExecutor taskExecutor = removeTask(runTasks, taskId);

                    //?????????runTasks??????????????????????????????monitor?????????
                    taskMonitor.removeTask(taskId);

                    //????????????task????????????failover????????????????????????????????????
                    if (taskCommunication.getState() == State.FAILED) {
                        taskFailedExecutorMap.put(taskId, taskExecutor);
                        assert taskExecutor != null;
                        if (taskExecutor.supportFailOver() && taskExecutor.getAttemptCount() < taskMaxRetryTimes) {
                            taskExecutor.shutdown(); //????????????executor
                            containerCommunicator.resetCommunication(taskId); //???task???????????????
                            Configuration taskConfig = taskConfigMap.get(taskId);
                            taskQueue.add(taskConfig); //????????????????????????
                        }
                        else {
                            failedOrKilled = true;
                            break;
                        }
                    }
                    else if (taskCommunication.getState() == State.KILLED) {
                        failedOrKilled = true;
                        break;
                    }
                    else if (taskCommunication.getState() == State.SUCCEEDED) {
                        Long taskStartTime = taskStartTimeMap.get(taskId);
                        if (taskStartTime != null) {
                            long usedTime = System.currentTimeMillis() - taskStartTime;
                            LOG.debug("taskGroup[{}] taskId[{}] is successful, used[{}]ms",
                                    this.taskGroupId, taskId, usedTime);
                            //usedTime*1000*1000 ?????????PerfRecord?????????ns?????????????????????????????????????????????????????????????????????????????????????????????
                            PerfRecord.addPerfRecord(taskGroupId, taskId, PerfRecord.PHASE.TASK_TOTAL, taskStartTime,
                                    usedTime * 1000L * 1000L);
                            taskStartTimeMap.remove(taskId);
                            taskConfigMap.remove(taskId);
                        }
                    }
                }

                // 2.?????????taskGroup???taskExecutor?????????????????????????????????
                if (failedOrKilled) {
                    lastTaskGroupContainerCommunication = reportTaskGroupCommunication(lastTaskGroupContainerCommunication, taskCountInThisTaskGroup);

                    throw AddaxException.asAddaxException(FrameworkErrorCode.PLUGIN_RUNTIME_ERROR, lastTaskGroupContainerCommunication.getThrowable());
                }

                //3.????????????????????????????????????????????????????????????????????????
                Iterator<Configuration> iterator = taskQueue.iterator();
                while (iterator.hasNext() && runTasks.size() < channelNumber) {
                    Configuration taskConfig = iterator.next();
                    Integer taskId = taskConfig.getInt(CoreConstant.TASK_ID);
                    int attemptCount = 1;
                    TaskExecutor lastExecutor = taskFailedExecutorMap.get(taskId);
                    if (lastExecutor != null) {
                        attemptCount = lastExecutor.getAttemptCount() + 1;
                        long now = System.currentTimeMillis();
                        long failedTime = lastExecutor.getTimeStamp();
                        if (now - failedTime < taskRetryIntervalInMs) {  //???????????????????????????????????????
                            continue;
                        }
                        if (!lastExecutor.isShutdown()) { //???????????????task????????????
                            if (now - failedTime > taskMaxWaitInMs) {
                                markCommunicationFailed(taskId);
                                reportTaskGroupCommunication(lastTaskGroupContainerCommunication, taskCountInThisTaskGroup);
                                throw AddaxException.asAddaxException(CommonErrorCode.WAIT_TIME_EXCEED, "task failover????????????");
                            }
                            else {
                                lastExecutor.shutdown(); //??????????????????
                                continue;
                            }
                        }
                        else {
                            LOG.debug("taskGroup[{}] taskId[{}] attemptCount[{}] has already shutdown",
                                    this.taskGroupId, taskId, lastExecutor.getAttemptCount());
                        }
                    }
                    Configuration taskConfigForRun = taskMaxRetryTimes > 1 ? taskConfig.clone() : taskConfig;
                    TaskExecutor taskExecutor = new TaskExecutor(taskConfigForRun, attemptCount);
                    taskStartTimeMap.put(taskId, System.currentTimeMillis());
                    taskExecutor.doStart();

                    iterator.remove();
                    runTasks.add(taskExecutor);

                    //???????????????task???runTasks??????????????????monitor????????????
                    taskMonitor.registerTask(taskId, this.containerCommunicator.getCommunication(taskId));

                    taskFailedExecutorMap.remove(taskId);
                    LOG.debug("taskGroup[{}] taskId[{}] attemptCount[{}] is started",
                            this.taskGroupId, taskId, attemptCount);
                }

                //4.?????????????????????executor?????????, ???????????????success--->??????
                if (taskQueue.isEmpty() && isAllTaskDone(runTasks) && containerCommunicator.collectState() == State.SUCCEEDED) {
                    // ????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
                    lastTaskGroupContainerCommunication = reportTaskGroupCommunication(lastTaskGroupContainerCommunication, taskCountInThisTaskGroup);

                    LOG.debug("taskGroup[{}] completed it's tasks.", this.taskGroupId);
                    break;
                }

                // 5.?????????????????????????????????????????????interval?????????????????????????????????
                long now = System.currentTimeMillis();
                if (now - lastReportTimeStamp > reportIntervalInMillSec) {
                    lastTaskGroupContainerCommunication = reportTaskGroupCommunication(lastTaskGroupContainerCommunication, taskCountInThisTaskGroup);

                    lastReportTimeStamp = now;

                    //taskMonitor?????????????????????task??????reportIntervalInMillSec????????????
                    for (TaskExecutor taskExecutor : runTasks) {
                        taskMonitor.report(taskExecutor.getTaskId(), this.containerCommunicator.getCommunication(taskExecutor.getTaskId()));
                    }
                }

                Thread.sleep(sleepIntervalInMillSec);
            }

            //6.????????????????????????
            reportTaskGroupCommunication(lastTaskGroupContainerCommunication, taskCountInThisTaskGroup);
        }
        catch (Throwable e) {
            Communication nowTaskGroupContainerCommunication = this.containerCommunicator.collect();

            if (nowTaskGroupContainerCommunication.getThrowable() == null) {
                nowTaskGroupContainerCommunication.setThrowable(e);
            }
            nowTaskGroupContainerCommunication.setState(State.FAILED);
            this.containerCommunicator.report(nowTaskGroupContainerCommunication);

            throw AddaxException.asAddaxException(
                    FrameworkErrorCode.RUNTIME_ERROR, e);
        }
        finally {
            if (!PerfTrace.getInstance().isJob()) {
                //????????????cpu??????????????????GC?????????
                VMInfo vmInfo = VMInfo.getVmInfo();
                if (vmInfo != null) {
                    vmInfo.getDelta(false);
                    LOG.debug(vmInfo.totalString());
                }

                LOG.debug(PerfTrace.getInstance().summarizeNoException());
                this.removeTaskGroup();//????????????JobId????????????Map
            }
        }
    }

    private Map<Integer, Configuration> buildTaskConfigMap(List<Configuration> configurations)
    {
        Map<Integer, Configuration> map = new HashMap<>();
        for (Configuration taskConfig : configurations) {
            int taskId = taskConfig.getInt(CoreConstant.TASK_ID);
            map.put(taskId, taskConfig);
        }
        return map;
    }

    private List<Configuration> buildRemainTasks(List<Configuration> configurations)
    {
        return new LinkedList<>(configurations);
    }

    private TaskExecutor removeTask(List<TaskExecutor> taskList, int taskId)
    {
        Iterator<TaskExecutor> iterator = taskList.iterator();
        while (iterator.hasNext()) {
            TaskExecutor taskExecutor = iterator.next();
            if (taskExecutor.getTaskId() == taskId) {
                iterator.remove();
                return taskExecutor;
            }
        }
        return null;
    }

    private boolean isAllTaskDone(List<TaskExecutor> taskList)
    {
        for (TaskExecutor taskExecutor : taskList) {
            if (!taskExecutor.isTaskFinished()) {
                return false;
            }
        }
        return true;
    }

    private Communication reportTaskGroupCommunication(Communication lastTaskGroupContainerCommunication, int taskCount)
    {
        Communication nowTaskGroupContainerCommunication = this.containerCommunicator.collect();
        nowTaskGroupContainerCommunication.setTimestamp(System.currentTimeMillis());
        Communication reportCommunication = CommunicationTool.getReportCommunication(nowTaskGroupContainerCommunication,
                lastTaskGroupContainerCommunication, taskCount);
        this.containerCommunicator.report(reportCommunication);
        return reportCommunication;
    }

    private void markCommunicationFailed(Integer taskId)
    {
        Communication communication = containerCommunicator.getCommunication(taskId);
        communication.setState(State.FAILED);
    }

    public void removeTaskGroup()
    {
        try {

            // ????????????JOB ID?????????????????? ??????????????????
            LoadUtil.getConfigurationSet().remove(this.jobId);
            Iterator<Map.Entry<Integer, Communication>> it = LocalTGCommunicationManager.getTaskGroupCommunicationMap().entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, Communication> entry = it.next();
                String strJobId = String.valueOf(this.jobId);
                String key = String.valueOf(entry.getKey());
                if (key.startsWith(strJobId)) {
                    it.remove();
                }
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * TaskExecutor???????????????task????????????
     * ????????????1???1???reader???writer
     */
    class TaskExecutor
    {
        private final Configuration taskConfig;

        private final int taskId;

        private final int attemptCount;

        private final Channel channel;

        private final Thread readerThread;

        private final Thread writerThread;

        private final ReaderRunner readerRunner;

        private final WriterRunner writerRunner;

        /**
         * ?????????taskCommunication??????????????????
         * 1. channel
         * 2. readerRunner???writerRunner
         * 3. reader???writer???taskPluginCollector
         */
        private final Communication taskCommunication;

        public TaskExecutor(Configuration taskConf, int attemptCount)
        {
            // ?????????taskExecutor?????????
            this.taskConfig = taskConf;
            Validate.isTrue(null != this.taskConfig.getConfiguration(CoreConstant.JOB_READER)
                            && null != this.taskConfig.getConfiguration(CoreConstant.JOB_WRITER),
                    "[reader|writer]???????????????????????????!");

            // ??????taskId
            this.taskId = this.taskConfig.getInt(CoreConstant.TASK_ID);
            this.attemptCount = attemptCount;

            /*
             * ???taskId?????????taskExecutor???Communication
             * ?????????readerRunner???writerRunner??????????????????channel????????????
             */
            this.taskCommunication = containerCommunicator
                    .getCommunication(taskId);
            Validate.notNull(this.taskCommunication,
                    String.format("taskId[%d]???Communication???????????????", taskId));
            this.channel = ClassUtil.instantiate(channelClazz,
                    Channel.class, configuration);
            this.channel.setCommunication(this.taskCommunication);

            /*
             * ??????transformer?????????
             */

            List<TransformerExecution> transformerInfoExecs = TransformerUtil.buildTransformerInfo(taskConfig);

            /*
             * ??????writerThread
             */
            writerRunner = (WriterRunner) generateRunner(PluginType.WRITER);
            this.writerThread = new Thread(writerRunner, String.format("%d-%d-%d-writer", jobId, taskGroupId, this.taskId));
            //????????????thread???contextClassLoader???????????????????????????????????????????????????
            this.writerThread.setContextClassLoader(LoadUtil.getJarLoader(PluginType.WRITER, this.taskConfig.getString(CoreConstant.JOB_WRITER_NAME), getJobId()));

            /*
             * ??????readerThread
             */
            readerRunner = (ReaderRunner) generateRunner(PluginType.READER, transformerInfoExecs);
            this.readerThread = new Thread(readerRunner, String.format("%d-%d-%d-reader", jobId, taskGroupId, this.taskId));
            /*
             * ????????????thread???contextClassLoader???????????????????????????????????????????????????
             */
            this.readerThread.setContextClassLoader(LoadUtil.getJarLoader(PluginType.READER, this.taskConfig.getString(CoreConstant.JOB_READER_NAME), getJobId()));
        }

        public void doStart()
        {
            this.writerThread.start();

            // reader???????????????writer???????????????
            if (!this.writerThread.isAlive() || this.taskCommunication.getState() == State.FAILED) {
                throw AddaxException.asAddaxException(FrameworkErrorCode.RUNTIME_ERROR, this.taskCommunication.getThrowable());
            }

            this.readerThread.start();

            // ??????reader??????????????????
            if (!this.readerThread.isAlive() && this.taskCommunication.getState() == State.FAILED) {
                // ?????????????????????Reader???????????????????????? ?????????????????? ????????????????????????
                throw AddaxException.asAddaxException(FrameworkErrorCode.RUNTIME_ERROR, this.taskCommunication.getThrowable());
            }
        }

        private AbstractRunner generateRunner(PluginType pluginType)
        {
            return generateRunner(pluginType, null);
        }

        private AbstractRunner generateRunner(PluginType pluginType, List<TransformerExecution> transformerInfoExecs)
        {
            AbstractRunner newRunner;
            TaskPluginCollector pluginCollector;

            switch (pluginType) {
                case READER:
                    newRunner = LoadUtil.loadPluginRunner(pluginType, this.taskConfig.getString(CoreConstant.JOB_READER_NAME), getJobId());
                    newRunner.setJobConf(this.taskConfig.getConfiguration(CoreConstant.JOB_READER_PARAMETER));

                    pluginCollector = ClassUtil.instantiate(taskCollectorClass, AbstractTaskPluginCollector.class, configuration, this.taskCommunication, PluginType.READER);

                    RecordSender recordSender;
                    if (transformerInfoExecs != null && !transformerInfoExecs.isEmpty()) {
                        recordSender = new BufferedRecordTransformerExchanger(taskGroupId, this.taskId, this.channel, this.taskCommunication, pluginCollector, transformerInfoExecs);
                    }
                    else {
                        recordSender = new BufferedRecordExchanger(this.channel, pluginCollector);
                    }

                    ((ReaderRunner) newRunner).setRecordSender(recordSender);

                    /*
                     * ??????taskPlugin???collector???????????????????????????job/task??????
                     */
                    newRunner.setTaskPluginCollector(pluginCollector);
                    break;
                case WRITER:
                    newRunner = LoadUtil.loadPluginRunner(pluginType, this.taskConfig.getString(CoreConstant.JOB_WRITER_NAME), getJobId());
                    newRunner.setJobConf(this.taskConfig.getConfiguration(CoreConstant.JOB_WRITER_PARAMETER));

                    pluginCollector = ClassUtil.instantiate(taskCollectorClass, AbstractTaskPluginCollector.class, configuration, this.taskCommunication, PluginType.WRITER);
                    ((WriterRunner) newRunner).setRecordReceiver(new BufferedRecordExchanger(this.channel, pluginCollector));
                    /*
                     * ??????taskPlugin???collector???????????????????????????job/task??????
                     */
                    newRunner.setTaskPluginCollector(pluginCollector);
                    break;
                default:
                    throw AddaxException.asAddaxException(FrameworkErrorCode.ARGUMENT_ERROR, "Cant generateRunner for:" + pluginType);
            }

            newRunner.setTaskGroupId(taskGroupId);
            newRunner.setTaskId(this.taskId);
            newRunner.setRunnerCommunication(this.taskCommunication);

            return newRunner;
        }

        // ????????????????????????
        private boolean isTaskFinished()
        {
            // ??????reader ??? writer?????????????????????????????????????????????????????????
            if (readerThread.isAlive() || writerThread.isAlive()) {
                return false;
            }

            return taskCommunication != null && taskCommunication.isFinished();
        }

        private int getTaskId()
        {
            return taskId;
        }

        private long getTimeStamp()
        {
            return taskCommunication.getTimestamp();
        }

        private int getAttemptCount()
        {
            return attemptCount;
        }

        private boolean supportFailOver()
        {
            return writerRunner.supportFailOver();
        }

        private void shutdown()
        {
            writerRunner.shutdown();
            readerRunner.shutdown();
            if (writerThread.isAlive()) {
                writerThread.interrupt();
            }
            if (readerThread.isAlive()) {
                readerThread.interrupt();
            }
        }

        private boolean isShutdown()
        {
            return !readerThread.isAlive() && !writerThread.isAlive();
        }
    }
}
