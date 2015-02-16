/*
 * Copyright 2008-2013, 2014, 2015 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd;

import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.HttpdServer;
import com.aoindustries.aoserv.client.HttpdSite;
import com.aoindustries.aoserv.client.HttpdSiteBind;
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.encoding.ChainWriter;
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
    static final String LOG_ROTATION_DIR_REDHAT = HttpdServerManager.CONF_DIRECTORY + "/logrotate.d";
    static final String LOG_ROTATION_DIR_CENTOS = HttpdServerManager.CONF_DIRECTORY + "/logrotate.sites";
    static final String SERVER_LOG_ROTATION_DIR_CENTOS = HttpdServerManager.CONF_DIRECTORY + "/logrotate.servers";

    /**
     * The directory that contains the per-apache-instance logs.
     */
    private static final UnixFile serverLogDir = new UnixFile("/var/log/httpd");

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

        // Rebuild /etc/httpd/conf/logrotate.(d|sites|servers) files
        doRebuildLogrotate(aoServer, deleteFileList, bout);
        
        // Rebuild /var/log/httpd
        doRebuildVarLogHttpd(aoServer, deleteFileList);
        
        // Other filesystem fixes related to logging
        fixFilesystem(deleteFileList);
    }

    /**
     * Rebuilds the /var/log/httpd directory
     */
    private static void doRebuildVarLogHttpd(AOServer aoServer, List<File> deleteFileList) throws IOException, SQLException {
        HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
        if(osConfig==HttpdOperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64) {

            // Remove any symlink at /var/log/httpd
            Stat serverLogDirStat = serverLogDir.getStat();
            if(serverLogDirStat.exists() && serverLogDirStat.isSymLink()) serverLogDir.delete();

            // Create /var/log/httpd if missing
            DaemonFileUtils.mkdir(serverLogDir, 0700, UnixFile.ROOT_UID, UnixFile.ROOT_GID);

            // Create all /var/log/httpd/* directories
            List<HttpdServer> hss = aoServer.getHttpdServers();
            Set<String> dontDeleteFilenames = new HashSet<>(hss.size()*4/3+1);
            for(HttpdServer hs : hss) {
                String filename = "httpd"+hs.getNumber();
                dontDeleteFilenames.add(filename);
                DaemonFileUtils.mkdir(new UnixFile(serverLogDir, filename, false), 0700, UnixFile.ROOT_UID, UnixFile.ROOT_GID);
            }

            // Remove any extra
            for(String filename : serverLogDir.list()) {
                if(!dontDeleteFilenames.contains(filename)) {
                    if(!filename.startsWith("suexec.log")) deleteFileList.add(new UnixFile(serverLogDir, filename, false).getFile());
                }
            }
        }
    }

    private static final UnixFile[] centOsAlwaysDelete = {
        new UnixFile("/etc/logrotate.d/httpd"),
        new UnixFile("/etc/logrotate.d/httpd1"),
        new UnixFile("/etc/logrotate.d/httpd2"),
        new UnixFile("/etc/logrotate.d/httpd3"),
        new UnixFile("/etc/logrotate.d/httpd4"),
        new UnixFile("/etc/logrotate.d/httpd5"),
        new UnixFile("/etc/logrotate.d/httpd6"),
        new UnixFile("/etc/httpd/conf/logrotate.d"),
        new UnixFile("/opt/aoserv-daemon/logrotate.d/httpd1"),
        new UnixFile("/opt/aoserv-daemon/logrotate.d/httpd2"),
        new UnixFile("/opt/aoserv-daemon/logrotate.d/httpd3"),
        new UnixFile("/opt/aoserv-daemon/logrotate.d/httpd4"),
        new UnixFile("/opt/aoserv-daemon/logrotate.d/httpd5"),
        new UnixFile("/opt/aoserv-daemon/logrotate.d/httpd6")
    };

    /**
     * Fixes any filesystem stuff related to logging.
     */
    private static void fixFilesystem(List<File> deleteFileList) throws IOException, SQLException {
        HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
        if(osConfig==HttpdOperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64) {
            Stat tempStat = new Stat();
            // Make sure these files don't exist.  They may be due to upgrades or a
            // result of RPM installs.
            for(UnixFile uf : centOsAlwaysDelete) {
                if(uf.getStat(tempStat).exists()) deleteFileList.add(uf.getFile());
            }
        }
    }

    /**
     * Rebuilds the directories under /logs
     */
    private static void doRebuildLogs(
        AOServer aoServer,
        List<File> deleteFileList,
        Stat tempStat,
        Set<HttpdServer> serversNeedingReloaded
    ) throws IOException, SQLException {
        // Values used below
        final int awstatsUID = aoServer.getLinuxServerAccount(LinuxAccount.AWSTATS).getUid().getID();

        // The log directories that exist but are not used will be removed
        String[] list = new File(LOG_DIR).list();
        Set<String> logDirectories = new HashSet<>(list.length*4/3+1);
        for(String dirname : list) {
            if(!dirname.equals("lost+found")) logDirectories.add(dirname);
        }

        for(HttpdSite httpdSite : aoServer.getHttpdSites()) {
            int lsgGID = httpdSite.getLinuxServerGroup().getGid().getID();

            // Create the /logs/<site_name> directory
            String siteName = httpdSite.getSiteName();
            UnixFile logDirectory = new UnixFile(LOG_DIR, siteName);
            logDirectory.getStat(tempStat);
            if(!tempStat.exists()) {
                logDirectory.mkdir();
                logDirectory.getStat(tempStat);
            }
            if(tempStat.getUid()!=awstatsUID || tempStat.getGid()!=lsgGID) logDirectory.chown(awstatsUID, lsgGID);
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
                if(tempStat.getUid()!=awstatsUID || tempStat.getGid()!=lsgGID) accessLogFile.chown(awstatsUID, lsgGID);
                
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
                if(tempStat.getUid()!=awstatsUID || tempStat.getGid()!=lsgGID) errorLogFile.chown(awstatsUID, lsgGID);
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
        final HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
        final String siteLogRotationDir;
        final String serverLogRotationDir;
        switch(osConfig) {
            case REDHAT_ES_4_X86_64 :
                siteLogRotationDir = LOG_ROTATION_DIR_REDHAT;
                serverLogRotationDir = null;
                break;
            case CENTOS_5_I686_AND_X86_64 :
                siteLogRotationDir = LOG_ROTATION_DIR_CENTOS;
                serverLogRotationDir = SERVER_LOG_ROTATION_DIR_CENTOS;
                break;
            default :
                throw new AssertionError("Unexpected value for osConfig: "+osConfig);
        }

        // Create directory if missing
        DaemonFileUtils.mkdir(siteLogRotationDir, 0700, UnixFile.ROOT_UID, UnixFile.ROOT_GID);

        // The log rotations that exist but are not used will be removed
        String[] list = new File(siteLogRotationDir).list();
        Set<String> logRotationFiles = new HashSet<>(list.length*4/3+1);
        for(String filename : list) logRotationFiles.add(filename);

        // Each log file will be only rotated at most once
        Set<String> completedPaths = new HashSet<>(list.length*4/3+1);

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
                             + "    missingok\n"
                             + "    daily\n"
                             + "    rotate 379\n"
                             + "}\n");
            }
            chainOut.flush();
            byte[] newFileContent=byteOut.toByteArray();

            // Write to disk if file missing or doesn't match
            DaemonFileUtils.writeIfNeeded(
                newFileContent,
                null,
                new UnixFile(siteLogRotationDir, site.getSiteName()),
                UnixFile.ROOT_UID,
                site.getLinuxServerGroup().getGid().getID(),
                0640
            );

            // Make sure the newly created or replaced log rotation file is not removed
            logRotationFiles.remove(site.getSiteName());
        }

        // Remove extra filenames
        for(String extraFilename : logRotationFiles) deleteFileList.add(new File(siteLogRotationDir, extraFilename));
        
        if(serverLogRotationDir!=null) {
            // Create directory if missing
            DaemonFileUtils.mkdir(serverLogRotationDir, 0700, UnixFile.ROOT_UID, UnixFile.ROOT_GID);
            
            // The log rotations that exist but are not used will be removed
            logRotationFiles.clear();
            for(String filename : new File(serverLogRotationDir).list()) logRotationFiles.add(filename);
            
            boolean isFirst = true;
            for(HttpdServer hs : aoServer.getHttpdServers()) {
                int num = hs.getNumber();
                String filename = "httpd"+num;
                logRotationFiles.remove(filename);
                
                // Build to RAM first
                byteOut.reset();
                ChainWriter out = new ChainWriter(byteOut);
                try {
                    out.write("/var/log/httpd/httpd").print(num).print("/access_log {\n"
                            + "    missingok\n"
                            + "    daily\n"
                            + "    rotate 379\n"
                            + "}\n"
                            + "/var/log/httpd/httpd").print(num).print("/error_log {\n"
                            + "    missingok\n"
                            + "    daily\n"
                            + "    rotate 379\n"
                            + "}\n"
                            + "/var/log/httpd/httpd").print(num).print("/jserv.log {\n"
                            + "    missingok\n"
                            + "    daily\n"
                            + "    rotate 379\n"
                            + "}\n"
                            + "/var/log/httpd/httpd").print(num).print("/rewrite.log {\n"
                            + "    missingok\n"
                            + "    daily\n"
                            + "    rotate 379\n"
                            + "}\n"
                            + "/var/log/httpd/httpd").print(num).print("/mod_jk.log {\n"
                            + "    missingok\n"
                            + "    daily\n"
                            + "    rotate 379\n"
                            + "}\n"
                            + "/var/log/httpd/httpd").print(num).print("/ssl_engine_log {\n"
                            + "    missingok\n"
                            + "    daily\n"
                            + "    rotate 379\n"
                            + "}\n");
                    if(isFirst) {
                        out.print("/var/log/httpd").print(num).print("/suexec.log {\n"
                                + "    missingok\n"
                                + "    daily\n"
                                + "    rotate 379\n"
                                + "}\n");
                        isFirst = false;
                    }
                } finally {
                    out.close();
                }
                DaemonFileUtils.writeIfNeeded(
                    byteOut.toByteArray(),
                    null,
                    new UnixFile(serverLogRotationDir+"/"+filename),
                    UnixFile.ROOT_UID,
                    UnixFile.ROOT_GID,
                    0600
                );
            }

            // Remove extra filenames
            for(String extraFilename : logRotationFiles) deleteFileList.add(new File(serverLogRotationDir, extraFilename));
        }
    }
}
