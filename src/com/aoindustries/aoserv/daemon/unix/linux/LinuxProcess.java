package com.aoindustries.aoserv.daemon.unix.linux;

/*
 * Copyright 2000-2006 by AO Industries, Inc.,
 * 2200 Dogwood Ct N, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.io.unix.*;
import com.aoindustries.aoserv.daemon.unix.*;
import com.aoindustries.profiler.*;
import java.io.*;

/**
 * A <code>UnixProcess</code> represents a process
 * running on any Unix machine.
 *
 * @author  AO Industries, Inc.
 */
public class LinuxProcess extends UnixProcess {

    /** The <code>/proc</code> file is cached after it is created */
    private static File proc;

    /** The <code>/proc/<i>pid</i> file is cached once created */
    private File processProc;

    /**
     * Constructs a Linux process given its process ID.
     */
    public LinuxProcess(int pid) {
	super(pid);
        Profiler.startProfile(Profiler.INSTANTANEOUS, LinuxProcess.class, "<init>(int)", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }

    /**
     * Determines the group ID of the currently running process.
     * The GID is considered the group owner of the file in the
     * /proc directory.  If the process is not running, a
     * FileNotFoundException is thrown.
     */
    public int getGID() throws IOException {
        Profiler.startProfile(Profiler.IO, LinuxProcess.class, "getGID()", null);
        try {
            return new UnixFile(getProc().getPath()).getGID();
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    /**
     * Gets the directory that contains the proc info.
     *
     * @return  the <code>File</code>
     * @exception  IOException if the proc is not mounted
     */
    private File getProc() throws IOException {
        Profiler.startProfile(Profiler.IO, LinuxProcess.class, "getProc()", null);
        try {
	    synchronized(this) {
		if(processProc==null) {
		    if(proc==null) {
			proc=new File("/proc");
			if(!proc.isDirectory()) throw new IOException("Unable to find "+proc.getPath()+" directory");
		    }
		    processProc=new File(proc, String.valueOf(pid));
		}
		return processProc;
	    }
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    /**
     * Determines the user ID of the currently running process.
     * The UID is considered the owner of the file in the
     * /proc directory.  If the process is not running, a
     * FileNotFoundException is thrown.
     */
    public int getUID() throws IOException {
        Profiler.startProfile(Profiler.IO, LinuxProcess.class, "getUID()", null);
        try {
            return new UnixFile(getProc().getPath()).getUID();
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    /**
     * Determines if the process is currently running.  The process
     * is considered running if a directory exists in /proc.
     */
    public boolean isRunning() throws IOException {
        Profiler.startProfile(Profiler.IO, LinuxProcess.class, "isRunning()", null);
        try {
            return getProc().exists();
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }
}
