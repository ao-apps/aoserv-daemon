package com.aoindustries.aoserv.daemon.unix.linux;

/*
 * Copyright 2001-2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.aoserv.daemon.backup.*;
import com.aoindustries.aoserv.daemon.client.AOServDaemonProtocol;
import com.aoindustries.aoserv.daemon.email.*;
import com.aoindustries.aoserv.daemon.unix.*;
import com.aoindustries.aoserv.daemon.util.*;
import com.aoindustries.io.*;
import com.aoindustries.io.unix.*;
import com.aoindustries.profiler.*;
import com.aoindustries.sql.*;
import com.aoindustries.table.*;
import com.aoindustries.util.*;
import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * @author  AO Industries, Inc.
 */
public class LinuxAccountManager extends BuilderThread {

    public static final File
        newPasswd = new File("/etc/passwd.new"),
        passwd=new File("/etc/passwd"),
        backupPasswd=new File("/etc/passwd.old"),

        newGroup=new File("/etc/group.new"),
        group=new File("/etc/group"),
        backupGroup=new File("/etc/group.old"),
        
        newGShadow=new File("/etc/gshadow.new"),
        gshadow=new File("/etc/gshadow"),
        backupGShadow=new File("/etc/gshadow.old")
    ;

    public static final String BASHRC=".bashrc";

    public static final File cronDirectory=new File("/var/spool/cron");

    /**
     * LinuxAccounts constructor comment.
     */
    private LinuxAccountManager() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, LinuxAccountManager.class, "<init>()", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }

    public static boolean comparePassword(String username, String password) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, LinuxAccountManager.class, "comparePassword(String,String)", null);
        try {
            String crypted=ShadowFile.getEncryptedPassword(username);
            if(crypted.equals(LinuxAccount.NO_PASSWORD_CONFIG_VALUE)) return false;
            int len=crypted.length();
            if(len<2) return false;
            String salt=crypted.substring(0,2);
            if(salt.equals("$1")) {
                // MD5 crypted, use longer salt
                int pos=crypted.indexOf('$', 3);
                if(pos==-1) return false;
                salt=crypted.substring(0, pos);
            }
            String newCrypted=UnixFile.crypt(password, salt);
            return crypted.equals(newCrypted);
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, LinuxAccountManager.class, "doRebuild()", null);
        try {
            rebuildLinuxAccountSettings();
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void rebuildLinuxAccountSettings() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, LinuxAccountManager.class, "rebuildLinuxAccountSettings()", null);
        try {
            AOServConnector connector=AOServDaemon.getConnector();
            AOServer aoServer=AOServDaemon.getThisAOServer();

            int osv=aoServer.getServer().getOperatingSystemVersion().getPkey();
            if(
                osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
                && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
                && osv!=OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            synchronized(rebuildLock) {
                // A list of all files to delete is created so that all the data can
                // be backed-up before removal.
                List<File> deleteFileList=new ArrayList<File>();

                // Get the list of users from the database
                List<LinuxServerAccount> accounts = aoServer.getLinuxServerAccounts();
                int accountsLen = accounts.size();

                // Build a sorted vector of all the usernames, user ids, and home directories
                final List<String> usernames=new SortedArrayList<String>(accountsLen);
                final IntList uids=new SortedIntArrayList(accountsLen);
                final List<String> homeDirs=new SortedArrayList<String>(accountsLen);
                for(int c=0;c<accountsLen;c++) {
                    LinuxServerAccount lsa=accounts.get(c);
                    usernames.add(lsa.getLinuxAccount().getUsername().getUsername());
                    // UIDs are not always unique, only need to store once in the list
                    int uid = lsa.getUID().getID();
                    if(!uids.contains(uid)) uids.add(uid);
                    // Home directories are not always unique, only need to store once in the list
                    String home = lsa.getHome();
                    if(!homeDirs.contains(home)) homeDirs.add(home);
                }

                /*
                 * Write the new /etc/passwd file.
                 */
                ChainWriter out = new ChainWriter(
                    new BufferedOutputStream(
                        new UnixFile(newPasswd).getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0644, true)
                    )
                );
		try {
		    // Write root first
		    boolean rootFound=false;
		    for (int c = 0; c < accountsLen; c++) {
			LinuxServerAccount account = accounts.get(c);
			if(account.getLinuxAccount().getUsername().getUsername().equals(LinuxAccount.ROOT)) {
			    printPasswdLine(account, out, true);
			    rootFound=true;
			    break;
			}
		    }
		    if(!rootFound) throw new SQLException("root user not found while creating "+newPasswd.getPath());
		    for (int c = 0; c < accountsLen; c++) {
			LinuxServerAccount account = accounts.get(c);
			if(!account.getLinuxAccount().getUsername().getUsername().equals(LinuxAccount.ROOT)) {
			    printPasswdLine(account, out, true);
			}
		    }
		} finally {
		    out.flush();
		    out.close();
		}

                /*
                 * Write the new /etc/group file.
                 */
                List<LinuxServerGroup> groups = aoServer.getLinuxServerGroups();
                int groupsLen = groups.size();
                out = new ChainWriter(
                    new BufferedOutputStream(
                        new UnixFile(newGroup).getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0644, true)
                    )
                );
		try {
                    Map<String,Object> tempMap=new HashMap<String,Object>();
		    boolean rootFound=false;
		    // Write root first
		    for (int c = 0; c < groupsLen; c++) {
			LinuxServerGroup lsg=groups.get(c);
			if(lsg.getLinuxGroup().getName().equals(LinuxGroup.ROOT)) {
			    printGroupLine(groups.get(c), out, true, tempMap);
			    rootFound=true;
			    break;
			}
		    }
		    if(!rootFound) throw new SQLException("root group not found while creating "+newGroup.getPath());
		    for (int c = 0; c < groupsLen; c++) {
			LinuxServerGroup lsg=groups.get(c);
			if(!lsg.getLinuxGroup().getName().equals(LinuxGroup.ROOT)) {
			    printGroupLine(groups.get(c), out, true, tempMap);
			}
		    }
		} finally {
		    out.flush();
		    out.close();
		}

                /*
                 * Write the new /etc/gshadow file.
                 */
                UnixFile newGShadowUF = new UnixFile(newGShadow);
                out = new ChainWriter(
                    new BufferedOutputStream(
                        newGShadowUF.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0600, true)
                    )
                );
		try {
		    // Write root first
		    boolean rootFound=false;
		    for (int c = 0; c < groupsLen; c++) {
			LinuxServerGroup lsg=groups.get(c);
			if(lsg.getLinuxGroup().getName().equals(LinuxGroup.ROOT)) {
			    printGShadowLine(groups.get(c), out);
			    rootFound=true;
			    break;
			}
		    }
		    if(!rootFound) throw new SQLException("root group not found while creating "+newGShadow.getPath());
		    for (int c = 0; c < groupsLen; c++) {
			LinuxServerGroup lsg=groups.get(c);
			if(!lsg.getLinuxGroup().getName().equals(LinuxGroup.ROOT)) {
			    printGShadowLine(groups.get(c), out);
			}
		    }
		} finally {
		    out.flush();
		    out.close();
		}

                ShadowFile.rebuildShadowFile(accounts);

                /*
                 * Move the new files into place.
                 */
                if(newPasswd.length()>0) {
                    passwd.renameTo(backupPasswd);
                    newPasswd.renameTo(passwd);
                } else throw new IOException(newPasswd.getPath()+" is zero or unknown length");

                if(newGroup.length()>0) {
                    group.renameTo(backupGroup);
                    newGroup.renameTo(group);
                } else throw new IOException(newGroup.getPath()+" is zero or unknown length");

                if(newGShadow.length()>0) {
                    if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                        // Do nothing
                    } else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                        // Do nothing
                    } else if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                        newGShadowUF.setMode(0400);
                    } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                    gshadow.renameTo(backupGShadow);
                    newGShadow.renameTo(gshadow);
                } else throw new IOException(newGShadow.getPath()+" is zero or unknown length");

                if(
                    osv==OperatingSystemVersion.MANDRIVA_2006_0_I586
                ) {
                    /*
                     * Create any inboxes that need to exist.
                     */
                    LinuxServerGroup mailGroup=connector.linuxGroups.get(LinuxGroup.MAIL).getLinuxServerGroup(aoServer);
                    if(mailGroup==null) throw new SQLException("Unable to find LinuxServerGroup: "+LinuxGroup.MAIL+" on "+aoServer.getServer().getHostname());
                    for (int c = 0; c < accounts.size(); c++) {
                        LinuxServerAccount account = accounts.get(c);
                        LinuxAccount linuxAccount = account.getLinuxAccount();
                        if(linuxAccount.getType().isEmail()) {
                            String username=linuxAccount.getUsername().getUsername();
                            File file=new File(EmailAddressManager.mailSpool, username);
                            if(!file.exists()) {
                                UnixFile unixFile=new UnixFile(file.getPath());
                                unixFile.getSecureOutputStream(
                                    account.getUID().getID(),
                                    mailGroup.getGID().getID(),
                                    0660,
                                    false
                                ).close();
                            }
                        }
                    }
                    /*
                     * Remove any inboxes that should not exist.
                     */
                    String[] list=EmailAddressManager.mailSpool.list();
                    if(list!=null) {
                        int len=list.length;
                        for(int c=0;c<len;c++) {
                            String filename=list[c];
                            if(!usernames.contains(filename)) {
                                // Also allow a username.lock file to remain
                                if(
                                    !filename.endsWith(".lock")
                                    || !usernames.contains(filename.substring(0, filename.length()-5))
                                ) {
                                    File spoolFile=new File(EmailAddressManager.mailSpool, filename);
                                    deleteFileList.add(spoolFile);
                                }
                            }
                        }
                    }
                }
                /*
                 * Create any home directories that do not exist.
                 */
                for (int c = 0; c < accountsLen; c++) {
                    LinuxServerAccount account = accounts.get(c);
                    LinuxAccount linuxAccount=account.getLinuxAccount();
                    String type=linuxAccount.getType().getName();
                    String username=linuxAccount.getUsername().getUsername();
                    LinuxServerGroup group=account.getPrimaryLinuxServerGroup();
                    String groupname=group.getLinuxGroup().getName();
                    int uid=account.getUID().getID();
                    int gid=group.getGID().getID();

                    File homeDir=new File(account.getHome());
                    if(!homeDir.exists()) {
                        // Make the parent of the home directory if it does not exist
                        File parent=homeDir.getParentFile();
                        if(!parent.exists()) {
                            parent.mkdir();
                            UnixFile unixFile=new UnixFile(parent.getPath());
                            unixFile.chown(UnixFile.ROOT_UID, UnixFile.ROOT_GID);
                            unixFile.setMode(0755);
                        }

                        // Make the home directory
                        homeDir.mkdir();
                    }

                    // Set up the directory if it was just created or was created as root before
                    String th=homeDir.getPath();
                    // Homes in /www will have all the skel copied, but will not set the directory perms
                    boolean isWWWAndUser=
                        th.length()>(HttpdSite.WWW_DIRECTORY.length()+1)
                        && th.substring(0, HttpdSite.WWW_DIRECTORY.length()+1).equals(HttpdSite.WWW_DIRECTORY+'/')
                        && (type.equals(LinuxAccountType.USER) || type.equals(LinuxAccountType.APPLICATION))
                        && linuxAccount.getFTPGuestUser()==null
                    ;
                    // Only build directories for accounts that are in /home/ or user account in /www/
                    if(
                        isWWWAndUser
                        || (th.length()>6 && th.substring(0, 6).equals("/home/"))
                    ) {
                        UnixFile unixFile=new UnixFile(th);
                        Stat unixFileStat = unixFile.getStat();
                        if(
                            !isWWWAndUser
                            && (
                                unixFileStat.getUID()==UnixFile.ROOT_UID
                                || unixFileStat.getGID()==UnixFile.ROOT_GID
                            )
                        ) {
                            unixFile.chown(uid, gid);
                            unixFile.setMode(0700);
                        }

                        // Copy the /etc/skel directory
                        File skel=new File("/etc/skel");
                        String[] skelList=skel.list();
                        if(skelList!=null) {
                            int len=skelList.length;
                            for(int d=0;d<len;d++) {
                                // Copy the file
                                String filename=skelList[d];
                                // Only copy the files for user accounts
                                if(type.equals(LinuxAccountType.USER) || type.equals(LinuxAccountType.APPLICATION)) {
                                    // Only do the rest of the files for user accounts
                                    UnixFile skelFile=new UnixFile(skel, filename);
                                    UnixFile homeFile=new UnixFile(homeDir, filename);
                                    if(!homeFile.getStat().exists()) {
                                        skelFile.copyTo(homeFile, false);
                                        homeFile.chown(uid, gid);
                                    }
                                }
                            }
                        }
                    }
                }

                /*
                 * Remove any home directories that should not exist.
                 */
                for(char ch='a';ch<='z';ch++) {
                    File homeDir=new File("/home/"+ch);
                    String[] homeList=homeDir.list();
                    if(homeList!=null) {
                        int len=homeList.length;
                        for(int c=0;c<len;c++) {
                            String dirName=homeList[c];
                            File dir=new File(homeDir, dirName);
                            String dirPath=dir.getPath();
                            if(!homeDirs.contains(dirPath)) deleteFileList.add(dir);
                        }
                    }
                }

                /*
                 * Remove any cron jobs that should not exist.
                 */ 
                String[] cronList=cronDirectory.list();
                if(cronList!=null) {
                    int len=cronList.length;
                    for(int c=0;c<len;c++) {
                        String filename=cronList[c];

                        // Filename must be the username of one of the users to be kept in tact
                        if(!usernames.contains(filename)) deleteFileList.add(new File(cronDirectory, filename));
                    }
                }

                // Disable and enable accounts
                for(int c=0;c<accounts.size();c++) {
                    LinuxServerAccount lsa=accounts.get(c);
                    String prePassword=lsa.getPredisablePassword();
                    if(lsa.getDisableLog()==null) {
                        if(prePassword!=null) {
                            setEncryptedPassword(lsa.getLinuxAccount().getUsername().getUsername(), prePassword);
                            lsa.setPredisablePassword(null);
                        }
                    } else {
                        if(prePassword==null) {
                            String username=lsa.getLinuxAccount().getUsername().getUsername();
                            lsa.setPredisablePassword(getEncryptedPassword(username));
                            setPassword(username, null);
                        }
                    }
                }

                // Only the top level server in a physical server gets to kill processes
                if(!AOServDaemonConfiguration.isNested() && aoServer.getFailoverServer()==null) {
                    List<AOServer> nestedServers=aoServer.getNestedAOServers();

                    /*
                     * Kill any processes that are running as a UID that
                     * should not exist on this server.
                     */
                    File procDir=new File("/proc");
                    String[] procList=procDir.list();
                    if(procList!=null) {
                        int len=procList.length;
                        for(int c=0;c<len;c++) {
                            String filename=procList[c];
                            int flen=filename.length();
                            boolean allNum=true;
                            for(int d=0;d<flen;d++) {
                                char ch=filename.charAt(d);
                                if(ch<'0' || ch>'9') {
                                    allNum=false;
                                    break;
                                }
                            }
                            if(allNum) {
                                try {
                                    LinuxProcess process=new LinuxProcess(Integer.parseInt(filename));
                                    int uid=process.getUID();
                                    // Don't kill root processes, just to be safe
                                    if(uid!=0) {
                                        // Search each server
                                        LinuxServerAccount lsa;
                                        if(
                                            !uids.contains(uid)
                                            || (lsa=aoServer.getLinuxServerAccount(uid))==null
                                            || lsa.getDisableLog()!=null
                                        ) {
                                            // Also must not be in a nested server
                                            boolean found=false;
                                            for(int d=0;d<nestedServers.size();d++) {
                                                if(
                                                    (lsa=nestedServers.get(d).getLinuxServerAccount(uid))!=null
                                                    && lsa.getDisableLog()==null
                                                ) {
                                                    found=true;
                                                    break;
                                                }
                                            }
                                            if(!found) process.killProc();
                                        }
                                    }
                                } catch(FileNotFoundException err) {
                                    // It is normal that this is thrown if the process has already closed
                                } catch(IOException err) {
                                    AOServDaemon.reportError(err, new Object[] {"filename="+filename});
                                }
                            }
                        }
                    }
                }

                /*
                 * Recursively find and remove any temporary files that should not exist.
                 */
                try {
                    AOServDaemon.findUnownedFiles(new File("/tmp"), uids, deleteFileList, 0);
                    AOServDaemon.findUnownedFiles(new File("/var/tmp"), uids, deleteFileList, 0);
                } catch(FileNotFoundException err) {
                    // This may normally occur because of the dynamic nature of the tmp directories
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
                        new UnixFile(file.getPath()).secureDeleteRecursive();
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static String getAutoresponderContent(String path) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, LinuxAccountManager.class, "getAutoresponderContent(String)", null);
        try {
            UnixFile file=new UnixFile(path);
            String content;
            if(file.getStat().exists()) {
		StringBuilder SB=new StringBuilder();
                InputStream in=new BufferedInputStream(file.getSecureInputStream());
		try {
		    int ch;
		    while((ch=in.read())!=-1) SB.append((char)ch);
		} finally {
		    in.close();
		}
                content=SB.toString();
            } else content="";
            return content;
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static String getCronTable(CompressedDataOutputStream out, String username) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, LinuxAccountManager.class, "getCronTable(CompressedDataOutputStream,String)", null);
        try {
            File cronFile=new File(cronDirectory, username);
            String cronTable;
            if(cronFile.exists()) {
		StringBuilder SB=new StringBuilder();
                InputStream in=new BufferedInputStream(new FileInputStream(cronFile));
		try {
		    int ch;
		    while((ch=in.read())!=-1) SB.append((char)ch);
		} finally {
		    in.close();
		}
                cronTable=SB.toString();
            } else cronTable="";
            return cronTable;
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static String getEncryptedPassword(String username) throws IOException, SQLException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, LinuxAccountManager.class, "getEncryptedPassword(String)", null);
        try {
            return ShadowFile.getEncryptedPassword(username);
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    /**
     * Prints one line of a Linux group file.
     *
     * @param  group     the <code>LinuxServerGroup</code> to print
     * @param  out       the <code>ChainWriter</code> to print to
     * @param  complete  if <code>true</code>, a complete line will be printed, otherwise
     *                   only the groupname and gid are included
     */
    public static void printGroupLine(LinuxServerGroup group, ChainWriter out, boolean complete, Map<String,Object> tempMap) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, LinuxAccountManager.class, "printGroupLine(LinuxServerGroup,ChainWriter,boolean,Map<String,Object>)", null);
        try {
            LinuxGroup linuxGroup = group.getLinuxGroup();
            String groupName=linuxGroup.getName();
            out
                .print(groupName)
                .print(complete?":*:":"::")
                .print(group.getGID().getID())
                .print(':')
            ;
            if(complete) {
                tempMap.clear();
                List<LinuxServerAccount> altAccounts = group.getAlternateLinuxServerAccounts();
                boolean didOne=false;
                int len = altAccounts.size();
                for (int d = 0; d < len; d++) {
                    String username = altAccounts.get(d)
                        .getLinuxAccount()
                        .getUsername()
                        .getUsername()
                    ;
                    if(!tempMap.containsKey(username)) {
                        if(didOne) out.print(',');
                        else didOne=true;
                        out.print(username);
                        tempMap.put(username, null);
                    }
                }
                if(groupName.equals(LinuxGroup.PROFTPD_JAILED)) {
                    AOServer aoServer=group.getAOServer();
                    int osv=aoServer.getServer().getOperatingSystemVersion().getPkey();
                    boolean addJailed;
                    if(
                        osv==OperatingSystemVersion.MANDRIVA_2006_0_I586
                        || osv==OperatingSystemVersion.REDHAT_ES_4_X86_64
                    ) {
                        addJailed = true;
                    } else if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                        addJailed = false;
                    } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                    if(addJailed) {
                        for(FTPGuestUser guestUser : aoServer.getFTPGuestUsers()) {
                            String username=guestUser.getLinuxAccount().getUsername().getUsername();
                            if(!tempMap.containsKey(username)) {
                                if(didOne) out.print(',');
                                else didOne=true;
                                out.print(username);
                                tempMap.put(username, null);
                            }
                        }
                    }
                }
            }
            out.print('\n');
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void printGShadowLine(LinuxServerGroup group, ChainWriter out) throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, LinuxAccountManager.class, "printGShadowLine(LinuxServerGroup,ChainWriter)", null);
        try {
            LinuxGroup linuxGroup = group.getLinuxGroup();
            out.print(linuxGroup.getName()).print(":::\n");
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    /**
     * Prints one line of a Linux passwd file.
     *
     * @param  account   the <code>LinuxServerAccount</code> to print
     * @param  out       the <code>ChainWriter</code> to print to
     * @param  complete  if <code>true</code>, a complete line will be printed, otherwise
     *                   only the username, uid, gid, and full name are included
     */
    public static void printPasswdLine(LinuxServerAccount account, ChainWriter out, boolean complete) throws IOException, SQLException {
        Profiler.startProfile(Profiler.IO, LinuxAccountManager.class, "printPasswdLine(LinuxServerAccount,ChainWriter,boolean)", null);
        try {
            LinuxAccount linuxAccount = account.getLinuxAccount();
            String username=linuxAccount.getUsername().getUsername();
            LinuxServerGroup primaryGroup = account.getPrimaryLinuxServerGroup();
            if(primaryGroup==null) throw new SQLException("Unable to find primary LinuxServerGroup for username="+username+" on "+account.getAOServer().getServer().getHostname());
            out
                .print(username)
                .print(":x:")
                .print(account.getUID().getID())
                .print(':')
                .print(primaryGroup.getGID().getID())
                .print(':')
                .print(linuxAccount.getName())
                .print(',')
            ;
            if(complete) {
                String S=linuxAccount.getOfficeLocation();
                if(S!=null) out.print(S);
                out.print(',');
                S=linuxAccount.getOfficePhone();
                if(S!=null) out.print(S);
                out.print(',');
                S=linuxAccount.getHomePhone();
                if(S!=null) out.print(S);
                out
                    .print(':')
                    .print(account.getHome())
                    .print(':')
                    .print(linuxAccount.getShell().getPath())
                    .print('\n')
                ;
            } else out.print(",,::\n");
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    public static void setBashProfile(LinuxServerAccount lsa, String profile) throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, LinuxAccountManager.class, "setBashProfile(LinuxServerAccount,String)", null);
        try {
            String profileLine=". "+profile;

            UnixFile profileFile=new UnixFile(lsa.getHome(), BASHRC);

            // Make sure the file exists
            if(profileFile.getStat().exists()) {
                // Read the old file, looking for the source in the file
                BufferedReader in=new BufferedReader(new InputStreamReader(profileFile.getSecureInputStream()));
                String line;

                boolean found=false;
                while((line=in.readLine())!=null) {
                    if(line.equals(profileLine)) {
                        found=true;
                        break;
                    }
                }
                in.close();
                if(!found) {
                    RandomAccessFile out=profileFile.getSecureRandomAccessFile("rw");
                    out.seek(out.length());
                    out.writeBytes(profileLine);
                    out.write('\n');
                    out.close();
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void setAutoresponderContent(String path, String content, int uid, int gid) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, LinuxAccountManager.class, "setAutoresponderContent(String,String)", null);
        try {
            File file=new File(path);
            synchronized(rebuildLock) {
                if(content==null) {
                    if(file.exists() && !file.delete()) throw new IOException("Unable to delete file: "+file.getPath());
                } else {
                    int len=content.length();
                    PrintWriter out=new PrintWriter(
                        new BufferedOutputStream(
                            new UnixFile(file).getSecureOutputStream(uid, gid, 0600, true)
                        )
                    );
                    out.print(content);
                    out.close();
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void setCronTable(String username, String cronTable) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, LinuxAccountManager.class, "setCronTable(String,String)", null);
        try {
            int len=cronTable.length();
            File cronFile=new File(cronDirectory, username);
            synchronized(rebuildLock) {
                if(cronTable.length()==0) {
                    if(cronFile.exists()) cronFile.delete();
                } else {
                    PrintWriter out=new PrintWriter(
                        new BufferedOutputStream(
                            new UnixFile(cronFile).getSecureOutputStream(
                                UnixFile.ROOT_UID,
                                AOServDaemon.getThisAOServer().getLinuxServerAccount(username).getPrimaryLinuxServerGroup().getGID().getID(),
                                0600,
                                true
                            )
                        )
                    );
                    out.print(cronTable);
                    out.close();
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void setEncryptedPassword(String username, String encryptedPassword) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, LinuxAccountManager.class, "setEncryptedPassword(String,String)", null);
        try {
            AOServer aoServer=AOServDaemon.getThisAOServer();
            LinuxServerAccount lsa=aoServer.getLinuxServerAccount(username);
            if(lsa==null) throw new SQLException("Unable to find LinuxServerAccount: "+username+" on "+aoServer.getServer().getHostname());
            ShadowFile.setEncryptedPassword(username, encryptedPassword);
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void setPassword(String username, String plain_password) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, LinuxAccountManager.class, "setPassword(String,String)", null);
        try {
            AOServer aoServer=AOServDaemon.getThisAOServer();
            LinuxServerAccount lsa=aoServer.getLinuxServerAccount(username);
            if(lsa==null) throw new SQLException("Unable to find LinuxServerAccount: "+username+" on "+aoServer.getServer().getHostname());
            ShadowFile.setPassword(username, plain_password);
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static LinuxAccountManager linuxAccountManager;
    public static void start() throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, LinuxAccountManager.class, "start()", null);
        try {
            if(AOServDaemonConfiguration.isManagerEnabled(LinuxAccountManager.class) && linuxAccountManager==null) {
                synchronized(System.out) {
                    if(linuxAccountManager==null) {
                        System.out.print("Starting LinuxAccountManager: ");
                        AOServConnector conn=AOServDaemon.getConnector();
                        linuxAccountManager=new LinuxAccountManager();
                        conn.ftpGuestUsers.addTableListener(linuxAccountManager, 0);
                        conn.linuxAccounts.addTableListener(linuxAccountManager, 0);
                        conn.linuxGroupAccounts.addTableListener(linuxAccountManager, 0);
                        conn.linuxServerAccounts.addTableListener(linuxAccountManager, 0);
                        conn.linuxServerGroups.addTableListener(linuxAccountManager, 0);
                        System.out.println("Done");
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void tarHomeDirectory(CompressedDataOutputStream out, String username) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, LinuxAccountManager.class, "tarHomeDirectory(CompressedDataOutputStream,String)", null);
        try {
            LinuxServerAccount lsa=AOServDaemon.getThisAOServer().getLinuxServerAccount(username);
            String home=lsa.getHome();
            UnixFile tempUF=UnixFile.mktemp("/tmp/tar_home_directory.tar.");
            tempUF.getFile().deleteOnExit();
            try {
                String[] cmd={
                    "/bin/tar",
                    "-c",
                    "-C",
                    home,
                    "-f",
                    tempUF.getFilename(),
                    "."
                };
                AOServDaemon.exec(cmd);
                InputStream in=new FileInputStream(tempUF.getFile());
                byte[] buff=BufferManager.getBytes();
                try {
                    int ret;
                    while((ret=in.read(buff, 0, BufferManager.BUFFER_SIZE))!=-1) {
                        out.writeByte(AOServDaemonProtocol.NEXT);
                        out.writeShort(ret);
                        out.write(buff, 0, ret);
                    }
                } finally {
                    BufferManager.release(buff);
                }
                in.close();
            } finally {
                tempUF.delete();
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void untarHomeDirectory(CompressedDataInputStream in, String username) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, LinuxAccountManager.class, "untarHomeDirectory(CompressedDataInputStream,String)", null);
        try {
            synchronized(rebuildLock) {
                LinuxServerAccount lsa=AOServDaemon.getThisAOServer().getLinuxServerAccount(username);
                String home=lsa.getHome();
                UnixFile tempUF=UnixFile.mktemp("/tmp/untar_home_directory.tar.");
                tempUF.getFile().deleteOnExit();
                try {
                    OutputStream out=tempUF.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0600, true);
                    int code;
                    byte[] buff=BufferManager.getBytes();
                    try {
                        while((code=in.readByte())==AOServDaemonProtocol.NEXT) {
                            int len=in.readShort();
                            in.readFully(buff, 0, len);
                            out.write(buff, 0, len);
                        }
                    } finally {
                        BufferManager.release(buff);
                    }
                    out.close();
                    if(code!=AOServDaemonProtocol.DONE) {
                        if(code==AOServDaemonProtocol.IO_EXCEPTION) throw new IOException(in.readUTF());
                        else if(code==AOServDaemonProtocol.SQL_EXCEPTION) throw new SQLException(in.readUTF());
                        else throw new IOException("Unknown result: " + code);
                    }
                    String[] cmd={
                        "/bin/tar",
                        "-x",
                        "-C",
                        home,
                        "-f",
                        tempUF.getFilename()
                    };
                    AOServDaemon.exec(cmd);
                } finally {
                    tempUF.delete();
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void waitForRebuild() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, LinuxAccountManager.class, "waitForRebuild()", null);
        try {
            if(linuxAccountManager!=null) linuxAccountManager.waitForBuild();
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public String getProcessTimerDescription() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, LinuxAccountManager.class, "getProcessTimerDescription()", null);
        try {
            return "Rebuild Linux Accounts";
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
    
    public long getProcessTimerMaximumTime() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, LinuxAccountManager.class, "getProcessTimerMaximumTime()", null);
        try {
            return 15*60*1000;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
    
    /**
     * Allows manual rebuild without the necessity of running the entire daemon (use carefully, only when main daemon not running).
     */
    public static void main(String[] args) {
        try {
            rebuildLinuxAccountSettings();
        } catch(IOException err) {
            ErrorPrinter.printStackTraces(err);
        } catch(SQLException err) {
            ErrorPrinter.printStackTraces(err);
        }
    }
}
