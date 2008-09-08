package com.aoindustries.aoserv.daemon.mysql;

/*
 * Copyright 2002-2008 by AO Industries, Inc.,
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
import java.util.List;

/**
 * Controls the MySQL Users.
 *
 * @author  AO Industries, Inc.
 */
final public class MySQLUserManager extends BuilderThread {

    private MySQLUserManager() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, MySQLUserManager.class, "<init>()", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, MySQLUserManager.class, "doRebuild()", null);
        try {
            AOServConnector connector = AOServDaemon.getConnector();
            AOServer thisAOServer=AOServDaemon.getThisAOServer();

            int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
            if(
                osv!=OperatingSystemVersion.MANDRAKE_10_1_I586
                && osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
                && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            synchronized (rebuildLock) {
                for(MySQLServer mysqlServer : thisAOServer.getMySQLServers()) {
                    String version=mysqlServer.getVersion().getVersion();
                    // Get the list of all users that should exist.  By getting the list and reusing it we have a snapshot of the configuration.
                    List<MySQLServerUser> users = mysqlServer.getMySQLServerUsers();

                    boolean modified = false;

                    // Get the connection to work through
                    AOConnectionPool pool = MySQLServerManager.getPool(mysqlServer);
                    Connection conn = pool.getConnection();
                    try {
                        // Get the list of all existing users
                        List<String> existing = new SortedArrayList<String>();
                        Statement stmt = conn.createStatement();
                        try {
                            ResultSet results = stmt.executeQuery("select host, user from user");
                            try {
                                while (results.next()) existing.add(results.getString(1) + '|' + results.getString(2));
                            } finally {
                                results.close();
                            }
                        } finally {
                            stmt.close();
                        }

                        // Update existing users to proper values
                        String updateSQL;
                        if(
                            osv==OperatingSystemVersion.MANDRAKE_10_1_I586
                            || osv==OperatingSystemVersion.MANDRIVA_2006_0_I586
                            || osv==OperatingSystemVersion.REDHAT_ES_4_X86_64
                        ) {
                            if(version.startsWith("4.0.")) {
                                updateSQL="update user set\n"
                                        + "  Select_priv=?,\n"
                                        + "  Insert_priv=?,\n"
                                        + "  Update_priv=?,\n"
                                        + "  Delete_priv=?,\n"
                                        + "  Create_priv=?,\n"
                                        + "  Drop_priv=?,\n"
                                        + "  Reload_priv=?,\n"
                                        + "  Shutdown_priv=?,\n"
                                        + "  Process_priv=?,\n"
                                        + "  File_priv=?,\n"
                                        + "  Grant_priv=?,\n"
                                        + "  References_priv=?,\n"
                                        + "  Index_priv=?,\n"
                                        + "  Alter_priv=?,\n"
                                        + "  Show_db_priv=?,\n"
                                        + "  Super_priv=?,\n"
                                        + "  Create_tmp_table_priv=?,\n"
                                        + "  Lock_tables_priv=?,\n"
                                        + "  Execute_priv=?,\n"
                                        + "  Repl_slave_priv=?,\n"
                                        + "  Repl_client_priv=?,\n"
                                        + "  max_questions=?,\n"
                                        + "  max_updates=?,\n"
                                        + "  max_connections=?\n"
                                        + "where\n"
                                        + "  Host=?\n"
                                        + "  and User=?\n"
                                        + "  and (\n"
                                        + "    Select_priv!=?\n"
                                        + "    or Insert_priv!=?\n"
                                        + "    or Update_priv!=?\n"
                                        + "    or Delete_priv!=?\n"
                                        + "    or Create_priv!=?\n"
                                        + "    or Drop_priv!=?\n"
                                        + "    or Reload_priv!=?\n"
                                        + "    or Shutdown_priv!=?\n"
                                        + "    or Process_priv!=?\n"
                                        + "    or File_priv!=?\n"
                                        + "    or Grant_priv!=?\n"
                                        + "    or References_priv!=?\n"
                                        + "    or Index_priv!=?\n"
                                        + "    or Alter_priv!=?\n"
                                        + "    or Show_db_priv!=?\n"
                                        + "    or Super_priv!=?\n"
                                        + "    or Create_tmp_table_priv!=?\n"
                                        + "    or Lock_tables_priv!=?\n"
                                        + "    or Execute_priv!=?\n"
                                        + "    or Repl_slave_priv!=?\n"
                                        + "    or Repl_client_priv!=?\n"
                                        + "    or max_questions!=?\n"
                                        + "    or max_updates!=?\n"
                                        + "    or max_connections!=?\n"
                                        + "  )";
                            } else if(version.startsWith("4.1.")) {
                                updateSQL="update user set\n"
                                        + "  Select_priv=?,\n"
                                        + "  Insert_priv=?,\n"
                                        + "  Update_priv=?,\n"
                                        + "  Delete_priv=?,\n"
                                        + "  Create_priv=?,\n"
                                        + "  Drop_priv=?,\n"
                                        + "  Reload_priv=?,\n"
                                        + "  Shutdown_priv=?,\n"
                                        + "  Process_priv=?,\n"
                                        + "  File_priv=?,\n"
                                        + "  Grant_priv=?,\n"
                                        + "  References_priv=?,\n"
                                        + "  Index_priv=?,\n"
                                        + "  Alter_priv=?,\n"
                                        + "  Show_db_priv=?,\n"
                                        + "  Super_priv=?,\n"
                                        + "  Create_tmp_table_priv=?,\n"
                                        + "  Lock_tables_priv=?,\n"
                                        + "  Execute_priv=?,\n"
                                        + "  Repl_slave_priv=?,\n"
                                        + "  Repl_client_priv=?,\n"
                                        + "  max_questions=?,\n"
                                        + "  max_updates=?,\n"
                                        + "  max_connections=?\n"
                                        + "where\n"
                                        + "  Host=?\n"
                                        + "  and User=?\n"
                                        + "  and (\n"
                                        + "    Select_priv!=?\n"
                                        + "    or Insert_priv!=?\n"
                                        + "    or Update_priv!=?\n"
                                        + "    or Delete_priv!=?\n"
                                        + "    or Create_priv!=?\n"
                                        + "    or Drop_priv!=?\n"
                                        + "    or Reload_priv!=?\n"
                                        + "    or Shutdown_priv!=?\n"
                                        + "    or Process_priv!=?\n"
                                        + "    or File_priv!=?\n"
                                        + "    or Grant_priv!=?\n"
                                        + "    or References_priv!=?\n"
                                        + "    or Index_priv!=?\n"
                                        + "    or Alter_priv!=?\n"
                                        + "    or Show_db_priv!=?\n"
                                        + "    or Super_priv!=?\n"
                                        + "    or Create_tmp_table_priv!=?\n"
                                        + "    or Lock_tables_priv!=?\n"
                                        + "    or Execute_priv!=?\n"
                                        + "    or Repl_slave_priv!=?\n"
                                        + "    or Repl_client_priv!=?\n"
                                        + "    or max_questions!=?\n"
                                        + "    or max_updates!=?\n"
                                        + "    or max_connections!=?\n"
                                        + "  )";
                            } else if(version.startsWith("5.0.")) {
                                updateSQL="update user set\n"
                                        + "  Select_priv=?,\n"
                                        + "  Insert_priv=?,\n"
                                        + "  Update_priv=?,\n"
                                        + "  Delete_priv=?,\n"
                                        + "  Create_priv=?,\n"
                                        + "  Drop_priv=?,\n"
                                        + "  Reload_priv=?,\n"
                                        + "  Shutdown_priv=?,\n"
                                        + "  Process_priv=?,\n"
                                        + "  File_priv=?,\n"
                                        + "  Grant_priv=?,\n"
                                        + "  References_priv=?,\n"
                                        + "  Index_priv=?,\n"
                                        + "  Alter_priv=?,\n"
                                        + "  Show_db_priv=?,\n"
                                        + "  Super_priv=?,\n"
                                        + "  Create_tmp_table_priv=?,\n"
                                        + "  Lock_tables_priv=?,\n"
                                        + "  Execute_priv=?,\n"
                                        + "  Repl_slave_priv=?,\n"
                                        + "  Repl_client_priv=?,\n"
                                        + "  Create_view_priv=?,\n"
                                        + "  Show_view_priv=?,\n"
                                        + "  Create_routine_priv=?,\n"
                                        + "  Alter_routine_priv=?,\n"
                                        + "  Create_user_priv=?,\n"
                                        + "  max_questions=?,\n"
                                        + "  max_updates=?,\n"
                                        + "  max_connections=?,\n"
                                        + "  max_user_connections=?\n"
                                        + "where\n"
                                        + "  Host=?\n"
                                        + "  and User=?\n"
                                        + "  and (\n"
                                        + "    Select_priv!=?\n"
                                        + "    or Insert_priv!=?\n"
                                        + "    or Update_priv!=?\n"
                                        + "    or Delete_priv!=?\n"
                                        + "    or Create_priv!=?\n"
                                        + "    or Drop_priv!=?\n"
                                        + "    or Reload_priv!=?\n"
                                        + "    or Shutdown_priv!=?\n"
                                        + "    or Process_priv!=?\n"
                                        + "    or File_priv!=?\n"
                                        + "    or Grant_priv!=?\n"
                                        + "    or References_priv!=?\n"
                                        + "    or Index_priv!=?\n"
                                        + "    or Alter_priv!=?\n"
                                        + "    or Show_db_priv!=?\n"
                                        + "    or Super_priv!=?\n"
                                        + "    or Create_tmp_table_priv!=?\n"
                                        + "    or Lock_tables_priv!=?\n"
                                        + "    or Execute_priv!=?\n"
                                        + "    or Repl_slave_priv!=?\n"
                                        + "    or Repl_client_priv!=?\n"
                                        + "    or Create_view_priv!=?\n"
                                        + "    or Show_view_priv!=?\n"
                                        + "    or Create_routine_priv!=?\n"
                                        + "    or Alter_routine_priv!=?\n"
                                        + "    or Create_user_priv!=?\n"
                                        + "    or max_questions!=?\n"
                                        + "    or max_updates!=?\n"
                                        + "    or max_connections!=?\n"
                                        + "    or max_user_connections!=?\n"
                                        + "  )";
                            } else throw new SQLException("Unsupported MySQL version: "+version);
                        } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

                        PreparedStatement pstmt = conn.prepareStatement(updateSQL);
                        try {
                            for(MySQLServerUser msu : users) {
                                MySQLUser mu = msu.getMySQLUser();
                                String host=msu.getHost();
                                if(host==null) host="";
                                String username=mu.getUsername().getUsername();
                                String key=host+'|'+username;
                                if(existing.contains(key)) {
                                    int pos=1;
                                    // set
                                    pstmt.setString(pos++, mu.canSelect()?"Y":"N");
                                    pstmt.setString(pos++, mu.canInsert()?"Y":"N");
                                    pstmt.setString(pos++, mu.canUpdate()?"Y":"N");
                                    pstmt.setString(pos++, mu.canDelete()?"Y":"N");
                                    pstmt.setString(pos++, mu.canCreate()?"Y":"N");
                                    pstmt.setString(pos++, mu.canDrop()?"Y":"N");
                                    pstmt.setString(pos++, mu.canReload()?"Y":"N");
                                    pstmt.setString(pos++, mu.canShutdown()?"Y":"N");
                                    pstmt.setString(pos++, mu.canProcess()?"Y":"N");
                                    pstmt.setString(pos++, mu.canFile()?"Y":"N");
                                    pstmt.setString(pos++, mu.canGrant()?"Y":"N");
                                    pstmt.setString(pos++, mu.canReference()?"Y":"N");
                                    pstmt.setString(pos++, mu.canIndex()?"Y":"N");
                                    pstmt.setString(pos++, mu.canAlter()?"Y":"N");
                                    pstmt.setString(pos++, mu.canShowDB()?"Y":"N");
                                    pstmt.setString(pos++, mu.isSuper()?"Y":"N");
                                    pstmt.setString(pos++, mu.canCreateTempTable()?"Y":"N");
                                    pstmt.setString(pos++, mu.canLockTables()?"Y":"N");
                                    pstmt.setString(pos++, mu.canExecute()?"Y":"N");
                                    pstmt.setString(pos++, mu.isReplicationSlave()?"Y":"N");
                                    pstmt.setString(pos++, mu.isReplicationClient()?"Y":"N");
                                    if(version.startsWith("5.0.")) {
                                        pstmt.setString(pos++, mu.canCreateView()?"Y":"N");
                                        pstmt.setString(pos++, mu.canShowView()?"Y":"N");
                                        pstmt.setString(pos++, mu.canCreateRoutine()?"Y":"N");
                                        pstmt.setString(pos++, mu.canAlterRoutine()?"Y":"N");
                                        pstmt.setString(pos++, mu.canCreateUser()?"Y":"N");
                                    }
                                    pstmt.setInt(pos++, msu.getMaxQuestions());
                                    pstmt.setInt(pos++, msu.getMaxUpdates());
                                    pstmt.setInt(pos++, msu.getMaxConnections());
                                    if(version.startsWith("5.0.")) pstmt.setInt(pos++, msu.getMaxUserConnections());
                                    // where
                                    pstmt.setString(pos++, host);
                                    pstmt.setString(pos++, username);
                                    pstmt.setString(pos++, mu.canSelect()?"Y":"N");
                                    pstmt.setString(pos++, mu.canInsert()?"Y":"N");
                                    pstmt.setString(pos++, mu.canUpdate()?"Y":"N");
                                    pstmt.setString(pos++, mu.canDelete()?"Y":"N");
                                    pstmt.setString(pos++, mu.canCreate()?"Y":"N");
                                    pstmt.setString(pos++, mu.canDrop()?"Y":"N");
                                    pstmt.setString(pos++, mu.canReload()?"Y":"N");
                                    pstmt.setString(pos++, mu.canShutdown()?"Y":"N");
                                    pstmt.setString(pos++, mu.canProcess()?"Y":"N");
                                    pstmt.setString(pos++, mu.canFile()?"Y":"N");
                                    pstmt.setString(pos++, mu.canGrant()?"Y":"N");
                                    pstmt.setString(pos++, mu.canReference()?"Y":"N");
                                    pstmt.setString(pos++, mu.canIndex()?"Y":"N");
                                    pstmt.setString(pos++, mu.canAlter()?"Y":"N");
                                    pstmt.setString(pos++, mu.canShowDB()?"Y":"N");
                                    pstmt.setString(pos++, mu.isSuper()?"Y":"N");
                                    pstmt.setString(pos++, mu.canCreateTempTable()?"Y":"N");
                                    pstmt.setString(pos++, mu.canLockTables()?"Y":"N");
                                    pstmt.setString(pos++, mu.canExecute()?"Y":"N");
                                    pstmt.setString(pos++, mu.isReplicationSlave()?"Y":"N");
                                    pstmt.setString(pos++, mu.isReplicationClient()?"Y":"N");
                                    if(version.startsWith("5.0.")) {
                                        pstmt.setString(pos++, mu.canCreateView()?"Y":"N");
                                        pstmt.setString(pos++, mu.canShowView()?"Y":"N");
                                        pstmt.setString(pos++, mu.canCreateRoutine()?"Y":"N");
                                        pstmt.setString(pos++, mu.canAlterRoutine()?"Y":"N");
                                        pstmt.setString(pos++, mu.canCreateUser()?"Y":"N");
                                    }
                                    pstmt.setInt(pos++, msu.getMaxQuestions());
                                    pstmt.setInt(pos++, msu.getMaxUpdates());
                                    pstmt.setInt(pos++, msu.getMaxConnections());
                                    if(version.startsWith("5.0.")) pstmt.setInt(pos++, msu.getMaxUserConnections());
                                    int updateCount=pstmt.executeUpdate();
                                    if(updateCount>0) modified = true;
                                }
                            }
                        } finally {
                            pstmt.close();
                        }

                        // Add the users that do not exist and should
                        String insertSQL;
                        if(
                            osv==OperatingSystemVersion.MANDRAKE_10_1_I586
                            || osv==OperatingSystemVersion.MANDRIVA_2006_0_I586
                            || osv==OperatingSystemVersion.REDHAT_ES_4_X86_64
                        ) {
                            if(version.startsWith("4.0.")) insertSQL="insert into user values(?,?,'"+MySQLUser.NO_PASSWORD_DB_VALUE+"',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'','','','',?,?,?)";
                            else if(version.startsWith("4.1.")) insertSQL="insert into user values(?,?,'"+MySQLUser.NO_PASSWORD_DB_VALUE+"',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'','','','',?,?,?)";
                            else if(version.startsWith("5.0.")) insertSQL="insert into user values(?,?,'"+MySQLUser.NO_PASSWORD_DB_VALUE+"',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'','','','',?,?,?,?)";
                            else throw new SQLException("Unsupported MySQL version: "+version);
                        } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

                        pstmt = conn.prepareStatement(insertSQL);
                        try {
                            for(MySQLServerUser msu : users) {
                                MySQLUser mu = msu.getMySQLUser();
                                String host=msu.getHost();
                                if(host==null) host="";
                                String username=mu.getUsername().getUsername();
                                String key=host+'|'+username;
                                if (existing.contains(key)) existing.remove(key);
                                else {
                                    // Add the user
                                    int pos=1;
                                    pstmt.setString(pos++, host);
                                    pstmt.setString(pos++, username);
                                    pstmt.setString(pos++, mu.canSelect()?"Y":"N");
                                    pstmt.setString(pos++, mu.canInsert()?"Y":"N");
                                    pstmt.setString(pos++, mu.canUpdate()?"Y":"N");
                                    pstmt.setString(pos++, mu.canDelete()?"Y":"N");
                                    pstmt.setString(pos++, mu.canCreate()?"Y":"N");
                                    pstmt.setString(pos++, mu.canDrop()?"Y":"N");
                                    pstmt.setString(pos++, mu.canReload()?"Y":"N");
                                    pstmt.setString(pos++, mu.canShutdown()?"Y":"N");
                                    pstmt.setString(pos++, mu.canProcess()?"Y":"N");
                                    pstmt.setString(pos++, mu.canFile()?"Y":"N");
                                    pstmt.setString(pos++, mu.canGrant()?"Y":"N");
                                    pstmt.setString(pos++, mu.canReference()?"Y":"N");
                                    pstmt.setString(pos++, mu.canIndex()?"Y":"N");
                                    pstmt.setString(pos++, mu.canAlter()?"Y":"N");
                                    pstmt.setString(pos++, mu.canShowDB()?"Y":"N");
                                    pstmt.setString(pos++, mu.isSuper()?"Y":"N");
                                    pstmt.setString(pos++, mu.canCreateTempTable()?"Y":"N");
                                    pstmt.setString(pos++, mu.canLockTables()?"Y":"N");
                                    pstmt.setString(pos++, mu.canExecute()?"Y":"N");
                                    pstmt.setString(pos++, mu.isReplicationSlave()?"Y":"N");
                                    pstmt.setString(pos++, mu.isReplicationClient()?"Y":"N");
                                    if(version.startsWith("5.0.")) {
                                        pstmt.setString(pos++, mu.canCreateView()?"Y":"N");
                                        pstmt.setString(pos++, mu.canShowView()?"Y":"N");
                                        pstmt.setString(pos++, mu.canCreateRoutine()?"Y":"N");
                                        pstmt.setString(pos++, mu.canAlterRoutine()?"Y":"N");
                                        pstmt.setString(pos++, mu.canCreateUser()?"Y":"N");
                                    }
                                    pstmt.setInt(pos++, msu.getMaxQuestions());
                                    pstmt.setInt(pos++, msu.getMaxUpdates());
                                    pstmt.setInt(pos++, msu.getMaxConnections());
                                    if(version.startsWith("5.0.")) pstmt.setInt(pos++, msu.getMaxUserConnections());
                                    pstmt.executeUpdate();

                                    modified = true;
                                }
                            }
                        } finally {
                            pstmt.close();
                        }

                        // Remove the extra users
                        if (!existing.isEmpty()) {
                            pstmt = conn.prepareStatement("delete from user where host=? and user=?");
                            try {
                                for (int c = 0; c < existing.size(); c++) {
                                    // Remove the extra host entry
                                    String key=existing.get(c);
                                    int pos=key.indexOf('|');
                                    String host=key.substring(0, pos);
                                    String user=key.substring(pos+1);
                                    if(user.equals(MySQLUser.ROOT)) {
                                        AOServDaemon.reportWarning(new SQLException("Refusing to remove the "+MySQLUser.ROOT+" user for host "+host+", please remove manually."), null);
                                    } else {
                                        pstmt.setString(1, host);
                                        pstmt.setString(2, user);
                                        pstmt.executeUpdate();

                                        modified = true;
                                    }
                                }
                            } finally {
                                pstmt.close();
                            }
                        }
                    } finally {
                        pool.releaseConnection(conn);
                    }

                    // Disable and enable accounts
                    for(MySQLServerUser msu : users) {
                        String prePassword=msu.getPredisablePassword();
                        if(msu.getDisableLog()==null) {
                            if(prePassword!=null) {
                                setEncryptedPassword(mysqlServer, msu.getMySQLUser().getUsername().getUsername(), prePassword);
                                modified=true;
                                msu.setPredisablePassword(null);
                            }
                        } else {
                            if(prePassword==null) {
                                String username=msu.getMySQLUser().getUsername().getUsername();
                                msu.setPredisablePassword(getEncryptedPassword(mysqlServer, username));
                                setPassword(mysqlServer, username, MySQLUser.NO_PASSWORD);
                                modified=true;
                            }
                        }
                    }
                    if (modified) MySQLServerManager.flushPrivileges(mysqlServer);
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static String getEncryptedPassword(MySQLServer mysqlServer, String username) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, MySQLUserManager.class, "getEncryptedPassword(MySQLServer,String)", null);
        try {
            AOConnectionPool pool=MySQLServerManager.getPool(mysqlServer);
            Connection conn=pool.getConnection(true);
            try {
                PreparedStatement pstmt=conn.prepareStatement("select password from user where user=?");
                try {
                    pstmt.setString(1, username);
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

    public static void setPassword(MySQLServer mysqlServer, String username, String password) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, MySQLUserManager.class, "setPassword(MySQLServer,String,String)", null);
        try {
            // Get the connection to work through
            AOConnectionPool pool = MySQLServerManager.getPool(mysqlServer);
            Connection conn = pool.getConnection();
            try {
                if(password==MySQLUser.NO_PASSWORD) {
                    // Disable the account
                    PreparedStatement pstmt = conn.prepareStatement("update user set password='"+MySQLUser.NO_PASSWORD_DB_VALUE+"' where user=?");
                    try {
                        pstmt.setString(1, username);
                        pstmt.executeUpdate();
                    } finally {
                        pstmt.close();
                    }
                } else {
                    // Reset the password
                    PreparedStatement pstmt = conn.prepareStatement("update user set password=password(?) where user=?");
                    try {
                        pstmt.setString(1, password);
                        pstmt.setString(2, username);
                        pstmt.executeUpdate();
                    } finally {
                        pstmt.close();
                    }
                }
            } finally {
                pool.releaseConnection(conn);
            }
            MySQLServerManager.flushPrivileges(mysqlServer);
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void setEncryptedPassword(MySQLServer mysqlServer, String username, String password) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, MySQLUserManager.class, "setEncryptedPassword(MySQLServer,String,String)", null);
        try {
            // Get the connection to work through
            AOConnectionPool pool = MySQLServerManager.getPool(mysqlServer);
            Connection conn = pool.getConnection();
            try {
                if(password==MySQLUser.NO_PASSWORD) {
                    // Disable the account
                    PreparedStatement pstmt = conn.prepareStatement("update user set password='"+MySQLUser.NO_PASSWORD_DB_VALUE+"' where user=?");
                    try {
                        pstmt.setString(1, username);
                        pstmt.executeUpdate();
                    } finally {
                        pstmt.close();
                    }
                } else {
                    // Reset the password
                    PreparedStatement pstmt = conn.prepareStatement("update user set password=? where user=?");
                    try {
                        pstmt.setString(1, password);
                        pstmt.setString(2, username);
                        pstmt.executeUpdate();
                    } finally {
                        pstmt.close();
                    }
                }
            } finally {
                pool.releaseConnection(conn);
            }
            MySQLServerManager.flushPrivileges(mysqlServer);
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static MySQLUserManager mysqlUserManager;
    public static void start() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, MySQLUserManager.class, "start()", null);
        try {
            if(AOServDaemonConfiguration.isManagerEnabled(MySQLUserManager.class) && mysqlUserManager==null) {
                synchronized(System.out) {
                    if(mysqlUserManager==null) {
                        System.out.print("Starting MySQLUserManager: ");
                        AOServConnector conn=AOServDaemon.getConnector();
                        mysqlUserManager=new MySQLUserManager();
                        conn.mysqlServerUsers.addTableListener(mysqlUserManager, 0);
                        System.out.println("Done");
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void waitForRebuild() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, MySQLUserManager.class, "waitForRebuild()", null);
        try {
            if(mysqlUserManager!=null) mysqlUserManager.waitForBuild();
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public String getProcessTimerDescription() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, MySQLUserManager.class, "getProcessTimerDescription()", null);
        try {
            return "Rebuild MySQL Users";
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
}