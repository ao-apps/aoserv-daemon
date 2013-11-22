/*
 * Copyright 2002-2013 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.mysql;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.MySQLServer;
import com.aoindustries.aoserv.client.MySQLServerUser;
import com.aoindustries.aoserv.client.MySQLUser;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.lang.ObjectUtils;
import com.aoindustries.sql.AOConnectionPool;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/**
 * Controls the MySQL Users.
 *
 * @author  AO Industries, Inc.
 */
final public class MySQLUserManager extends BuilderThread {

    private MySQLUserManager() {
    }

    private static final Object rebuildLock=new Object();
	@Override
    protected boolean doRebuild() {
        try {
            //AOServConnector connector = AOServDaemon.getConnector();
            AOServer thisAOServer=AOServDaemon.getThisAOServer();

            int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
            if(
                osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
                && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
                && osv!=OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
            ) throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);

            synchronized (rebuildLock) {
                for(MySQLServer mysqlServer : thisAOServer.getMySQLServers()) {
                    final String version=mysqlServer.getVersion().getVersion();
                    // Get the list of all users that should exist.  By getting the list and reusing it we have a snapshot of the configuration.
                    List<MySQLServerUser> users = mysqlServer.getMySQLServerUsers();

                    boolean modified = false;

                    // Get the connection to work through
                    AOConnectionPool pool = MySQLServerManager.getPool(mysqlServer);
                    Connection conn = pool.getConnection();
                    try {
                        // Get the list of all existing users
                        Set<String> existing = new HashSet<>();
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
                        if(version.startsWith(MySQLServer.VERSION_4_0_PREFIX)) {
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
                        } else if(version.startsWith(MySQLServer.VERSION_4_1_PREFIX)) {
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
                        } else if(version.startsWith(MySQLServer.VERSION_5_0_PREFIX)) {
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
                        } else if(
							version.startsWith(MySQLServer.VERSION_5_1_PREFIX)
							|| version.startsWith(MySQLServer.VERSION_5_6_PREFIX)
						) {
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
                                    + "  Event_priv=?,\n"
                                    + "  Trigger_priv=?,\n"
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
                                    + "    or Event_priv!=?\n"
                                    + "    or Trigger_priv!=?\n"
                                    + "    or max_questions!=?\n"
                                    + "    or max_updates!=?\n"
                                    + "    or max_connections!=?\n"
                                    + "    or max_user_connections!=?\n"
                                    + "  )";
                        } else throw new SQLException("Unsupported MySQL version: "+version);

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
                                    if(
                                        version.startsWith(MySQLServer.VERSION_5_0_PREFIX)
                                        || version.startsWith(MySQLServer.VERSION_5_1_PREFIX)
                                        || version.startsWith(MySQLServer.VERSION_5_6_PREFIX)
                                    ) {
                                        pstmt.setString(pos++, mu.canCreateView()?"Y":"N");
                                        pstmt.setString(pos++, mu.canShowView()?"Y":"N");
                                        pstmt.setString(pos++, mu.canCreateRoutine()?"Y":"N");
                                        pstmt.setString(pos++, mu.canAlterRoutine()?"Y":"N");
                                        pstmt.setString(pos++, mu.canCreateUser()?"Y":"N");
                                        if(
											version.startsWith(MySQLServer.VERSION_5_1_PREFIX)
											|| version.startsWith(MySQLServer.VERSION_5_6_PREFIX)
										) {
                                            pstmt.setString(pos++, mu.canEvent()?"Y":"N");
                                            pstmt.setString(pos++, mu.canTrigger()?"Y":"N");
                                        }
                                    }
                                    pstmt.setInt(pos++, msu.getMaxQuestions());
                                    pstmt.setInt(pos++, msu.getMaxUpdates());
                                    pstmt.setInt(pos++, msu.getMaxConnections());
                                    if(
                                        version.startsWith(MySQLServer.VERSION_5_0_PREFIX)
                                        || version.startsWith(MySQLServer.VERSION_5_1_PREFIX)
                                        || version.startsWith(MySQLServer.VERSION_5_6_PREFIX)
                                    ) pstmt.setInt(pos++, msu.getMaxUserConnections());
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
                                    if(
                                        version.startsWith(MySQLServer.VERSION_5_0_PREFIX)
                                        || version.startsWith(MySQLServer.VERSION_5_1_PREFIX)
                                        || version.startsWith(MySQLServer.VERSION_5_6_PREFIX)
                                    ) {
                                        pstmt.setString(pos++, mu.canCreateView()?"Y":"N");
                                        pstmt.setString(pos++, mu.canShowView()?"Y":"N");
                                        pstmt.setString(pos++, mu.canCreateRoutine()?"Y":"N");
                                        pstmt.setString(pos++, mu.canAlterRoutine()?"Y":"N");
                                        pstmt.setString(pos++, mu.canCreateUser()?"Y":"N");
                                        if(
											version.startsWith(MySQLServer.VERSION_5_1_PREFIX)
											|| version.startsWith(MySQLServer.VERSION_5_6_PREFIX)
										) {
                                            pstmt.setString(pos++, mu.canEvent()?"Y":"N");
                                            pstmt.setString(pos++, mu.canTrigger()?"Y":"N");
                                        }
                                    }
                                    pstmt.setInt(pos++, msu.getMaxQuestions());
                                    pstmt.setInt(pos++, msu.getMaxUpdates());
                                    pstmt.setInt(pos++, msu.getMaxConnections());
                                    if(
                                        version.startsWith(MySQLServer.VERSION_5_0_PREFIX)
                                        || version.startsWith(MySQLServer.VERSION_5_1_PREFIX)
                                        || version.startsWith(MySQLServer.VERSION_5_6_PREFIX)
                                    ) pstmt.setInt(pos++, msu.getMaxUserConnections());
                                    int updateCount=pstmt.executeUpdate();
                                    if(updateCount>0) modified = true;
                                }
                            }
                        } finally {
                            pstmt.close();
                        }

                        // Add the users that do not exist and should
                        String insertSQL;
                        if(version.startsWith(MySQLServer.VERSION_4_0_PREFIX)) insertSQL="insert into user values(?,?,'"+MySQLUser.NO_PASSWORD_DB_VALUE+"',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'','','','',?,?,?)";
                        else if(version.startsWith(MySQLServer.VERSION_4_1_PREFIX)) insertSQL="insert into user values(?,?,'"+MySQLUser.NO_PASSWORD_DB_VALUE+"',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'','','','',?,?,?)";
                        else if(version.startsWith(MySQLServer.VERSION_5_0_PREFIX)) insertSQL="insert into user values(?,?,'"+MySQLUser.NO_PASSWORD_DB_VALUE+"',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'','','','',?,?,?,?)";
                        else if(version.startsWith(MySQLServer.VERSION_5_1_PREFIX)) insertSQL="insert into user values(?,?,'"+MySQLUser.NO_PASSWORD_DB_VALUE+"',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'','','','',?,?,?,?)";
                        else if(version.startsWith(MySQLServer.VERSION_5_6_PREFIX)) insertSQL="insert into user values(?,?,'"+MySQLUser.NO_PASSWORD_DB_VALUE+"',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'N','','','','',?,?,?,?,'',NULL,'N')";
                        else throw new SQLException("Unsupported MySQL version: "+version);

                        pstmt = conn.prepareStatement(insertSQL);
                        try {
                            for(MySQLServerUser msu : users) {
                                MySQLUser mu = msu.getMySQLUser();
                                String host=msu.getHost();
                                if(host==null) host="";
                                String username=mu.getUsername().getUsername();
                                String key=host+'|'+username;
                                if (!existing.remove(key)) {
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
                                    if(
                                        version.startsWith(MySQLServer.VERSION_5_0_PREFIX)
                                        || version.startsWith(MySQLServer.VERSION_5_1_PREFIX)
                                        || version.startsWith(MySQLServer.VERSION_5_6_PREFIX)
                                    ) {
                                        pstmt.setString(pos++, mu.canCreateView()?"Y":"N");
                                        pstmt.setString(pos++, mu.canShowView()?"Y":"N");
                                        pstmt.setString(pos++, mu.canCreateRoutine()?"Y":"N");
                                        pstmt.setString(pos++, mu.canAlterRoutine()?"Y":"N");
                                        pstmt.setString(pos++, mu.canCreateUser()?"Y":"N");
                                        if(
											version.startsWith(MySQLServer.VERSION_5_1_PREFIX)
											|| version.startsWith(MySQLServer.VERSION_5_6_PREFIX)
										) {
                                            pstmt.setString(pos++, mu.canEvent()?"Y":"N");
                                            pstmt.setString(pos++, mu.canTrigger()?"Y":"N");
                                        }
                                    }
                                    pstmt.setInt(pos++, msu.getMaxQuestions());
                                    pstmt.setInt(pos++, msu.getMaxUpdates());
                                    pstmt.setInt(pos++, msu.getMaxConnections());
                                    if(
                                        version.startsWith(MySQLServer.VERSION_5_0_PREFIX)
                                        || version.startsWith(MySQLServer.VERSION_5_1_PREFIX)
                                        || version.startsWith(MySQLServer.VERSION_5_6_PREFIX)
                                    ) pstmt.setInt(pos++, msu.getMaxUserConnections());
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
                                for (String key : existing) {
                                    // Remove the extra host entry
                                    int pos=key.indexOf('|');
                                    String host=key.substring(0, pos);
                                    String user=key.substring(pos+1);
                                    if(user.equals(MySQLUser.ROOT)) {
                                        LogFactory.getLogger(this.getClass()).log(Level.WARNING, null, new SQLException("Refusing to remove the "+MySQLUser.ROOT+" user for host "+host+", please remove manually."));
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
                        if(!msu.isDisabled()) {
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
            return true;
        } catch(ThreadDeath TD) {
            throw TD;
        } catch(Throwable T) {
            LogFactory.getLogger(MySQLUserManager.class).log(Level.SEVERE, null, T);
            return false;
        }
    }

    public static String getEncryptedPassword(MySQLServer mysqlServer, String username) throws IOException, SQLException {
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
    }

    public static void setPassword(MySQLServer mysqlServer, String username, String password) throws IOException, SQLException {
        // Get the connection to work through
        AOConnectionPool pool = MySQLServerManager.getPool(mysqlServer);
        Connection conn = pool.getConnection();
        try {
            if(ObjectUtils.equals(password, MySQLUser.NO_PASSWORD)) {
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
    }

    public static void setEncryptedPassword(MySQLServer mysqlServer, String username, String password) throws IOException, SQLException {
        // Get the connection to work through
        AOConnectionPool pool = MySQLServerManager.getPool(mysqlServer);
        Connection conn = pool.getConnection();
        try {
            if(ObjectUtils.equals(password, MySQLUser.NO_PASSWORD)) {
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
    }

    private static MySQLUserManager mysqlUserManager;
    public static void start() throws IOException, SQLException {
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();

        synchronized(System.out) {
            if(
                // Nothing is done for these operating systems
                osv!=OperatingSystemVersion.CENTOS_5_DOM0_I686
                && osv!=OperatingSystemVersion.CENTOS_5_DOM0_X86_64
                // Check config after OS check so config entry not needed
                && AOServDaemonConfiguration.isManagerEnabled(MySQLUserManager.class)
                && mysqlUserManager==null
            ) {
                System.out.print("Starting MySQLUserManager: ");
                AOServConnector conn=AOServDaemon.getConnector();
                mysqlUserManager=new MySQLUserManager();
                conn.getMysqlServerUsers().addTableListener(mysqlUserManager, 0);
                System.out.println("Done");
            }
        }
    }

    public static void waitForRebuild() {
        if(mysqlUserManager!=null) mysqlUserManager.waitForBuild();
    }

	@Override
    public String getProcessTimerDescription() {
        return "Rebuild MySQL Users";
    }
}