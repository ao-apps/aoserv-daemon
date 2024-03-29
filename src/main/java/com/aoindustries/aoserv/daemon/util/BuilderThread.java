/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2002-2012, 2017, 2018, 2019, 2020, 2021, 2022  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of aoserv-daemon.
 *
 * aoserv-daemon is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * aoserv-daemon is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with aoserv-daemon.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.aoindustries.aoserv.daemon.util;

import com.aoapps.hodgepodge.logging.ProcessTimer;
import com.aoapps.hodgepodge.table.Table;
import com.aoapps.hodgepodge.table.TableListener;
import com.aoindustries.aoserv.daemon.AoservDaemon;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
import java.util.SortedSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles the building of CVS repositories and configs.
 *
 * @author  AO Industries, Inc.
 */
public abstract class BuilderThread implements TableListener, PackageManager.PackageListener {

  private static final Logger logger = Logger.getLogger(BuilderThread.class.getName());

  public static final long DEFAULT_PROCESS_TIMER_MAXIMUM_TIME = 5L * 60 * 1000;
  public static final long DEFAULT_PROCESS_TIMER_REMINDER_INTERVAL = 15L * 60 * 1000;
  public static final int DEFAULT_MINIMUM_DELAY = 5 * 1000;
  public static final int DEFAULT_MAXIMUM_DELAY = 35 * 1000;

  private volatile Thread rebuildThread;
  private long lastUpdated;
  private long lastRebuild;
  private final Object sleepLock = new Object();

  protected BuilderThread() {
    // Always rebuild the configs after start-up
    delayAndRebuild();
  }

  @Override
  public void tableUpdated(Table<?> table) {
    delayAndRebuild();
  }

  @Override
  public void packageListUpdated(SortedSet<PackageManager.Rpm> allRpms) {
    delayAndRebuild();
  }

  /**
   * Will wait a random amount of time and then call {@link #doRebuild()}.
   */
  private void delayAndRebuild() {
    synchronized (this) {
      lastUpdated = System.currentTimeMillis();
      if (rebuildThread == null) {
        rebuildThread = new Thread() {
          @Override
          @SuppressWarnings({"SleepWhileInLoop", "BroadCatchBlock", "TooBroadCatch"})
          public void run() {
            try {
              long lastBuilt = -1;
              long updateCopy;
              synchronized (BuilderThread.this) {
                updateCopy = lastUpdated;
              }
              while (
                  (lastBuilt == -1 || lastBuilt < updateCopy)
                      && !Thread.currentThread().isInterrupted()
              ) {
                if (waitForBuildCount == 0) {
                  try {
                    synchronized (sleepLock) {
                      sleepLock.wait(getRandomDelay());
                    }
                  } catch (InterruptedException err) {
                    // Restore the interrupted status
                    Thread.currentThread().interrupt();
                  }
                }
                try {
                  try (
                      ProcessTimer timer = new ProcessTimer(
                          logger,
                          BuilderThread.this.getClass().getName(),
                          "delayAndRebuild",
                          getProcessTimerSubject(),
                          getProcessTimerDescription(),
                          getProcessTimerMaximumTime(),
                          getProcessTimerReminderInterval()
                      )
                      ) {
                    AoservDaemon.executorService.submit(timer);
                    long buildStart = System.currentTimeMillis();
                    while (!doRebuild() && !Thread.currentThread().isInterrupted()) {
                      try {
                        synchronized (sleepLock) {
                          sleepLock.wait(getRandomDelay());
                        }
                      } catch (InterruptedException err) {
                        logger.logp(Level.WARNING, BuilderThread.this.getClass().getName(), "run", null, err);
                        // Restore the interrupted status
                        Thread.currentThread().interrupt();
                      }
                    }
                    lastBuilt = buildStart;
                    synchronized (BuilderThread.this) {
                      lastRebuild = buildStart;
                      BuilderThread.this.notifyAll();
                    }
                  }
                } catch (ThreadDeath td) {
                  throw td;
                } catch (Throwable t) {
                  logger.logp(Level.SEVERE, BuilderThread.this.getClass().getName(), "run", null, t);
                  try {
                    synchronized (sleepLock) {
                      sleepLock.wait(getRandomDelay());
                    }
                  } catch (InterruptedException err) {
                    logger.logp(Level.WARNING, BuilderThread.this.getClass().getName(), "run", null, err);
                    // Restore the interrupted status
                    Thread.currentThread().interrupt();
                  }
                }
                synchronized (BuilderThread.this) {
                  updateCopy = lastUpdated;
                }
              }
              BuilderThread.this.rebuildThread = null;
            } catch (ThreadDeath td) {
              throw td;
            } catch (Throwable t) {
              logger.logp(Level.SEVERE, BuilderThread.this.getClass().getName(), "run", null, t);
            }
          }
        };
        rebuildThread.start();
      }
    }
  }

  /**
   * @return  <code>true</code> if successful or <code>false</code> if unsuccessful and needs to be retried.
   */
  protected abstract boolean doRebuild();

  private int waitForBuildCount;

  public void waitForBuild() {
    synchronized (this) {
      waitForBuildCount++;
      try {
        // Notify rebuild thread if it is waiting on the batch
        synchronized (sleepLock) {
          sleepLock.notify(); // notifyAll() not needed, since will only ever be one thread
        }

        long updated = lastUpdated;
        while (updated <= lastUpdated && updated > lastRebuild && !Thread.currentThread().isInterrupted()) {
          try {
            wait();
          } catch (InterruptedException err) {
            logger.log(Level.WARNING, null, err);
            // Restore the interrupted status
            Thread.currentThread().interrupt();
          }
        }
      } finally {
        waitForBuildCount--;
        notifyAll();
      }
    }
  }

  public String getProcessTimerSubject() {
    return getProcessTimerDescription() + " is taking too long";
  }

  public abstract String getProcessTimerDescription();

  public long getProcessTimerMaximumTime() {
    return DEFAULT_PROCESS_TIMER_MAXIMUM_TIME;
  }

  public long getProcessTimerReminderInterval() {
    return DEFAULT_PROCESS_TIMER_REMINDER_INTERVAL;
  }

  public final int getRandomDelay() {
    int min = getMinimumDelay();
    int max = getMaximumDelay();
    if (min > max) {
      throw new RuntimeException("getMinimumDelay() is greater than getMaximumDelay()");
    }
    int deviation = max - min;
    if (deviation == 0) {
      return min;
    }
    return min + AoservDaemon.getFastRandom().nextInt(deviation);
  }

  /**
   * The delay is random between the minimum and maximum.
   */
  public int getMinimumDelay() {
    return DEFAULT_MINIMUM_DELAY;
  }

  /**
   * The delay is random between the minimum and maximum.
   */
  public int getMaximumDelay() {
    return DEFAULT_MAXIMUM_DELAY;
  }
}
