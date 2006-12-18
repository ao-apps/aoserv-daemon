package com.aoindustries.aoserv.daemon.timezone;

/*
 * Copyright 2006 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.profiler.Profiler;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.SQLException;

/**
 * Configures the timezone settings in /etc/localtime and /etc/sysconfig/clock based on the
 * settings in the ao_servers table.
 *
 * @author  AO Industries, Inc.
 */
public class TimeZoneManager extends BuilderThread {

    private static TimeZoneManager timeZoneManager;

    private TimeZoneManager() {
    }

    public static void start() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, TimeZoneManager.class, "start()", null);
        try {
            if(AOServDaemonConfiguration.isManagerEnabled(TimeZoneManager.class) && timeZoneManager==null) {
                synchronized(System.out) {
                    if(timeZoneManager==null) {
                        System.out.print("Starting TimeZoneManager: ");
                        AOServConnector connector=AOServDaemon.getConnector();
                        timeZoneManager=new TimeZoneManager();
                        connector.aoServers.addTableListener(timeZoneManager, 0);
                        timeZoneManager.delayAndRebuild();
                        System.out.println("Done");
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, TimeZoneManager.class, "doRebuild()", null);
        try {
            AOServConnector connector=AOServDaemon.getConnector();
            AOServer server=AOServDaemon.getThisAOServer();

            int osv=server.getServer().getOperatingSystemVersion().getPKey();
            if(
                osv!=OperatingSystemVersion.MANDRAKE_10_1_I586
                && osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
                && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            String timeZone = server.getTimeZone().getName();

            /*
             * Control the /etc/localtime symbolic link
             */
            String correctTarget = "../usr/share/zoneinfo/" + timeZone;
            UnixFile localtime = new UnixFile("/etc/localtime");
            Stat localtimeStat = localtime.getStat();
            if(!localtimeStat.exists()) localtime.symLink(correctTarget);
            else {
                if(localtimeStat.isSymLink()) {
                    String currentTarget = localtime.readLink();
                    if(!currentTarget.equals(correctTarget)) {
                        localtime.delete();
                        localtime.symLink(correctTarget);
                    }
                } else {
                    localtime.delete();
                    localtime.symLink(correctTarget);
                }
            }
            
            /*
             * Control /etc/sysconfig/clock
             */
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            ChainWriter newOut = new ChainWriter(bout);
            try {
                newOut.print("ARC=false\n"
                           + "ZONE=").print(timeZone).print("\n"
                           + "UTC=false\n");
            } finally {
                newOut.close();
            }
            byte[] newBytes = bout.toByteArray();
            UnixFile clockConfig = new UnixFile("/etc/sysconfig/clock");
            if(!clockConfig.contentEquals(newBytes)) {
                UnixFile newClockConfig = new UnixFile("/etc/sysconfig/clock.new");
                newClockConfig.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0755, true);
                OutputStream newClockOut = new FileOutputStream(newClockConfig.getFile());
                try {
                    newClockOut.write(newBytes);
                } finally {
                    newClockOut.close();
                }
                newClockConfig.renameTo(clockConfig);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public String getProcessTimerDescription() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, TimeZoneManager.class, "getProcessTimerDescription()", null);
        try {
            return "Rebuild time zones";
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public long getProcessTimerMaximumTime() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, TimeZoneManager.class, "getProcessTimerMaximumTime()", null);
        try {
            return (long)30*60*1000;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
}
