/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.crunch.impl.mr.exec;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.crunch.PipelineExecution;
import org.apache.crunch.PipelineResult;
import org.apache.crunch.SourceTarget;
import org.apache.crunch.Target;
import org.apache.crunch.hadoop.mapreduce.lib.jobcontrol.CrunchControlledJob;
import org.apache.crunch.hadoop.mapreduce.lib.jobcontrol.CrunchJobControl;
import org.apache.crunch.impl.mr.collect.PCollectionImpl;
import org.apache.crunch.materialize.MaterializableIterable;

import com.google.common.collect.Lists;

/**
 *
 *
 */
public class MRExecutor implements PipelineExecution {

  private static final Log LOG = LogFactory.getLog(MRExecutor.class);

  private final CrunchJobControl control;
  private final Map<PCollectionImpl<?>, Set<Target>> outputTargets;
  private final Map<PCollectionImpl<?>, MaterializableIterable> toMaterialize;
  private final CountDownLatch doneSignal = new CountDownLatch(1);
  private final CountDownLatch killSignal = new CountDownLatch(1);
  private AtomicReference<Status> status = new AtomicReference<Status>(Status.READY);
  private PipelineResult result;
  private Thread monitorThread;

  private String planDotFile;
  
  public MRExecutor(Class<?> jarClass, Map<PCollectionImpl<?>, Set<Target>> outputTargets,
      Map<PCollectionImpl<?>, MaterializableIterable> toMaterialize) {
    this.control = new CrunchJobControl(jarClass.toString());
    this.outputTargets = outputTargets;
    this.toMaterialize = toMaterialize;
    this.monitorThread = new Thread(new Runnable() {
      @Override
      public void run() {
        monitorLoop();
      }
    });
  }

  public void addJob(CrunchJob job) {
    this.control.addJob(job);
  }

  public void setPlanDotFile(String planDotFile) {
    this.planDotFile = planDotFile;
  }
  
  public PipelineExecution execute() {
    monitorThread.start();
    return this;
  }

  /** Monitors running status. It is called in {@code MonitorThread}. */
  private void monitorLoop() {
    try {
      Thread controlThread = new Thread(control);
      controlThread.start();
      while (killSignal.getCount() > 0 && !control.allFinished()) {
        killSignal.await(1, TimeUnit.SECONDS);
      }
      control.stop();
      killAllRunningJobs();

      List<CrunchControlledJob> failures = control.getFailedJobList();
      if (!failures.isEmpty()) {
        System.err.println(failures.size() + " job failure(s) occurred:");
        for (CrunchControlledJob job : failures) {
          System.err.println(job.getJobName() + "(" + job.getJobID() + "): " + job.getMessage());
        }
      }
      List<PipelineResult.StageResult> stages = Lists.newArrayList();
      for (CrunchControlledJob job : control.getSuccessfulJobList()) {
        try {
          stages.add(new PipelineResult.StageResult(job.getJobName(), job.getJob().getCounters()));
        } catch (Exception e) {
          LOG.error("Exception thrown fetching job counters for stage: " + job.getJobName(), e);
        }
      }

      for (PCollectionImpl<?> c : outputTargets.keySet()) {
        if (toMaterialize.containsKey(c)) {
          MaterializableIterable iter = toMaterialize.get(c);
          if (iter.isSourceTarget()) {
            iter.materialize();
            c.materializeAt((SourceTarget) iter.getSource());
          }
        } else {
          boolean materialized = false;
          for (Target t : outputTargets.get(c)) {
            if (!materialized) {
              if (t instanceof SourceTarget) {
                c.materializeAt((SourceTarget) t);
                materialized = true;
              } else {
                SourceTarget st = t.asSourceTarget(c.getPType());
                if (st != null) {
                  c.materializeAt(st);
                  materialized = true;
                }
              }
            }
          }
        }
      }

      synchronized (this) {
        result = new PipelineResult(stages);
        if (killSignal.getCount() == 0) {
          status.set(Status.KILLED);
        } else {
          status.set(result.succeeded() ? Status.SUCCEEDED : Status.FAILED);
        }
      }
    } catch (InterruptedException e) {
      throw new AssertionError(e); // Nobody should interrupt us.
    } finally {
      doneSignal.countDown();
    }
  }

  private void killAllRunningJobs() {
    for (CrunchControlledJob job : control.getRunningJobList()) {
      if (!job.isCompleted()) {
        try {
          job.killJob();
        } catch (Exception e) {
          LOG.error("Exception killing job: " + job.getJobName(), e);
        }
      }
    }
  }

  @Override
  public String getPlanDotFile() {
    return planDotFile;
  }

  @Override
  public void waitFor(long timeout, TimeUnit timeUnit) throws InterruptedException {
    doneSignal.await(timeout, timeUnit);
  }

  @Override
  public void waitUntilDone() throws InterruptedException {
    doneSignal.await();
  }

  @Override
  public synchronized Status getStatus() {
    return status.get();
  }

  @Override
  public synchronized PipelineResult getResult() {
    return result;
  }

  @Override
  public void kill() throws InterruptedException {
    killSignal.countDown();
  }
}
