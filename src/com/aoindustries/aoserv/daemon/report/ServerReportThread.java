package com.aoindustries.aoserv.daemon.report;

/*
 * Copyright 2001-2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.profiler.*;
import java.io.*;
import java.sql.*;

/**
 * Encapsulates the output of the /usr/bin/procinfo command.
 *
 * @author  AO Industries, Inc.
 */
final public class ServerReportThread extends Thread {

    private static ServerReportThread thread;

    private ServerReportThread() {
	super(ServerReportThread.class.getName());
        Profiler.startProfile(Profiler.FAST, ServerReportThread.class, "<init>()", null);
        try {
            setPriority(MAX_PRIORITY);
            setDaemon(true);
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public void run() {
        Profiler.startProfile(Profiler.UNKNOWN, ServerReportThread.class, "run()", null);
        try {
            while(true) {
                try {
                    while(true) {
                        long startTime=System.currentTimeMillis();
                        for(int c=0;c<AOServDaemon.getThisAOServer().getServerReportInterval();c++) {
                            sleep(AOServDaemon.getThisAOServer().getServerReportDelay());
                        }
                    }
                } catch(ThreadDeath TD) {
                    throw TD;
                } catch(Throwable T) {
                    AOServDaemon.reportError(T, null);
                    try {
                        sleep(10000);
                    } catch(InterruptedException err) {
                        AOServDaemon.reportWarning(err, null);
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void startThread() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, ServerReportThread.class, "startThread()", null);
        try {
            // Only the outer-most server logs its resources
            if(AOServDaemonConfiguration.isManagerEnabled(ServerReportThread.class) && thread==null) {
                synchronized(System.out) {
                    if(thread==null) {
                        System.out.print("Starting ServerReportThread: ");
                        (thread=new ServerReportThread()).start();
                        System.out.println("Done");
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
}