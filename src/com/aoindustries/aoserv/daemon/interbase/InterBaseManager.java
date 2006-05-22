package com.aoindustries.aoserv.daemon.interbase;

/*
 * Copyright 2002-2006 by AO Industries, Inc.,
 * 2200 Dogwood Ct N, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.aoserv.daemon.backup.*;
import com.aoindustries.aoserv.daemon.client.AOServDaemonProtocol;
import com.aoindustries.aoserv.daemon.server.*;
import com.aoindustries.aoserv.daemon.util.*;
import com.aoindustries.io.*;
import com.aoindustries.io.unix.*;
import com.aoindustries.md5.*;
import com.aoindustries.profiler.*;
import com.aoindustries.sql.*;
import com.aoindustries.util.*;
import com.aoindustries.util.zip.*;
import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * The <code>InterBaseManager</code> controls the InterBase server.
 *
 * @author  AO Industries, Inc.
 */
final public class InterBaseManager extends BuilderThread {

    /**
     * The directory containing the InterBaseDBGroups.
     */
    public static final UnixFile dbGroupPathUF=new UnixFile(InterBaseDBGroup.DB_GROUP_PATH);

    /**
     * The isc4.gdb database used to store passwords.
     */
    public static final String ISC4_DB_PATH=InterBaseDBGroup.DB_GROUP_PATH+'/'+InterBaseDBGroup.IBSERVER+'/'+InterBaseDatabase.ISC4+InterBaseDatabase.DB_FILENAME_EXTENSION;

    /**
     * The permissions used for the data directories.
     */
    public static final long
        DB_GROUP_DIRECTORY_MODE=0750,
        DB_FILE_MODE=0660,
        IBSERVER_FILE_MODE=0640
    ;

    private InterBaseManager() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, InterBaseManager.class, "<init>()", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, InterBaseManager.class, "doRebuild()", null);
        try {
            AOServConnector connector = AOServDaemon.getConnector();
            AOServer thisAOServer=AOServDaemon.getThisAOServer();

            int osv=thisAOServer.getServer().getOperatingSystemVersion().getPKey();
            if(
                osv!=OperatingSystemVersion.MANDRAKE_10_1_I586
                && osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            // Get the list of all database objects that should exist
            List<InterBaseDBGroup> groups=thisAOServer.getInterBaseDBGroups();
            List<InterBaseDatabase> databases=thisAOServer.getInterBaseDatabases();
            List<InterBaseServerUser> users = thisAOServer.getInterBaseServerUsers();

            // Using during rebuild process
            Map<String,Map<String,InterBaseDatabase>> foundGroups=new HashMap<String,Map<String,InterBaseDatabase>>();
            Map<String,InterBaseServerUser> foundUsers=new HashMap<String,InterBaseServerUser>();

            synchronized (rebuildLock) {
                LinuxServerAccount interbaseLSA=thisAOServer.getLinuxServerAccount(LinuxAccount.INTERBASE);
                if(interbaseLSA==null) throw new SQLException("Unable to find InterBaseServerUser: "+LinuxAccount.INTERBASE+" on "+thisAOServer.getServer().getHostname());
                int interbaseUID=interbaseLSA.getUID().getID();

                // Delete any old DB groups
                // Delete any database that is not in a deleted DB group
                // Enforce permissions on remaining groups and databases
                // Mark existing groups and databases for the add step
                List<File> deleteFileList=new ArrayList<File>();
                String[] dbGroupDirectories=dbGroupPathUF.list();
                if(dbGroupDirectories==null) throw new IOException("UnixFile.list() returned null: "+dbGroupPathUF.getFilename());
                for(int c=0;c<dbGroupDirectories.length;c++) {
                    String dbGroupName=dbGroupDirectories[c];
                    if(!dbGroupName.equals(InterBaseDBGroup.LOST_AND_FOUND)) {
                        UnixFile dbGroupDirectoryUF=new UnixFile(dbGroupPathUF, dbGroupName);
                        InterBaseDBGroup dbGroup=null;
                        if(
                            dbGroupDirectoryUF.isDirectory()
                            && !dbGroupDirectoryUF.isSymLink()
                        ) {
                            for(int d=0;d<groups.size();d++) {
                                if(groups.get(d).getName().equals(dbGroupName)) {
                                    // Mark DB group as existing
                                    dbGroup=groups.get(d);
                                    foundGroups.put(dbGroupName, new HashMap<String,InterBaseDatabase>());
                                    break;
                                }
                            }
                        }
                        if(dbGroup==null) {
                            if(dbGroupName.equals(InterBaseDBGroup.IBSERVER)) {
                                AOServDaemon.reportWarning(new SQLException("Warning, InterBaseDBGroup named "+InterBaseDBGroup.IBSERVER+" not configured in AOServ"), null);
                            } else {
                                // Delete old DB group
                                deleteFileList.add(dbGroupDirectoryUF.getFile());
                            }
                        } else {
                            // Enforce DB group permissions
                            if(dbGroupDirectoryUF.getMode()!=DB_GROUP_DIRECTORY_MODE) dbGroupDirectoryUF.setMode(DB_GROUP_DIRECTORY_MODE);
                            int dbGroupUID=dbGroupName.equals(InterBaseDBGroup.IBSERVER)?UnixFile.ROOT_UID:interbaseUID;
                            int dbGroupGID=dbGroup.getLinuxServerGroup().getGID().getID();
                            if(
                                dbGroupDirectoryUF.getUID()!=dbGroupUID
                                || dbGroupDirectoryUF.getGID()!=dbGroupGID
                            ) dbGroupDirectoryUF.chown(dbGroupUID, dbGroupGID);

                            String[] databaseFiles=dbGroupDirectoryUF.list();
                            if(databaseFiles==null) throw new IOException("UnixFile.list() returned null: "+dbGroupDirectoryUF.getFilename());
                            for(int d=0;d<databaseFiles.length;d++) {
                                String dbName=databaseFiles[d];
                                boolean dontDelete=
                                    dbGroupName.equals(InterBaseDBGroup.IBSERVER)
                                    && (
                                        dbName.equals("interbase.log")
                                        || dbName.equals("isc_event1")
                                        || dbName.equals("isc_guard1")
                                        || dbName.equals("isc_init1")
                                        || dbName.equals("isc_lock1")
                                    )
                                ;
                                UnixFile dbFileUF=new UnixFile(dbGroupDirectoryUF, dbName);
                                InterBaseDatabase database=null;
                                if(
                                    !dontDelete
                                    && dbFileUF.isRegularFile()
                                    && !dbFileUF.isSymLink()
                                ) {
                                    for(int e=0;e<databases.size();e++) {
                                        if(
                                            databases.get(e).getInterBaseDBGroup().equals(dbGroup)
                                            && dbName.equals(databases.get(e).getName()+InterBaseDatabase.DB_FILENAME_EXTENSION)
                                        ) {
                                            // Mark database as existing
                                            foundGroups.get(dbGroupName).put(dbName, database=databases.get(e));
                                            break;
                                        }
                                    }
                                }
                                if(!dontDelete && database==null) {
                                    if(
                                        dbGroupName.equals(InterBaseDBGroup.IBSERVER)
                                        && dbName.equals(InterBaseDatabase.ISC4)
                                    ) {
                                        AOServDaemon.reportWarning(new SQLException("Warning, InterBaseDatabase named "+InterBaseDatabase.ISC4+" in InterBaseDBGroup named "+InterBaseDBGroup.IBSERVER+" not configured in AOServ"), null);
                                    } else {
                                        // Delete old DB group
                                        deleteFileList.add(dbFileUF.getFile());
                                    }
                                } else {
                                    // Enforce database permissions
                                    long fileMode=dbGroupName.equals(InterBaseDBGroup.IBSERVER) ? IBSERVER_FILE_MODE : DB_FILE_MODE ;
                                    if(dbFileUF.getMode()!=fileMode) dbFileUF.setMode(fileMode);
                                    if(
                                        dbFileUF.getUID()!=interbaseUID
                                        || dbFileUF.getGID()!=dbGroupGID
                                    ) dbFileUF.chown(interbaseUID, dbGroupGID);
                                }
                            }
                        }
                    }
                }

                /*
                 * Back up the files scheduled for removal.
                 */
                int deleteFileListLen=deleteFileList.size();
                if(deleteFileListLen>0) {
                    // Get the next backup filename
                    File backupFile=BackupManager.getNextBackupFile();
                    BackupManager.backupFiles(deleteFileList, backupFile);

                    /*
                     * Remove the files that have been backed up.
                     */
                    for(int c=0;c<deleteFileListLen;c++) {
                        File file=deleteFileList.get(c);
                        UnixFile deleteUF=new UnixFile(file);
                        // First try to drop the databases properly
                        if(deleteUF.isDirectory()) {
                            String[] list=deleteUF.list();
                            if(list!=null) {
                                for(int d=0;d<list.length;d++) {
                                    if(list[d].endsWith(InterBaseDatabase.DB_FILENAME_EXTENSION)) {
                                        UnixFile dbUF=new UnixFile(deleteUF, list[d]);
                                        try {
                                            dropDatabase(dbUF.getFilename());
                                        } catch(IOException err) {
                                            // This should not normally happen, but we will delete the file if not already removed
                                            AOServDaemon.reportWarning(err, new Object[] {"filename="+dbUF.getFilename()});
                                        } catch(SQLException err) {
                                            // This should not normally happen, but we will delete the file if not already removed
                                            AOServDaemon.reportWarning(err, new Object[] {"filename="+dbUF.getFilename()});
                                        }
                                    }
                                }
                            }
                        } else if(deleteUF.getFilename().endsWith(InterBaseDatabase.DB_FILENAME_EXTENSION)) {
                            try {
                                dropDatabase(deleteUF.getFilename());
                            } catch(IOException err) {
                                // This should not normally happen, but we will delete the file if not already removed
                                AOServDaemon.reportWarning(err, new Object[] {"filename="+deleteUF.getFilename()});
                            } catch(SQLException err) {
                                // This should not normally happen, but we will delete the file if not already removed
                                AOServDaemon.reportWarning(err, new Object[] {"filename="+deleteUF.getFilename()});
                            }
                        }
                        
                        // Delete if not already gone
                        if(deleteUF.exists()) deleteUF.secureDeleteRecursive();
                    }
                }

                // Delete and modify any users
                // Flag existing as found
                List<String[]> existingUsers=getExistingUsers();
                for(int c=0;c<existingUsers.size();c++) {
                    String[] user=existingUsers.get(c);
                    String username=user[0].toLowerCase();
                    String firstname=user[1];
                    String middlename=user[2];
                    String lastname=user[3];
                    InterBaseServerUser ibUser=null;
                    for(int d=0;d<users.size();d++) {
                        InterBaseServerUser ibsu=users.get(d);
                        InterBaseUser ibu=ibsu.getInterBaseUser();
                        Username un=ibu.getUsername();
                        if(un.getUsername().equals(username)) {
                            foundUsers.put(username, ibUser=ibsu);
                            if(
                                !StringUtility.equals(firstname, ibu.getFirstName())
                                || !StringUtility.equals(middlename, ibu.getMiddleName())
                                || !StringUtility.equals(lastname, ibu.getLastName())
                            ) setNames(username, ibu.getFirstName(), ibu.getMiddleName(), ibu.getLastName());
                            break;
                        }
                    }
                    if(ibUser==null) removeUser(username);
                }

                // Add any new users
                for(int c=0;c<users.size();c++) {
                    InterBaseServerUser ibsu=users.get(c);
                    InterBaseUser ibu=ibsu.getInterBaseUser();
                    Username un=ibu.getUsername();
                    String username=un.getUsername();
                    if(!foundUsers.containsKey(username)) {
                        createUser(username, ibu.getFirstName(), ibu.getMiddleName(), ibu.getLastName());
                    }
                }

                // Add any new db groups
                for(int c=0;c<groups.size();c++) {
                    InterBaseDBGroup group=groups.get(c);
                    String groupName=group.getName();
                    if(!foundGroups.containsKey(groupName)) {
                        UnixFile dbGroupUF=new UnixFile(group.getPath());
                        dbGroupUF.mkdir(false, DB_GROUP_DIRECTORY_MODE).chown(interbaseUID, group.getLinuxServerGroup().getGID().getID());
                    }
                }

                // Add any new databases
                for(int c=0;c<databases.size();c++) {
                    InterBaseDatabase database=databases.get(c);
                    String databaseName=database.getName();
                    InterBaseDBGroup group=database.getInterBaseDBGroup();
                    String groupName=group.getName();
                    Map<String,InterBaseDatabase> foundDatabases=foundGroups.get(groupName);
                    if(foundDatabases==null || !foundDatabases.containsKey(databaseName)) {
                        createDatabase(database, interbaseUID);
                    }
                }

                // Disable and enable accounts
                for(int c=0;c<users.size();c++) {
                    InterBaseServerUser isu=users.get(c);
                    String prePassword=isu.getPredisablePassword();
                    if(isu.getDisableLog()==null) {
                        if(prePassword!=null) {
                            setEncryptedPassword(isu.getInterBaseUser().getUsername().getUsername(), prePassword);
                            isu.setPredisablePassword(null);
                        }
                    } else {
                        if(prePassword==null) {
                            String username=isu.getInterBaseUser().getUsername().getUsername();
                            isu.setPredisablePassword(getEncryptedPassword(username));
                            setPassword(username, InterBaseUser.NO_PASSWORD);
                        }
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static String getJdbcUrl(String path) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, InterBaseManager.class, "getJdbcUrl(String)", null);
        try {
            AOServer ao=AOServDaemon.getThisAOServer();
            return
                "jdbc:interbase://"
                + ao.getNetDevice(ao.getDaemonDeviceID().getName()).getPrimaryIPAddress().getIPAddress()
                + path
            ;
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void createDatabase(InterBaseDatabase database, int interbaseUID) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, InterBaseManager.class, "createDatabase(InterBaseDatabase,int)", null);
        try {
            String path=database.getPath();

            Process isql=getIsqlProcess(null);
            try {
                ChainWriter out=new ChainWriter(isql.getOutputStream());
                try {
                    out.print("create database '").print(path).println("';");
                } finally {
                    out.flush();
                    out.close();
                }
            } finally {
                try {
                    int retCode=isql.waitFor();
                    if(retCode!=0) throw new IOException("Unable to create database, isql returned non-zero value: "+retCode);
                } catch(InterruptedException err) {
                    InterruptedIOException ioErr=new InterruptedIOException();
                    ioErr.initCause(err);
                }
            }

            UnixFile dbUF=new UnixFile(path);
            long fileMode=database.getInterBaseDBGroup().getName().equals(InterBaseDBGroup.IBSERVER) ? IBSERVER_FILE_MODE : DB_FILE_MODE ;
            dbUF.chown(interbaseUID, database.getInterBaseDBGroup().getLinuxServerGroup().getGID().getID()).setMode(fileMode);
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void dropDatabase(String path) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, InterBaseManager.class, "dropDatabase()", null);
        try {
            Process isql=getIsqlProcess(path);
            try {
                ChainWriter out=new ChainWriter(isql.getOutputStream());
                try {
                    out.println("drop database;");
                } finally {
                    out.flush();
                    out.close();
                }
            } finally {
                try {
                    int retCode=isql.waitFor();
                    if(retCode!=0) throw new IOException("Unable to drop database, isql returned non-zero value: "+retCode);
                } catch(InterruptedException err) {
                    InterruptedIOException ioErr=new InterruptedIOException();
                    ioErr.initCause(err);
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * Gets the list of users in the database system in a String[] of user_name, first_name, middle_name, last_name.
     */
    private static List<String[]> getExistingUsers() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, InterBaseManager.class, "getExistingUsers()", null);
        try {
            List<String[]> users=new ArrayList<String[]>();
            Connection conn=getSingleUseConnection(ISC4_DB_PATH);
            try {
                Statement stmt=conn.createStatement();
                try {
                    ResultSet results=stmt.executeQuery("select user_name, first_name, middle_name, last_name from users order by user_name");
                    try {
                        while(results.next()) {
                            users.add(
                                new String[] {
                                    results.getString(1),
                                    results.getString(2),
                                    results.getString(3),
                                    results.getString(4)
                                }
                            );
                        }
                    } finally {
                        results.close();
                    }
                } finally {
                    stmt.close();
                }
            } finally {
                conn.close();
            }
            return users;
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static final String[] gsecCommand={
        "/opt/interbase/bin/gsec"
    };

    private static Process getGsecProcess() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, InterBaseManager.class, "getGsecProcess()", null);
        try {
            return Runtime.getRuntime().exec(gsecCommand);
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * Gets a connection to the InterBase server through command line tools.
     *
     * @param  dbPath to connect to a database or <code>null</code> to not connect
     *         automatically
     */
    private static Process getIsqlProcess(String dbPath) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, InterBaseManager.class, "getIsqlProcess(String)", null);
        try {
            String[] isqlCmd;
            if(dbPath==null) isqlCmd=new String[] {"/opt/interbase/bin/isql"};
            else isqlCmd=new String[] {"/opt/interbase/bin/isql", dbPath};
            return Runtime.getRuntime().exec(isqlCmd);
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void createUser(String username, String firstname, String middlename, String lastname) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, InterBaseManager.class, "createUser(String,String,String,String)", null);
        try {
            if(InterBaseUser.SYSDBA.equalsIgnoreCase(username)) throw new SQLException("Refusing to create user for the "+InterBaseUser.SYSDBA+" InterBase user");
            username=username.toUpperCase();

            Process gsec=getGsecProcess();
            try {
                ChainWriter out=new ChainWriter(gsec.getOutputStream());
                try {
                    out.print("add ").print(username);
                    if(firstname!=null) out.print(" -fname ").print(firstname);
                    if(middlename!=null) out.print(" -mname ").print(middlename);
                    if(lastname!=null) out.print(" -lname ").print(lastname);
                    out.println();
                } finally {
                    out.flush();
                    out.close();
                }
            } finally {
                try {
                    int retCode=gsec.waitFor();
                    if(retCode!=0) throw new IOException("Unable to create user, gsec returned non-zero value: "+retCode);
                } catch(InterruptedException err) {
                    InterruptedIOException ioErr=new InterruptedIOException();
                    ioErr.initCause(err);
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void setNames(String username, String firstname, String middlename, String lastname) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, InterBaseManager.class, "setNames(String,String,String,String)", null);
        try {
            username=username.toUpperCase();
            if(AOServDaemon.DEBUG) System.err.println("DEBUG: InterBaseManager: setNames("+username+", "+firstname+", "+middlename+", "+lastname+')');

            Process isql=getIsqlProcess(ISC4_DB_PATH);
            try {
                ChainWriter out=new ChainWriter(isql.getOutputStream());
                try {
                    StringBuilder SB=new StringBuilder();
                    SB.append("update users set first_name=");
                    if(firstname==null) SB.append("null");
                    else SB.append('\'').append(firstname).append('\'');
                    SB.append(", middle_name=");
                    if(middlename==null) SB.append("null");
                    else SB.append('\'').append(middlename).append('\'');
                    SB.append(", last_name=");
                    if(lastname==null) SB.append("null");
                    else SB.append('\'').append(lastname).append('\'');
                    SB.append(" where user_name='").append(username).append("';");
                    out.println(SB.toString());
                } finally {
                    out.flush();
                    out.close();
                }
            } finally {
                try {
                    int retCode=isql.waitFor();
                    if(retCode!=0) throw new IOException("Unable to create database, isql returned non-zero value: "+retCode);
                } catch(InterruptedException err) {
                    InterruptedIOException ioErr=new InterruptedIOException();
                    ioErr.initCause(err);
                }
            }

            /*
            Connection conn=getSingleUseConnection(ISC4_DB_PATH);
            try {
                PreparedStatement pstmt=conn.prepareStatement("update users set first_name=?, middle_name=?, last_name=? where user_name=?");
                try {
                    if(firstname==null) pstmt.setNull(1, Types.VARCHAR);
                    else pstmt.setString(1, firstname);
                    if(middlename==null) pstmt.setNull(2, Types.VARCHAR);
                    else pstmt.setString(2, middlename);
                    if(lastname==null) pstmt.setNull(3, Types.VARCHAR);
                    else pstmt.setString(3, lastname);
                    pstmt.setString(4, username);
                    pstmt.executeUpdate();
                    conn.commit();
                } finally {
                    pstmt.close();
                }
            } finally {
                conn.close();
            }
             */
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void setEncryptedPassword(String username, String encryptedPassword) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, InterBaseManager.class, "setEncryptedPassword(String,String)", null);
        try {
            if(AOServDaemon.DEBUG) System.err.println("DEBUG: InterBaseManager: setEncryptedPassword("+username+", "+encryptedPassword+')');

            if(InterBaseUser.SYSDBA.equalsIgnoreCase(username)) throw new SQLException("Refusing to set the encrypted password for the "+InterBaseUser.SYSDBA+" InterBase user");
            username=username.toUpperCase();

            Process isql=getIsqlProcess(ISC4_DB_PATH);
            try {
                ChainWriter out=new ChainWriter(isql.getOutputStream());
                try {
                    if(encryptedPassword==null) out.print("update users set passwd=null");
                    else out.print("update users set passwd='").print(encryptedPassword).print('\'');
                    out.print(" where user_name='").print(username).println("';");
                } finally {
                    out.flush();
                    out.close();
                }
            } finally {
                try {
                    int retCode=isql.waitFor();
                    if(retCode!=0) throw new IOException("Unable to create database, isql returned non-zero value: "+retCode);
                } catch(InterruptedException err) {
                    InterruptedIOException ioErr=new InterruptedIOException();
                    ioErr.initCause(err);
                }
            }
            /*
            Connection conn=getSingleUseConnection(ISC4_DB_PATH);
            try {
                PreparedStatement pstmt=conn.prepareStatement("update users set passwd=? where user_name=?");
                try {
                    if(encryptedPassword==null) pstmt.setNull(1, Types.VARCHAR);
                    else pstmt.setString(1, encryptedPassword);
                    pstmt.setString(2, username);
                    pstmt.executeUpdate();
                    conn.commit();
                } finally {
                    pstmt.close();
                }
            } finally {
                conn.close();
            }
             */
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static String getEncryptedPassword(String username) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, InterBaseManager.class, "getEncryptedPassword(String)", null);
        try {
            if(InterBaseUser.SYSDBA.equalsIgnoreCase(username)) throw new SQLException("Refusing to get the encrypted password for the "+InterBaseUser.SYSDBA+" InterBase user");
            username=username.toUpperCase();

            Connection conn=getSingleUseConnection(ISC4_DB_PATH);
            try {
                PreparedStatement pstmt=conn.prepareStatement("select passwd from users where user_name=?");
                try {
                    pstmt.setString(1, username);
                    ResultSet results=pstmt.executeQuery();
                    try {
                        if(results.next()) {
                            String password=results.getString(1);
                            if(results.next()) throw new SQLException("More than one row returned.  username="+username);
                            return password;
                        } else throw new SQLException("No rows returned. username="+username);
                    } finally {
                        results.close();
                    }
                } finally {
                    pstmt.close();
                }
            } finally {
                conn.close();
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void setPassword(String username, String password) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, InterBaseManager.class, "setPassword(String,String)", null);
        try {
            if(InterBaseUser.SYSDBA.equalsIgnoreCase(username)) throw new SQLException("Refusing to set the password for the "+InterBaseUser.SYSDBA+" InterBase user");
            username=username.toUpperCase();

            if(StringUtility.equals(password, InterBaseUser.NO_PASSWORD)) setEncryptedPassword(username, InterBaseUser.NO_PASSWORD_DB_VALUE);
            else {
                Process gsec=getGsecProcess();
                try {
                    ChainWriter out=new ChainWriter(gsec.getOutputStream());
                    try {
                        out.print("modify ").print(username).print(" -pw ").println(password);
                    } finally {
                        out.flush();
                        out.close();
                    }
                } finally {
                    try {
                        int retCode=gsec.waitFor();
                        if(retCode!=0) throw new IOException("Unable to set password, gsec returned non-zero value: "+retCode);
                    } catch(InterruptedException err) {
                        InterruptedIOException ioErr=new InterruptedIOException();
                        ioErr.initCause(err);
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void removeUser(String username) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, InterBaseManager.class, "removeUser(String)", null);
        try {
            if(InterBaseUser.SYSDBA.equalsIgnoreCase(username)) throw new SQLException("Refusing to remove the "+InterBaseUser.SYSDBA+" InterBase user");
            username=username.toUpperCase();

            Process gsec=getGsecProcess();
            try {
                ChainWriter out=new ChainWriter(gsec.getOutputStream());
                try {
                    out.print("delete ").println(username);
                } finally {
                    out.flush();
                    out.close();
                }
            } finally {
                try {
                    int retCode=gsec.waitFor();
                    if(retCode!=0) throw new IOException("Unable to set password, gsec returned non-zero value: "+retCode);
                } catch(InterruptedException err) {
                    InterruptedIOException ioErr=new InterruptedIOException();
                    ioErr.initCause(err);
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void restartInterBase() throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, InterBaseManager.class, "restartInterBase()", null);
        try {
            AOServer thisAOServer=AOServDaemon.getThisAOServer();
            if(thisAOServer.isInterBase()) ServerManager.controlProcess("interbase", "restart");
            else throw new SQLException("AOServer not an InterBase server: hostname="+thisAOServer.getServer().getHostname());
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static void startInterBase() throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, InterBaseManager.class, "startInterBase()", null);
        try {
            AOServer thisAOServer=AOServDaemon.getThisAOServer();
            if(thisAOServer.isInterBase()) ServerManager.controlProcess("interbase", "start");
            else throw new SQLException("AOServer not an InterBase server: hostname="+thisAOServer.getServer().getHostname());
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static void stopInterBase() throws IOException, SQLException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, InterBaseManager.class, "stopInterBase()", null);
        try {
            ServerManager.controlProcess("interbase", "stop");
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    private static InterBaseManager interbaseManager;
    public static void start() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, InterBaseManager.class, "start()", null);
        try {
            if(interbaseManager==null) {
                if(AOServDaemon.getThisAOServer().isInterBase()) {
                    synchronized(System.out) {
                        if(interbaseManager==null) {
                            System.out.print("Starting InterBaseManager: ");
                            AOServConnector conn=AOServDaemon.getConnector();
                            interbaseManager=new InterBaseManager();
                            conn.interBaseDatabases.addTableListener(interbaseManager, 0);
                            conn.interBaseDBGroups.addTableListener(interbaseManager, 0);
                            conn.interBaseServerUsers.addTableListener(interbaseManager, 0);
                            conn.interBaseUsers.addTableListener(interbaseManager, 0);
                            System.out.println("Done");
                        }
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void waitForRebuild() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, InterBaseManager.class, "waitForRebuild()", null);
        try {
            if(interbaseManager!=null) interbaseManager.waitForBuild();
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public String getProcessTimerDescription() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, InterBaseManager.class, "getProcessTimerDescription()", null);
        try {
            return "Rebuild InterBase";
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    private static Connection getSingleUseConnection(String dbPath) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, InterBaseManager.class, "getSingleUseConnection(String)", null);
        try {
            try {
                Class.forName(InterBaseDatabase.JDBC_DRIVER).newInstance();
                return DriverManager.getConnection(
                    getJdbcUrl(ISC4_DB_PATH),
                    InterBaseUser.SYSDBA,
                    AOServDaemonConfiguration.getInterBasePassword()
                );
            } catch(ClassNotFoundException err) {
                SQLException sqlErr=new SQLException();
                sqlErr.initCause(err);
                throw sqlErr;
            } catch(InstantiationException err) {
                SQLException sqlErr=new SQLException();
                sqlErr.initCause(err);
                throw sqlErr;
            } catch(IllegalAccessException err) {
                SQLException sqlErr=new SQLException();
                sqlErr.initCause(err);
                throw sqlErr;
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void backupDatabase(CompressedDataInputStream masterIn, CompressedDataOutputStream masterOut) throws IOException, SQLException {
        Profiler.startProfile(Profiler.IO, InterBaseManager.class, "backupDatabase(CompressedDataInputStream,CompressedDataOutputStream)", null);
        try {
            int pkey=masterIn.readCompressedInt();
            InterBaseDatabase id=AOServDaemon.getConnector().interBaseDatabases.get(pkey);
            if(id==null) throw new SQLException("Unable to find InterBaseDatabase: "+pkey);
            String dbPath=id.getPath();
            String dbName=id.getName();
            UnixFile tempFile=UnixFile.mktemp("/tmp/backup_interbase_database.gbak.base64.gz.");
            tempFile.getFile().deleteOnExit();
            try {
                // Dump, count raw bytes, create MD5, and compress to a temp file
                String[] command={
                    "/usr/aoserv/daemon/bin/backup_interbase_database",
                    dbPath,
                    tempFile.getFilename(),
                    dbName
                };
                Process P=Runtime.getRuntime().exec(command);
                long dataSize;
                BufferedReader dumpIn=new BufferedReader(new InputStreamReader(P.getInputStream()));
                try {
                    dataSize=Long.parseLong(dumpIn.readLine());
                } finally {
                    dumpIn.close();
                }
                try {
                    int retCode=P.waitFor();
                    if(retCode!=0) throw new IOException("backup_interbase_database exited with non-zero return code: "+retCode);
                } catch(InterruptedException err) {
                    InterruptedIOException ioErr=new InterruptedIOException();
                    ioErr.initCause(err);
                    throw ioErr;
                }

                MD5InputStream md5In=new MD5InputStream(new CorrectedGZIPInputStream(new BufferedInputStream(new FileInputStream(tempFile.getFilename()))));
                try {
                    byte[] buff=BufferManager.getBytes();
                    try {
                        while(md5In.read(buff, 0, BufferManager.BUFFER_SIZE)!=-1);
                        md5In.close();

                        byte[] md5=md5In.hash();
                        long md5_hi=MD5.getMD5Hi(md5);
                        long md5_lo=MD5.getMD5Lo(md5);

                        masterOut.write(AOServDaemonProtocol.NEXT);
                        masterOut.writeLong(dataSize);
                        masterOut.writeLong(md5_hi);
                        masterOut.writeLong(md5_lo);
                        masterOut.flush();

                        boolean sendData=masterIn.readBoolean();
                        if(sendData) {
                            InputStream tmpIn=new FileInputStream(tempFile.getFile());
                            int ret;
                            while((ret=tmpIn.read(buff, 0, BufferManager.BUFFER_SIZE))!=-1) {
                                masterOut.write(AOServDaemonProtocol.NEXT);
                                masterOut.writeShort(ret);
                                masterOut.write(buff, 0, ret);
                            }
                        }
                    } finally {
                        BufferManager.release(buff);
                    }
                    masterOut.write(AOServDaemonProtocol.DONE);
                    masterOut.flush();
                } finally {
                    md5In.close();
                }
            } finally {
                if(tempFile.exists()) tempFile.delete();
            }
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    public static void dumpDatabase(InterBaseDatabase id, CompressedDataOutputStream masterOut) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, InterBaseManager.class, "dumpDatabase(InterBaseDatabase,CompressedDataOutputStream)", null);
        try {
            UnixFile tempFile=UnixFile.mktemp("/tmp/dump_interbase_database.gbak.base64");
            tempFile.getFile().deleteOnExit();
            try {
                String dbPath=id.getPath();
                String[] command={
                    "/usr/aoserv/daemon/bin/dump_interbase_database",
                    dbPath,
                    tempFile.getFilename(),
                    id.getName()
                };
                AOServDaemon.exec(command);

                InputStream dumpin=new FileInputStream(tempFile.getFile());
                try {
                    byte[] buff=BufferManager.getBytes();
                    try {
                        int ret;
                        while((ret=dumpin.read(buff, 0, BufferManager.BUFFER_SIZE))!=-1) {
                            masterOut.writeByte(AOServDaemonProtocol.NEXT);
                            masterOut.writeShort(ret);
                            masterOut.write(buff, 0, ret);
                        }
                    } finally {
                        BufferManager.release(buff);
                    }
                } finally {
                    dumpin.close();
                }
            } finally {
                if(tempFile.exists()) tempFile.delete();
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /*
    public static void main(String[] args) {
        try {
            setNames("TEST", "first", "middle", "last");
        } catch(IOException err) {
            AOServDaemon.reportError(err, null);
        } catch(SQLException err) {
            AOServDaemon.reportError(err, null);
        }
    }
     */
}
