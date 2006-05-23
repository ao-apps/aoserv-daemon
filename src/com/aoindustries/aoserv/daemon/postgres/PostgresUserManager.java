package com.aoindustries.aoserv.daemon.postgres;

/*
 * Copyright 2001-2006 by AO Industries, Inc.,
 * 2200 Dogwood Ct N, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.aoserv.daemon.util.*;
import com.aoindustries.profiler.*;
import com.aoindustries.sql.*;
import com.aoindustries.table.*;
import com.aoindustries.util.*;
import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * Controls the PostgreSQL server.
 *
 * @author  AO Industries, Inc.
 */
final public class PostgresUserManager extends BuilderThread {

    private PostgresUserManager() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, PostgresUserManager.class, "<init>()", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, PostgresUserManager.class, "doRebuild()", null);
        try {
            AOServConnector connector = AOServDaemon.getConnector();
            AOServer thisAOServer=AOServDaemon.getThisAOServer();

            int osv=thisAOServer.getServer().getOperatingSystemVersion().getPKey();
            if(
                osv!=OperatingSystemVersion.MANDRAKE_10_1_I586
                && osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            synchronized (rebuildLock) {
                List<PostgresServer> pss=thisAOServer.getPostgresServers();
                for(int c=0;c<pss.size();c++) {
                    PostgresServer ps=pss.get(c);
                    String version=ps.getPostgresVersion().getTechnologyVersion(connector).getVersion();

                    // Get the connection to work through
                    AOConnectionPool pool=PostgresServerManager.getPool(ps);
                    Connection conn=pool.getConnection(false);
                    // Get the list of all users that should exist
                    List<PostgresServerUser> users=ps.getPostgresServerUsers();
                    boolean disableEnableDone = false;
                    try {
                        // Get the list of all existing users
                        List<String> existing=new SortedArrayList<String>();
                        Statement stmt=conn.createStatement();
                        try {
                            ResultSet results=stmt.executeQuery(
                                version.equals(PostgresVersion.VERSION_7_1_3)
                                || version.equals(PostgresVersion.VERSION_7_2_7)
                                || version.equals(PostgresVersion.VERSION_7_3_14)
                                || version.equals(PostgresVersion.VERSION_8_0_7)
                                ? "select usename from pg_user"
                                : "select rolname from pg_authid"
                            );
                            try {
                                while (results.next()) existing.add(results.getString(1));
                            } finally {
                                results.close();
                            }

                            // Find the users that do not exist and should be added
                            List<PostgresServerUser> needAdded=new ArrayList<PostgresServerUser>();
                            for (int d=0; d<users.size(); d++) {
                                PostgresServerUser psu=users.get(d);
                                PostgresUser pu=psu.getPostgresUser();
                                String username=pu.getUsername().getUsername();
                                if (existing.contains(username)) existing.remove(username);
                                else needAdded.add(psu);
                            }

                            // Remove the extra users before adding to avoid usesysid or usename conflicts
                            for (int d=0; d<existing.size(); d++) {
                                // Remove the extra user
                                String username=existing.get(d);
                                if(
                                    username.equals(PostgresUser.POSTGRES)
                                    || username.equals(PostgresUser.AOADMIN)
                                    || username.equals(PostgresUser.AOSERV_APP)
                                    || username.equals(PostgresUser.AOWEB_APP)
                                ) throw new SQLException("AOServ Daemon will not automatically drop user, please drop manually: "+username+" on "+ps.getName());
                                stmt.executeUpdate("DROP USER "+username);
                            }

                            // Add the new users
                            for(PostgresServerUser psu : needAdded) {
                                PostgresUser pu = psu.getPostgresUser();
                                String username=pu.getUsername().getUsername();

                                // Add the user
                                StringBuilder sql=new StringBuilder();
                                sql
                                    .append(
                                        version.equals(PostgresVersion.VERSION_8_1_3)
                                        ? "CREATE ROLE "
                                        : "CREATE USER "
                                    )
                                    .append(username)
                                    .append(
                                        (
                                            version.equals(PostgresVersion.VERSION_7_1_3)
                                        )
                                        ? " PASSWORD '"
                                        : " UNENCRYPTED PASSWORD '"
                                    )
                                    .append(PostgresUser.NO_PASSWORD_DB_VALUE)
                                    .append("' ")
                                    .append(pu.canCreateDB()?"CREATEDB":"NOCREATEDB")
                                    .append(' ')
                                    .append(
                                        version.equals(PostgresVersion.VERSION_8_1_3)
                                        ? (pu.canCatUPD()?"CREATEROLE":"NOCREATEROLE")
                                        : (pu.canCatUPD()?"CREATEUSER":"NOCREATEUSER")
                                    ).append(
                                        version.equals(PostgresVersion.VERSION_8_1_3)
                                        ? " LOGIN"
                                        : ""
                                    )
                                        
                                ;
                                stmt.executeUpdate(sql.toString());
                            }
                            if(
                                !(
                                    version.equals(PostgresVersion.VERSION_7_1_3)
                                    || version.equals(PostgresVersion.VERSION_7_2_7)
                                    || version.equals(PostgresVersion.VERSION_7_3_14)
                                    || version.equals(PostgresVersion.VERSION_8_0_7)
                                )
                            ) {
                                // Enable/disable using rolcanlogin
                                for(int d=0;d<users.size();d++) {
                                    PostgresServerUser psu=users.get(d);
                                    String username=psu.getPostgresUser().getUsername().getUsername();
                                    // Get the current login state
                                    boolean rolcanlogin;
                                    results=stmt.executeQuery("select rolcanlogin from pg_authid where rolname='"+username+"'");
                                    try {
                                        if(results.next()) {
                                            rolcanlogin = results.getBoolean(1);
                                        } else {
                                            throw new SQLException("Unable to find pg_authid entry for rolname='"+username+"'");
                                        }
                                    } finally {
                                        results.close();
                                    }
                                    if(psu.getDisableLog()==null) {
                                        // Enable if needed
                                        if(!rolcanlogin) stmt.executeUpdate("alter role "+username+" login");
                                    } else {
                                        // Disable if needed
                                        if(rolcanlogin) stmt.executeUpdate("alter role "+username+" nologin");
                                    }
                                }
                                disableEnableDone=true;
                            }
                        } finally {
                            stmt.close();
                        }
                    } finally {
                        pool.releaseConnection(conn);
                    }

                    if(!disableEnableDone) {
                        // Disable/enable using password value
                        for(int d=0;d<users.size();d++) {
                            PostgresServerUser psu=users.get(d);
                            String prePassword=psu.getPredisablePassword();
                            if(psu.getDisableLog()==null) {
                                if(prePassword!=null) {
                                    setPassword(psu, prePassword, true);
                                    psu.setPredisablePassword(null);
                                }
                            } else {
                                if(prePassword==null) {
                                    psu.setPredisablePassword(getPassword(psu));
                                    setPassword(psu, PostgresUser.NO_PASSWORD,true);
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static String getPassword(PostgresServerUser psu) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, PostgresUserManager.class, "getPassword(PostgresServerUser)", null);
        try {
            PostgresServer ps=psu.getPostgresServer();
            String version=ps.getPostgresVersion().getTechnologyVersion(AOServDaemon.getConnector()).getVersion();
            AOConnectionPool pool=PostgresServerManager.getPool(ps);
            Connection conn=pool.getConnection(true);
            try {
                PreparedStatement pstmt=conn.prepareStatement(
                    version.equals(PostgresVersion.VERSION_7_1_3)
                    || version.equals(PostgresVersion.VERSION_7_2_7)
                    || version.equals(PostgresVersion.VERSION_7_3_14)
                    || version.equals(PostgresVersion.VERSION_8_0_7)
                    ? "select passwd from pg_shadow where usename=?"
                    : "select rolpassword from pg_authid where rolname=?"
                );
                try {
                    pstmt.setString(1, psu.getPostgresUser().getUsername().getUsername());
                    ResultSet result=pstmt.executeQuery();
                    try {
                        if(result.next()) {
                            return result.getString(1);
                        } else throw new SQLException("No rows returned.");
                    } finally {
                        result.close();
                    }
                } catch(SQLException err) {
                    System.err.println("Error from query: "+pstmt);
                    throw err;
                } finally {
                    pstmt.close();
                }
            } finally {
                pool.releaseConnection(conn);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void setPassword(PostgresServerUser psu, String password, boolean forceUnencrypted) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, PostgresUserManager.class, "setPostgresUserPassword(PostgresServerUser,String,boolean)", null);
        try {
            // Get the connection to work through
            AOServConnector aoservConn=AOServDaemon.getConnector();
            PostgresServer ps=psu.getPostgresServer();
            String username=psu.getPostgresUser().getUsername().getUsername();
            AOConnectionPool pool=PostgresServerManager.getPool(ps);
            Connection conn = pool.getConnection(false);
            try {
                String version=ps.getPostgresVersion().getTechnologyVersion(aoservConn).getVersion();
                String midStatement;
                if(forceUnencrypted) {
                    if(version.equals(PostgresVersion.VERSION_7_1_3)) midStatement=" with password ";
                    else midStatement=" with unencrypted password ";
                } else {
                    if(
                        version.equals(PostgresVersion.VERSION_7_2_7)
                        || version.equals(PostgresVersion.VERSION_7_3_14)
                    ) {
                        midStatement=" with unencrypted password ";
                    } else {
                        midStatement=" with password ";
                    }
                }
                if(password==PostgresUser.NO_PASSWORD) {
                    // Disable the account
                    Statement stmt = conn.createStatement();
                    try {
                        stmt.executeUpdate("alter user "+username+midStatement+"'"+PostgresUser.NO_PASSWORD_DB_VALUE+'\'');
                    } finally {
                        stmt.close();
                    }
                } else {
                    // Reset the password
                    PreparedStatement pstmt = conn.prepareStatement("alter user "+username+midStatement+'?');
                    try {
                        pstmt.setString(1, password);
                        pstmt.executeUpdate();
                    } finally {
                        pstmt.close();
                    }
                }
            } finally {
                pool.releaseConnection(conn);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static PostgresUserManager postgresUserManager;
    public static void start() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, PostgresUserManager.class, "start()", null);
        try {
            if(AOServDaemonConfiguration.isManagerEnabled(PostgresUserManager.class) && postgresUserManager==null) {
                synchronized(System.out) {
                    if(postgresUserManager==null) {
                        System.out.print("Starting PostgresUserManager: ");
                        AOServConnector conn=AOServDaemon.getConnector();
                        postgresUserManager=new PostgresUserManager();
                        conn.postgresServerUsers.addTableListener(postgresUserManager, 0);
                        System.out.println("Done");
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void waitForRebuild() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, PostgresDatabaseManager.class, "waitForRebuild()", null);
        try {
            if(postgresUserManager!=null) postgresUserManager.waitForBuild();
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public String getProcessTimerDescription() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, PostgresDatabaseManager.class, "getProcessTimerDescription()", null);
        try {
            return "Rebuild PostgresSQL Users";
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
}