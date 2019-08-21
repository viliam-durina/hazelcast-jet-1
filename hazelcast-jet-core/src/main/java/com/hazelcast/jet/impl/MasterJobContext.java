/*
 * Copyright (c) 2008-2019, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.impl;

import com.hazelcast.core.IMap;
import com.hazelcast.core.LocalMemberResetException;
import com.hazelcast.internal.cluster.MemberInfo;
import com.hazelcast.internal.cluster.impl.ClusterServiceImpl;
import com.hazelcast.internal.cluster.impl.MembersView;
import com.hazelcast.jet.Job;
import com.hazelcast.jet.core.DAG;
import com.hazelcast.jet.core.Edge;
import com.hazelcast.jet.core.JobStatus;
import com.hazelcast.jet.core.TopologyChangedException;
import com.hazelcast.jet.core.Vertex;
import com.hazelcast.jet.core.metrics.JobMetrics;
import com.hazelcast.jet.datamodel.Tuple2;
import com.hazelcast.jet.impl.TerminationMode.ActionAfterTerminate;
import com.hazelcast.jet.impl.exception.JobTerminateRequestedException;
import com.hazelcast.jet.impl.exception.TerminatedWithSnapshotException;
import com.hazelcast.jet.impl.execution.init.ExecutionPlan;
import com.hazelcast.jet.impl.metrics.RawJobMetrics;
import com.hazelcast.jet.impl.operation.GetLocalJobMetricsOperation;
import com.hazelcast.jet.impl.operation.GetLocalJobMetricsOperation.ExecutionNotFoundException;
import com.hazelcast.jet.impl.operation.InitExecutionOperation;
import com.hazelcast.jet.impl.operation.StartExecutionOperation;
import com.hazelcast.jet.impl.operation.TerminateExecutionOperation;
import com.hazelcast.jet.impl.util.ExceptionUtil;
import com.hazelcast.jet.impl.util.LoggingUtil;
import com.hazelcast.jet.impl.util.NonCompletableFuture;
import com.hazelcast.jet.impl.util.Util;
import com.hazelcast.logging.ILogger;
import com.hazelcast.spi.ExecutionService;
import com.hazelcast.spi.Operation;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.hazelcast.jet.Util.idToString;
import static com.hazelcast.jet.config.ProcessingGuarantee.NONE;
import static com.hazelcast.jet.core.Edge.between;
import static com.hazelcast.jet.core.JobStatus.COMPLETED;
import static com.hazelcast.jet.core.JobStatus.FAILED;
import static com.hazelcast.jet.core.JobStatus.NOT_RUNNING;
import static com.hazelcast.jet.core.JobStatus.RUNNING;
import static com.hazelcast.jet.core.JobStatus.STARTING;
import static com.hazelcast.jet.core.JobStatus.SUSPENDED;
import static com.hazelcast.jet.core.JobStatus.SUSPENDED_EXPORTING_SNAPSHOT;
import static com.hazelcast.jet.core.processor.SourceProcessors.readMapP;
import static com.hazelcast.jet.datamodel.Tuple2.tuple2;
import static com.hazelcast.jet.function.Functions.entryKey;
import static com.hazelcast.jet.impl.JobRepository.EXPORTED_SNAPSHOTS_PREFIX;
import static com.hazelcast.jet.impl.MasterContext.EXECUTION_COMPLETED;
import static com.hazelcast.jet.impl.SnapshotValidator.validateSnapshot;
import static com.hazelcast.jet.impl.TerminationMode.ActionAfterTerminate.RESTART;
import static com.hazelcast.jet.impl.TerminationMode.ActionAfterTerminate.SUSPEND;
import static com.hazelcast.jet.impl.TerminationMode.CANCEL_FORCEFUL;
import static com.hazelcast.jet.impl.TerminationMode.CANCEL_GRACEFUL;
import static com.hazelcast.jet.impl.TerminationMode.RESTART_GRACEFUL;
import static com.hazelcast.jet.impl.execution.init.CustomClassLoadedObject.deserializeWithCustomClassLoader;
import static com.hazelcast.jet.impl.execution.init.ExecutionPlanBuilder.createExecutionPlans;
import static com.hazelcast.jet.impl.util.ExceptionUtil.isRestartableException;
import static com.hazelcast.jet.impl.util.ExceptionUtil.isTopologyException;
import static com.hazelcast.jet.impl.util.ExceptionUtil.peel;
import static com.hazelcast.jet.impl.util.ExceptionUtil.rethrow;
import static com.hazelcast.jet.impl.util.ExceptionUtil.withTryCatch;
import static com.hazelcast.jet.impl.util.LoggingUtil.logFinest;
import static com.hazelcast.jet.impl.util.Util.toMapNullSafe;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.stream.Collectors.partitioningBy;
import static java.util.stream.Collectors.toMap;

/**
 * Part of {@link MasterContext} that deals with execution starting and
 * termination.
 */
public class MasterJobContext {

    public static final int SNAPSHOT_RESTORE_EDGE_PRIORITY = Integer.MIN_VALUE;
    public static final String SNAPSHOT_VERTEX_PREFIX = "__snapshot_";

    private static final int COLLECT_METRICS_RETRY_DELAY_MILLIS = 100;
    private static final Runnable NO_OP = () -> {
    };

    private final MasterContext mc;
    private final ILogger logger;
    private final int defaultParallelism;

    private volatile long executionStartTime = System.nanoTime();
    private volatile ExecutionCompletionCallback executionCompletionCallback;
    private volatile Set<Vertex> vertices;
    @Nonnull
    private volatile JobMetrics jobMetrics = JobMetrics.empty();
    private final Map<MemberInfo, RawJobMetrics> partialMetrics = new ConcurrentHashMap<>();

    /**
     * A future (re)created when the job is started and completed when its
     * execution ends. Execution ending doesn't mean the job is done, it may
     * be just temporarily stopping due to suspension, job restarting, etc.
     *
     * <p>It's always completed normally, even if the execution fails.
     */
    @Nonnull
    private volatile CompletableFuture<Void> executionCompletionFuture = completedFuture(null);

    /**
     * A future completed when the job fully completes. It's NOT completed when
     * the job is suspended or when it is going to be restarted. It's used for
     * {@link Job#join()}.
     */
    private final NonCompletableFuture jobCompletionFuture = new NonCompletableFuture();

    /**
     * Null initially. When a job termination is requested, it is assigned a
     * termination mode. It's reset back to null when execute operations
     * complete.
     */
    private volatile TerminationMode requestedTerminationMode;

    MasterJobContext(MasterContext masterContext, ILogger logger) {
        this.mc = masterContext;
        this.logger = logger;
        this.defaultParallelism = mc.getJetService().getJetInstance().getConfig()
              .getInstanceConfig().getCooperativeThreadCount();
    }

    public CompletableFuture<Void> jobCompletionFuture() {
        return jobCompletionFuture;
    }

    TerminationMode requestedTerminationMode() {
        return requestedTerminationMode;
    }

    private boolean isCancelled() {
        return requestedTerminationMode == CANCEL_FORCEFUL;
    }

    /**
     * Starts the execution of the job if it is not already completed,
     * cancelled or failed.
     * <p>
     * If the job is already cancelled, triggers the job completion procedure.
     * <p>
     * If the job quorum is not satisfied, reschedules the job restart.
     * <p>
     * If there was a membership change and the partition table is not completely
     * fixed yet, reschedules the job restart.
     */
    void tryStartJob(Supplier<Long> executionIdSupplier) {
        executionStartTime = System.nanoTime();
        try {
            JobExecutionRecord jobExecRec = mc.jobExecutionRecord();
            jobExecRec.markExecuted();
            mc.membersWithCompletedExecution().clear();
            partialMetrics.clear();
            Tuple2<DAG, ClassLoader> dagAndClassloader = resolveDagAndCL(executionIdSupplier);
            if (dagAndClassloader == null) {
                return;
            }
            DAG dag = dagAndClassloader.f0();
            ClassLoader classLoader = dagAndClassloader.f1();
            // must call this before rewriteDagWithSnapshotRestore()
            String dotRepresentation = dag.toDotString(defaultParallelism);
            long snapshotId = jobExecRec.snapshotId();
            String snapshotName = mc.jobConfig().getInitialSnapshotName();
            String mapName =
                    snapshotId >= 0 ? jobExecRec.successfulSnapshotDataMapName(mc.jobId())
                    : snapshotName != null ? EXPORTED_SNAPSHOTS_PREFIX + snapshotName
                    : null;
            if (mapName != null) {
                rewriteDagWithSnapshotRestore(dag, snapshotId, mapName, snapshotName);
            } else {
                logger.info("Didn't find any snapshot to restore for " + mc.jobIdString());
            }
            MembersView membersView = getMembersView();
            logger.info("Start executing " + mc.jobIdString()
                    + ", execution graph in DOT format:\n" + dotRepresentation
                    + "\nHINT: You can use graphviz or http://viz-js.com to visualize the printed graph.");
            logger.fine("Building execution plan for " + mc.jobIdString());
            try {
                Util.doWithClassLoader(classLoader, () ->
                        mc.setExecutionPlanMap(createExecutionPlans(mc.nodeEngine(), membersView, dag, mc.jobId(),
                                mc.executionId(), mc.jobConfig(), jobExecRec.ongoingSnapshotId())));
            } catch (Exception e) {
                throw new UserCausedException(e);
            }
            logger.fine("Built execution plans for " + mc.jobIdString());
            Set<MemberInfo> participants = mc.executionPlanMap().keySet();
            Function<MemberInfo, Operation> operationCtor = memberInfo ->
                    new InitExecutionOperation(mc.jobId(), mc.executionId(), membersView.getVersion(), participants,
                            mc.nodeEngine().getSerializationService().toData(mc.executionPlanMap().get(memberInfo)));
            mc.invokeOnParticipants(operationCtor, this::onInitStepCompleted, null, false);
        } catch (UserCausedException e) {
            finalizeJob(e.getCause());
        }
    }

    @Nullable
    private Tuple2<DAG, ClassLoader> resolveDagAndCL(Supplier<Long> executionIdSupplier)
            throws UserCausedException {
        mc.lock();
        try {
            if (isCancelled()) {
                logger.fine("Skipping init job '" + mc.jobName() + "': is already cancelled.");
                throw new UserCausedException(new CancellationException());
            }
            if (mc.jobStatus() != NOT_RUNNING) {
                logger.fine("Not starting job '" + mc.jobName() + "': status is " + mc.jobStatus());
                return null;
            }
            if (mc.jobExecutionRecord().isSuspended()) {
                mc.jobExecutionRecord().setSuspended(false);
                mc.writeJobExecutionRecord(false);
                mc.setJobStatus(NOT_RUNNING);
            }
            if (scheduleRestartIfQuorumAbsent() || scheduleRestartIfClusterIsNotSafe()) {
                return null;
            }
            mc.setJobStatus(STARTING);

            // ensure JobExecutionRecord exists
            mc.writeJobExecutionRecord(true);

            if (requestedTerminationMode != null) {
                if (requestedTerminationMode.actionAfterTerminate() != RESTART) {
                    throw new UserCausedException(new JobTerminateRequestedException(requestedTerminationMode));
                }
                // requested termination mode is RESTART, ignore it because we are just starting
                requestedTerminationMode = null;
            }
            ClassLoader classLoader = mc.getJetService().getClassLoader(mc.jobId());
            DAG dag;
            try {
                dag = deserializeWithCustomClassLoader(mc.nodeEngine().getSerializationService(),
                        classLoader, mc.jobRecord().getDag());
            } catch (Exception e) {
                logger.warning("DAG deserialization failed", e);
                throw new UserCausedException(e);
            }
            // save a copy of the vertex list because it is going to change
            vertices = new HashSet<>();
            dag.iterator().forEachRemaining(vertices::add);
            mc.setExecutionId(executionIdSupplier.get());
            mc.snapshotContext().onExecutionStarted();
            executionCompletionFuture = new CompletableFuture<>();
            return tuple2(dag, classLoader);
        } finally {
            mc.unlock();
        }
    }

    // Used only in tryStartJob() and its callees, should never escape that method.
    private static class UserCausedException extends Exception {
        UserCausedException(Exception cause) {
            super("", cause, false, false);
        }
    }

    /**
     * Returns a tuple of:<ol>
     *     <li>a future that will be completed when the execution completes (or
     *          a completed future, if execution is not RUNNING or STARTING)
     *     <li>a string with a message why this call did nothing or null, if
     *          this call actually initiated the termination
     * </ol>
     *
     * @param allowWhileExportingSnapshot if false and jobStatus is
     *      SUSPENDED_EXPORTING_SNAPSHOT, termination will be rejected
     */
    @Nonnull
    Tuple2<CompletableFuture<Void>, String> requestTermination(TerminationMode mode, boolean allowWhileExportingSnapshot) {
        // Switch graceful method to forceful if we don't do snapshots, except for graceful
        // cancellation, which is allowed even if not snapshotting.
        if (mc.jobConfig().getProcessingGuarantee() == NONE && mode != CANCEL_GRACEFUL) {
            mode = mode.withoutTerminalSnapshot();
        }

        JobStatus localStatus;
        Tuple2<CompletableFuture<Void>, String> result;
        mc.lock();
        try {
            localStatus = mc.jobStatus();
            if (localStatus == SUSPENDED_EXPORTING_SNAPSHOT && !allowWhileExportingSnapshot) {
                return tuple2(executionCompletionFuture, "Cannot cancel when job status is "
                        + SUSPENDED_EXPORTING_SNAPSHOT);
            }
            if (localStatus == SUSPENDED && mode != CANCEL_FORCEFUL) {
                // if suspended, we can only cancel the job. Other terminations have no effect.
                return tuple2(executionCompletionFuture, "Job is " + SUSPENDED);
            }
            if (requestedTerminationMode != null) {
                // don't report the cancellation of a cancelled job as an error
                String message = requestedTerminationMode == CANCEL_FORCEFUL && mode == CANCEL_FORCEFUL ? null
                        : "Job is already terminating in mode: " + requestedTerminationMode.name();
                return tuple2(executionCompletionFuture, message);
            }
            requestedTerminationMode = mode;
            // handle cancellation of a suspended job
            if (localStatus == SUSPENDED || localStatus == SUSPENDED_EXPORTING_SNAPSHOT) {
                mc.setJobStatus(FAILED);
                setFinalResult(new CancellationException());
            }
            if (mode.isWithTerminalSnapshot()) {
                mc.snapshotContext().enqueueSnapshot(null, true, null);
            }

            result = tuple2(executionCompletionFuture, null);
        } finally {
            mc.unlock();
        }

        if (localStatus == SUSPENDED) {
            try {
                mc.coordinationService().completeJob(mc, System.currentTimeMillis(), new CancellationException()).get();
            } catch (Exception e) {
                throw rethrow(e);
            }
        } else {
            if (localStatus == RUNNING || localStatus == STARTING) {
                handleTermination(mode);
            }
        }

        return result;
    }

    private void rewriteDagWithSnapshotRestore(
            DAG dag, long snapshotId, String mapName, String snapshotName
    ) throws UserCausedException {
        try {
            IMap<Object, Object> snapshotMap = mc.nodeEngine().getHazelcastInstance().getMap(mapName);
            long resolvedSnapshotId = validateSnapshot(
                    snapshotId, snapshotMap, mc.jobIdString(), snapshotName);
            logger.info(String.format(
                    "About to restore the state of %s from snapshot %d, mapName = %s",
                    mc.jobIdString(), resolvedSnapshotId, mapName));
            List<Vertex> originalVertices = new ArrayList<>();
            dag.iterator().forEachRemaining(originalVertices::add);

            Map<String, Integer> vertexToOrdinal = new HashMap<>();
            Vertex readSnapshotVertex = dag.newVertex(SNAPSHOT_VERTEX_PREFIX + "read", readMapP(mapName));
            Vertex explodeVertex = dag.newVertex(SNAPSHOT_VERTEX_PREFIX + "explode",
                    () -> new ExplodeSnapshotP(vertexToOrdinal, resolvedSnapshotId));
            dag.edge(between(readSnapshotVertex, explodeVertex).isolated());

            int index = 0;
            // add the edges
            for (Vertex userVertex : originalVertices) {
                vertexToOrdinal.put(userVertex.getName(), index);
                int destOrdinal = dag.getInboundEdges(userVertex.getName()).size();
                dag.edge(new SnapshotRestoreEdge(explodeVertex, index, userVertex, destOrdinal));
                index++;
            }
        } catch (Exception e) {
            throw new UserCausedException(e);
        }
    }

    private boolean scheduleRestartIfQuorumAbsent() {
        int quorumSize = mc.jobExecutionRecord().getQuorumSize();
        if (mc.coordinationService().isQuorumPresent(quorumSize)) {
            return false;
        }

        logger.fine("Rescheduling restart of '" + mc.jobName() + "': quorum size " + quorumSize + " is not met");
        scheduleRestart();
        return true;
    }

    private boolean scheduleRestartIfClusterIsNotSafe() {
        if (mc.coordinationService().shouldStartJobs()) {
            return false;
        }

        logger.fine("Rescheduling restart of '" + mc.jobName() + "': cluster is not safe");
        scheduleRestart();
        return true;
    }

    private void scheduleRestart() {
        mc.assertLockHeld();
        JobStatus jobStatus = mc.jobStatus();
        if (jobStatus != NOT_RUNNING && jobStatus != STARTING && jobStatus != RUNNING) {
            throw new IllegalStateException("Restart scheduled in an unexpected state: " + jobStatus);
        }
        mc.setJobStatus(NOT_RUNNING);
        mc.coordinationService().scheduleRestart(mc.jobId());
    }

    private MembersView getMembersView() {
        ClusterServiceImpl clusterService = (ClusterServiceImpl) mc.nodeEngine().getClusterService();
        return clusterService.getMembershipManager().getMembersView();
    }

    // Called as callback when all InitOperation invocations are done
    private void onInitStepCompleted(Map<MemberInfo, Object> responses) {
        Throwable error = getResult("Init", responses);
        JobStatus status = mc.jobStatus();
        if (error == null && status == STARTING) {
            invokeStartExecution();
        } else {
            cancelExecutionInvocations(mc.jobId(), mc.executionId(), null, () ->
                    onCompleteExecution(error != null
                            ? error
                            : new IllegalStateException("Cannot execute " + mc.jobIdString() + ": status is " + status))
            );
        }
    }

    // If a participant leaves or the execution fails in a participant locally, executions are cancelled
    // on the remaining participants and the callback is completed after all invocations return.
    @SuppressWarnings("unchecked")
    private void invokeStartExecution() {
        logger.fine("Executing " + mc.jobIdString());

        long executionId = mc.executionId();

        executionCompletionCallback = new ExecutionCompletionCallback(executionId);
        if (requestedTerminationMode != null) {
            handleTermination(requestedTerminationMode);
        }

        Function<MemberInfo, Operation> operationCtor = memberInfo -> new StartExecutionOperation(mc.jobId(), executionId);
        Consumer<Map<MemberInfo, Object>> completionCallback =
                responses -> {
                    if (responses.values().stream().anyMatch(Throwable.class::isInstance)) {
                        // log errors
                        logger.severe(mc.jobIdString() + ": some " + StartExecutionOperation.class.getSimpleName()
                                + " invocations failed: " + responses);
                    } else {
                        // unwrap the RawJobMetrics from Tuple2<RawJobMetrics, Throwable>
                        Map<MemberInfo, Object> metrics = responses.entrySet().stream()
                                .collect(toMap(
                                        en -> en.getKey(),
                                        en -> ((Tuple2) en.getValue()).f0()));
                        setJobMetrics(mergeMetrics(metrics, emptyMap()));
                    }
                    // unwrap the Throwable from Tuple2<RawJobMetrics, Throwable>
                    Map<MemberInfo, Object> exceptions = responses.entrySet().stream()
                            .collect(toMapNullSafe(
                                    en -> en.getKey(),
                                    en -> {
                                        Object throwable;
                                        if (en.getValue() instanceof Throwable) {
                                            throwable = en.getValue();
                                        } else {
                                            throwable = ((Tuple2<RawJobMetrics, Throwable>) en.getValue()).f1();
                                        }
                                        return throwable;
                                    }
                            ));
                    onCompleteExecution(getResult("Execution", exceptions));
                };

        mc.setJobStatus(RUNNING);
        mc.invokeOnParticipants(operationCtor, completionCallback, executionCompletionCallback, false);

        if (mc.jobConfig().getProcessingGuarantee() != NONE) {
            mc.coordinationService().scheduleSnapshot(mc, executionId);
        }
    }

    private void handleTermination(@Nonnull TerminationMode mode) {
        // this method can be called multiple times to handle the termination, it must
        // be safe against it (idempotent).
        if (mode.isWithTerminalSnapshot()) {
            mc.snapshotContext().tryBeginSnapshot();
        } else if (executionCompletionCallback != null) {
            executionCompletionCallback.cancelInvocations(mode);
        }
    }

    void setFinalResult(Throwable failure) {
        if (failure == null) {
            jobCompletionFuture.internalComplete();
        } else {
            jobCompletionFuture.internalCompleteExceptionally(failure);
        }
    }

    /**
     * <ul>
     * <li>Returns {@code null} if there is no failure
     * <li>Returns a {@link CancellationException} if the job is cancelled
     *     forcefully.
     * <li>Returns a {@link JobTerminateRequestedException} if the current
     *     execution is stopped due to a requested termination, except for
     *     CANCEL_GRACEFUL, in which case CancellationException is returned.
     * <li>If there is at least one user failure, such as an exception in user
     *     code (restartable or not), then returns that failure.
     * <li>Otherwise, the failure is because a job participant has left the
     *     cluster. In that case, it returns {@code TopologyChangeException} so
     *     that the job will be restarted
     * </ul>
     */
    private Throwable getResult(String opName, Map<MemberInfo, Object> responses) {
        if (isCancelled()) {
            logger.fine(mc.jobIdString() + " to be cancelled after " + opName);
            return new CancellationException();
        }

        Map<Boolean, List<Object>> grouped = groupResponses(responses);
        int successfulMembersCount = grouped.get(false).size();

        List<Object> failures = grouped.get(true);
        if (!failures.isEmpty()) {
            logger.fine(opName + " of " + mc.jobIdString() + " has failures: " + failures);
        }

        if (successfulMembersCount == mc.executionPlanMap().size()) {
            logger.fine(opName + " of " + mc.jobIdString() + " was successful");
            return null;
        }

        // Handle TerminatedWithSnapshotException. If only part of the members
        // threw it and others completed normally, the terminal snapshot will fail,
        // but we still handle it as if terminal snapshot was done. If there are
        // other exceptions, ignore this and handle the other exception.
        if (failures.stream().allMatch(entry -> entry instanceof TerminatedWithSnapshotException)) {
            assert opName.equals("Execution") : "opName is '" + opName + "', expected 'Execution'";
            logger.fine(opName + " of " + mc.jobIdString() + " terminated after a terminal snapshot");
            TerminationMode mode = requestedTerminationMode;
            assert mode != null && mode.isWithTerminalSnapshot() : "mode=" + mode;
            return mode == CANCEL_GRACEFUL ? new CancellationException() : new JobTerminateRequestedException(mode);
        }

        // If there is no user-code exception, it means at least one job
        // participant has left the cluster. In that case, all remaining
        // participants return a TopologyChangedException.
        return failures
                .stream()
                .map(entry -> (Throwable) entry)
                .filter(e -> !(e instanceof CancellationException
                        || e instanceof TerminatedWithSnapshotException
                        || isTopologyException(e)))
                .findFirst()
                .map(ExceptionUtil::peel)
                .orElseGet(TopologyChangedException::new);
    }

    private void onCompleteExecution(Throwable error) {
        JobStatus status = mc.jobStatus();

        if (status != STARTING && status != RUNNING) {
            logCannotComplete(error);
            error = new IllegalStateException("Job coordination failed");
        }

        if (error instanceof JobTerminateRequestedException
                && ((JobTerminateRequestedException) error).mode().isWithTerminalSnapshot()) {
            // have to use Async version, the future is completed inside a synchronized block
            Throwable finalError = error;
            mc.snapshotContext().terminalSnapshotFuture()
              .whenCompleteAsync(withTryCatch(logger, (r, e) -> finalizeJob(finalError)));
        } else {
            finalizeJob(error);
        }
    }

    private void logCannotComplete(Throwable error) {
        if (error != null) {
            logger.severe("Cannot properly complete failed " + mc.jobIdString()
                    + ": status is " + mc.jobStatus(), error);
        } else {
            logger.severe("Cannot properly complete " + mc.jobIdString()
                    + ": status is " + mc.jobStatus());
        }
    }

    void cancelExecutionInvocations(long jobId, long executionId, TerminationMode mode, Runnable callback) {
        mc.nodeEngine().getExecutionService().execute(ExecutionService.ASYNC_EXECUTOR, () ->
                mc.invokeOnParticipants(plan -> new TerminateExecutionOperation(jobId, executionId, mode),
                        responses -> {
                            if (responses.values().stream().anyMatch(Objects::nonNull)) {
                                // log errors
                                logger.severe(mc.jobIdString() + ": some TerminateExecutionOperation invocations " +
                                        "failed, execution might remain stuck: " + responses);
                            }
                            if (callback != null) {
                                callback.run();
                            }
                        }, null, true));
    }

    void finalizeJob(@Nullable Throwable failure) {
        final Runnable nonSynchronizedAction;
        mc.lock();
        try {
            JobStatus status = mc.jobStatus();
            if (status == COMPLETED || status == FAILED) {
                logIgnoredCompletion(failure, status);
                return;
            }
            completeVertices(failure);

            // reset state for the next execution
            mc.membersWithCompletedExecution().clear();
            partialMetrics.clear();
            boolean wasCancelled = isCancelled();
            requestedTerminationMode = null;
            executionCompletionCallback = null;
            ActionAfterTerminate terminationModeAction = failure instanceof JobTerminateRequestedException
                    ? ((JobTerminateRequestedException) failure).mode().actionAfterTerminate() : null;
            mc.snapshotContext().onExecutionTerminated();

            // if restart was requested, restart immediately
            if (terminationModeAction == RESTART) {
                mc.setJobStatus(NOT_RUNNING);
                nonSynchronizedAction = () -> mc.coordinationService().restartJob(mc.jobId());
            } else if (!wasCancelled && isRestartableException(failure) && mc.jobConfig().isAutoScaling()) {
                // if restart is due to a failure, schedule a restart after a delay
                scheduleRestart();
                nonSynchronizedAction = NO_OP;
            } else if (terminationModeAction == SUSPEND
                    || isRestartableException(failure)
                    && !wasCancelled
                    && !mc.jobConfig().isAutoScaling()
                    && mc.jobConfig().getProcessingGuarantee() != NONE
            ) {
                mc.setJobStatus(SUSPENDED);
                mc.jobExecutionRecord().setSuspended(true);
                nonSynchronizedAction = () -> mc.writeJobExecutionRecord(false);
            } else {
                mc.setJobStatus(isSuccess(failure) ? COMPLETED : FAILED);
                if (failure instanceof LocalMemberResetException) {
                    logger.fine("Cancelling job " + mc.jobIdString() + " locally: member (local or remote) reset. " +
                            "We don't delete job metadata: job will restart on majority cluster");
                    setFinalResult(new CancellationException());
                    return;
                }
                mc.coordinationService()
                  .completeJob(mc, System.currentTimeMillis(), failure)
                  .whenComplete(withTryCatch(logger, (r, f) -> {
                      if (f != null) {
                          logger.warning("Completion of " + mc.jobIdString() + " failed", f);
                      } else {
                          setFinalResult(failure);
                      }
                  }));
                nonSynchronizedAction = NO_OP;
            }
        } finally {
            mc.unlock();
        }
        executionCompletionFuture.complete(null);
        nonSynchronizedAction.run();
    }

    private boolean isSuccess(@Nullable Throwable failure) {
        long elapsed = NANOSECONDS.toMillis(System.nanoTime() - executionStartTime);
        if (failure == null) {
            logger.info(String.format("Execution of %s completed in %,d ms", mc.jobIdString(), elapsed));
            return true;
        }
        if (failure instanceof CancellationException || failure instanceof JobTerminateRequestedException) {
            logger.info(String.format("Execution of %s completed in %,d ms, reason=%s",
                    mc.jobIdString(), elapsed, failure));
            return false;
        }
        logger.severe(String.format("Execution of %s failed after %,d ms", mc.jobIdString(), elapsed), failure);
        return false;
    }

    private void logIgnoredCompletion(@Nullable Throwable failure, JobStatus status) {
        if (failure != null) {
            logger.severe("Ignoring failure completion of " + idToString(mc.jobId()) + " because status is " + status,
                    failure);
        } else {
            logger.severe("Ignoring completion of " + idToString(mc.jobId()) + " because status is " + status);
        }
    }

    private void completeVertices(@Nullable Throwable failure) {
        if (vertices != null) {
            for (Vertex vertex : vertices) {
                try {
                    vertex.getMetaSupplier().close(failure);
                } catch (Throwable e) {
                    logger.severe(mc.jobIdString()
                            + " encountered an exception in ProcessorMetaSupplier.complete(), ignoring it", e);
                }
            }
        }
    }

    void resumeJob(Supplier<Long> executionIdSupplier) {
        mc.lock();
        try {
            if (mc.jobStatus() != SUSPENDED) {
                logger.info("Not resuming " + mc.jobIdString() + ": not " + SUSPENDED + ", but " + mc.jobStatus());
                return;
            }
            mc.setJobStatus(NOT_RUNNING);
        } finally {
            mc.unlock();
        }
        logger.fine("Resuming job " + mc.jobName());
        tryStartJob(executionIdSupplier);
    }

    private boolean hasParticipant(String uuid) {
        // a member is a participant when it is a master member (that's we) or it's in the execution plan
        Map<MemberInfo, ExecutionPlan> planMap = mc.executionPlanMap();
        return mc.nodeEngine().getLocalMember().getUuid().equals(uuid)
                || planMap != null && planMap.keySet().stream().anyMatch(mi -> mi.getUuid().equals(uuid));
    }

    /**
     * Called when job participant is going to gracefully shut down. Will
     * initiate terminal snapshot and when it's done, it will complete the
     * returned future.
     *
     * @return a future to wait for, which may be already completed
     */
    @Nonnull
    CompletableFuture<Void> onParticipantGracefulShutdown(String uuid) {
        return hasParticipant(uuid) ? gracefullyTerminate() : completedFuture(null);
    }

    @Nonnull
    CompletableFuture<Void> gracefullyTerminate() {
        return requestTermination(RESTART_GRACEFUL, false).f0();
    }

    /**
     * Checks if the job is running on all members and maybe restart it.
     *
     * <p>Returns {@code false}, if this method should be scheduled to
     * be called later. That is, when the job is running, but we've
     * failed to request the restart.
     *
     * <p>Returns {@code true}, if the job is not running, has
     * auto-scaling disabled, is already running on all members or if
     * we've managed to request a restart.
     */
    boolean maybeScaleUp(int dataMembersWithPartitionsCount) {
        if (!mc.jobConfig().isAutoScaling()) {
            return true;
        }

        // We only compare the number of our participating members and current members.
        // If there is any member in our participants that is not among current data members,
        // this job will be restarted anyway. If it's the other way, then the sizes won't match.
        if (mc.executionPlanMap() == null || mc.executionPlanMap().size() == dataMembersWithPartitionsCount) {
            LoggingUtil.logFine(logger, "Not scaling up %s: not running or already running on all members",
                    mc.jobIdString());
            return true;
        }

        JobStatus localStatus = mc.jobStatus();
        if (localStatus == RUNNING && requestTermination(TerminationMode.RESTART_GRACEFUL, false).f1() == null) {
            logger.info("Requested restart of " + mc.jobIdString() + " to make use of added member(s). "
                    + "Job was running on " + mc.executionPlanMap().size() + " members, cluster now has "
                    + dataMembersWithPartitionsCount + " data members with assigned partitions");
            return true;
        }

        // if status was not RUNNING or requestTermination didn't succeed, we'll try again later.
        return false;
    }

    JobMetrics jobMetrics() {
        return jobMetrics;
    }

    private void setJobMetrics(JobMetrics jobMetrics) {
        this.jobMetrics = Objects.requireNonNull(jobMetrics);
    }

    void collectMetrics(CompletableFuture<JobMetrics> clientFuture) {
        if (mc.jobStatus() == RUNNING) {
            long jobId = mc.jobId();
            long executionId = mc.executionId();
            mc.invokeOnParticipants(
                    plan -> new GetLocalJobMetricsOperation(jobId, executionId),
                    responses -> completeWithMergedMetrics(clientFuture, responses),
                    null,
                    false
            );
        } else {
            clientFuture.complete(jobMetrics);
        }
    }

    private void completeWithMergedMetrics(CompletableFuture<JobMetrics> clientFuture,
                                           Map<MemberInfo, Object> metrics) {
        if (metrics.values().stream().anyMatch(r -> r instanceof ExecutionNotFoundException)) {
            // If any member threw ExecutionNotFoundException, we'll retry.
            // This happens when the job is starting or completing - master
            // sees the job as RUNNING, but some members might have terminated
            // already and the master doesn't know about it. When retrying, the
            // job will eventually either not be RUNNING, in which case we'll
            // return last known metrics, or all members that didn't terminate
            // will be running, in which case we'll get fresh metrics.
            scheduleRetryCollectMetrics(clientFuture);
            return;
        }
        Throwable firstThrowable = (Throwable) metrics.values().stream()
                                                      .filter(Throwable.class::isInstance).findFirst().orElse(null);
        if (firstThrowable != null) {
            clientFuture.completeExceptionally(firstThrowable);
        } else {
            JobMetrics jobMetrics = mergeMetrics(metrics, partialMetrics);
            if (jobMetrics != null) {
                clientFuture.complete(jobMetrics);
            }
        }
    }

    private void scheduleRetryCollectMetrics(CompletableFuture<JobMetrics> clientFuture) {
        logFinest(logger, "Rescheduling collectMetrics for %s, some members threw %s", mc.jobIdString(),
                ExecutionNotFoundException.class.getSimpleName());
        mc.nodeEngine().getExecutionService().schedule(() ->
                collectMetrics(clientFuture), COLLECT_METRICS_RETRY_DELAY_MILLIS, MILLISECONDS);
    }

    /**
     * @return null if metrics from some member are missing in metricsResponses
     * and partialMetrics.
     */
    @Nullable
    private JobMetrics mergeMetrics(
            @Nonnull Map<MemberInfo, Object> metricsResponses,
            @Nonnull Map<MemberInfo, RawJobMetrics> partialMetrics
    ) {
        JobMetrics mergedMetrics = JobMetrics.empty();
        for (Entry<MemberInfo, Object> memberEntry : metricsResponses.entrySet()) {
            String memberPrefix = JobMetricsUtil.getMemberPrefix(memberEntry.getKey());
            RawJobMetrics rawJobMetrics;
            if (memberEntry.getValue() == EXECUTION_COMPLETED) {
                rawJobMetrics = partialMetrics.get(memberEntry.getKey());
                if (rawJobMetrics == null) {
                    return null;
                }
            } else {
                rawJobMetrics = (RawJobMetrics) memberEntry.getValue();
            }
            rawJobMetrics = rawJobMetrics.prefixNames(memberPrefix);
            mergedMetrics = mergedMetrics.merge(JobMetrics.of(rawJobMetrics.getTimestamp(), rawJobMetrics.getValues()));
        }
        return mergedMetrics;
    }

    // true -> failures, false -> success responses
    private Map<Boolean, List<Object>> groupResponses(Map<MemberInfo, Object> responses) {
        Map<Boolean, List<Object>> grouped = responses.values().stream()
                .collect(partitioningBy(e -> e instanceof Throwable));

        grouped.putIfAbsent(true, emptyList());
        grouped.putIfAbsent(false, emptyList());

        return grouped;
    }

    /**
     * Specific type of edge to be used when restoring snapshots
     */
    private static class SnapshotRestoreEdge extends Edge {

        SnapshotRestoreEdge(Vertex source, int sourceOrdinal, Vertex destination, int destOrdinal) {
            super(source, sourceOrdinal, destination, destOrdinal);
            distributed();
            partitioned(entryKey());
        }

        @Override
        public int getPriority() {
            return SNAPSHOT_RESTORE_EDGE_PRIORITY;
        }
    }

    /**
     * Attached to {@link StartExecutionOperation} invocations to cancel
     * invocations in case of a failure.
     */
    private class ExecutionCompletionCallback implements BiConsumer<MemberInfo, Object> {

        private final AtomicBoolean invocationsCancelled = new AtomicBoolean();
        private final long executionId;

        ExecutionCompletionCallback(long executionId) {
            this.executionId = executionId;
        }

        @SuppressWarnings("unchecked")
        @Override
        public void accept(MemberInfo memberInfo, Object response) {
            mc.membersWithCompletedExecution().add(memberInfo);
            if (response instanceof Throwable) {
                if (!(peel((Throwable) response) instanceof TerminatedWithSnapshotException)) {
                    cancelInvocations(null);
                }
            } else {
                partialMetrics.put(memberInfo, ((Tuple2<RawJobMetrics, Throwable>) response).f0());
            }
        }

        void cancelInvocations(TerminationMode mode) {
            if (invocationsCancelled.compareAndSet(false, true)) {
                cancelExecutionInvocations(mc.jobId(), executionId, mode, null);
            }
        }
    }
}
