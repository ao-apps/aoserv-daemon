package com.aoindustries.aoserv.daemon.postgres;

/*
 * Copyright 2003-2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.aoserv.daemon.server.*;
import com.aoindustries.aoserv.daemon.unix.linux.*;
import com.aoindustries.aoserv.daemon.util.*;
import com.aoindustries.io.*;
import com.aoindustries.io.unix.*;
import com.aoindustries.md5.*;
import com.aoindustries.profiler.*;
import com.aoindustries.sql.*;
import com.aoindustries.table.*;
import com.aoindustries.util.*;
import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.zip.*;

/**
 * Controls the pg_hba.conf files for the PostgreSQL installations.
 *
 * @author  AO Industries, Inc.
 */
public final class PgHbaManager extends BuilderThread {

    private PgHbaManager() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, PgHbaManager.class, "<init>()", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, PgHbaManager.class, "doRebuild()", null);
        try {
            AOServConnector connector=AOServDaemon.getConnector();
            AOServer thisAOServer=AOServDaemon.getThisAOServer();

            
            int osv=thisAOServer.getServer().getOperatingSystemVersion().getPKey();
            if(
                osv!=OperatingSystemVersion.MANDRAKE_10_1_I586
                && osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
                && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            final Stat tempStat = new Stat();

            synchronized(rebuildLock) {
                for(PostgresServer ps : thisAOServer.getPostgresServers()) {
                    String version=ps.getPostgresVersion().getTechnologyVersion(connector).getVersion();
                    int postgresUID=thisAOServer.getLinuxServerAccount(LinuxAccount.POSTGRES).getUID().getID();
                    int postgresGID=thisAOServer.getLinuxServerGroup(LinuxGroup.POSTGRES).getGID().getID();
                    String serverName=ps.getName();
                    File serverDir=new File(PostgresServerManager.pgsqlDirectory, serverName);
                    UnixFile newHbaUF=new UnixFile(serverDir, "pg_hba.conf.new");
                    ChainWriter out=new ChainWriter(new FileOutputStream(newHbaUF.getFile()));
                    try {
                        newHbaUF.setMode(0600).chown(postgresUID, postgresGID);

                        if(
                            version.startsWith(PostgresVersion.VERSION_7_1+'.')
                            || version.startsWith(PostgresVersion.VERSION_7_2+'.')
                        ) {
                            out.print("local all trust\n"
                                    + "host all 127.0.0.1 255.255.255.255 ident sameuser\n"
                                    + "host all 0.0.0.0 0.0.0.0 password\n");
                        } else if(version.startsWith(PostgresVersion.VERSION_7_3+'.')) {
                            out.print("local all all trust\n");
                            List<PostgresServerUser> users=ps.getPostgresServerUsers();
                            for(PostgresDatabase db : ps.getPostgresDatabases()) {
                                out.print("host ").print(db.getName()).print(' ');
                                boolean didOne=false;
                                for(PostgresServerUser psu : users) {
                                    Username un=psu.getPostgresUser().getUsername();

                                    if(
                                        // Allow postgres to all databases
                                        un.getUsername().equals(PostgresUser.POSTGRES)

                                        // Allow database admin
                                        || psu.equals(db.getDatDBA())

                                        // Allow in same business
                                        || un.getPackage().getBusiness().equals(db.getDatDBA().getPostgresUser().getUsername().getPackage().getBusiness())
                                    ) {
                                        if(didOne) out.print(',');
                                        else didOne=true;
                                        out.print(un.getUsername());
                                    }
                                }
                                out.print(" 127.0.0.1 255.255.255.255 ident sameuser\n"
                                        + "host ").print(db.getName()).print(' ');
                                didOne=false;
                                for(PostgresServerUser psu : users) {
                                    Username un=psu.getPostgresUser().getUsername();

                                    if(
                                        // Allow postgres to all databases
                                        un.getUsername().equals(PostgresUser.POSTGRES)

                                        // Allow database admin
                                        || psu.equals(db.getDatDBA())

                                        // Allow in same business
                                        || un.getPackage().getBusiness().equals(db.getDatDBA().getPostgresUser().getUsername().getPackage().getBusiness())
                                    ) {
                                        if(didOne) out.print(',');
                                        else didOne=true;
                                        out.print(un.getUsername());
                                    }
                                }
                                out.print(" 0.0.0.0 0.0.0.0 password\n");
                            }
                        } else if(version.startsWith(PostgresVersion.VERSION_8_1+'.')) {
                            List<PostgresServerUser> users=ps.getPostgresServerUsers();
                            for(PostgresDatabase db : ps.getPostgresDatabases()) {
                                // ident used from local
                                out.print("local ").print(db.getName()).print(' ');
                                boolean didOne=false;
                                for(PostgresServerUser psu : users) {
                                    Username un=psu.getPostgresUser().getUsername();

                                    if(
                                        // Allow postgres to all databases
                                        un.getUsername().equals(PostgresUser.POSTGRES)

                                        // Allow database admin
                                        || psu.equals(db.getDatDBA())

                                        // Allow in same business
                                        || un.getPackage().getBusiness().equals(db.getDatDBA().getPostgresUser().getUsername().getPackage().getBusiness())
                                    ) {
                                        if(didOne) out.print(',');
                                        else didOne=true;
                                        out.print(un.getUsername());
                                    }
                                }
                                out.print(" ident sameuser\n");

                                // ident used from 127.0.0.1
                                out.print("host ").print(db.getName()).print(' ');
                                didOne=false;
                                for(PostgresServerUser psu : users) {
                                    Username un=psu.getPostgresUser().getUsername();

                                    if(
                                        // Allow postgres to all databases
                                        un.getUsername().equals(PostgresUser.POSTGRES)

                                        // Allow database admin
                                        || psu.equals(db.getDatDBA())

                                        // Allow in same business
                                        || un.getPackage().getBusiness().equals(db.getDatDBA().getPostgresUser().getUsername().getPackage().getBusiness())
                                    ) {
                                        if(didOne) out.print(',');
                                        else didOne=true;
                                        out.print(un.getUsername());
                                    }
                                }
                                out.print(" 127.0.0.1/32 ident sameuser\n");

                                // ident used from ::1/128
                                out.print("host ").print(db.getName()).print(' ');
                                didOne=false;
                                for(PostgresServerUser psu : users) {
                                    Username un=psu.getPostgresUser().getUsername();

                                    if(
                                        // Allow postgres to all databases
                                        un.getUsername().equals(PostgresUser.POSTGRES)

                                        // Allow database admin
                                        || psu.equals(db.getDatDBA())

                                        // Allow in same business
                                        || un.getPackage().getBusiness().equals(db.getDatDBA().getPostgresUser().getUsername().getPackage().getBusiness())
                                    ) {
                                        if(didOne) out.print(',');
                                        else didOne=true;
                                        out.print(un.getUsername());
                                    }
                                }
                                out.print(" ::1/128 ident sameuser\n");

                                // md5 used for other connections
                                out.print("host ").print(db.getName()).print(' ');
                                didOne=false;
                                for(PostgresServerUser psu : users) {
                                    Username un=psu.getPostgresUser().getUsername();

                                    if(
                                        // Allow postgres to all databases
                                        un.getUsername().equals(PostgresUser.POSTGRES)

                                        // Allow database admin
                                        || psu.equals(db.getDatDBA())

                                        // Allow in same business
                                        || un.getPackage().getBusiness().equals(db.getDatDBA().getPostgresUser().getUsername().getPackage().getBusiness())
                                    ) {
                                        if(didOne) out.print(',');
                                        else didOne=true;
                                        out.print(un.getUsername());
                                    }
                                }
                                out.print(" 0.0.0.0 0.0.0.0 md5\n");
                            }
                        } else throw new RuntimeException("Unexpected version of PostgreSQL: "+version);
                    } finally {
                        out.flush();
                        out.close();
                    }

                    // Move the new file into place
                    UnixFile hbaUF=new UnixFile(serverDir, "pg_hba.conf");
                    if(hbaUF.getStat(tempStat).exists() && newHbaUF.contentEquals(hbaUF)) newHbaUF.delete();
                    else {
                        // Move the new file into place
                        newHbaUF.renameTo(hbaUF);

                        // Signal reload on PostgreSQL
                        BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(new File(serverDir, "postmaster.pid"))));
                        String pid=in.readLine();
                        // Must be all 0-9
                        for(int d=0;d<pid.length();d++) {
                            char ch=pid.charAt(d);
                            if(ch<'0' || ch>'9') throw new IOException("Invalid character in postmaster.pid first line: "+ch);
                        }
                        new LinuxProcess(Integer.parseInt(pid)).signal("HUP");
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static PgHbaManager pgHbaManager;
    public static void start() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, PgHbaManager.class, "start()", null);
        try {
            if(AOServDaemonConfiguration.isManagerEnabled(PgHbaManager.class) && pgHbaManager==null) {
                synchronized(System.out) {
                    if(pgHbaManager==null) {
                        System.out.print("Starting PgHbaManager: ");
                        AOServConnector conn=AOServDaemon.getConnector();
                        pgHbaManager=new PgHbaManager();
                        conn.postgresDatabases.addTableListener(pgHbaManager, 0);
                        conn.postgresServerUsers.addTableListener(pgHbaManager, 0);
                        System.out.println("Done");
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public String getProcessTimerDescription() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, PgHbaManager.class, "getProcessTimerDescription()", null);
        try {
            return "Rebuild PostgreSQL pg_hba.conf";
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
}
