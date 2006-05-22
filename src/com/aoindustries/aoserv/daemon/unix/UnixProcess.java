package com.aoindustries.aoserv.daemon.unix;

/*
 * Copyright 2000-2006 by AO Industries, Inc.,
 * 2200 Dogwood Ct N, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.profiler.*;
import java.io.*;

/**
 * A <code>UnixProcess</code> represents a process
 * running on any Unix machine.
 *
 * @author  AO Industries, Inc.
 */
abstract public class UnixProcess {

    protected int pid;

    /**
     * Constructs a Unix process given its process ID.
     */
    public UnixProcess(int pid) {
        Profiler.startProfile(Profiler.INSTANTANEOUS, UnixProcess.class, "<init>(int)", null);
        try {
            this.pid=pid;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    /**
     * Determines the group ID of a process.  The subclasses of
     * <code>UnixProcess</code> must implement this functionality.  Calling
     * the method on a <code>UnixProcess</code> will result in an
     * <code>IOException</code>.
     */
    abstract public int getGID() throws IOException;

    /**
     * Determines the user ID of a process.  The subclasses of
     * <code>UnixProcess</code> must implement this functionality.  Calling
     * the method on a <code>UnixProcess</code> will result in an
     * <code>IOException</code>.
     */
    abstract public int getUID() throws IOException;

    /**
     * Determines if the process is currently running.  The subclasses of
     * <code>UnixProcess</code> must implement this functionality.  Calling
     * the method on a <code>UnixProcess</code> will result in an
     * <code>IOException</code>.
     */
    abstract public boolean isRunning() throws IOException;

    /**
     * Kills this process.  Sends a term signal once, waits two seconds,
     * then sends a kill signal.  The signals are sent to the execution of the
     * <code>/bin/kill</code> executable.
     */
    public void killProc() throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, UnixProcess.class, "killProc()", null);
        try {
            Runtime runtime=Runtime.getRuntime();
            String pidS=String.valueOf(pid);
            if(isRunning()) {
                String[] cmd={"/bin/kill", "-SIGTERM", pidS};
                AOServDaemon.exec(cmd);
            }
            if(isRunning()) {
                try {
                    Thread.sleep(2000);
                } catch(InterruptedException err) {
                    AOServDaemon.reportWarning(err, null);
                }
            }
            if(isRunning()) {
                String[] cmd={"/bin/kill", "-SIGKILL", pidS};
                AOServDaemon.exec(cmd);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * Sends a signal to this process.  The signals are sent to the execution of the
     * <code>/bin/kill</code> executable.
     */
    public void signal(String signalName) throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, UnixProcess.class, "signal(String)", null);
        try {
            AOServDaemon.exec(
                new String[] {
                    "/bin/kill",
                    "-"+signalName,
                    Integer.toString(pid)
                }
            );
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
}