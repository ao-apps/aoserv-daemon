package com.aoindustries.aoserv.daemon.postgres;

/*
 * Copyright 2001-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
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

            int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
            if(
                osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
                && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
                && osv!=OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
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
                                version.startsWith(PostgresVersion.VERSION_7_1+'.')
                                || version.startsWith(PostgresVersion.VERSION_7_2+'.')
                                || version.startsWith(PostgresVersion.VERSION_7_3+'.')
                                || version.startsWith(PostgresVersion.VERSION_8_0+'.')
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
                                        (
                                            version.startsWith(PostgresVersion.VERSION_8_1+'.')
                                            || version.startsWith(PostgresVersion.VERSION_8_3+'.')
                                            || version.startsWith(PostgresVersion.VERSION_8_3+'R')
                                        )
                                        ? "CREATE ROLE "
                                        : "CREATE USER "
                                    )
                                    .append(username)
                                    .append(
                                        (
                                            version.startsWith(PostgresVersion.VERSION_7_1+'.')
                                        )
                                        ? " PASSWORD '"
                                        : " UNENCRYPTED PASSWORD '"
                                    )
                                    .append(PostgresUser.NO_PASSWORD_DB_VALUE)
                                    .append("' ")
                                    .append(pu.canCreateDB()?"CREATEDB":"NOCREATEDB")
                                    .append(' ')
                                    .append(
                                        (
                                            version.startsWith(PostgresVersion.VERSION_8_1+'.')
                                            || version.startsWith(PostgresVersion.VERSION_8_3+'.')
                                            || version.startsWith(PostgresVersion.VERSION_8_3+'R')
                                        )
                                        ? (pu.canCatUPD()?"CREATEROLE":"NOCREATEROLE")
                                        : (pu.canCatUPD()?"CREATEUSER":"NOCREATEUSER")
                                    ).append(
                                        (
                                            version.startsWith(PostgresVersion.VERSION_8_1+'.')
                                            || version.startsWith(PostgresVersion.VERSION_8_3+'.')
                                            || version.startsWith(PostgresVersion.VERSION_8_3+'R')
                                        )
                                        ? " LOGIN"
                                        : ""
                                    )
                                ;
                                stmt.executeUpdate(sql.toString());
                            }
                            if(
                                !(
                                    version.startsWith(PostgresVersion.VERSION_7_1+'.')
                                    || version.startsWith(PostgresVersion.VERSION_7_2+'.')
                                    || version.startsWith(PostgresVersion.VERSION_7_3+'.')
                                    || version.startsWith(PostgresVersion.VERSION_8_0+'.')
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
                    version.startsWith(PostgresVersion.VERSION_7_1+'.')
                    || version.startsWith(PostgresVersion.VERSION_7_2+'.')
                    || version.startsWith(PostgresVersion.VERSION_7_3+'.')
                    || version.startsWith(PostgresVersion.VERSION_8_0+'.')
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
                if(version.startsWith(PostgresVersion.VERSION_7_1+'.')) {
                    if(password==PostgresUser.NO_PASSWORD) {
                        // Remove the password
                        Statement stmt = conn.createStatement();
                        String sqlString = "alter user " + username + " with password '"+PostgresUser.NO_PASSWORD_DB_VALUE+'\'';
                        try {
                            stmt.executeUpdate(sqlString);
                        } catch(SQLException err) {
                            throw new WrappedSQLException(err, sqlString);
                        } finally {
                            stmt.close();
                        }
                    } else {
                        // Reset the password
                        PreparedStatement pstmt = conn.prepareStatement("alter user " + username + " with password ?");
                        try {
                            pstmt.setString(1, password);
                            pstmt.executeUpdate();
                        } catch(SQLException err) {
                            throw new WrappedSQLException(err, pstmt);
                        } finally {
                            pstmt.close();
                        }
                    }
                } else if(version.startsWith(PostgresVersion.VERSION_7_2+'.') || version.startsWith(PostgresVersion.VERSION_7_3+'.')) {
                    if(password==PostgresUser.NO_PASSWORD) {
                        // Remove the password
                        Statement stmt = conn.createStatement();
                        String sqlString = "alter user " + username + " with unencrypted password '"+PostgresUser.NO_PASSWORD_DB_VALUE+'\'';
                        try {
                            stmt.executeUpdate(sqlString);
                        } catch(SQLException err) {
                            throw new WrappedSQLException(err, sqlString);
                        } finally {
                            stmt.close();
                        }
                    } else {
                        // Reset the password
                        PreparedStatement pstmt = conn.prepareStatement("alter user " + username + " with unencrypted password ?");
                        try {
                            pstmt.setString(1, password);
                            pstmt.executeUpdate();
                        } catch(SQLException err) {
                            throw new WrappedSQLException(err, pstmt);
                        } finally {
                            pstmt.close();
                        }
                    }
                } else if(
                    version.startsWith(PostgresVersion.VERSION_8_1+'.')
                    || version.startsWith(PostgresVersion.VERSION_8_3+'.')
                    || version.startsWith(PostgresVersion.VERSION_8_3+'R')
                ) {
                    if(password==PostgresUser.NO_PASSWORD) {
                        // Remove the password
                        Statement stmt = conn.createStatement();
                        String sqlString = "alter role " + username + " with unencrypted password '"+PostgresUser.NO_PASSWORD_DB_VALUE+'\'';
                        try {
                            stmt.executeUpdate(sqlString);
                        } catch(SQLException err) {
                            throw new WrappedSQLException(err, sqlString);
                        } finally {
                            stmt.close();
                        }
                    } else {
                        // TODO: Find a way to use PreparedStatement here for PostgreSQL 8.1 and PostgreSQL 8.3
                        checkPasswordChars(password);
                        if(forceUnencrypted) {
                            // Reset the password (unencrypted)
                            Statement stmt = conn.createStatement();
                            String sqlString = "alter role " + username + " with unencrypted password '"+password+'\'';
                            try {
                                stmt.executeUpdate(sqlString);
                            } catch(SQLException err) {
                                throw new WrappedSQLException(err, sqlString);
                            } finally {
                                stmt.close();
                            }
                        } else {
                            // Reset the password (encrypted)
                            Statement stmt = conn.createStatement();
                            String sqlString = "alter role " + username + " with password '"+password+'\'';
                            try {
                                stmt.executeUpdate(sqlString);
                            } catch(SQLException err) {
                                throw new WrappedSQLException(err, sqlString);
                            } finally {
                                stmt.close();
                            }
                        }
                    }
                } else {
                    throw new SQLException("Unsupported version of PostgreSQL: "+version);
                }
            } finally {
                pool.releaseConnection(conn);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * Makes sure a password is safe for direct concatenation for PostgreSQL 8.1.
     *
     * Throw SQLException if not acceptable
     */
    private static void checkPasswordChars(String password) throws SQLException {
        if(password==null) throw new SQLException("password is null");
        if(password.length()==0) throw new SQLException("password is empty");
        for(int c=0;c<password.length();c++) {
            char ch = password.charAt(c);
            if(
                (ch<'a' || ch>'z')
                && (ch<'A' || ch>'Z')
                && (ch<'0' || ch>'9')
                && ch!=' '
                && ch!='!'
                && ch!='@'
                && ch!='#'
                && ch!='$'
                && ch!='%'
                && ch!='^'
                && ch!='&'
                && ch!='*'
                && ch!='('
                && ch!=')'
                && ch!='-'
                && ch!='_'
                && ch!='='
                && ch!='+'
                && ch!='['
                && ch!=']'
                && ch!='{'
                && ch!='}'
                && ch!='|'
                && ch!=';'
                && ch!=':'
                && ch!='"'
                && ch!=','
                && ch!='.'
                && ch!='<'
                && ch!='>'
                && ch!='/'
                && ch!='?'
            ) throw new SQLException("Invalid character in password, may only contain a-z, A-Z, 0-9, (space), !@#$%^&*()-_=+[]{}|;:\",.<>/?");
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