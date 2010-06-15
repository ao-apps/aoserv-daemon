package com.aoindustries.aoserv.daemon.httpd;

/*
 * Copyright 2000-2010 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.HttpdSite;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
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
import java.util.logging.Level;

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
    protected boolean doRebuild() {
        try {
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
            return true;
        } catch(ThreadDeath TD) {
            throw TD;
        } catch(Throwable T) {
            LogFactory.getLogger(HttpdManager.class).log(Level.SEVERE, null, T);
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
                && AOServDaemonConfiguration.isManagerEnabled(HttpdManager.class)
                && httpdManager==null
            ) {
                System.out.print("Starting HttpdManager: ");
                AOServConnector<?,?> connector=AOServDaemon.getConnector();
                httpdManager=new HttpdManager();
                connector.getHttpdBinds().getTable().addTableListener(httpdManager, 0);
                connector.getHttpdServers().getTable().addTableListener(httpdManager, 0);
                connector.getHttpdJBossSites().getTable().addTableListener(httpdManager, 0);
                connector.getHttpdSharedTomcats().getTable().addTableListener(httpdManager, 0);
                connector.getHttpdSites().getTable().addTableListener(httpdManager, 0);
                connector.getHttpdSiteAuthenticatedLocationTable().getTable().addTableListener(httpdManager, 0);
                connector.getHttpdSiteBinds().getTable().addTableListener(httpdManager, 0);
                connector.getHttpdSiteURLs().getTable().addTableListener(httpdManager, 0);
                connector.getHttpdStaticSites().getTable().addTableListener(httpdManager, 0);
                connector.getHttpdTomcatContexts().getTable().addTableListener(httpdManager, 0);
                connector.getHttpdTomcatDataSources().getTable().addTableListener(httpdManager, 0);
                connector.getHttpdTomcatParameters().getTable().addTableListener(httpdManager, 0);
                connector.getHttpdTomcatSites().getTable().addTableListener(httpdManager, 0);
                connector.getHttpdTomcatSharedSites().getTable().addTableListener(httpdManager, 0);
                connector.getHttpdTomcatStdSites().getTable().addTableListener(httpdManager, 0);
                connector.getHttpdWorkers().getTable().addTableListener(httpdManager, 0);
                connector.getIpAddresses().getTable().addTableListener(httpdManager, 0);
                connector.getLinuxAccounts().getTable().addTableListener(httpdManager, 0);
                connector.getNetBinds().getTable().addTableListener(httpdManager, 0);
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
