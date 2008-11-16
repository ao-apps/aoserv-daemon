package com.aoindustries.aoserv.daemon.server;

/*
 * Copyright 2002-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.profiler.Profiler;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * The <code>ServerManager</code> controls stuff at a server level.
 *
 * @author  AO Industries, Inc.
 */
final public class ServerManager {

    private static final File procLoadavg = new File("/proc/loadavg");
    private static final File procMeminfo = new File("/proc/meminfo");

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

    public static String getHddTempReport() throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, ServerManager.class, "getHddTempReport()", null);
        try {
            /*List<String> command = new ArrayList<String>();
            command.add("/opt/aoserv-daemon/bin/hddtemp");

            Stat tempStat = new Stat();

            // IDE hard drives
            for(char ch='a'; ch<='z'; ch++) {
                String devPath = "/dev/hd"+ch;
                UnixFile uf = new UnixFile(devPath);
                uf.getStat(tempStat);
                if(
                    tempStat.exists()
                    && tempStat.isBlockDevice()
                ) {
                    command.add(devPath);
                }
            }
            // SATA/USB/SCSI hard drives
            for(char ch='a'; ch<='z'; ch++) {
                String devPath = "/dev/sd"+ch;
                UnixFile uf = new UnixFile(devPath);
                uf.getStat(tempStat);
                if(
                    tempStat.exists()
                    && tempStat.isBlockDevice()
                ) {
                    command.add(devPath);
                }
            }
            
            // Run the command
            if(command.size()==1) {
                // No hard drives detected
                return "";
            }
            else {
                String[] commandSA = command.toArray(new String[command.size()]);
                return AOServDaemon.execAndCapture(commandSA);
            }*/
            return AOServDaemon.execAndCapture(
                new String[] {
                    "/opt/aoserv-daemon/bin/hddtemp"
                }
            );
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static String getFilesystemsCsvReport() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, ServerManager.class, "getFilesystemsCsvReport()", null);
        try {
            OperatingSystemVersion osvObj = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion();
            if(osvObj==null) return "";
            else {
                int osv = osvObj.getPkey();
                if(
                    osv==OperatingSystemVersion.CENTOS_5DOM0_I686
                    || osv==OperatingSystemVersion.CENTOS_5DOM0_X86_64
                    || osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
                    || osv==OperatingSystemVersion.REDHAT_ES_4_X86_64
                ) {
                    return AOServDaemon.execAndCapture(
                        new String[] {
                            "/opt/aoserv-daemon/bin/filesystemscsv"
                        }
                    );
                } else if(
                    osv==OperatingSystemVersion.MANDRAKE_10_1_I586
                    || osv==OperatingSystemVersion.MANDRIVA_2006_0_I586
                ) {
                    return AOServDaemon.execAndCapture(
                        new String[] {
                            "/usr/aoserv/daemon/bin/filesystemscsv"
                        }
                    );
                } else {
                    throw new IOException("Unexpected operating system version: "+osv);
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static String getLoadAvgReport() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, ServerManager.class, "getLoadAvgReport()", null);
        try {
            StringBuilder report = new StringBuilder(40);
            InputStream in=new BufferedInputStream(new FileInputStream(procLoadavg));
            try {
                int ch;
                while((ch=in.read())!=-1) report.append((char)ch);
            } finally {
                in.close();
            }
            return report.toString();
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static String getMemInfoReport() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, ServerManager.class, "getMemInfoReport()", null);
        try {
            StringBuilder report = new StringBuilder(40);
            InputStream in=new BufferedInputStream(new FileInputStream(procMeminfo));
            try {
                int ch;
                while((ch=in.read())!=-1) report.append((char)ch);
            } finally {
                in.close();
            }
            return report.toString();
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
}
