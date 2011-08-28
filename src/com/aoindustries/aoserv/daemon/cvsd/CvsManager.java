package com.aoindustries.aoserv.daemon.cvsd;

/*
 * Copyright 2002-2011 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.CvsRepository;
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.client.LinuxAccountGroup;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.validator.UnixPath;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.backup.BackupManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/**
 * Handles the building of CVS repositories, xinetd configs are handled by XinetdManager.
 *
 * @author  AO Industries, Inc.
 */
final public class CvsManager extends BuilderThread {

    public static final String CVS_DIRECTORY="/var/cvs";

    private static CvsManager cvsManager;

    public static boolean isSafePath(UnixPath path) {
        if(path==null) return false;
        String pathString = path.toString();
        int len=pathString.length();
        for(int c=1;c<len;c++) {
            char ch=pathString.charAt(c);
            if(
                (ch<'a' || ch>'z')
                && (ch<'A' || ch>'Z')
                && (ch<'0' || ch>'9')
                && ch!='_'
                && ch!='.'
                && ch!='-'
                && ch!='/'
            ) return false;
        }
        return true;
    }

    private CvsManager() {
    }

    private static final Object rebuildLock=new Object();
    @Override
    protected boolean doRebuild() {
        try {
            //AOServConnector conn=AOServDaemon.getConnector();
            AOServer thisAOServer=AOServDaemon.getThisAOServer();

            int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
            if(
                osv!=OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            synchronized(rebuildLock) {
                // Get a list of all the directories in /var/cvs
                File cvsDir=new File(CVS_DIRECTORY);
                if(!cvsDir.exists()) new UnixFile(cvsDir).mkdir(false, 0755, UnixFile.ROOT_UID, UnixFile.ROOT_GID);
                String[] list=cvsDir.list();
                int listLen=list.length;
                Set<UnixPath> existing = new HashSet<UnixPath>(listLen);
                for(int c=0;c<listLen;c++) existing.add(UnixPath.valueOf(CVS_DIRECTORY+'/'+list[c]));

                // Add each directory that doesn't exist, fix permissions and ownerships, too
                // while removing existing directories from existing
                for(CvsRepository cvs : thisAOServer.getCvsRepositories()) {
                    UnixPath path=cvs.getPath();
                    UnixFile cvsUF=new UnixFile(path.toString());
                    Stat cvsStat = cvsUF.getStat();
                    long cvsMode=cvs.getMode();
                    // Make the directory
                    if(!cvsStat.exists()) {
                        cvsUF.mkdir(true, cvsMode);
                        cvsUF.getStat(cvsStat);
                    }
                    // Set the mode
                    if(cvsStat.getMode()!=cvsMode) {
                        cvsUF.setMode(cvsMode);
                        cvsUF.getStat(cvsStat);
                    }
                    // Set the owner and group
                    LinuxAccountGroup lag = cvs.getLinuxAccountGroup();
                    LinuxAccount la=lag.getLinuxAccount();
                    int uid=la.getUid().getId();
                    int gid=lag.getLinuxGroup().getGid().getId();
                    if(uid!=cvsStat.getUid() || gid!=cvsStat.getGid()) {
                        cvsUF.chown(uid, gid);
                        cvsUF.getStat(cvsStat);
                    }
                    // Init if needed
                    UnixFile cvsRootUF=new UnixFile(cvsUF, "CVSROOT", false);
                    if(!cvsRootUF.getStat().exists()) {
                        if(!isSafePath(path)) throw new AssertionError("Refusing to call shell.  Not safe path: "+path);
                        AOServDaemon.suexec(
                            la.getUserId(),
                            "/usr/bin/cvs -d "+path+" init",
                            0
                        );
                    }
                    // Remove from list
                    existing.remove(path);
                }

                /*
                 * Back up the files scheduled for removal.
                 */
                int svLen=existing.size();
                if(svLen!=0) {
                    List<File> deleteFileList=new ArrayList<File>(svLen);
                    for(UnixPath deleteFilename : existing) deleteFileList.add(new File(deleteFilename.toString()));

                    // Get the next backup filename
                    File backupFile=BackupManager.getNextBackupFile();
                    BackupManager.backupFiles(deleteFileList, backupFile);

                    /*
                     * Remove the files that have been backed up.
                     */
                    for(int c=0;c<svLen;c++) {
                        File file=deleteFileList.get(c);
                        new UnixFile(file.getPath()).secureDeleteRecursive();
                    }
                }
            }
            return true;
        } catch(ThreadDeath TD) {
            throw TD;
        } catch(Throwable T) {
            LogFactory.getLogger(CvsManager.class).log(Level.SEVERE, null, T);
            return false;
        }
    }

    public static void start() throws IOException, SQLException {
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();

        synchronized(System.out) {
            if(
                // Nothing is done for these operating systems
                osv!=OperatingSystemVersion.CENTOS_5DOM0_I686
                && osv!=OperatingSystemVersion.CENTOS_5DOM0_X86_64
                // Check config after OS check so config entry not needed
                && AOServDaemonConfiguration.isManagerEnabled(CvsManager.class)
                && cvsManager==null
            ) {
                System.out.print("Starting CvsManager: ");
                AOServConnector conn=AOServDaemon.getConnector();
                cvsManager=new CvsManager();
                conn.getCvsRepositories().getTable().addTableListener(cvsManager, 0);
                System.out.println("Done");
            }
        }
    }

    @Override
    public String getProcessTimerDescription() {
        return "Rebuild CVS";
    }
}