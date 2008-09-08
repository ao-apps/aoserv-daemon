package com.aoindustries.aoserv.daemon.util;

/*
 * Copyright 2002-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.email.*;
import com.aoindustries.profiler.*;
import com.aoindustries.table.*;
import com.aoindustries.util.*;
import java.io.*;
import java.sql.*;

/**
 * Handles the building of CVS repositories and configs.
 *
 * @author  AO Industries, Inc.
 */
abstract public class BuilderThread implements TableListener {

    public static final long
        DEFAULT_PROCESS_TIMER_MAXIMUM_TIME=5*60*1000,
        DEFAULT_PROCESS_TIMER_REMINDER_INTERVAL=15*60*1000
    ;
    public static final int
        DEFAULT_MINIMUM_DELAY=5*1000,
        DEFAULT_MAXIMUM_DELAY=35*1000
    ;

    private Thread rebuildThread;
    private long lastUpdated;
    private long lastRebuild;
    private boolean isSleeping=false;

    public void tableUpdated(Table table) {
        Profiler.startProfile(Profiler.INSTANTANEOUS, BuilderThread.class, "tableUpdated(Table)", null);
        try {
            delayAndRebuild();
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    /**
     * Will wait a random amount of time and then call doRebuild()
     */
    public void delayAndRebuild() {
        Profiler.startProfile(Profiler.UNKNOWN, BuilderThread.class, "delayAndRebuild()", null);
        try {
	    synchronized(this) {
		lastUpdated = System.currentTimeMillis();
		if (rebuildThread == null) {
		    rebuildThread = new Thread() {
			    public void run() {
				try {
				    long lastBuilt = -1;
				    long updateCopy;
				    synchronized (BuilderThread.this) {
					updateCopy = lastUpdated;
				    }
				    int delay=getRandomDelay();
				    while (lastBuilt == -1 || lastBuilt < updateCopy) {
					if(waitForBuildCount==0) {
					    try {
						isSleeping=true;
						sleep(delay);
					    } catch (InterruptedException err) {
						// Interrupted by waitForRebuild call
					    }
					    isSleeping=false;
					}
					try {
                                            ProcessTimer timer=new ProcessTimer(
                                                AOServDaemon.getRandom(),
                                                AOServDaemonConfiguration.getWarningSmtpServer(),
                                                AOServDaemonConfiguration.getWarningEmailFrom(),
                                                AOServDaemonConfiguration.getWarningEmailTo(),
                                                getProcessTimerSubject(),
                                                getProcessTimerDescription(),
                                                getProcessTimerMaximumTime(),
                                                getProcessTimerReminderInterval()
                                            );
                                            try {
                                                timer.start();
                                                long buildStart=System.currentTimeMillis();
                                                doRebuild();
                                                lastBuilt = buildStart;
                                                synchronized(BuilderThread.this) {
                                                    lastRebuild=buildStart;
                                                    BuilderThread.this.notify();
                                                }
                                            } finally {
                                                timer.stop();
                                            }
					} catch(ThreadDeath TD) {
					    throw TD;
					} catch(Throwable T) {
					    AOServDaemon.reportError(T, null);
					    try {
						Thread.sleep(getRandomDelay());
					    } catch(InterruptedException err) {
                                                AOServDaemon.reportWarning(err, null);
					    }
					}
					synchronized(BuilderThread.this) {
					    updateCopy = lastUpdated;
					}
					delay=60000;
				    }
				    BuilderThread.this.rebuildThread = null;
				} catch(ThreadDeath TD) {
				    throw TD;
				} catch(Throwable T) {
				    AOServDaemon.reportError(T, null);
				}
			    }
			};
		    rebuildThread.start();
		}
	    }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
    
    protected abstract void doRebuild() throws IOException, SQLException;

    private int waitForBuildCount=0;
    public void waitForBuild() {
        Profiler.startProfile(Profiler.UNKNOWN, BuilderThread.class, "waitForRebuild()", null);
        try {
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
			    AOServDaemon.reportWarning(err, null);
			}
		    }
		} finally {
		    waitForBuildCount--;
		    notify();
		}
	    }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
    
    public String getProcessTimerSubject() {
        Profiler.startProfile(Profiler.FAST, BuilderThread.class, "getProcessTimerSubject()", null);
        try {
            return getProcessTimerDescription()+" is taking too long";
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    abstract public String getProcessTimerDescription();

    public long getProcessTimerMaximumTime() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, BuilderThread.class, "getProcessTimerMaximumTime()", null);
        try {
            return DEFAULT_PROCESS_TIMER_MAXIMUM_TIME;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
    
    public long getProcessTimerReminderInterval() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, BuilderThread.class, "getProcessTimerReminderInterval()", null);
        try {
            return DEFAULT_PROCESS_TIMER_REMINDER_INTERVAL;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
 
    final public int getRandomDelay() {
        Profiler.startProfile(Profiler.FAST, BuilderThread.class, "getRandomDelay()", null);
        try {
            int min=getMinimumDelay();
            int max=getMaximumDelay();
            if(min>max) throw new RuntimeException("getMinimumDelay() is greater than getMaximumDelay()");
            int deviation=max-min;
            if(deviation==0) return min;
            return min+AOServDaemon.getRandom().nextInt(deviation);
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    /**
     * The delay is random between the minimum and maximum.
     */
    public int getMinimumDelay() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, BuilderThread.class, "getMinimumDelay()", null);
        try {
            return DEFAULT_MINIMUM_DELAY;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    /**
     * The delay is random between the minimum and maximum.
     */
    public int getMaximumDelay() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, BuilderThread.class, "getMaximumDelay()", null);
        try {
            return DEFAULT_MAXIMUM_DELAY;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
}
