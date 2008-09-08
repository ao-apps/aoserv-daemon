package com.aoindustries.aoserv.daemon.postgres;

/*
 * Copyright 2002-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.aoserv.daemon.server.*;
import com.aoindustries.aoserv.daemon.util.*;
import com.aoindustries.cron.CronDaemon;
import com.aoindustries.cron.CronJob;
import com.aoindustries.io.*;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.sql.*;
import com.aoindustries.profiler.*;
import com.aoindustries.table.*;
import com.aoindustries.util.*;
import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * Controls the PostgreSQL servers.
 *
 * @author  AO Industries, Inc.
 */
final public class PostgresServerManager extends BuilderThread implements CronJob {

    public static final File pgsqlDirectory=new File(PostgresServer.DATA_BASE_DIR);

    private PostgresServerManager() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, PostgresServerManager.class, "<init>()", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, PostgresServerManager.class, "doRebuild()", null);
        try {
            AOServConnector connector=AOServDaemon.getConnector();

            synchronized(rebuildLock) {
                // TODO: Add and initialize any missing /var/lib/pgsql/name
                // TODO: Add/update any /etc/rc.d/init.d/postgresql-name
                // TODO: restart any that need started/restarted
            }
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private static final Map<Integer,AOConnectionPool> pools=new HashMap<Integer,AOConnectionPool>();
    static AOConnectionPool getPool(PostgresServer ps) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, PostgresServerManager.class, "getPool(PostgresServer)", null);
        try {
	    synchronized(pools) {
                Integer I=Integer.valueOf(ps.getPkey());
                AOConnectionPool pool=pools.get(I);
		if(pool==null) {
                    PostgresDatabase pd=ps.getPostgresDatabase(PostgresDatabase.AOSERV);
                    if(pd==null) throw new SQLException("Unable to find PostgresDatabase: "+PostgresDatabase.AOSERV+" on "+ps.toString());
		    pool=new AOConnectionPool(
                        pd.getJdbcDriver(),
                        pd.getJdbcUrl(true),
                        PostgresUser.POSTGRES,
                        AOServDaemonConfiguration.getPostgresPassword(),
                        AOServDaemonConfiguration.getPostgresConnections(),
                        AOServDaemonConfiguration.getPostgresMaxConnectionAge(),
                        AOServDaemon.getErrorHandler()
                    );
                    pools.put(I, pool);
		}
		return pool;
	    }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static PostgresServerManager postgresServerManager;
    public static void start() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, PostgresServerManager.class, "start()", null);
        try {
            if(AOServDaemonConfiguration.isManagerEnabled(PostgresServerManager.class) && postgresServerManager==null) {
                synchronized(System.out) {
                    if(postgresServerManager==null) {
                        System.out.print("Starting PostgresServerManager: ");
                        AOServConnector conn=AOServDaemon.getConnector();
                        postgresServerManager=new PostgresServerManager();
                        conn.postgresServers.addTableListener(postgresServerManager, 0);
                        // Register in CronDaemon
                        CronDaemon.addCronJob(postgresServerManager, AOServDaemon.getErrorHandler());
                        System.out.println("Done");
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void waitForRebuild() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, PostgresServerManager.class, "waitForRebuild()", null);
        try {
            if(postgresServerManager!=null) postgresServerManager.waitForBuild();
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public String getProcessTimerDescription() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, PostgresServerManager.class, "getProcessTimerDescription()", null);
        try {
            return "Rebuild PostgreSQL Servers";
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public static void restartPostgreSQL(PostgresServer ps) throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, PostgresServerManager.class, "restartPostgreSQL(PostgresServer)", null);
        try {
            ServerManager.controlProcess("postgresql-"+ps.getName(), "restart");
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static void startPostgreSQL(PostgresServer ps) throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, PostgresServerManager.class, "startPostgresSQL(PostgresServer)", null);
        try {
            ServerManager.controlProcess("postgresql-"+ps.getName(), "start");
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static void stopPostgreSQL(PostgresServer ps) throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, PostgresServerManager.class, "stopPostgreSQL(PostgresServer)", null);
        try {
            ServerManager.controlProcess("postgresql-"+ps.getName(), "stop");
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public String getCronJobName() {
        return PostgresServerManager.class.getName();
    }
    
    public int getCronJobScheduleMode() {
        return CRON_JOB_SCHEDULE_SKIP;
    }

    /**
     * This task will be ran once per day at 1:30am.
     */
    public boolean isCronJobScheduled(int minute, int hour, int dayOfMonth, int month, int dayOfWeek, int year) {
        return
            minute==30
            && hour==1
        ;
    }

    public int getCronJobThreadPriority() {
        return Thread.NORM_PRIORITY-2;
    }

    /**
     * Rotates PostgreSQL log files.  Those older than one month are removed.
     */
    public void runCronJob(int minute, int hour, int dayOfMonth, int month, int dayOfWeek, int year) {
        Profiler.startProfile(Profiler.UNKNOWN, PostgresServerManager.class, "runCronJob(int,int,int,int,int)", null);
        try {
            try {
                AOServConnector conn = AOServDaemon.getConnector();
                for(PostgresServer postgresServer : AOServDaemon.getThisAOServer().getPostgresServers()) {
                    String version=postgresServer.getPostgresVersion().getTechnologyVersion(conn).getVersion();
                    if(
                        !version.startsWith(PostgresVersion.VERSION_7_1+'.')
                        && !version.startsWith(PostgresVersion.VERSION_7_2+'.')
                        && !version.startsWith(PostgresVersion.VERSION_7_3+'.')
                        && !version.startsWith(PostgresVersion.VERSION_8_0+'.')
                    ) {
                        // Is 8.1 or newer, need to compress and rotate logs
                        File logDirectory=new File("/var/log/postgresql", postgresServer.getName());
                        String[] list=logDirectory.list();
                        if(list!=null) {
                            for(String filename : list) {
                                if(
                                    !filename.equals("stderr")
                                    && !filename.equals("stdout")
                                ) {
                                    // Must be in postgresql-2006-02-14_011332.log format
                                    if(
                                        filename.length()!=32
                                        || !filename.substring(0, 11).equals("postgresql-")
                                        || filename.charAt(11)<'0' || filename.charAt(11)>'9'
                                        || filename.charAt(12)<'0' || filename.charAt(12)>'9'
                                        || filename.charAt(13)<'0' || filename.charAt(13)>'9'
                                        || filename.charAt(14)<'0' || filename.charAt(14)>'9'
                                        || filename.charAt(15)!='-'
                                        || filename.charAt(16)<'0' || filename.charAt(16)>'9'
                                        || filename.charAt(17)<'0' || filename.charAt(17)>'9'
                                        || filename.charAt(18)!='-'
                                        || filename.charAt(19)<'0' || filename.charAt(19)>'9'
                                        || filename.charAt(20)<'0' || filename.charAt(20)>'9'
                                        || filename.charAt(21)!='_'
                                        || filename.charAt(22)<'0' || filename.charAt(22)>'9'
                                        || filename.charAt(23)<'0' || filename.charAt(23)>'9'
                                        || filename.charAt(24)<'0' || filename.charAt(24)>'9'
                                        || filename.charAt(25)<'0' || filename.charAt(25)>'9'
                                        || filename.charAt(26)<'0' || filename.charAt(26)>'9'
                                        || filename.charAt(27)<'0' || filename.charAt(27)>'9'
                                        || filename.charAt(28)!='.'
                                        || filename.charAt(29)!='l'
                                        || filename.charAt(30)!='o'
                                        || filename.charAt(31)!='g'
                                    ) {
                                        AOServDaemon.reportWarning(new IOException("Warning, unexpected filename, will not remove: "+logDirectory.getPath()+"/"+filename), null);
                                    } else {
                                        // Determine the timestamp of the file
                                        Calendar fileDate=Calendar.getInstance();
                                        fileDate.set(Calendar.YEAR, Integer.parseInt(filename.substring(11, 15)));
                                        fileDate.set(Calendar.MONTH, Integer.parseInt(filename.substring(16, 18))-1);
                                        fileDate.set(Calendar.DAY_OF_MONTH, Integer.parseInt(filename.substring(19, 21)));
                                        fileDate.set(Calendar.HOUR_OF_DAY, 0);
                                        fileDate.set(Calendar.MINUTE, 0);
                                        fileDate.set(Calendar.SECOND, 0);
                                        fileDate.set(Calendar.MILLISECOND, 0);
                                        
                                        Calendar monthAgo=Calendar.getInstance();
                                        monthAgo.add(Calendar.MONTH, -1);
                                        
                                        if(fileDate.compareTo(monthAgo)<0) {
                                            new UnixFile(logDirectory, filename).delete();
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch(ThreadDeath TD) {
                throw TD;
            } catch(Throwable T) {
                AOServDaemon.reportError(T, null);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
}
