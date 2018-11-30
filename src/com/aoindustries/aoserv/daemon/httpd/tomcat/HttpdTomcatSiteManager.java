/*
 * Copyright 2007-2013, 2014, 2015, 2017, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.validator.UnixPath;
import com.aoindustries.aoserv.client.validator.UserId;
import com.aoindustries.aoserv.client.web.tomcat.Context;
import com.aoindustries.aoserv.client.web.tomcat.JkMount;
import com.aoindustries.aoserv.client.web.tomcat.PrivateTomcatSite;
import com.aoindustries.aoserv.client.web.tomcat.SharedTomcat;
import com.aoindustries.aoserv.client.web.tomcat.SharedTomcatSite;
import com.aoindustries.aoserv.client.web.tomcat.Site;
import com.aoindustries.aoserv.client.web.tomcat.Worker;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Level;

/**
 * Manages Site configurations.
 *
 * @author  AO Industries, Inc.
 */
public abstract class HttpdTomcatSiteManager<TC extends TomcatCommon> extends HttpdSiteManager implements StopStartable {

	/**
	 * Gets the specific manager for one type of web site.
	 */
	public static HttpdTomcatSiteManager<? extends TomcatCommon> getInstance(Site tomcatSite) throws IOException, SQLException {
		PrivateTomcatSite stdSite=tomcatSite.getHttpdTomcatStdSite();
		if(stdSite!=null) return HttpdTomcatStdSiteManager.getInstance(stdSite);

		com.aoindustries.aoserv.client.web.jboss.Site jbossSite = tomcatSite.getHttpdJBossSite();
		if(jbossSite!=null) return HttpdJBossSiteManager.getInstance(jbossSite);

		SharedTomcatSite shrSite = tomcatSite.getHttpdTomcatSharedSite();
		if(shrSite!=null) return HttpdTomcatSharedSiteManager.getInstance(shrSite);

		throw new SQLException("Site must be one of PrivateTomcatSite, Site, or SharedTomcatSite: "+tomcatSite);
	}

	/**
	 * The name of the file generated for version change detection.
	 */
	private static final String README_TXT = "README.txt";

	final protected Site tomcatSite;

	protected HttpdTomcatSiteManager(Site tomcatSite) throws IOException, SQLException {
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
	public Map<String,List<Location>> getRejectedLocations() throws IOException, SQLException {
		Map<String,List<Location>> standardRejectedLocations = super.getRejectedLocations();

		// Tomcats may now be disabled separately from the sites, and when disabled
		// Apache will serve content directly.
		// Always protect at the Apache level to not expose sensitive information
		// If not using Apache, let Tomcat do its own protection
		//if(!tomcatSite.getUseApache()) return standardRejectedLocations;
		List<Context> htcs;
		if(
			!tomcatSite.getBlockWebinf()
			|| (htcs = tomcatSite.getHttpdTomcatContexts()).isEmpty()
		) {
			return standardRejectedLocations;
		} else {
			List<Location> locations = new ArrayList<>(htcs.size() * 2);
			for(Context htc : htcs) {
				String path = htc.getPath();
				locations.add(new Location(false, path + "/META-INF"));
				locations.add(new Location(false, path + "/WEB-INF"));
			}
			Map<String,List<Location>> rejectedLocations = new LinkedHashMap<>((standardRejectedLocations.size()+1)*4/3+1);
			rejectedLocations.putAll(standardRejectedLocations);
			rejectedLocations.put(
				"Protect Tomcat webapps",
				Collections.unmodifiableList(locations)
			);
			return Collections.unmodifiableMap(rejectedLocations);
		}
	}

	/**
	 * Gets the Jk worker for the site.
	 */
	protected abstract Worker getHttpdWorker() throws IOException, SQLException;

	@Override
	public boolean stop() throws IOException, SQLException {
		UnixFile pidFile = getPidFile();
		if(pidFile.getStat().exists()) {
			AOServDaemon.suexec(
				getStartStopScriptUsername(),
				getStartStopScriptPath() + " stop",
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
				getStartStopScriptPath() + " start",
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
						getStartStopScriptPath() + " start",
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
		// Only include JK settings when this site is enabled
		if(httpdSite.isDisabled()) {
			// Return no settings when this site is disabled
			return super.getJkSettings();
		}
		// Only include JK settings when Tomcat instance is enabled
		boolean tomcatDisabled;
		{
			SharedTomcatSite htss = tomcatSite.getHttpdTomcatSharedSite();
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
		final String jkCode = getHttpdWorker().getName().getCode();
		SortedSet<JkSetting> settings = new TreeSet<>();
		for(JkMount jkMount : tomcatSite.getJkMounts()) {
			settings.add(new JkSetting(jkMount.isMount(), jkMount.getPath(), jkCode));
		}
		return settings;
	}

	@Override
	public SortedMap<String,WebAppSettings> getWebapps() throws IOException, SQLException {
		SortedMap<String,WebAppSettings> webapps = new TreeMap<>();

		// Set up all of the webapps
		for(Context htc : tomcatSite.getHttpdTomcatContexts()) {
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
	final protected void buildSiteDirectory(UnixFile siteDirectory, String optSlash, Set<com.aoindustries.aoserv.client.web.Site> sitesNeedingRestarted, Set<SharedTomcat> sharedTomcatsNeedingRestarted, Set<UnixFile> restorecon) throws IOException, SQLException {
		final int apacheUid = getApacheUid();
		final int uid = httpdSite.getLinuxServerAccount().getUid().getId();
		final int gid = httpdSite.getLinuxServerGroup().getGid().getId();
		final String siteDir = siteDirectory.getPath();
		// TODO: Consider unpackWARs setting for root directory existence and apache configs
		// TODO: Also unpackwars=false incompatible cgi or php options (and htaccess, ssi?)
		// TODO: Also unpackWARs=false would require use_apache=false

		final TC tomcatCommon = getTomcatCommon();
		final String apacheTomcatDir = tomcatCommon.getApacheTomcatDir();

		final UnixFile rootDirectory = new UnixFile(siteDir+"/webapps/"+Context.ROOT_DOC_BASE);
		final UnixFile cgibinDirectory = new UnixFile(rootDirectory, "cgi-bin", false);

		boolean needsRestart = false;

		// Create and fill in the directory if it does not exist or is owned by root.
		Stat siteDirectoryStat = siteDirectory.getStat();
		final boolean isInstall =
			!siteDirectoryStat.exists()
			|| siteDirectoryStat.getUid() == UnixFile.ROOT_UID;

		// Perform upgrade in-place when not doing a full install and the README.txt file missing or changed
		final byte[] readmeTxtContent = generateReadmeTxt(optSlash, apacheTomcatDir, siteDirectory);
		// readmeTxt will be null when in-place upgrade not supported
		final UnixFile readmeTxt = readmeTxtContent == null ? null : new UnixFile(siteDirectory, README_TXT, false);
		final boolean isUpgrade;
		{
			final Stat readmeTxtStat;
			isUpgrade =
				!isInstall
				&& readmeTxt != null
				&& !(
					(readmeTxtStat = readmeTxt.getStat()).exists()
					&& readmeTxtStat.isRegularFile()
					&& readmeTxt.contentEquals(readmeTxtContent)
				);
		}
		assert !(isInstall && isUpgrade);
		if (isInstall || isUpgrade) {

			// /var/www/(site-name)/
			if(isInstall) {
				if (!siteDirectoryStat.exists()) siteDirectory.mkdir();
				siteDirectory.setMode(0770);
				siteDirectoryStat = siteDirectory.getStat();
			}

			// Build the per-Tomcat-version unique values
			buildSiteDirectoryContents(optSlash, siteDirectory, isUpgrade);

			if(isInstall) {
				// index.html
				UnixFile indexFile = new UnixFile(rootDirectory, "index.html", false);
				createTestIndex(indexFile);
			}

			// Create or replace the README.txt
			if(readmeTxt != null) {
				DaemonFileUtils.atomicWrite(
					readmeTxt, readmeTxtContent, 0440, uid, gid,
					null, null
				);
			}

			// Always cause restart when is new
			needsRestart = true;
		}

		// Perform any necessary upgrades
		if(upgradeSiteDirectoryContents(optSlash, siteDirectory)) needsRestart = true;

		// CGI-based PHP
		createCgiPhpScript(siteDirectory, cgibinDirectory, restorecon);

		// Rebuild config files that need to be updated
		if(rebuildConfigFiles(siteDirectory, restorecon)) needsRestart = true;

		// Complete, set permission and ownership
		if(siteDirectoryStat.getMode() != 0770) siteDirectory.setMode(0770);
		if(siteDirectoryStat.getUid() != apacheUid || siteDirectoryStat.getGid() != gid) siteDirectory.chown(apacheUid, gid);

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
	protected abstract void buildSiteDirectoryContents(String optSlash, UnixFile siteDirectory, boolean isUpgrade) throws IOException, SQLException;

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
	protected abstract boolean rebuildConfigFiles(UnixFile siteDirectory, Set<UnixFile> restorecon) throws IOException, SQLException;

	/**
	 * Enables/disables the site by adding/removing symlinks, if appropriate for
	 * the type of site.
	 */
	protected abstract void enableDisable(UnixFile siteDirectory) throws IOException, SQLException;

	/**
	 * Flags that the site needs restarted.
	 */
	protected abstract void flagNeedsRestart(Set<com.aoindustries.aoserv.client.web.Site> sitesNeedingRestarted, Set<SharedTomcat> sharedTomcatsNeedingRestarted) throws SQLException, IOException;

	/**
	 * Generates the README.txt that is used to detect major version changes to rebuild the Tomcat installation.
	 *
	 * TODO: Generate and use these readme.txt files to detect when version changed
	 *
	 * @return  The README.txt file contents or {@code null} if no README.txt used for change detection
	 *
	 * @see  VersionedSharedTomcatManager#generateReadmeTxt(java.lang.String, java.lang.String, com.aoindustries.io.unix.UnixFile)
	 */
	protected abstract byte[] generateReadmeTxt(String optSlash, String apacheTomcatDir, UnixFile installDir) throws IOException, SQLException;
}
