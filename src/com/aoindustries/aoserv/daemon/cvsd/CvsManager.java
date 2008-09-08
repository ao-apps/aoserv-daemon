package com.aoindustries.aoserv.daemon.cvsd;

/*
 * Copyright 2002-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.aoserv.daemon.backup.*;
import com.aoindustries.aoserv.daemon.util.*;
import com.aoindustries.io.*;
import com.aoindustries.io.unix.*;
import com.aoindustries.profiler.*;
import com.aoindustries.table.*;
import com.aoindustries.util.*;
import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * Handles the building of CVS repositories, xinetd configs are handled by XinetdManager.
 *
 * @author  AO Industries, Inc.
 */
final public class CvsManager extends BuilderThread {

    public static final String CVS_DIRECTORY="/var/cvs";

    private static CvsManager cvsManager;

    private CvsManager() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, CvsManager.class, "<init>()", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, CvsManager.class, "doRebuild()", null);
        try {
            AOServConnector conn=AOServDaemon.getConnector();
            AOServer thisAOServer=AOServDaemon.getThisAOServer();

            int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
            if(
                osv!=OperatingSystemVersion.MANDRAKE_10_1_I586
                && osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            synchronized(rebuildLock) {
                // Get a list of all the directories in /var/cvs
                File cvsDir=new File(CVS_DIRECTORY);
                String[] list=cvsDir.list();
                int listLen=list.length;
                List<String> sv=new SortedArrayList<String>(listLen);
                for(int c=0;c<listLen;c++) sv.add(CVS_DIRECTORY+'/'+list[c]);

                // Add each directory that doesn't exist, fix permissions and ownerships, too
                // while removing existing directories from sv
                for(CvsRepository cvs : thisAOServer.getCvsRepositories()) {
                    String path=cvs.getPath();
                    UnixFile cvsUF=new UnixFile(path);
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
                    LinuxServerAccount lsa=cvs.getLinuxServerAccount();
                    int uid=lsa.getUID().getID();
                    int gid=cvs.getLinuxServerGroup().getGID().getID();
                    if(uid!=cvsStat.getUID() || gid!=cvsStat.getGID()) {
                        cvsUF.chown(uid, gid);
                        cvsUF.getStat(cvsStat);
                    }
                    // Init if needed
                    UnixFile cvsRootUF=new UnixFile(cvsUF, "CVSROOT", false);
                    if(!cvsRootUF.getStat().exists()) {
                        AOServDaemon.suexec(
                            lsa.getLinuxAccount().getUsername().getUsername(),
                            "/usr/bin/cvs -d "+path+" init"
                        );
                    }
                    // Remove from list
                    if(sv.contains(path)) sv.remove(path);
                }

                /*
                 * Back up the files scheduled for removal.
                 */
                int svLen=sv.size();
                if(svLen>0) {
                    List<File> deleteFileList=new ArrayList<File>(svLen);
                    for(int c=0;c<svLen;c++) deleteFileList.add(new File(sv.get(c)));

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
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void start() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, CvsManager.class, "start()", null);
        try {
            if(AOServDaemonConfiguration.isManagerEnabled(CvsManager.class) && cvsManager==null) {
                synchronized(System.out) {
                    if(cvsManager==null) {
                        System.out.print("Starting CvsManager: ");
                        AOServConnector conn=AOServDaemon.getConnector();
                        cvsManager=new CvsManager();
                        conn.cvsRepositories.addTableListener(cvsManager, 0);
                        System.out.println("Done");
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public String getProcessTimerDescription() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, CvsManager.class, "getProcessTimerDescription()", null);
        try {
            return "Rebuild CVS";
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
}