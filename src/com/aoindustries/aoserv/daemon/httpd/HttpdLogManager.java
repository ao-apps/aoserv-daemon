package com.aoindustries.aoserv.daemon.httpd;

/*
 * Copyright 2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.HttpdServer;
import com.aoindustries.aoserv.client.HttpdSite;
import com.aoindustries.aoserv.client.HttpdSiteBind;
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.util.FileUtils;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Manages web site logs.
 *
 * @author  AO Industries, Inc.
 */
class HttpdLogManager {

    /**
     * The directory that logs are stored in.
     */
    static final String LOG_DIR="/logs";

    /**
     * The directory that contains the log rotation scripts.
     */
    static final String LOG_ROTATION_DIR = HttpdServerManager.CONF_DIRECTORY + "/logrotate.d";

    /**
     * Responsible for control of all things in /logs and /etc/httpd/conf/logrotate.d
     *
     * Only called by the already synchronized <code>HttpdManager.doRebuild()</code> method.
     */
    static void doRebuild(
        List<File> deleteFileList,
        Set<HttpdServer> serversNeedingReloaded
    ) throws IOException, SQLException {
        // Used below
        Stat tempStat = new Stat();
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        AOServer aoServer = AOServDaemon.getThisAOServer();

        // Rebuild /logs
        doRebuildLogs(aoServer, deleteFileList, tempStat, serversNeedingReloaded);

        // Rebuild /etc/httpd/conf/logrotate.d files
        doRebuildLogrotate(aoServer, deleteFileList, bout);
    }

    /**
     * Rebuilds the directories under /logs
     */
    private static void doRebuildLogs(
        AOServer aoServer,
        List<File> deleteFileList,
        Stat tempStat,
        Set<HttpdServer> serversNeedingReloaded
    ) throws IOException {
        // Values used below
        final int awstatsUID = aoServer.getLinuxServerAccount(LinuxAccount.AWSTATS).getUID().getID();

        // The log directories that exist but are not used will be removed
        String[] list = new File(LOG_DIR).list();
        Set<String> logDirectories = new HashSet<String>(list.length*4/3+1);
        for(String dirname : list) {
            if(!dirname.equals("lost+found")) logDirectories.add(dirname);
        }

        for(HttpdSite httpdSite : aoServer.getHttpdSites()) {
            int lsgGID = httpdSite.getLinuxServerGroup().getGID().getID();

            // Create the /logs/<site_name> directory
            String siteName = httpdSite.getSiteName();
            UnixFile logDirectory = new UnixFile(LOG_DIR, siteName);
            logDirectory.getStat(tempStat);
            if(!tempStat.exists()) {
                logDirectory.mkdir();
                logDirectory.getStat(tempStat);
            }
            if(tempStat.getUID()!=awstatsUID || tempStat.getGID()!=lsgGID) logDirectory.chown(awstatsUID, lsgGID);
            if(tempStat.getMode()!=0750) logDirectory.setMode(0750);

            // Remove from list so it will not be deleted
            logDirectories.remove(siteName);

            // Make sure each log file referenced under HttpdSiteBinds exists
            List<HttpdSiteBind> hsbs = httpdSite.getHttpdSiteBinds();
            for(HttpdSiteBind hsb : hsbs) {
                // access_log
                String accessLog = hsb.getAccessLog();
                UnixFile accessLogFile = new UnixFile(hsb.getAccessLog());
                if(!accessLogFile.getStat(tempStat).exists()) {
                    // Make sure the parent directory exists
                    UnixFile accessLogParent=accessLogFile.getParent();
                    if(!accessLogParent.getStat(tempStat).exists()) accessLogParent.mkdir(true, 0750, awstatsUID, lsgGID);
                    // Create the empty logfile
                    new FileOutputStream(accessLogFile.getFile(), true).close();
                    accessLogFile.getStat(tempStat);
                    // Need to restart servers if log file created
                    for(HttpdSiteBind hsb2 : hsbs) {
                        if(hsb2.getAccessLog().equals(accessLog)) {
                            serversNeedingReloaded.add(hsb2.getHttpdBind().getHttpdServer());
                        }
                    }
                }
                if(tempStat.getMode()!=0640) accessLogFile.setMode(0640);
                if(tempStat.getUID()!=awstatsUID || tempStat.getGID()!=lsgGID) accessLogFile.chown(awstatsUID, lsgGID);
                
                // error_log
                String errorLog = hsb.getErrorLog();
                UnixFile errorLogFile = new UnixFile(hsb.getErrorLog());
                if(!errorLogFile.getStat(tempStat).exists()) {
                    // Make sure the parent directory exists
                    UnixFile errorLogParent=errorLogFile.getParent();
                    if(!errorLogParent.getStat(tempStat).exists()) errorLogParent.mkdir(true, 0750, awstatsUID, lsgGID);
                    // Create the empty logfile
                    new FileOutputStream(errorLogFile.getFile(), true).close();
                    errorLogFile.getStat(tempStat);
                    // Need to restart servers if log file created
                    for(HttpdSiteBind hsb2 : hsbs) {
                        if(hsb2.getErrorLog().equals(errorLog)) {
                            serversNeedingReloaded.add(hsb2.getHttpdBind().getHttpdServer());
                        }
                    }
                }
                if(tempStat.getMode()!=0640) errorLogFile.setMode(0640);
                if(tempStat.getUID()!=awstatsUID || tempStat.getGID()!=lsgGID) errorLogFile.chown(awstatsUID, lsgGID);
            }
        }

        for(String filename : logDirectories) deleteFileList.add(new File(LOG_DIR, filename));
    }

    /**
     * Rebuilds the per-site logrotation files.
     */
    private static void doRebuildLogrotate(
        AOServer aoServer,
        List<File> deleteFileList,
        ByteArrayOutputStream byteOut
    ) throws IOException, SQLException {
        // The log rotations that exist but are not used will be removed
        String[] list = new File(LOG_ROTATION_DIR).list();
        Set<String> logRotationFiles = new HashSet<String>(list.length*4/3+1);
        for(String filename : list) logRotationFiles.add(filename);

        // Each log file will be only rotated at most once
        Set<String> completedPaths = new HashSet<String>(list.length*4/3+1);

        // For each site, build/rebuild the logrotate.d file as necessary and create any necessary log files
        ChainWriter chainOut=new ChainWriter(byteOut);
        for(HttpdSite site : aoServer.getHttpdSites()) {
            // Write the new file to RAM first
            byteOut.reset();
            boolean wroteOne = false;
            List<HttpdSiteBind> binds=site.getHttpdSiteBinds();

            for(int d=0;d<binds.size();d++) {
                HttpdSiteBind bind=binds.get(d);
                String access_log = bind.getAccessLog();
                // Each unique path is only rotated once
                if(completedPaths.add(access_log)) {
                    // Add to the site log rotation
                    if(wroteOne) chainOut.print(' ');
                    else wroteOne = true;
                    chainOut.print(access_log);
                }
                String error_log = bind.getErrorLog();
                if(completedPaths.add(error_log)) {
                    // Add to the site log rotation
                    if(wroteOne) chainOut.print(' ');
                    else wroteOne = true;
                    chainOut.print(error_log);
                }
            }
            // Finish the file
            if(wroteOne) {
                chainOut.print(" {\n"
                             + "    daily\n"
                             + "    rotate 379\n"
                             + "}\n");
            }
            chainOut.flush();
            byte[] newFileContent=byteOut.toByteArray();

            // Write to disk if file missing or doesn't match
            FileUtils.writeIfNeeded(
                newFileContent,
                null,
                new UnixFile(LOG_ROTATION_DIR, site.getSiteName()),
                UnixFile.ROOT_UID,
                site.getLinuxServerGroup().getGID().getID(),
                0640
            );

            // Make sure the newly created or replaced log rotation file is not removed
            logRotationFiles.remove(site.getSiteName());
        }

        // Remove extra filenames
        for(String extraFilename : logRotationFiles) deleteFileList.add(new File(LOG_ROTATION_DIR, extraFilename));
    }
}
