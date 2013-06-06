/*
 * Copyright 2002-2013 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.postgres;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.PostgresDatabase;
import com.aoindustries.aoserv.client.PostgresServer;
import com.aoindustries.aoserv.client.PostgresUser;
import com.aoindustries.aoserv.client.PostgresVersion;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.server.ServerManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.cron.CronDaemon;
import com.aoindustries.cron.CronJob;
import com.aoindustries.cron.CronJobScheduleMode;
import com.aoindustries.cron.Schedule;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.sql.AOConnectionPool;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Controls the PostgreSQL servers.
 *
 * @author  AO Industries, Inc.
 */
final public class PostgresServerManager extends BuilderThread implements CronJob {

    public static final File pgsqlDirectory=new File(PostgresServer.DATA_BASE_DIR);

    private PostgresServerManager() {
    }

    private static final Object rebuildLock=new Object();
    protected boolean doRebuild() {
        synchronized(rebuildLock) {
            // TODO: Add and initialize any missing /var/lib/pgsql/name
            // TODO: Add/update any /etc/rc.d/init.d/postgresql-name
            // TODO: restart any that need started/restarted
        }
        return true;
    }

    private static final Map<Integer,AOConnectionPool> pools=new HashMap<Integer,AOConnectionPool>();
    static AOConnectionPool getPool(PostgresServer ps) throws IOException, SQLException {
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
                    LogFactory.getLogger(PostgresServerManager.class)
                );
                pools.put(I, pool);
            }
            return pool;
        }
    }

    private static PostgresServerManager postgresServerManager;
    public static void start() throws IOException, SQLException {
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();

        synchronized(System.out) {
            if(
                // Nothing is done for these operating systems
                osv!=OperatingSystemVersion.CENTOS_5_DOM0_I686
                && osv!=OperatingSystemVersion.CENTOS_5_DOM0_X86_64
                // Check config after OS check so config entry not needed
                && AOServDaemonConfiguration.isManagerEnabled(PostgresServerManager.class)
                && postgresServerManager==null
            ) {
                System.out.print("Starting PostgresServerManager: ");
                AOServConnector conn=AOServDaemon.getConnector();
                postgresServerManager=new PostgresServerManager();
                conn.getPostgresServers().addTableListener(postgresServerManager, 0);
                // Register in CronDaemon
                CronDaemon.addCronJob(postgresServerManager, LogFactory.getLogger(PostgresServerManager.class));
                System.out.println("Done");
            }
        }
    }

    public static void waitForRebuild() {
        if(postgresServerManager!=null) postgresServerManager.waitForBuild();
    }

    public String getProcessTimerDescription() {
        return "Rebuild PostgreSQL Servers";
    }

    public static void restartPostgreSQL(PostgresServer ps) throws IOException, SQLException {
        ServerManager.controlProcess("postgresql-"+ps.getName(), "restart");
    }

    public static void startPostgreSQL(PostgresServer ps) throws IOException, SQLException {
        ServerManager.controlProcess("postgresql-"+ps.getName(), "start");
    }

    public static void stopPostgreSQL(PostgresServer ps) throws IOException, SQLException {
        ServerManager.controlProcess("postgresql-"+ps.getName(), "stop");
    }

    public String getCronJobName() {
        return PostgresServerManager.class.getName();
    }
    
    public CronJobScheduleMode getCronJobScheduleMode() {
        return CronJobScheduleMode.SKIP;
    }

    private static final Schedule schedule = new Schedule() {
        /**
         * This task will be ran once per day at 1:30am.
         */
        public boolean isCronJobScheduled(int minute, int hour, int dayOfMonth, int month, int dayOfWeek, int year) {
            return
                minute==30
                && hour==1
            ;
        }
    };

    public Schedule getCronJobSchedule() {
        return schedule;
    }

    public int getCronJobThreadPriority() {
        return Thread.NORM_PRIORITY-2;
    }

    /**
     * Rotates PostgreSQL log files.  Those older than one month are removed.
     */
    public void runCronJob(int minute, int hour, int dayOfMonth, int month, int dayOfWeek, int year) {
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
                                    LogFactory.getLogger(PostgresServerManager.class).log(Level.WARNING, null, new IOException("Warning, unexpected filename, will not remove: "+logDirectory.getPath()+"/"+filename));
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
            LogFactory.getLogger(PostgresServerManager.class).log(Level.SEVERE, null, T);
        }
    }
}
