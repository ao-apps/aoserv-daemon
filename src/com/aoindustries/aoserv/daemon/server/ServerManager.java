package com.aoindustries.aoserv.daemon.server;

/*
 * Copyright 2002-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.daemon.AOServDaemon;
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
        int osv=AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPkey();
        if(
            osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
            && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
            && osv!=OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
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
    }

    public static void restartCron() throws IOException, SQLException {
        controlProcess("crond", "restart");
    }

    public static void restartXfs() throws IOException, SQLException {
        controlProcess("xfs", "restart");
    }

    public static void restartXvfb() throws IOException, SQLException {
        controlProcess("xvfb", "restart");
    }

    public static void startCron() throws IOException, SQLException {
        controlProcess("crond", "start");
    }

    public static void startXfs() throws IOException, SQLException {
        controlProcess("xfs", "start");
    }

    public static void startXvfb() throws IOException, SQLException {
        controlProcess("xvfb", "start");
    }

    public static void stopCron() throws IOException, SQLException {
        controlProcess("crond", "stop");
    }

    public static void stopXfs() throws IOException, SQLException {
        controlProcess("xfs", "stop");
    }

    public static void stopXvfb() throws IOException, SQLException {
        controlProcess("xvfb", "stop");
    }

    public static String get3wareRaidReport() throws IOException {
        String[] command = {
            "/opt/tw_cli/tw_cli",
            "show"
        };
        return AOServDaemon.execAndCapture(command);
    }

    public static String getMdRaidReport() throws IOException {
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
    }

    public static String getDrbdReport() throws IOException {
        String[] command = {
            "/opt/aoserv-daemon/bin/drbdcstate"
        };
        return AOServDaemon.execAndCapture(command);
    }

    public static String[] getLvmReport() throws IOException {
        return new String[] {
            AOServDaemon.execAndCapture(
                new String[] {
                    "/usr/sbin/vgs",
                    "--noheadings",
                    "--separator=\t",
                    "--units=b",
                    "-o",
                    "vg_name,vg_extent_size,vg_extent_count,vg_free_count,pv_count,lv_count"
                }
            ),
            AOServDaemon.execAndCapture(
                new String[] {
                    "/usr/sbin/pvs",
                    "--noheadings",
                    "--separator=\t",
                    "-o",
                    "pv_name,pv_pe_count,pv_pe_alloc_count,vg_name"
                }
            ),
            AOServDaemon.execAndCapture(
                new String[] {
                    "/usr/sbin/lvs",
                    "--noheadings",
                    "--separator=\t",
                    "-o",
                    "vg_name,lv_name,seg_count,segtype,stripes,seg_start_pe,seg_pe_ranges"
                }
            )
        };
    }

    public static String getHddTempReport() throws IOException {
        return AOServDaemon.execAndCapture(
            new String[] {
                "/opt/aoserv-daemon/bin/hddtemp"
            }
        );
    }

    public static String getHddModelReport() throws IOException {
        return AOServDaemon.execAndCapture(
            new String[] {
                "/opt/aoserv-daemon/bin/hddmodel"
            }
        );
    }

    public static String getFilesystemsCsvReport() throws IOException, SQLException {
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
                osv==OperatingSystemVersion.MANDRIVA_2006_0_I586
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
    }

    public static String getLoadAvgReport() throws IOException, SQLException {
        StringBuilder report = new StringBuilder(40);
        InputStream in=new BufferedInputStream(new FileInputStream(procLoadavg));
        try {
            int ch;
            while((ch=in.read())!=-1) report.append((char)ch);
        } finally {
            in.close();
        }
        return report.toString();
    }

    public static String getMemInfoReport() throws IOException, SQLException {
        StringBuilder report = new StringBuilder(40);
        InputStream in=new BufferedInputStream(new FileInputStream(procMeminfo));
        try {
            int ch;
            while((ch=in.read())!=-1) report.append((char)ch);
        } finally {
            in.close();
        }
        return report.toString();
    }
}
