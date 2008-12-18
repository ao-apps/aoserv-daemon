package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2007-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.HttpdJBossSite;
import com.aoindustries.aoserv.client.HttpdSharedTomcat;
import com.aoindustries.aoserv.client.HttpdSite;
import com.aoindustries.aoserv.client.HttpdTomcatContext;
import com.aoindustries.aoserv.client.HttpdTomcatSharedSite;
import com.aoindustries.aoserv.client.HttpdTomcatSite;
import com.aoindustries.aoserv.client.HttpdTomcatStdSite;
import com.aoindustries.aoserv.client.HttpdWorker;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.httpd.HttpdSiteManager;
import com.aoindustries.aoserv.daemon.httpd.StopStartable;
import com.aoindustries.aoserv.daemon.httpd.jboss.HttpdJBossSiteManager;
import com.aoindustries.aoserv.daemon.util.FileUtils;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Manages HttpdTomcatSite configurations.
 *
 * @author  AO Industries, Inc.
 */
public abstract class HttpdTomcatSiteManager<TC extends TomcatCommon> extends HttpdSiteManager implements StopStartable {

    /**
     * Gets the specific manager for one type of web site.
     */
    public static HttpdTomcatSiteManager<? extends TomcatCommon> getInstance(HttpdTomcatSite tomcatSite) throws IOException, SQLException {
        HttpdTomcatStdSite stdSite=tomcatSite.getHttpdTomcatStdSite();
        if(stdSite!=null) return HttpdTomcatStdSiteManager.getInstance(stdSite);

        HttpdJBossSite jbossSite = tomcatSite.getHttpdJBossSite();
        if(jbossSite!=null) return HttpdJBossSiteManager.getInstance(jbossSite);

        HttpdTomcatSharedSite shrSite = tomcatSite.getHttpdTomcatSharedSite();
        if(shrSite!=null) return HttpdTomcatSharedSiteManager.getInstance(shrSite);

        throw new SQLException("HttpdTomcatSite must be one of HttpdTomcatStdSite, HttpdJBossSite, or HttpdTomcatSharedSite: "+tomcatSite);
    }

    final protected HttpdTomcatSite tomcatSite;

    protected HttpdTomcatSiteManager(HttpdTomcatSite tomcatSite) {
        super(tomcatSite.getHttpdSite());
        this.tomcatSite = tomcatSite;
    }

    protected boolean enableCgi() {
        return true;
    }

    protected boolean enablePhp() {
        return true;
    }
    
    public boolean enableAnonymousFtp() {
        return true;
    }

    public abstract TC getTomcatCommon();

    /**
     * In addition to the standard values, also protects the /WEB-INF/ and /META-INF/ directories of all contexts.
     * This is only done when the "use_apache" flag is on.  Otherwise, Tomcat should protect the necessary
     * paths.
     */
    @Override
    public SortedSet<Location> getRejectedLocations() {
        // If not using Apache, let Tomcat do its own protection
        SortedSet<Location> standardRejectedLocations = super.getRejectedLocations();
        if(!tomcatSite.useApache()) return standardRejectedLocations;

        SortedSet<Location> rejectedLocations = new TreeSet<Location>(standardRejectedLocations);
        for(HttpdTomcatContext htc : tomcatSite.getHttpdTomcatContexts()) {
            String path=htc.getPath();
            rejectedLocations.add(new Location(false, path+"/META-INF/"));
            rejectedLocations.add(new Location(false, path+"/WEB-INF/"));
        }
        return Collections.unmodifiableSortedSet(rejectedLocations);
    }
    
    /**
     * Gets the Jk worker for the site.
     */
    protected abstract HttpdWorker getHttpdWorker() throws IOException, SQLException;

    public boolean stop() throws IOException, SQLException {
        UnixFile pidFile = getPidFile();
        if(pidFile.getStat().exists()) {
            AOServDaemon.suexec(
                httpdSite.getLinuxServerAccount().getLinuxAccount().getUsername().getUsername(),
                getStartStopScriptPath()+" stop",
                0
            );
            if(pidFile.getStat().exists()) pidFile.delete();
            return true;
        } else {
            return false;
        }
    }

    public boolean start() throws IOException, SQLException {
        UnixFile pidFile = getPidFile();
        if(!pidFile.getStat().exists()) {
            AOServDaemon.suexec(
                httpdSite.getLinuxServerAccount().getLinuxAccount().getUsername().getUsername(),
                getStartStopScriptPath()+" start",
                0
            );
            return true;
        } else {
            // Read the PID file and make sure the process is still running
            String pid = FileUtils.readFileAsString(pidFile);
            try {
                int pidNum = Integer.parseInt(pid.trim());
                UnixFile procDir = new UnixFile("/proc/"+pidNum);
                if(!procDir.getStat().exists()) {
                    System.err.println("Warning: Deleting PID file for dead process: "+pidFile.getPath());
                    pidFile.delete();
                    AOServDaemon.suexec(
                        httpdSite.getLinuxServerAccount().getLinuxAccount().getUsername().getUsername(),
                        getStartStopScriptPath()+" start",
                        0
                    );
                    return true;
                }
            } catch(NumberFormatException err) {
                AOServDaemon.reportWarning(err, null);
            }
            return false;
        }
    }

    @Override
    public SortedSet<JkSetting> getJkSettings() throws IOException, SQLException {
        final String jkCode = getHttpdWorker().getCode().getCode();
        SortedSet<JkSetting> settings = new TreeSet<JkSetting>();
        if(tomcatSite.useApache()) {
            // Using Apache for static content, send specific requests to Tomcat
            for(HttpdTomcatContext context : tomcatSite.getHttpdTomcatContexts()) {
                String path=context.getPath();
                settings.add(new JkSetting(true, path+"/j_security_check", jkCode));
                settings.add(new JkSetting(true, path+"/servlet/*", jkCode));
                settings.add(new JkSetting(true, path+"/*.do", jkCode));
                settings.add(new JkSetting(true, path+"/*.jsp", jkCode));
                settings.add(new JkSetting(true, path+"/*.jspa", jkCode));
                settings.add(new JkSetting(true, path+"/*.vm", jkCode));
                settings.add(new JkSetting(true, path+"/*.xml", jkCode));
            }
        } else {
            // Not using Apache, send as much as possible to Tomcat
            settings.add(new JkSetting(true, "/*", jkCode));
            for(HttpdTomcatContext context : tomcatSite.getHttpdTomcatContexts()) {
                String path=context.getPath();
                if(enableCgi()) settings.add(new JkSetting(false, path+"/cgi-bin/*", jkCode));
                if(enablePhp()) settings.add(new JkSetting(false, path+"/*.php", jkCode));
            }
        }
        return settings;
    }

    public SortedMap<String,WebAppSettings> getWebapps() {
        SortedMap<String,WebAppSettings> webapps = new TreeMap<String,WebAppSettings>();

        // Set up all of the webapps
        for(HttpdTomcatContext htc : tomcatSite.getHttpdTomcatContexts()) {
            webapps.put(
                htc.getPath(),
                new WebAppSettings(
                    htc.getDocBase(),
                    "All",
                    "Indexes IncludesNOEXEC",
                    enableCgi()
                )
            );
        }
        return webapps;
    }

    /**
     * Gets the PID file for the wrapper script.  When this file exists the
     * script is assumed to be running.  This PID file may be shared between
     * multiple sites in the case of a shared Tomcat.
     */
    public abstract UnixFile getPidFile() throws IOException, SQLException;
    
    /**
     * Gets the path to the start/stop script.
     */
    public abstract String getStartStopScriptPath() throws IOException, SQLException;

    /**
     * Every Tomcat site is built through the same overall set of steps.
     */
    final protected void buildSiteDirectory(UnixFile siteDirectory, Set<HttpdSite> sitesNeedingRestarted, Set<HttpdSharedTomcat> sharedTomcatsNeedingRestarted) throws IOException, SQLException {
        final int apacheUid = getApacheUid();
        final int uid = httpdSite.getLinuxServerAccount().getUID().getID();
        final int gid = httpdSite.getLinuxServerGroup().getGID().getID();
        final String siteDir = siteDirectory.getPath();

        // Create wwwDirectory if needed
        final Stat siteDirectoryStat = new Stat();
        if(!siteDirectory.getStat(siteDirectoryStat).exists()) {
            siteDirectory.mkdir(false, 0700);
            siteDirectory.getStat(siteDirectoryStat);
        } else if(!siteDirectoryStat.isDirectory()) throw new IOException("Not a directory: "+siteDirectory);

        // New if still owned by root
        final boolean isNew = siteDirectoryStat.getUID() == UnixFile.ROOT_UID;

        // Build directory contents if is new or incomplete
        boolean needsRestart = false;
        if(isNew) {

            // Build the per-Tomcat-version unique values
            buildSiteDirectoryContents(siteDirectory);

            // CGI
            UnixFile rootDirectory = new UnixFile(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE);
            if(enableCgi()) {
                UnixFile cgibinDirectory = new UnixFile(rootDirectory, "cgi-bin", false);
                FileUtils.mkdir(cgibinDirectory, 0755, uid, gid);
                FileUtils.ln("webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/cgi-bin", siteDir+"/cgi-bin", uid, gid);
                createTestCGI(cgibinDirectory);
                createCgiPhpScript(cgibinDirectory);
            }

            // index.html
            UnixFile indexFile = new UnixFile(rootDirectory, "index.html", false);
            createTestIndex(indexFile);

            // PHP
            createTestPHP(rootDirectory);

            // Always cause restart when is new
            needsRestart = true;
        }

        // Perform any necessary upgrades
        if(upgradeSiteDirectoryContents(siteDirectory)) needsRestart = true;
        
        // Rebuild config files that need to be updated
        if(rebuildConfigFiles(siteDirectory)) needsRestart = true;

        // Complete, set permission and ownership
        siteDirectory.getStat(siteDirectoryStat);
        if(siteDirectoryStat.getMode()!=0770) siteDirectory.setMode(0770);
        if(siteDirectoryStat.getUID()!=apacheUid || siteDirectoryStat.getGID()!=gid) siteDirectory.chown(apacheUid, gid);

        // Enable/disables now that all is setup (including proper permissions)
        enableDisable(siteDirectory);

        // Flag as needing restart
        if(needsRestart) flagNeedsRestart(sitesNeedingRestarted, sharedTomcatsNeedingRestarted);
    }

    /**
     * Builds the complete directory tree for a new site.  This should not include
     * the siteDirectory itself, which has already been created.  This should also
     * not include any files that enable/disable the site.
     * 
     * This doesn't need to create the cgi-bin, cgi-bin/test, test.php, or index.html
     */
    protected abstract void buildSiteDirectoryContents(UnixFile siteDirectory) throws IOException, SQLException;

    /**
     * Upgrades the site directory contents for an auto-upgrade.
     *
     * @return  <code>true</code> if the site needs to be restarted.
     */
    protected abstract boolean upgradeSiteDirectoryContents(UnixFile siteDirectory) throws IOException, SQLException;

    /**
     * Rebuilds any config files that need updated.  This should not include any
     * files/symlinks that enable/disable this site.
     *
     * @return  <code>true</code> if the site needs to be restarted.
     */
    protected abstract boolean rebuildConfigFiles(UnixFile siteDirectory) throws IOException, SQLException;

    /**
     * Enables/disables the site by adding/removing symlinks, if appropriate for
     * the type of site.
     */
    protected abstract void enableDisable(UnixFile siteDirectory) throws IOException, SQLException;

    /**
     * Flags that the site needs restarted.
     */
    protected abstract void flagNeedsRestart(Set<HttpdSite> sitesNeedingRestarted, Set<HttpdSharedTomcat> sharedTomcatsNeedingRestarted);
}
