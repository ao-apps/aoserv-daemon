/*
 * Copyright 2007-2013, 2014, 2015, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.HttpdJBossSite;
import com.aoindustries.aoserv.client.HttpdSharedTomcat;
import com.aoindustries.aoserv.client.HttpdSite;
import com.aoindustries.aoserv.client.HttpdTomcatContext;
import com.aoindustries.aoserv.client.HttpdTomcatSharedSite;
import com.aoindustries.aoserv.client.HttpdTomcatSite;
import com.aoindustries.aoserv.client.HttpdTomcatStdSite;
import com.aoindustries.aoserv.client.HttpdWorker;
import com.aoindustries.aoserv.client.validator.UnixPath;
import com.aoindustries.aoserv.client.validator.UserId;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.httpd.HttpdSiteManager;
import com.aoindustries.aoserv.daemon.httpd.StopStartable;
import com.aoindustries.aoserv.daemon.httpd.jboss.HttpdJBossSiteManager;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.io.FileUtils;
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
import java.util.logging.Level;

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

    protected HttpdTomcatSiteManager(HttpdTomcatSite tomcatSite) throws IOException, SQLException {
        super(tomcatSite.getHttpdSite());
        this.tomcatSite = tomcatSite;
    }

    public abstract TC getTomcatCommon();

    /**
     * In addition to the standard values, also protects the /WEB-INF/ and /META-INF/ directories of all contexts.
     * This is only done when the "use_apache" flag is on.  Otherwise, Tomcat should protect the necessary
     * paths.
     */
    @Override
    public SortedSet<Location> getRejectedLocations() throws IOException, SQLException {
        // If not using Apache, let Tomcat do its own protection
        SortedSet<Location> standardRejectedLocations = super.getRejectedLocations();
        if(!tomcatSite.getUseApache()) return standardRejectedLocations;

        SortedSet<Location> rejectedLocations = new TreeSet<>(standardRejectedLocations);
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

	@Override
    public boolean stop() throws IOException, SQLException {
        UnixFile pidFile = getPidFile();
        if(pidFile.getStat().exists()) {
            AOServDaemon.suexec(
                getStartStopScriptUsername(),
                getStartStopScriptPath()+" stop",
                0
            );
            if(pidFile.getStat().exists()) pidFile.delete();
            return true;
        } else {
            return false;
        }
    }

	@Override
    public boolean start() throws IOException, SQLException {
        UnixFile pidFile = getPidFile();
        if(!pidFile.getStat().exists()) {
            AOServDaemon.suexec(
                getStartStopScriptUsername(),
                getStartStopScriptPath()+" start",
                0
            );
            return true;
        } else {
            // Read the PID file and make sure the process is still running
            String pid = FileUtils.readFileAsString(pidFile.getFile());
            try {
                int pidNum = Integer.parseInt(pid.trim());
                UnixFile procDir = new UnixFile("/proc/"+pidNum);
                if(!procDir.getStat().exists()) {
                    System.err.println("Warning: Deleting PID file for dead process: "+pidFile.getPath());
                    pidFile.delete();
                    AOServDaemon.suexec(
                        getStartStopScriptUsername(),
                        getStartStopScriptPath()+" start",
                        0
                    );
                    return true;
                }
            } catch(NumberFormatException err) {
                LogFactory.getLogger(HttpdTomcatSiteManager.class).log(Level.WARNING, null, err);
            }
            return false;
        }
    }

    @Override
    public SortedSet<JkSetting> getJkSettings() throws IOException, SQLException {
		// Only include JK settings when Tomcat instance is enabled
		boolean tomcatDisabled;
		{
			HttpdTomcatSharedSite htss = tomcatSite.getHttpdTomcatSharedSite();
			if(htss != null) {
				// Shared Tomcat
				tomcatDisabled = htss.getHttpdSharedTomcat().isDisabled();
			} else {
				// Standard Tomcat
				tomcatDisabled = httpdSite.isDisabled();
			}
		}
		if(tomcatDisabled) {
			// Return no settings when Tomcat disabled
			return super.getJkSettings();
		}
        final String jkCode = getHttpdWorker().getCode().getCode();
        SortedSet<JkSetting> settings = new TreeSet<>();
        if(tomcatSite.getUseApache()) {
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

	@Override
    public SortedMap<String,WebAppSettings> getWebapps() throws IOException, SQLException {
        SortedMap<String,WebAppSettings> webapps = new TreeMap<>();

        // Set up all of the webapps
        for(HttpdTomcatContext htc : tomcatSite.getHttpdTomcatContexts()) {
            webapps.put(
                htc.getPath(),
                new WebAppSettings(
                    htc.getDocBase(),
                    httpdSite.getEnableHtaccess() ? "All" : "None",
					httpdSite.getEnableSsi(),
					httpdSite.getEnableIndexes(),
					httpdSite.getEnableFollowSymlinks(),
                    enableCgi()
                )
            );
        }
        return webapps;
    }

	/**
     * Gets the PID file for the wrapper script.  When this file exists the
     * script is assumed to be running.  This PID file may be shared between
     * multiple sites in the case of a shared Tomcat.  Also, it is possible
     * that the JVM may be disabled while the site overall is not.
     * 
     * @return  the .pid file or <code>null</code> if should not be running
     */
    public abstract UnixFile getPidFile() throws IOException, SQLException;
    
    /**
     * Gets the path to the start/stop script.
     */
    public abstract UnixPath getStartStopScriptPath() throws IOException, SQLException;

    /**
     * Gets the username to run the start/stop script as.
     */
    public abstract UserId getStartStopScriptUsername() throws IOException, SQLException;

	@Override
	protected Set<PackageManager.PackageName> getRequiredPackages() throws IOException, SQLException {
		return getTomcatCommon().getRequiredPackages();
	}

	/**
     * Every Tomcat site is built through the same overall set of steps.
     */
	@Override
    final protected void buildSiteDirectory(UnixFile siteDirectory, String optSlash, Set<HttpdSite> sitesNeedingRestarted, Set<HttpdSharedTomcat> sharedTomcatsNeedingRestarted) throws IOException, SQLException {
        final int apacheUid = getApacheUid();
        final int uid = httpdSite.getLinuxServerAccount().getUid().getId();
        final int gid = httpdSite.getLinuxServerGroup().getGid().getId();
        final String siteDir = siteDirectory.getPath();
		// TODO: Consider unpackWARs setting for root directory existence and apache configs
		// TODO: Also unpackwars=false incompatible cgi or php options (and htaccess, ssi?)
		// TODO: Also unpackWARs=false would require use_apache=false
        final UnixFile rootDirectory = new UnixFile(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE);
        final UnixFile cgibinDirectory = new UnixFile(rootDirectory, "cgi-bin", false);

        // Create wwwDirectory if needed
        Stat siteDirectoryStat = siteDirectory.getStat();
        if(!siteDirectoryStat.exists()) {
            siteDirectory.mkdir(false, 0700);
            siteDirectoryStat = siteDirectory.getStat();
        } else if(!siteDirectoryStat.isDirectory()) throw new IOException("Not a directory: "+siteDirectory);

        // New if still owned by root
        final boolean isNew = siteDirectoryStat.getUid() == UnixFile.ROOT_UID;

        // Build directory contents if is new or incomplete
        boolean needsRestart = false;
        if(isNew) {
            // Build the per-Tomcat-version unique values
            buildSiteDirectoryContents(optSlash, siteDirectory);

            // CGI
            if(enableCgi()) {
                DaemonFileUtils.mkdir(cgibinDirectory, 0755, uid, gid);
                //FileUtils.ln("webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/cgi-bin", siteDir+"/cgi-bin", uid, gid);
            }

            // index.html
            UnixFile indexFile = new UnixFile(rootDirectory, "index.html", false);
            createTestIndex(indexFile);

            // Always cause restart when is new
            needsRestart = true;
        }

        // Perform any necessary upgrades
        if(upgradeSiteDirectoryContents(optSlash, siteDirectory)) needsRestart = true;

		// CGI-based PHP
        createCgiPhpScript(cgibinDirectory);

        // Rebuild config files that need to be updated
        if(rebuildConfigFiles(siteDirectory)) needsRestart = true;

        // Complete, set permission and ownership
        siteDirectoryStat = siteDirectory.getStat();
        if(siteDirectoryStat.getMode()!=0770) siteDirectory.setMode(0770);
        if(siteDirectoryStat.getUid()!=apacheUid || siteDirectoryStat.getGid()!=gid) siteDirectory.chown(apacheUid, gid);

        // Enable/disables now that all is setup (including proper permissions)
        enableDisable(siteDirectory);

        // Flag as needing restart
        if(needsRestart) flagNeedsRestart(sitesNeedingRestarted, sharedTomcatsNeedingRestarted);
    }

    /**
     * Builds the complete directory tree for a new site.  This should not include
     * the siteDirectory itself, which has already been created.  This should also
     * not include any files that enable/disable the site.
     * <p>
     * This doesn't need to create the cgi-bin, cgi-bin/test, or index.html
	 * </p>
	 *
	 * @param optSlash  Relative path from the CATALINA_HOME to /opt/, including trailing slash, such as <code>../../opt/</code>.
     */
    protected abstract void buildSiteDirectoryContents(String optSlash, UnixFile siteDirectory) throws IOException, SQLException;

    /**
     * Upgrades the site directory contents for an auto-upgrade.
	 *
	 * @param optSlash  Relative path from the CATALINA_HOME to /opt/, including trailing slash, such as <code>../../opt/</code>.
     *
     * @return  <code>true</code> if the site needs to be restarted.
     */
    protected abstract boolean upgradeSiteDirectoryContents(String optSlash, UnixFile siteDirectory) throws IOException, SQLException;

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
    protected abstract void flagNeedsRestart(Set<HttpdSite> sitesNeedingRestarted, Set<HttpdSharedTomcat> sharedTomcatsNeedingRestarted) throws SQLException, IOException;
}
