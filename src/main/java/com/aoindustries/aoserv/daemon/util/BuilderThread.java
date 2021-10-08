/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2002-2012, 2017, 2018, 2019, 2020, 2021  AO Industries, Inc.
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
 * along with aoserv-daemon.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aoindustries.aoserv.daemon.util;

import com.aoapps.hodgepodge.logging.ProcessTimer;
import com.aoapps.hodgepodge.table.Table;
import com.aoapps.hodgepodge.table.TableListener;
import com.aoindustries.aoserv.daemon.AOServDaemon;
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

	public static final long
		DEFAULT_PROCESS_TIMER_MAXIMUM_TIME=5*60*1000,
		DEFAULT_PROCESS_TIMER_REMINDER_INTERVAL=15*60*1000
	;
	public static final int
		DEFAULT_MINIMUM_DELAY=5*1000,
		DEFAULT_MAXIMUM_DELAY=35*1000
	;

	private volatile Thread rebuildThread;
	private long lastUpdated;
	private long lastRebuild;
	private volatile boolean isSleeping=false;

	protected BuilderThread() {
		// Always rebuild the configs after start-up
		delayAndRebuild();
	}

	@Override
	public void tableUpdated(Table<?> table) {
		delayAndRebuild();
	}

	@Override
	public void packageListUpdated(SortedSet<PackageManager.RPM> allRpms) {
		delayAndRebuild();
	}

	/**
	 * Will wait a random amount of time and then call doRebuild()
	 */
	private void delayAndRebuild() {
		synchronized(this) {
			lastUpdated = System.currentTimeMillis();
			if (rebuildThread == null) {
				rebuildThread = new Thread() {
					@Override
					public void run() {
						try {
							long lastBuilt = -1;
							long updateCopy;
							synchronized (BuilderThread.this) {
								updateCopy = lastUpdated;
							}
							while (lastBuilt == -1 || lastBuilt < updateCopy) {
								if(waitForBuildCount==0) {
									try {
										isSleeping=true;
										sleep(getRandomDelay());
									} catch (InterruptedException err) {
										// Interrupted by waitForRebuild call
									}
									isSleeping=false;
								}
								try {
									try (
										ProcessTimer timer=new ProcessTimer(
											logger,
											BuilderThread.this.getClass().getName(),
											"delayAndRebuild",
											getProcessTimerSubject(),
											getProcessTimerDescription(),
											getProcessTimerMaximumTime(),
											getProcessTimerReminderInterval()
										)
									) {
										AOServDaemon.executorService.submit(timer);
										long buildStart=System.currentTimeMillis();
										while(!doRebuild()) {
											try {
												isSleeping=true;
												Thread.sleep(getRandomDelay());
											} catch(InterruptedException err) {
												logger.logp(Level.WARNING, BuilderThread.this.getClass().getName(), "run", null, err);
											}
											isSleeping=false;
										}
										lastBuilt = buildStart;
										synchronized(BuilderThread.this) {
											lastRebuild=buildStart;
											BuilderThread.this.notifyAll();
										}
									}
								} catch(ThreadDeath TD) {
									throw TD;
								} catch(Throwable T) {
									logger.logp(Level.SEVERE, BuilderThread.this.getClass().getName(), "run", null, T);
									try {
										isSleeping=true;
										Thread.sleep(getRandomDelay());
									} catch(InterruptedException err) {
										logger.logp(Level.WARNING, BuilderThread.this.getClass().getName(), "run", null, err);
									}
									isSleeping=false;
								}
								synchronized(BuilderThread.this) {
									updateCopy = lastUpdated;
								}
							}
							BuilderThread.this.rebuildThread = null;
						} catch(ThreadDeath TD) {
							throw TD;
						} catch(Throwable T) {
							logger.logp(Level.SEVERE, BuilderThread.this.getClass().getName(), "run", null, T);
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

	private int waitForBuildCount=0;
	public void waitForBuild() {
		synchronized(this) {
			waitForBuildCount++;
			try {
				// Interrupt rebuild thread if it is waiting on the batch
				Thread T=rebuildThread;
				if(T!=null && isSleeping) T.interrupt();

				long updated=lastUpdated;
				while(updated<=lastUpdated && updated>lastRebuild) {
					try {
						wait();
					} catch(InterruptedException err) {
						logger.log(Level.WARNING, null, err);
					}
				}
			} finally {
				waitForBuildCount--;
				notifyAll();
			}
		}
	}

	public String getProcessTimerSubject() {
		return getProcessTimerDescription()+" is taking too long";
	}

	public abstract String getProcessTimerDescription();

	public long getProcessTimerMaximumTime() {
		return DEFAULT_PROCESS_TIMER_MAXIMUM_TIME;
	}

	public long getProcessTimerReminderInterval() {
		return DEFAULT_PROCESS_TIMER_REMINDER_INTERVAL;
	}

	public final int getRandomDelay() {
		int min=getMinimumDelay();
		int max=getMaximumDelay();
		if(min>max) throw new RuntimeException("getMinimumDelay() is greater than getMaximumDelay()");
		int deviation=max-min;
		if(deviation==0) return min;
		return min+AOServDaemon.getFastRandom().nextInt(deviation);
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
