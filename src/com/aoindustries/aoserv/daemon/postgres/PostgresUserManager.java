/*
 * Copyright 2001-2013 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.postgres;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.PostgresServer;
import com.aoindustries.aoserv.client.PostgresServerUser;
import com.aoindustries.aoserv.client.PostgresUser;
import com.aoindustries.aoserv.client.PostgresVersion;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.lang.ObjectUtils;
import com.aoindustries.sql.AOConnectionPool;
import com.aoindustries.sql.WrappedSQLException;
import com.aoindustries.util.SortedArrayList;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Controls the PostgreSQL server.
 *
 * @author  AO Industries, Inc.
 */
final public class PostgresUserManager extends BuilderThread {

    private static final boolean DEBUG = false;

    private static void debug(String message) {
        if(DEBUG) {
            System.err.println("DEBUG: [" + PostgresUserManager.class.getName() +"]: " + message);
        }
    }

    private PostgresUserManager() {
    }

    private static final Object rebuildLock=new Object();
	@Override
    protected boolean doRebuild() {
        try {
            AOServConnector connector = AOServDaemon.getConnector();
            AOServer thisAOServer=AOServDaemon.getThisAOServer();

            int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
            if(
                osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
                && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
                && osv!=OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
            ) throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);

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
                        List<String> existing=new SortedArrayList<>();
                        Statement stmt=conn.createStatement();
                        try {
                            String sqlString =
                                version.startsWith(PostgresVersion.VERSION_7_1+'.')
                                || version.startsWith(PostgresVersion.VERSION_7_2+'.')
                                || version.startsWith(PostgresVersion.VERSION_7_3+'.')
                                || version.startsWith(PostgresVersion.VERSION_8_0+'.')
                                ? "select usename from pg_user"
                                : "select rolname from pg_authid"
                            ;
                            try {
                                ResultSet results=stmt.executeQuery(sqlString);
                                try {
                                    while (results.next()) {
                                        String username = results.getString(1);
                                        if(DEBUG) debug("Found user " + username);
                                        existing.add(username);
                                    }
                                } finally {
                                    results.close();
                                }
                            } catch(SQLException err) {
                                throw new WrappedSQLException(err, sqlString);
                            }

                            // Find the users that do not exist and should be added
                            List<PostgresServerUser> needAdded=new ArrayList<>();
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
                                sqlString = "DROP USER "+username;
                                try {
                                    if(DEBUG) debug("Dropping user: " + sqlString);
                                    stmt.executeUpdate(sqlString);
                                    conn.commit();
                                } catch(SQLException err) {
                                    throw new WrappedSQLException(err, sqlString);
                                }
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
                                    //.append(
                                    //    (
                                    //        version.startsWith(PostgresVersion.VERSION_7_1+'.')
                                    //    )
                                    //    ? " PASSWORD '"
                                    //    : " UNENCRYPTED PASSWORD '"
                                    //)
                                    //.append(PostgresUser.NO_PASSWORD_DB_VALUE)
                                    //.append("' ")
                                    .append(pu.canCreateDB()?" CREATEDB":" NOCREATEDB")
                                    .append(
                                        (
                                            version.startsWith(PostgresVersion.VERSION_8_1+'.')
                                            || version.startsWith(PostgresVersion.VERSION_8_3+'.')
                                            || version.startsWith(PostgresVersion.VERSION_8_3+'R')
                                        )
                                        ? (pu.canCatUPD()?" CREATEROLE":" NOCREATEROLE")
                                        : (pu.canCatUPD()?" CREATEUSER":" NOCREATEUSER")
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
                                sqlString = sql.toString();
                                try {
                                    if(DEBUG) debug("Adding user: " + sqlString);
                                    stmt.executeUpdate(sqlString);
                                    conn.commit();
                                } catch(SQLException err) {
                                    throw new WrappedSQLException(err, sqlString);
                                }
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
                                    sqlString = "select rolcanlogin from pg_authid where rolname='"+username+"'";
                                    try {
                                        ResultSet results=stmt.executeQuery(sqlString);
                                        try {
                                            if(results.next()) {
                                                rolcanlogin = results.getBoolean(1);
                                            } else {
                                                throw new SQLException("Unable to find pg_authid entry for rolname='"+username+"'");
                                            }
                                        } finally {
                                            results.close();
                                        }
                                    } catch(SQLException err) {
                                        throw new WrappedSQLException(err, sqlString);
                                    }
                                    if(!psu.isDisabled()) {
                                        // Enable if needed
                                        if(!rolcanlogin) {
                                            sqlString = "alter role "+username+" login";
                                            try {
                                                if(DEBUG) debug("Adding login role: " + sqlString);
                                                stmt.executeUpdate(sqlString);
                                            } catch(SQLException err) {
                                                throw new WrappedSQLException(err, sqlString);
                                            }
                                        }
                                    } else {
                                        // Disable if needed
                                        if(rolcanlogin) {
                                            sqlString = "alter role "+username+" nologin";
                                            try {
                                                if(DEBUG) debug("Removing login role: " + sqlString);
                                                stmt.executeUpdate(sqlString);
                                            } catch(SQLException err) {
                                                throw new WrappedSQLException(err, sqlString);
                                            }
                                        }
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
                            if(!psu.isDisabled()) {
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
            return true;
        } catch(ThreadDeath TD) {
            throw TD;
        } catch(Throwable T) {
            LogFactory.getLogger(PostgresUserManager.class).log(Level.SEVERE, null, T);
            return false;
        }
    }

    public static String getPassword(PostgresServerUser psu) throws IOException, SQLException {
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
                throw new WrappedSQLException(err, pstmt);
            } finally {
                pstmt.close();
            }
        } finally {
            pool.releaseConnection(conn);
        }
    }

    public static void setPassword(PostgresServerUser psu, String password, boolean forceUnencrypted) throws IOException, SQLException {
        // Get the connection to work through
        AOServConnector aoservConn=AOServDaemon.getConnector();
        PostgresServer ps=psu.getPostgresServer();
        String username=psu.getPostgresUser().getUsername().getUsername();
        AOConnectionPool pool=PostgresServerManager.getPool(ps);
        Connection conn = pool.getConnection(false);
        try {
            String version=ps.getPostgresVersion().getTechnologyVersion(aoservConn).getVersion();
            if(version.startsWith(PostgresVersion.VERSION_7_1+'.')) {
                if(ObjectUtils.equals(password, PostgresUser.NO_PASSWORD)) {
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
                if(ObjectUtils.equals(password, PostgresUser.NO_PASSWORD)) {
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
                if(ObjectUtils.equals(password, PostgresUser.NO_PASSWORD)) {
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
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();

        synchronized(System.out) {
            if(
                // Nothing is done for these operating systems
                osv!=OperatingSystemVersion.CENTOS_5_DOM0_I686
                && osv!=OperatingSystemVersion.CENTOS_5_DOM0_X86_64
                // Check config after OS check so config entry not needed
                && AOServDaemonConfiguration.isManagerEnabled(PostgresUserManager.class)
                && postgresUserManager==null
            ) {
                System.out.print("Starting PostgresUserManager: ");
                AOServConnector conn=AOServDaemon.getConnector();
                postgresUserManager=new PostgresUserManager();
                conn.getPostgresServerUsers().addTableListener(postgresUserManager, 0);
                System.out.println("Done");
            }
        }
    }

    public static void waitForRebuild() {
        if(postgresUserManager!=null) postgresUserManager.waitForBuild();
    }

	@Override
    public String getProcessTimerDescription() {
        return "Rebuild PostgresSQL Users";
    }
}