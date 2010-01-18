package com.aoindustries.aoserv.daemon.unix.linux;

/*
 * Copyright 2001-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.client.LinuxAccountGroup;
import com.aoindustries.aoserv.client.LinuxGroup;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.ResourceType;
import com.aoindustries.aoserv.client.validator.Gecos;
import com.aoindustries.aoserv.client.validator.GroupId;
import com.aoindustries.aoserv.client.validator.LinuxID;
import com.aoindustries.aoserv.client.validator.UnixPath;
import com.aoindustries.aoserv.client.validator.UserId;
import com.aoindustries.aoserv.client.validator.ValidationException;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.backup.BackupManager;
import com.aoindustries.aoserv.daemon.client.AOServDaemonProtocol;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.unix.ShadowFile;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.CompressedDataInputStream;
import com.aoindustries.io.CompressedDataOutputStream;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.util.BufferManager;
import com.aoindustries.util.ErrorPrinter;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.logging.Level;

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
    }

    public static boolean comparePassword(UserId username, String password) throws IOException, ValidationException {
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
    }

    private static final Object rebuildLock=new Object();
    protected boolean doRebuild() {
        try {
            rebuildLinuxAccountSettings();
            return true;
        } catch(ThreadDeath TD) {
            throw TD;
        } catch(Throwable T) {
            LogFactory.getLogger(LinuxAccountManager.class).log(Level.SEVERE, null, T);
            return false;
        }
    }

    private static void rebuildLinuxAccountSettings() throws IOException, ValidationException {
        AOServer aoServer=AOServDaemon.getThisAOServer();
        HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
        final String wwwDirectory = osConfig.getHttpdSitesDirectory();

        int osv=aoServer.getServer().getOperatingSystemVersion().getPkey();
        if(
            osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
            && osv!=OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
        ) throw new RemoteException("Unsupported OperatingSystemVersion: "+osv);

        synchronized(rebuildLock) {
            // A list of all files to delete is created so that all the data can
            // be backed-up before removal.
            List<File> deleteFileList=new ArrayList<File>();

            // Get the list of users from the database
            SortedSet<LinuxAccount> accounts = new TreeSet<LinuxAccount>(aoServer.getLinuxAccounts());
            int accountsLen = accounts.size();

            // Build a sorted vector of all the usernames, user ids, and home directories
            final Set<UserId> usernames=new HashSet<UserId>(accountsLen*4/3+1);
            final Set<LinuxID> allUids=new HashSet<LinuxID>(accountsLen*4/3+1);
            final Set<LinuxID> enabledUids = new HashSet<LinuxID>(accountsLen*4/3+1);
            final Set<UnixPath> homeDirs=new HashSet<UnixPath>(accountsLen*4/3+1);
            for(LinuxAccount la : accounts) {
                usernames.add(la.getUsername().getUsername());
                // UIDs are not always unique, only need to store once in the list
                LinuxID uid = la.getUid();
                allUids.add(uid);
                if(la.getAoServerResource().getResource().getDisableLog()==null) enabledUids.add(uid);
                homeDirs.add(la.getHome());
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
                for(LinuxAccount account : accounts) {
                    if(account.getUsername().getUsername().equals(LinuxAccount.ROOT)) {
                        printPasswdLine(account, out, true);
                        rootFound=true;
                        break;
                    }
                }
                if(!rootFound) throw new AssertionError("root user not found while creating "+newPasswd.getPath());
                for(LinuxAccount account : accounts) {
                    if(!account.getUsername().getUsername().equals(LinuxAccount.ROOT)) {
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
            SortedSet<LinuxGroup> groups = new TreeSet<LinuxGroup>(aoServer.getLinuxGroups());
            out = new ChainWriter(
                new BufferedOutputStream(
                    new UnixFile(newGroup).getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0644, true)
                )
            );
            try {
                boolean rootFound=false;
                // Write root first
                for(LinuxGroup lg : groups) {
                    if(lg.getGroupName().getGroupName().equals(LinuxGroup.ROOT)) {
                        printGroupLine(lg, out, true);
                        rootFound=true;
                        break;
                    }
                }
                if(!rootFound) throw new AssertionError("root group not found while creating "+newGroup.getPath());
                for(LinuxGroup lg : groups) {
                    if(!lg.getGroupName().getGroupName().equals(LinuxGroup.ROOT)) {
                        printGroupLine(lg, out, true);
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
                for(LinuxGroup lg : groups) {
                    GroupId groupId = lg.getGroupName().getGroupName();
                    if(groupId.equals(LinuxGroup.ROOT)) {
                        printGShadowLine(groupId, out);
                        rootFound=true;
                        break;
                    }
                }
                if(!rootFound) throw new AssertionError("root group not found while creating "+newGShadow.getPath());
                for(LinuxGroup lg : groups) {
                    GroupId groupId = lg.getGroupName().getGroupName();
                    if(!groupId.equals(LinuxGroup.ROOT)) {
                        printGShadowLine(groupId, out);
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
                if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                    // Do nothing
                } else if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                    newGShadowUF.setMode(0400);
                } else throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);
                gshadow.renameTo(backupGShadow);
                newGShadow.renameTo(gshadow);
            } else throw new IOException(newGShadow.getPath()+" is zero or unknown length");

            /*
             * Create any home directories that do not exist.
             */
            for(LinuxAccount linuxAccount : accounts) {
                String type=linuxAccount.getLinuxAccountType().getResourceType().getName();
                //String username=linuxAccount.getUsername().getUsername();
                LinuxGroup primaryGroup=linuxAccount.getPrimaryLinuxGroup();
                //String groupname=group.getLinuxGroup().getName();
                int uid=linuxAccount.getUid().getId();
                int gid=primaryGroup.getGid().getId();

                File homeDir=new File(linuxAccount.getHome().getPath());
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
                    th.length()>(wwwDirectory.length()+1)
                    && th.substring(0, wwwDirectory.length()+1).equals(wwwDirectory+'/')
                    && type.equals(ResourceType.SHELL_ACCOUNT)
                    && linuxAccount.getFtpGuestUser()==null
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
                            if(type.equals(ResourceType.SHELL_ACCOUNT)) {
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
                        UnixPath dirPath=UnixPath.valueOf(dir.getPath());
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
                    if(!usernames.contains(UserId.valueOf(filename))) deleteFileList.add(new File(cronDirectory, filename));
                }
            }

            // Disable and enable accounts
            for(LinuxAccount la : accounts) {
                String prePassword=la.getPredisablePassword();
                if(la.getAoServerResource().getResource().getDisableLog()==null) {
                    if(prePassword!=null) {
                        setEncryptedPassword(la.getUsername().getUsername(), prePassword);
                        la.setPredisablePassword(null);
                    }
                } else {
                    if(prePassword==null) {
                        UserId username=la.getUsername().getUsername();
                        la.setPredisablePassword(getEncryptedPassword(username));
                        setPassword(username, null);
                    }
                }
            }

            // Only the top level server in a physical server gets to kill processes
            if(!AOServDaemonConfiguration.isNested() && aoServer.getFailoverServer()==null) {
                // Build the set of UIDs that are allowed for any nested servers
                for(AOServer nested : aoServer.getNestedAoServers()) {
                    for(LinuxAccount la : nested.getLinuxAccounts()) {
                        if(la.getAoServerResource().getResource().getDisableLog()==null) enabledUids.add(la.getUid());
                    }
                }

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
                                LinuxID uid=process.getUID();
                                if(
                                    // Don't kill root processes, just to be safe
                                    uid.getId()!=UnixFile.ROOT_UID
                                    && !enabledUids.contains(uid)
                                ) process.killProc();
                            } catch(FileNotFoundException err) {
                                // It is normal that this is thrown if the process has already closed
                            } catch(IOException err) {
                                LogFactory.getLogger(LinuxAccountManager.class).log(Level.SEVERE, "filename="+filename, err);
                            }
                        }
                    }
                }
            }

            /*
             * Recursively find and remove any temporary files that should not exist.
             */
            try {
                AOServDaemon.findUnownedFiles(new File("/tmp"), allUids, deleteFileList, 0);
                AOServDaemon.findUnownedFiles(new File("/var/tmp"), allUids, deleteFileList, 0);
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
    }

    public static String getAutoresponderContent(UnixPath path) throws IOException {
        UnixFile file=new UnixFile(path.getPath());
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
    }

    public static String getCronTable(UserId username) throws IOException {
        File cronFile=new File(cronDirectory, username.getId());
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
    }

    public static String getEncryptedPassword(UserId username) throws IOException, ValidationException {
        return ShadowFile.getEncryptedPassword(username);
    }

    /**
     * Prints one line of a Linux group file.
     *
     * @param  group     the <code>LinuxServerGroup</code> to print
     * @param  out       the <code>ChainWriter</code> to print to
     * @param  complete  if <code>true</code>, a complete line will be printed, otherwise
     *                   only the groupname and gid are included
     */
    public static void printGroupLine(LinuxGroup group, ChainWriter out, boolean complete) throws IOException {
        String groupName=group.getGroupName().getGroupName().getId();
        out
            .print(groupName)
            .print(complete?":*:":"::")
            .print(group.getGid())
            .print(':')
        ;
        if(complete) {
            SortedSet<LinuxAccount> altAccounts = new TreeSet<LinuxAccount>();
            for(LinuxAccountGroup lag : group.getAlternateLinuxAccountGroups()) altAccounts.add(lag.getLinuxAccount());
            boolean didOne=false;
            for(LinuxAccount la : altAccounts) {
                if(didOne) out.print(',');
                else didOne=true;
                out.print(la.getUsername().getUsername());
            }
        }
        out.print('\n');
    }

    public static void printGShadowLine(GroupId groupId, ChainWriter out) throws IOException {
        out.print(groupId).print(":::\n");
    }

    /**
     * Prints one line of a Linux passwd file.
     *
     * @param  account   the <code>LinuxServerAccount</code> to print
     * @param  out       the <code>ChainWriter</code> to print to
     * @param  complete  if <code>true</code>, a complete line will be printed, otherwise
     *                   only the username, uid, gid, and full name are included
     */
    public static void printPasswdLine(LinuxAccount linuxAccount, ChainWriter out, boolean complete) throws IOException {
        UserId username=linuxAccount.getUsername().getUsername();
        LinuxGroup primaryGroup = linuxAccount.getPrimaryLinuxGroup();
        out
            .print(username)
            .print(":x:")
            .print(linuxAccount.getUid())
            .print(':')
            .print(primaryGroup.getGid())
            .print(':')
            .print(linuxAccount.getName())
            .print(',')
        ;
        if(complete) {
            Gecos S=linuxAccount.getOfficeLocation();
            if(S!=null) out.print(S);
            out.print(',');
            S=linuxAccount.getOfficePhone();
            if(S!=null) out.print(S);
            out.print(',');
            S=linuxAccount.getHomePhone();
            if(S!=null) out.print(S);
            out
                .print(':')
                .print(linuxAccount.getHome())
                .print(':')
                .print(linuxAccount.getShell().getPath())
                .print('\n')
            ;
        } else out.print(",,::\n");
    }

    public static void setBashProfile(LinuxAccount la, UnixPath profile) throws IOException {
        String profileLine=". "+profile;

        UnixFile profileFile=new UnixFile(la.getHome().getPath(), BASHRC);

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
                out.write('\n');
                out.writeBytes(profileLine);
                out.write('\n');
                out.close();
            }
        }
    }

    public static void setAutoresponderContent(UnixPath path, String content, int uid, int gid) throws IOException {
        File file=new File(path.getPath());
        synchronized(rebuildLock) {
            if(content==null) {
                if(file.exists() && !file.delete()) throw new IOException("Unable to delete file: "+file.getPath());
            } else {
                //int len=content.length();
                PrintWriter out=new PrintWriter(
                    new BufferedOutputStream(
                        new UnixFile(file).getSecureOutputStream(uid, gid, 0600, true)
                    )
                );
                out.print(content);
                out.close();
            }
        }
    }

    public static void setCronTable(UserId username, String cronTable) throws IOException {
        int len=cronTable.length();
        File cronFile=new File(cronDirectory, username.getId());
        synchronized(rebuildLock) {
            if(cronTable.length()==0) {
                if(cronFile.exists()) cronFile.delete();
            } else {
                PrintWriter out=new PrintWriter(
                    new BufferedOutputStream(
                        new UnixFile(cronFile).getSecureOutputStream(
                            UnixFile.ROOT_UID,
                            AOServDaemon.getThisAOServer().getLinuxAccount(username).getPrimaryLinuxGroup().getGid().getId(),
                            0600,
                            true
                        )
                    )
                );
                out.print(cronTable);
                out.close();
            }
        }
    }

    public static void setEncryptedPassword(UserId username, String encryptedPassword) throws IOException, ValidationException {
        AOServer aoServer=AOServDaemon.getThisAOServer();
        LinuxAccount la=aoServer.getLinuxAccount(username);
        if(la==null) throw new AssertionError("Unable to find LinuxServerAccount: "+username+" on "+aoServer.getHostname());
        ShadowFile.setEncryptedPassword(username, encryptedPassword);
    }

    public static void setPassword(UserId username, String plain_password) throws IOException, ValidationException {
        AOServer aoServer=AOServDaemon.getThisAOServer();
        LinuxAccount la=aoServer.getLinuxAccount(username);
        if(la==null) throw new AssertionError("Unable to find LinuxAccount: "+username+" on "+aoServer.getHostname());
        ShadowFile.setPassword(username, plain_password);
    }

    private static LinuxAccountManager linuxAccountManager;
    public static void start() throws IOException {
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();

        synchronized(System.out) {
            if(
                // Nothing is done for these operating systems
                osv!=OperatingSystemVersion.CENTOS_5DOM0_I686
                && osv!=OperatingSystemVersion.CENTOS_5DOM0_X86_64
                // Check config after OS check so config entry not needed
                && AOServDaemonConfiguration.isManagerEnabled(LinuxAccountManager.class)
                && linuxAccountManager==null
            ) {
                System.out.print("Starting LinuxAccountManager: ");
                AOServConnector<?,?> conn=AOServDaemon.getConnector();
                linuxAccountManager=new LinuxAccountManager();
                conn.getFtpGuestUsers().getTable().addTableListener(linuxAccountManager, 0);
                conn.getLinuxAccounts().getTable().addTableListener(linuxAccountManager, 0);
                conn.getLinuxAccountGroups().getTable().addTableListener(linuxAccountManager, 0);
                conn.getLinuxGroups().getTable().addTableListener(linuxAccountManager, 0);
                System.out.println("Done");
            }
        }
    }

    public static void tarHomeDirectory(CompressedDataOutputStream out, UserId username) throws IOException {
        LinuxAccount la=AOServDaemon.getThisAOServer().getLinuxAccount(username);
        UnixPath home=la.getHome();
        UnixFile tempUF=UnixFile.mktemp("/tmp/tar_home_directory.tar.", true);
        try {
            String[] cmd={
                "/bin/tar",
                "-c",
                "-C",
                home.getPath(),
                "-f",
                tempUF.getPath(),
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
    }

    public static void untarHomeDirectory(CompressedDataInputStream in, UserId username) throws IOException {
        synchronized(rebuildLock) {
            LinuxAccount la=AOServDaemon.getThisAOServer().getLinuxAccount(username);
            UnixPath home=la.getHome();
            UnixFile tempUF=UnixFile.mktemp("/tmp/untar_home_directory.tar.", true);
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
                    if(code==AOServDaemonProtocol.REMOTE_EXCEPTION) throw new RemoteException(in.readUTF());
                    else throw new IOException("Unknown result: " + code);
                }
                String[] cmd={
                    "/bin/tar",
                    "-x",
                    "-C",
                    home.getPath(),
                    "-f",
                    tempUF.getPath()
                };
                AOServDaemon.exec(cmd);
            } finally {
                tempUF.delete();
            }
        }
    }

    public static void waitForRebuild() {
        if(linuxAccountManager!=null) linuxAccountManager.waitForBuild();
    }

    public String getProcessTimerDescription() {
        return "Rebuild Linux Accounts";
    }

    @Override
    public long getProcessTimerMaximumTime() {
        return 15*60*1000;
    }
    
    /**
     * Allows manual rebuild without the necessity of running the entire daemon (use carefully, only when main daemon not running).
     */
    public static void main(String[] args) {
        try {
            rebuildLinuxAccountSettings();
        } catch(RuntimeException err) {
            ErrorPrinter.printStackTraces(err);
            System.exit(1);
        } catch(IOException err) {
            ErrorPrinter.printStackTraces(err);
            System.exit(2);
        } catch(ValidationException err) {
            ErrorPrinter.printStackTraces(err);
            System.exit(3);
        }
    }
}
