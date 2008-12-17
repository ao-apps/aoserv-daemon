package com.aoindustries.aoserv.daemon.httpd;

/*
 * Copyright 2000-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.HttpdServer;
import com.aoindustries.aoserv.client.HttpdSharedTomcat;
import com.aoindustries.aoserv.client.HttpdSite;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.backup.BackupManager;
import com.aoindustries.aoserv.daemon.httpd.tomcat.HttpdSharedTomcatManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Manages all configuration and control over HTTP-related systems.
 *
 * @author  AO Industries, Inc.
 */
final public class HttpdManager extends BuilderThread {

    private static HttpdManager httpdManager;

    private HttpdManager() {
    }

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        synchronized(rebuildLock) {
            // Get some variables that will be used throughout the method
            List<File> deleteFileList=new ArrayList<File>();
            Set<HttpdSharedTomcat> sharedTomcatsNeedingRestarted = new HashSet<HttpdSharedTomcat>();
            Set<HttpdSite> sitesNeedingRestarted = new HashSet<HttpdSite>();
            Set<HttpdServer> serversNeedingReloaded = new HashSet<HttpdServer>();

            // Rebuild file system objects
            HttpdLogManager.doRebuild(deleteFileList, serversNeedingReloaded);
            HttpdSharedTomcatManager.doRebuild(deleteFileList, sharedTomcatsNeedingRestarted);
            HttpdSiteManager.doRebuild(deleteFileList, sitesNeedingRestarted, sharedTomcatsNeedingRestarted);
            HttpdServerManager.doRebuild(deleteFileList, serversNeedingReloaded);

            // stop, disable, enable, start, and restart necessary processes
            HttpdSharedTomcatManager.stopStartAndRestart(sharedTomcatsNeedingRestarted);
            HttpdSiteManager.stopStartAndRestart(sitesNeedingRestarted);

            // Reload the Apache server configs
            HttpdServerManager.reloadConfigs(serversNeedingReloaded);

            // Back-up and delete the files scheduled for removal
            BackupManager.backupAndDeleteFiles(deleteFileList);
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
                && AOServDaemonConfiguration.isManagerEnabled(HttpdManager.class)
                && httpdManager==null
            ) {
                System.out.print("Starting HttpdManager: ");
                AOServConnector connector=AOServDaemon.getConnector();
                httpdManager=new HttpdManager();
                connector.httpdBinds.addTableListener(httpdManager, 0);
                connector.httpdServers.addTableListener(httpdManager, 0);
                connector.httpdJBossSites.addTableListener(httpdManager, 0);
                connector.httpdSharedTomcats.addTableListener(httpdManager, 0);
                connector.httpdSites.addTableListener(httpdManager, 0);
                connector.httpdSiteAuthenticatedLocationTable.addTableListener(httpdManager, 0);
                connector.httpdSiteBinds.addTableListener(httpdManager, 0);
                connector.httpdSiteURLs.addTableListener(httpdManager, 0);
                connector.httpdStaticSites.addTableListener(httpdManager, 0);
                connector.httpdTomcatContexts.addTableListener(httpdManager, 0);
                connector.httpdTomcatDataSources.addTableListener(httpdManager, 0);
                connector.httpdTomcatParameters.addTableListener(httpdManager, 0);
                connector.httpdTomcatSites.addTableListener(httpdManager, 0);
                connector.httpdTomcatSharedSites.addTableListener(httpdManager, 0);
                connector.httpdTomcatStdSites.addTableListener(httpdManager, 0);
                connector.httpdWorkers.addTableListener(httpdManager, 0);
                connector.ipAddresses.addTableListener(httpdManager, 0);
                connector.linuxServerAccounts.addTableListener(httpdManager, 0);
                connector.netBinds.addTableListener(httpdManager, 0);
                System.out.println("Done");
            }
        }
    }

    public static void waitForRebuild() {
        if(httpdManager!=null) httpdManager.waitForBuild();
    }

    public String getProcessTimerDescription() {
        return "Rebuild HTTPD";
    }

    @Override
    public long getProcessTimerMaximumTime() {
        return 15L*60*1000;
    }
}
