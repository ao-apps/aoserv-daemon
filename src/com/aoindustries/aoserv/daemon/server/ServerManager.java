package com.aoindustries.aoserv.daemon.server;

/*
 * Copyright 2002-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.aoserv.daemon.client.AOServDaemonProtocol;
import com.aoindustries.io.*;
import com.aoindustries.util.*;
import com.aoindustries.profiler.*;
import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * The <code>ServerManager</code> controls stuff at a server level.
 *
 * @author  AO Industries, Inc.
 */
final public class ServerManager {

    /** One lock per process name */
    private static final Map<String,Object> processLocks=new HashMap<String,Object>();
    public static void controlProcess(String process, String command) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, ServerManager.class, "controlProcess(String,String)", null);
        try {
            int osv=AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPkey();
            if(
                osv!=OperatingSystemVersion.MANDRAKE_10_1_I586
                && osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            Object lock;
            synchronized(processLocks) {
                lock=processLocks.get(process);
                if(lock==null) processLocks.put(process, lock=new Object());
            }
            synchronized(lock) {
                String[] cmd={
                    "/etc/rc.d/init.d/"+process,
                    command
                };
                AOServDaemon.exec(cmd);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void restartCron() throws IOException, SQLException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, ServerManager.class, "restartCron()", null);
        try {
            controlProcess("crond", "restart");
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public static void restartXfs() throws IOException, SQLException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, ServerManager.class, "restartXfs()", null);
        try {
            controlProcess("xfs", "restart");
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public static void restartXvfb() throws IOException, SQLException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, ServerManager.class, "restartXvfb()", null);
        try {
            controlProcess("xvfb", "restart");
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public static void startCron() throws IOException, SQLException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, ServerManager.class, "startCron()", null);
        try {
            controlProcess("crond", "start");
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public static void startXfs() throws IOException, SQLException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, ServerManager.class, "startXfs()", null);
        try {
            controlProcess("xfs", "start");
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public static void startXvfb() throws IOException, SQLException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, ServerManager.class, "startXvfb()", null);
        try {
            controlProcess("xvfb", "start");
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public static void stopCron() throws IOException, SQLException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, ServerManager.class, "stopCron()", null);
        try {
            controlProcess("crond", "stop");
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public static void stopXfs() throws IOException, SQLException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, ServerManager.class, "stopXfs()", null);
        try {
            controlProcess("xfs", "stop");
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public static void stopXvfb() throws IOException, SQLException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, ServerManager.class, "stopXvfb()", null);
        try {
            controlProcess("xvfb", "stop");
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public static String get3wareRaidReport() throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, ServerManager.class, "get3wareRaidReport()", null);
        try {
            String[] command = {
                "/opt/tw_cli/tw_cli",
                "show"
            };
            return AOServDaemon.execAndCapture(command);
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static String getMdRaidReport() throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, ServerManager.class, "getMdRaidReport()", null);
        try {
            File procFile = new File("/proc/mdstat");
            String report;
            if(procFile.exists()) {
		StringBuilder SB=new StringBuilder();
                InputStream in=new BufferedInputStream(new FileInputStream(procFile));
		try {
		    int ch;
		    while((ch=in.read())!=-1) SB.append((char)ch);
		} finally {
		    in.close();
		}
                report = SB.toString();
            } else report="";
            return report;
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static String getDrbdReport() throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, ServerManager.class, "getDrbdReport()", null);
        try {
            String[] command = {
                "/opt/aoserv-daemon/bin/drbdcstate"
            };
            return AOServDaemon.execAndCapture(command);
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
}
