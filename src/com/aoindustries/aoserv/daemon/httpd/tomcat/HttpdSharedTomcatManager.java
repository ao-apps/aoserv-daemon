package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOSHCommand;
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.HttpdSharedTomcat;
import com.aoindustries.aoserv.client.HttpdTomcatVersion;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.httpd.HttpdSiteManager;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Manages HttpdSharedTomcat configurations.
 *
 * @author  AO Industries, Inc.
 */
public abstract class HttpdSharedTomcatManager<TC extends TomcatCommon> {

    /**
     * Gets the specific manager for one version of shared Tomcat.
     */
    static HttpdSharedTomcatManager<? extends TomcatCommon> getInstance(HttpdSharedTomcat sharedTomcat) throws IOException, SQLException {
        AOServConnector connector=AOServDaemon.getConnector();

        HttpdTomcatVersion htv=sharedTomcat.getHttpdTomcatVersion();
        if(htv.isTomcat3_1(connector)) return new HttpdSharedTomcatManager_3_1(sharedTomcat);
        if(htv.isTomcat3_2_4(connector)) return new HttpdSharedTomcatManager_3_2_4(sharedTomcat);
        if(htv.isTomcat4_1_X(connector)) return new HttpdSharedTomcatManager_4_1_X(sharedTomcat);
        if(htv.isTomcat5_5_X(connector)) return new HttpdSharedTomcatManager_5_5_X(sharedTomcat);
        if(htv.isTomcat6_0_X(connector)) return new HttpdSharedTomcatManager_6_0_X(sharedTomcat);
        throw new SQLException("Unsupported version of shared Tomcat: "+htv.getTechnologyVersion(connector).getVersion()+" on "+sharedTomcat);
    }

    /**
     * Responsible for control of all things in /wwwgroup
     *
     * Only called by the already synchronized <code>HttpdManager.doRebuild()</code> method.
     */
    public static void doRebuild(
	List<File> deleteFileList,
        Set<HttpdSharedTomcat> sharedTomcatsNeedingRestarted
    ) throws IOException, SQLException {
        // Get values used in the rest of the method.
        HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
        AOServer aoServer = AOServDaemon.getThisAOServer();
        Stat tempStat = new Stat();

        // The www group directories that exist but are not used will be removed
        UnixFile wwwgroupDirectory = new UnixFile(osConfig.getHttpdSharedTomcatsDirectory());
        String[] list = wwwgroupDirectory.list();
        Set<String> wwwgroupRemoveList = new HashSet<String>(list.length*4/3+1);
        for (String dirname : list) {
            if(
                !dirname.equals("lost+found")
                && !dirname.equals("aquota.user")
            ) {
                wwwgroupRemoveList.add(dirname);
            }
        }

        // Iterate through each shared Tomcat
        for(HttpdSharedTomcat sharedTomcat : aoServer.getHttpdSharedTomcats()) {
            final HttpdSharedTomcatManager<?> manager = getInstance(sharedTomcat);

            // Create and fill in any incomplete installations.
            final String tomcatName = sharedTomcat.getName();
            UnixFile sharedTomcatDirectory = new UnixFile(wwwgroupDirectory, tomcatName, false);
            manager.buildSharedTomcatDirectory(sharedTomcatDirectory, deleteFileList, sharedTomcatsNeedingRestarted);
            wwwgroupRemoveList.remove(tomcatName);
        }

        // Stop, disable, and mark files for deletion
        for (String tomcatName : wwwgroupRemoveList) {
            UnixFile removeFile = new UnixFile(wwwgroupDirectory, tomcatName, false);
            // Stop and disable any daemons
            stopAndDisableDaemons(removeFile, tempStat);
            // Only remove the directory when not used by a home directory
            if(!aoServer.isHomeUsed(removeFile.getPath())) deleteFileList.add(removeFile.getFile());
        }
    }
    
    /**
     * Stops any daemons that should not be running.
     * Restarts any sites that need restarted.
     * Starts any daemons that should be running.
     * 
     * Makes calls with a one-minute time-out.
     * Logs errors on calls as warnings, continues to next site.
     *
     * Only called by the already synchronized <code>HttpdManager.doRebuild()</code> method.
     */
    public static void stopStartAndRestart(Set<HttpdSharedTomcat> sharedTomcatsNeedingRestarted) throws IOException, SQLException {
        for(HttpdSharedTomcat sharedTomcat : AOServDaemon.getThisAOServer().getHttpdSharedTomcats()) {
            final HttpdSharedTomcatManager manager = getInstance(sharedTomcat);

            Callable<Object> commandCallable;
            if(sharedTomcat.getDisableLog()==null && !sharedTomcat.getHttpdTomcatSharedSites().isEmpty()) {
                // Enabled and has sites, start or restart
                if(sharedTomcatsNeedingRestarted.contains(sharedTomcat)) {
                    commandCallable = new Callable<Object>() {
                        public Object call() {
                            manager.restart();
                            return null;
                        }
                    };
                } else {
                    commandCallable = new Callable<Object>() {
                        public Object call() {
                            manager.start();
                            return null;
                        }
                    };
                }
            } else {
                // Disabled or has no sites, can only stop if needed
                commandCallable = new Callable<Object>() {
                    public Object call() {
                        manager.stop();
                        return null;
                    }
                };
            }
            try {
                Future commandFuture = AOServDaemon.executorService.submit(commandCallable);
                commandFuture.get(1, TimeUnit.MINUTES);
            } catch(InterruptedException err) {
                AOServDaemon.reportWarning(err, null);
            } catch(ExecutionException err) {
                AOServDaemon.reportWarning(err, null);
            } catch(TimeoutException err) {
                AOServDaemon.reportWarning(err, null);
            }
        }
    }

    /**
     * @see  HttpdSiteManager#stopAndDisableDaemons
     */
    private static void stopAndDisableDaemons(UnixFile sharedTomcatDirectory, Stat tempStat) throws IOException, SQLException {
        HttpdSiteManager.stopAndDisableDaemons(sharedTomcatDirectory, tempStat);
    }

    final protected HttpdSharedTomcat sharedTomcat;

    HttpdSharedTomcatManager(HttpdSharedTomcat sharedTomcat) {
        this.sharedTomcat = sharedTomcat;
    }
    
    /**
     * Gets the auto-mode warning for this website for use in XML files.  This
     * may be used on any config files that a user would be tempted to change
     * directly.
     */
    String getAutoWarningXml() throws IOException, SQLException {
        return
            "<!--\n"
            + "  Warning: This file is automatically created by HttpdManager.  Any manual changes\n"
            + "  to this file will be overwritten.  Please set the is_manual flag for this multi-site\n"
            + "  JVM to be able to make permanent changes to this file.\n"
            + "\n"
            + "  Control Panel: https://www.aoindustries.com/clientarea/control/httpd/HttpdSharedTomcatCP.ao?pkey="+sharedTomcat.getPkey()+"\n"
            + "\n"
            + "  AOSH: "+AOSHCommand.SET_HTTPD_SHARED_TOMCAT_IS_MANUAL+" "+sharedTomcat.getName()+' '+sharedTomcat.getAOServer().getHostname()+" true\n"
            + "\n"
            + "  support@aoindustries.com\n"
            + "  (866) 270-6195\n"
            + "-->\n"
        ;
    }

    /**
     * Gets the auto-mode warning using Unix-style comments (#).  This
     * may be used on any config files that a user would be tempted to change
     * directly.
     */
    String getAutoWarningUnix() throws IOException, SQLException {
        return
            "#\n"
            + "# Warning: This file is automatically created by HttpdManager.  Any manual changes\n"
            + "# to this file will be overwritten.  Please set the is_manual flag for this multi-site\n"
            + "# JVM to be able to make permanent changes to this file.\n"
            + "#\n"
            + "# Control Panel: https://www.aoindustries.com/clientarea/control/httpd/HttpdSharedTomcatCP.ao?pkey="+sharedTomcat.getPkey()+"\n"
            + "#\n"
            + "# AOSH: "+AOSHCommand.SET_HTTPD_SHARED_TOMCAT_IS_MANUAL+" "+sharedTomcat.getName()+' '+sharedTomcat.getAOServer().getHostname()+" true\n"
            + "#\n"
            + "# support@aoindustries.com\n"
            + "# (866) 270-6195\n"
            + "#\n"
        ;
    }

    /**
     * (Re)builds the shared tomcat directory, from scratch if it doesn't exist.
     * Creates, recreates, or removes resources as necessary.
     * Also performs an automatic upgrade of resources if appropriate for the shared tomcat.
     * Also reconfigures any config files within the directory if appropriate for the shared tomcat type and settings.
     * If this shared tomcat or other shared tomcats needs to be restarted due to changes in the files, add to <code>sharedTomcatsNeedingRestarted</code>.
     * Any files under sharedTomcatDirectory that need to be updated to enable/disable this site should be changed.
     * Actual process start/stop will be performed later in <code>disableEnableAndRestart</code>.
     * 
     * <ol>
     *   <li>If <code>sharedTomcatDirectory</code> doesn't exist, create it as root with mode 0700</li>
     *   <li>If <code>sharedTomcatDirectory</code> owned by root, do full pass (this implies manual=false regardless of setting)</li>
     *   <li>Otherwise, make necessary config changes or upgrades while adhering to the manual flag</li>
     * </ol>
     */
    abstract void buildSharedTomcatDirectory(UnixFile sharedTomcatDirectory, List<File> deleteFileList, Set<HttpdSharedTomcat> sharedTomcatsNeedingRestarted) throws IOException, SQLException;

    /**
     * Stops all processes for this shared tomcat if it is running.
     */
    public void stop() {
        // TODO
    }

    /**
     * Starts all processes for this shared tomcat if it is not running.
     */
    public void start() {
        // TODO
    }

    /**
     * Restarts all processes for this shared tomcat if running.
     * If not already running, starts the services.
     */
    public void restart() {
        // TODO
    }

    abstract TC getTomcatCommon();

    /**
     * Only called by the already synchronized <code>HttpdManager.doRebuild()</code> method.
     */
    /*private static void moveMeTODO2(Set<HttpdSharedTomcat> sharedTomcatsNeedingRestarted) throws IOException, SQLException {
        Stat tempStat = new Stat();
        AOServer aoServer = AOServDaemon.getThisAOServer();
        List<HttpdSharedTomcat> hsts=aoServer.getHttpdSharedTomcats();
        for(int c=0;c<hsts.size();c++) {
            HttpdSharedTomcat hst=hsts.get(c);
            String tomcatName=hst.getName();
            UnixFile pidUF=new UnixFile(HttpdSharedTomcat.WWW_GROUP_DIR+'/'+tomcatName+"/var/run/tomcat.pid");
            UnixFile daemonUF=new UnixFile(HttpdSharedTomcat.WWW_GROUP_DIR+'/'+tomcatName+"/daemon/tomcat");
            if(hst.getDisableLog()==null) {
                // Enabled, make sure running and auto
                if(sharedTomcatsNeedingRestarted.contains(hst) || !pidUF.getStat(tempStat).exists()) startSharedTomcat(hst);
                if(!daemonUF.getStat(tempStat).exists()) enableSharedTomcat(hst, tempStat);
            } else {
                // Disabled, make sure stopped and not auto
                if(daemonUF.getStat(tempStat).exists()) disableSharedTomcat(hst, tempStat);
                if(pidUF.getStat(tempStat).exists()) stopSharedTomcat(hst, tempStat);
            }
        }
    }*/

    /**
     * TODO: Is this used?
     */
    /*static void startSharedTomcat(HttpdSharedTomcat hst) throws IOException, SQLException {
        LinuxServerAccount lsa=hst.getLinuxServerAccount();
        AOServDaemon.suexec(
            lsa.getLinuxAccount().getUsername().getUsername(),
            HttpdSharedTomcat.WWW_GROUP_DIR+'/'+hst.getName()+"/bin/tomcat start",
            0
        );
    }*/

    /**
     * TODO: Is this used?
     */
    /*static void stopSharedTomcat(HttpdSharedTomcat hst, Stat tempStat) throws IOException, SQLException {
        stopSharedTomcat(hst.getLinuxServerAccount(), hst.getName(), tempStat);
    }*/
    
    /**
     * TODO: Is this used?
     */
    /*private static void stopSharedTomcat(String tomcatName, Stat tempStat) throws IOException, SQLException {
        UnixFile uf=new UnixFile(HttpdSharedTomcat.WWW_GROUP_DIR+'/'+tomcatName+"/daemon");
        uf.getStat(tempStat);
        if(tempStat.exists()) {
            int uid=tempStat.getUID();
            LinuxServerAccount lsa=AOServDaemon.getThisAOServer().getLinuxServerAccount(uid);
            if(lsa!=null) {
                stopSharedTomcat(lsa, tomcatName, tempStat);
            }
        }
    }*/

    /**
     * TODO: Is this used?
     */
    /*private static void stopSharedTomcat(LinuxServerAccount lsa, String tomcatName, Stat tempStat) throws IOException, SQLException {
        UnixFile pidUF=new UnixFile(HttpdSharedTomcat.WWW_GROUP_DIR+'/'+tomcatName+"/var/run/tomcat.pid");
        if(pidUF.getStat(tempStat).exists()) {
            AOServDaemon.suexec(
                lsa.getLinuxAccount().getUsername().getUsername(),
                HttpdSharedTomcat.WWW_GROUP_DIR+'/'+tomcatName+"/bin/tomcat stop",
                0
            );
        }
    }*/

    /**
     * TODO: Is this used?
     */
    /*private static void enableSharedTomcat(HttpdSharedTomcat hst, Stat tempStat) throws IOException, SQLException {
        UnixFile uf=new UnixFile(HttpdSharedTomcat.WWW_GROUP_DIR+'/'+hst.getName()+"/daemon/tomcat");
        if(!uf.getStat(tempStat).exists()) uf.symLink("../bin/tomcat").chown(
            hst.getLinuxServerAccount().getUID().getID(),
            hst.getLinuxServerGroup().getGID().getID()
        );
    }*/

    /**
     * TODO: Is this used?
     */
    /*private static void disableSharedTomcat(HttpdSharedTomcat hst, Stat tempStat) throws IOException {
        disableSharedTomcat(hst.getName(), tempStat);
    }*/

    /**
     * TODO: Is this used?
     */
    /*private static void disableSharedTomcat(String tomcatName, Stat tempStat) throws IOException {
        UnixFile uf=new UnixFile(HttpdSharedTomcat.WWW_GROUP_DIR+'/'+tomcatName+"/daemon/tomcat");
        if(uf.getStat(tempStat).exists()) uf.delete();
    }*/
}
