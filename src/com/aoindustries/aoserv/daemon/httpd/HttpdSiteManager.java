/*
 * Copyright 2007-2013, 2014, 2015, 2016, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd;

import com.aoindustries.aoserv.client.AOSHCommand;
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.HttpdSharedTomcat;
import com.aoindustries.aoserv.client.HttpdSite;
import com.aoindustries.aoserv.client.HttpdSiteBind;
import com.aoindustries.aoserv.client.HttpdSiteURL;
import com.aoindustries.aoserv.client.HttpdStaticSite;
import com.aoindustries.aoserv.client.HttpdTomcatSite;
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.validator.LinuxId;
import com.aoindustries.aoserv.client.validator.UnixPath;
import com.aoindustries.aoserv.client.validator.UserId;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.httpd.tomcat.HttpdTomcatSiteManager;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.validation.ValidationException;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages HttpdSite configurations.
 *
 * @author  AO Industries, Inc.
 */
public abstract class HttpdSiteManager {

	private static final Logger logger = Logger.getLogger(HttpdSiteManager.class.getName());

	/**
	 * The directories in /www or /var/www that will never be deleted.
	 * <p>
	 * Note: This matches the check constraint on the httpd_sites table.
	 * Note: This matches isValidSiteName in HttpdSite.
	 * </p>
	 */
	private static final Set<String> keepWwwDirs = new HashSet<>(Arrays.asList(
        "disabled", // Provided by aoserv-httpd-site-disabled package
        // CentOS 5 only
        "cache", // nginx only?
        "fastcgi",
        "error",
        "icons",
        // CentOS 7
        "cgi-bin",
        "html",
        "mrtg",
		// Other filesystem patterns
        "lost+found",
        "aquota.group",
        "aquota.user"
	));

	/**
	 * Gets the specific manager for one type of web site.
	 */
	public static HttpdSiteManager getInstance(HttpdSite site) throws IOException, SQLException {
		HttpdStaticSite staticSite=site.getHttpdStaticSite();
		if(staticSite!=null) return HttpdStaticSiteManager.getInstance(staticSite);

		HttpdTomcatSite tomcatSite=site.getHttpdTomcatSite();
		if(tomcatSite!=null) return HttpdTomcatSiteManager.getInstance(tomcatSite);

		throw new SQLException("HttpdSite must be either HttpdStaticSite and HttpdTomcatSite: "+site);
	}

	/**
	 * Responsible for control of all things in [/var]/www
	 *
	 * Only called by the already synchronized <code>HttpdManager.doRebuild()</code> method.
	 */
	static void doRebuild(
		List<File> deleteFileList,
		Set<HttpdSite> sitesNeedingRestarted,
		Set<HttpdSharedTomcat> sharedTomcatsNeedingRestarted,
		Set<UnixFile> restorecon
	) throws IOException, SQLException {
		try {
			// Get values used in the rest of the method.
			HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
			String optSlash = osConfig.getHttpdSitesOptSlash();
			AOServer aoServer = AOServDaemon.getThisAOServer();

			// The www directories that exist but are not used will be removed
			UnixFile wwwDirectory = new UnixFile(osConfig.getHttpdSitesDirectory().toString());
			Set<String> wwwRemoveList = new HashSet<>();
			{
				String[] list = wwwDirectory.list();
				if(list != null) {
					wwwRemoveList.addAll(Arrays.asList(list));
					wwwRemoveList.removeAll(keepWwwDirs);
				}
			}

			// Iterate through each site
			for(HttpdSite httpdSite : aoServer.getHttpdSites()) {
				final HttpdSiteManager manager = getInstance(httpdSite);

				// Install any required RPMs
				PackageManager.installPackages(manager.getRequiredPackages());

				// Create and fill in any incomplete installations.
				final String siteName = httpdSite.getSiteName();
				UnixFile siteDirectory = new UnixFile(wwwDirectory, siteName, false);
				manager.buildSiteDirectory(
					siteDirectory,
					optSlash,
					sitesNeedingRestarted,
					sharedTomcatsNeedingRestarted,
					restorecon
				);
				wwwRemoveList.remove(siteName);
			}

			// Stop, disable, and mark files for deletion
			for(String siteName : wwwRemoveList) {
				UnixFile removeFile = new UnixFile(wwwDirectory, siteName, false);
				// Stop and disable any daemons
				stopAndDisableDaemons(removeFile);
				// Only remove the directory when not used by a home directory
				if(!aoServer.isHomeUsed(UnixPath.valueOf(removeFile.getPath()))) {
					File toDelete = removeFile.getFile();
					if(logger.isLoggable(Level.INFO)) logger.info("Scheduling for removal: " + toDelete);
					deleteFileList.add(toDelete);
				}
			}
		} catch(ValidationException e) {
			throw new IOException(e);
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
	static void stopStartAndRestart(Set<HttpdSite> sitesNeedingRestarted) throws IOException, SQLException {
		for(HttpdSite httpdSite : AOServDaemon.getThisAOServer().getHttpdSites()) {
			HttpdSiteManager manager = getInstance(httpdSite);
			if(manager instanceof StopStartable) {
				final StopStartable stopStartRestartable = (StopStartable)manager;
				Callable<Object> commandCallable;
				if(stopStartRestartable.isStartable()) {
					// Enabled, start or restart
					if(sitesNeedingRestarted.contains(httpdSite)) {
						commandCallable = () -> {
							if(stopStartRestartable.stop()) {
								try {
									Thread.sleep(5000);
								} catch(InterruptedException err) {
									LogFactory.getLogger(HttpdSiteManager.class).log(Level.WARNING, null, err);
									// Restore the interrupted status
									Thread.currentThread().interrupt();
								}
							}
							stopStartRestartable.start();
							return null;
						};
					} else {
						commandCallable = () -> {
							if(!new File("/var/run/aoserv-user-daemons.pid").exists()) {
								stopStartRestartable.start();
							} else {
								if(logger.isLoggable(Level.INFO)) logger.info("Skipping start because /var/run/aoserv-user-daemons.pid exists: " + httpdSite);
							}
							return null;
						};
					}
				} else {
					// Disabled, can only stop if needed
					commandCallable = () -> {
						stopStartRestartable.stop();
						return null;
					};
				}
				try {
					Future<Object> commandFuture = AOServDaemon.executorService.submit(commandCallable);
					commandFuture.get(60, TimeUnit.SECONDS);
				} catch(InterruptedException err) {
					LogFactory.getLogger(HttpdSiteManager.class).log(Level.WARNING, null, err);
					// Restore the interrupted status
					Thread.currentThread().interrupt();
				} catch(ExecutionException | TimeoutException err) {
					LogFactory.getLogger(HttpdSiteManager.class).log(Level.WARNING, null, err);
				}
			}
		}
	}

	/**
	 * Stops all daemons that may be running in the provided directory.  The
	 * exact type of site is not known.  This is called during site clean-up
	 * to shutdown processes that should no longer be running.  It is assumed
	 * that all types of sites will use the "daemon" directory with symbolic
	 * links that accept "start" and "stop" parameters.
	 * 
	 * @see  #doRebuild
	 */
	public static void stopAndDisableDaemons(UnixFile siteDirectory) throws IOException, SQLException {
		UnixFile daemonDirectory = new UnixFile(siteDirectory, "daemon", false);
		Stat daemonDirectoryStat = daemonDirectory.getStat();
		if(daemonDirectoryStat.exists()) {
			int daemonUid=daemonDirectoryStat.getUid();
			LinuxServerAccount daemonLsa;
			try {
				daemonLsa = AOServDaemon.getThisAOServer().getLinuxServerAccount(LinuxId.valueOf(daemonUid));
			} catch(ValidationException e) {
				throw new IOException(e);
			}
			// If the account doesn't exist or is disabled, the process killer will kill any processes
			if(daemonLsa!=null && !daemonLsa.isDisabled()) {
				String[] list = daemonDirectory.list();
				if(list!=null) {
					for(String scriptName : list) {
						final UnixFile scriptFile = new UnixFile(daemonDirectory, scriptName, false);
						// Call stop with a one-minute time-out if not owned by root
						if(daemonUid!=UnixFile.ROOT_UID) {
							final UserId username = daemonLsa.getLinuxAccount().getUsername().getUsername();
							try {
								Future<Object> stopFuture = AOServDaemon.executorService.submit(() -> {
									AOServDaemon.suexec(
										username,
										scriptFile.getPath()+" stop",
										0
									);
									return null;
								});
								stopFuture.get(60, TimeUnit.SECONDS);
							} catch(InterruptedException err) {
								LogFactory.getLogger(HttpdSiteManager.class).log(Level.WARNING, null, err);
								// Restore the interrupted status
								Thread.currentThread().interrupt();
							} catch(ExecutionException | TimeoutException err) {
								LogFactory.getLogger(HttpdSiteManager.class).log(Level.WARNING, null, err);
							}
						}
						// Remove the file
						scriptFile.delete();
					}
				}
			}
		}
	}

	/**
	 * Starts the site if it is not running.  Restarts it if it is running.
	 * 
	 * @return  <code>null</code> if successful or a user-readable reason if not successful
	 */
	public static String startHttpdSite(int sitePKey) throws IOException, SQLException {
		AOServConnector conn = AOServDaemon.getConnector();

		HttpdSite httpdSite=conn.getHttpdSites().get(sitePKey);
		AOServer thisAOServer = AOServDaemon.getThisAOServer();
		if(!httpdSite.getAOServer().equals(thisAOServer)) return "HttpdSite #"+sitePKey+" has server of "+httpdSite.getAOServer().getHostname()+", which is not this server ("+thisAOServer.getHostname()+')';

		HttpdSiteManager manager = getInstance(httpdSite);
		if(manager instanceof StopStartable) {
			StopStartable stopStartable = (StopStartable)manager;
			if(stopStartable.isStartable()) {
				if(stopStartable.stop()) {
					try {
						Thread.sleep(5000);
					} catch(InterruptedException err) {
						LogFactory.getLogger(HttpdSiteManager.class).log(Level.WARNING, null, err);
						// Restore the interrupted status
						Thread.currentThread().interrupt();
					}
				}
				stopStartable.start();

				// Null means all went well
				return null;
			} else {
				return "HttpdSite #"+sitePKey+" is not currently startable";
			}
		} else {
			return "HttpdSite #"+sitePKey+" is not a type of site that can be stopped and started";
		}
	}

	/**
	 * Stops the site if it is running.  Will return a message if already stopped.
	 * 
	 * @return  <code>null</code> if successful or a user-readable reason if not success.
	 */
	public static String stopHttpdSite(int sitePKey) throws IOException, SQLException {
		AOServConnector conn = AOServDaemon.getConnector();

		HttpdSite httpdSite=conn.getHttpdSites().get(sitePKey);
		AOServer thisAOServer = AOServDaemon.getThisAOServer();
		if(!httpdSite.getAOServer().equals(thisAOServer)) return "HttpdSite #"+sitePKey+" has server of "+httpdSite.getAOServer().getHostname()+", which is not this server ("+thisAOServer.getHostname()+')';

		HttpdSiteManager manager = getInstance(httpdSite);
		if(manager instanceof StopStartable) {
			StopStartable stopStartable = (StopStartable)manager;
			if(stopStartable.stop()) {
				// Null means all went well
				return null;
			} else {
				return "Site was already stopped";
			}
		} else {
			return "HttpdSite #"+sitePKey+" is not a type of site that can be stopped and started";
		}
	}

	final protected HttpdSite httpdSite;

	protected HttpdSiteManager(HttpdSite httpdSite) {
		this.httpdSite = httpdSite;
	}

	/**
	 * Gets the auto-mode warning for this website for use in XML files.  This
	 * may be used on any config files that a user would be tempted to change
	 * directly.
	 */
	public String getAutoWarningXmlOld() throws IOException, SQLException {
		return
			"<!--\n"
			+ "  Warning: This file is automatically created by HttpdManager.  Any manual changes\n"
			+ "  to this file will be overwritten.  Please set the is_manual flag for this website\n"
			+ "  to be able to make permanent changes to this file.\n"
			+ "\n"
			+ "  Control Panel: https://www.aoindustries.com/clientarea/control/httpd/HttpdSiteCP.ao?pkey="+httpdSite.getPkey()+"\n"
			+ "\n"
			+ "  AOSH: "+AOSHCommand.SET_HTTPD_SITE_IS_MANUAL+" "+httpdSite.getSiteName()+" "+httpdSite.getAOServer().getHostname()+" true\n"
			+ "\n"
			+ "  support@aoindustries.com\n"
			+ "  (205) 454-2556\n"
			+ "-->\n"
		;
	}

	/**
	 * Gets the auto-mode warning for this website for use in XML files.  This
	 * may be used on any config files that a user would be tempted to change
	 * directly.
	 */
	public String getAutoWarningXml() throws IOException, SQLException {
		return
			"<!--\n"
			+ "  Warning: This file is automatically created by HttpdManager.  Any manual changes\n"
			+ "  to this file will be overwritten.  Please set the is_manual flag for this website\n"
			+ "  to be able to make permanent changes to this file.\n"
			+ "\n"
			+ "  Control Panel: https://aoindustries.com/clientarea/control/httpd/HttpdSiteCP.ao?pkey="+httpdSite.getPkey()+"\n"
			+ "\n"
			+ "  AOSH: "+AOSHCommand.SET_HTTPD_SITE_IS_MANUAL+" "+httpdSite.getSiteName()+" "+httpdSite.getAOServer().getHostname()+" true\n"
			+ "\n"
			+ "  support@aoindustries.com\n"
			+ "  (205) 454-2556\n"
			+ "-->\n"
		;
	}

	/**
	 * Gets the auto-mode warning using Unix-style comments (#).  This
	 * may be used on any config files that a user would be tempted to change
	 * directly.
	 */
	/* public String getAutoWarningUnix() throws IOException, SQLException {
		return
			"#\n"
			+ "# Warning: This file is automatically created by HttpdManager.  Any manual changes\n"
			+ "# to this file will be overwritten.  Please set the is_manual flag for this website\n"
			+ "# to be able to make permanent changes to this file.\n"
			+ "#\n"
			+ "# Control Panel: https://aoindustries.com/clientarea/control/httpd/HttpdSiteCP.ao?pkey="+httpdSite.getPkey()+"\n"
			+ "#\n"
			+ "# AOSH: "+AOSHCommand.SET_HTTPD_SITE_IS_MANUAL+" "+httpdSite.getSiteName()+' '+httpdSite.getAOServer().getHostname()+" true\n"
			+ "#\n"
			+ "# support@aoindustries.com\n"
			+ "# (205) 454-2556\n"
			+ "#\n"
		;
	}*/

	/**
	 * Gets any packages that must be installed for this site.
	 *
	 * By default, no specific packages are required.
	 */
	protected Set<PackageManager.PackageName> getRequiredPackages() throws IOException, SQLException {
		return Collections.emptySet();
	}

	/**
	 * (Re)builds the site directory, from scratch if it doesn't exist.
	 * Creates, recreates, or removes resources as necessary.
	 * Also performs an automatic upgrade of resources if appropriate for the site.
	 * Also reconfigures any config files within the directory if appropriate for the site type and settings.
	 * If this site or other sites needs to be restarted due to changes in the files, add to <code>sitesNeedingRestarted</code>.
	 * If any shared Tomcat needs to be restarted due to changes in the files, add to <code>sharedTomcatsNeedingRestarted</code>.
	 * Any files under siteDirectory that need to be updated to enable/disable this site should be changed.
	 * Actual process start/stop will be performed later in <code>stopStartAndRestart</code>.
	 * 
	 * <ol>
	 *   <li>If <code>siteDirectory</code> doesn't exist, create it as root with mode 0700</li>
	 *   <li>If <code>siteDirectory</code> owned by root, do full pass (this implies manual=false regardless of setting)</li>
	 *   <li>Otherwise, make necessary config changes or upgrades while adhering to the manual flag</li>
	 * </ol>
	 */
	protected abstract void buildSiteDirectory(UnixFile siteDirectory, String optSlash, Set<HttpdSite> sitesNeedingRestarted, Set<HttpdSharedTomcat> sharedTomcatsNeedingRestarted, Set<UnixFile> restorecon) throws IOException, SQLException;

	/**
	 * Determines if should have anonymous FTP area.
	 */
	public boolean enableAnonymousFtp() {
		return httpdSite.getEnableAnonymousFtp();
	}

	/**
	 * Configures the anonymous FTP directory associated with this site.
	 * If the site is disabled, will make owner root, group root, mode 0700.
	 * Will reset ownership and permissions as needed.
	 * This will only be called when <code>enableAnonymousFtp</code> returns <code>true</code>.
	 * Manual mode has no impact on the ownership and permissions set.
	 *
	 * @see  #enableAnonymousFtp()
	 * @see  FTPManager#doRebuildSharedFtpDirectory
	 */
	public void configureFtpDirectory(UnixFile ftpDirectory) throws IOException, SQLException {
		if(httpdSite.isDisabled()) {
			// Disabled
			DaemonFileUtils.mkdir(
				ftpDirectory,
				0700,
				UnixFile.ROOT_UID,
				UnixFile.ROOT_GID
			);
		} else {
			// Enabled
			DaemonFileUtils.mkdir(
				ftpDirectory,
				0775,
				httpdSite.getLinuxServerAccount().getUid().getId(),
				httpdSite.getLinuxServerGroup().getGid().getId()
			);
		}
	}

	/**
	 * Determines if CGI should be enabled.
	 *
	 * @see  HttpdSite#getEnableCgi()
	 */
	protected boolean enableCgi() {
		return httpdSite.getEnableCgi();
	}

	/**
	 * Determines if PHP should be enabled.
	 *
	 * If this is enabled and CGI is disabled, then the HttpdServer for the
	 * site must use mod_php.
	 *
	 * @see  HttpdSite#getPhpVersion()
	 */
	protected boolean enablePhp() throws IOException, SQLException {
		return httpdSite.getPhpVersion() != null;
	}

	/**
	 * Creates or updates the CGI php script, CGI must be enabled and PHP enabled.
	 * If CGI is disabled or PHP is disabled, removes any php script.
	 * Any existing file will be overwritten, even when in manual mode.
	 */
	protected void createCgiPhpScript(UnixFile cgibinDirectory, Set<UnixFile> restorecon) throws IOException, SQLException {
		UnixFile phpFile = new UnixFile(cgibinDirectory, "php", false);
		// TODO: If every server this site runs as uses mod_php, then don't make the script?
		if(enableCgi() && enablePhp()) {
			AOServer thisAoServer = AOServDaemon.getThisAOServer();
			final OperatingSystemVersion osv = thisAoServer.getServer().getOperatingSystemVersion();
			final int osvId = osv.getPkey();

			PackageManager.PackageName requiredPackage;
			String phpVersion = httpdSite.getPhpVersion().getVersion();
			// Build to RAM first
			ByteArrayOutputStream bout = new ByteArrayOutputStream();
			try (ChainWriter out = new ChainWriter(bout)) {
				String phpMinorVersion;
				out.print("#!/bin/sh\n");
				if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
					if(phpVersion.startsWith("4.4.")) {
						phpMinorVersion = "4.4";
						requiredPackage = null;
						out.print(". /opt/mysql-5.0-i686/setenv.sh\n");
						out.print(". /opt/postgresql-7.3-i686/setenv.sh\n");
					} else if(phpVersion.startsWith("5.2.")) {
						phpMinorVersion = "5.2";
						requiredPackage = PackageManager.PackageName.PHP_5_2_I686;
						out.print(". /opt/mysql-5.0-i686/setenv.sh\n");
						out.print(". /opt/postgresql-8.3-i686/setenv.sh\n");
					} else if(phpVersion.startsWith("5.3.")) {
						phpMinorVersion = "5.3";
						requiredPackage = PackageManager.PackageName.PHP_5_3_I686;
						out.print(". /opt/mysql-5.1-i686/setenv.sh\n");
						out.print(". /opt/postgresql-8.3-i686/setenv.sh\n");
					} else if(phpVersion.startsWith("5.4.")) {
						phpMinorVersion = "5.4";
						requiredPackage = PackageManager.PackageName.PHP_5_4_I686;
						out.print(". /opt/mysql-5.6-i686/setenv.sh\n");
						out.print(". /opt/postgresql-9.2-i686/setenv.sh\n");
					} else if(phpVersion.startsWith("5.5.")) {
						phpMinorVersion = "5.5";
						requiredPackage = PackageManager.PackageName.PHP_5_5_I686;
						out.print(". /opt/mysql-5.6-i686/setenv.sh\n");
						out.print(". /opt/postgresql-9.2-i686/setenv.sh\n");
					} else if(phpVersion.startsWith("5.6.")) {
						phpMinorVersion = "5.6";
						requiredPackage = PackageManager.PackageName.PHP_5_6_I686;
						out.print(". /opt/mysql-5.7-i686/setenv.sh\n");
						out.print(". /opt/postgresql-9.4-i686/setenv.sh\n");
					} else {
						throw new SQLException("Unexpected version for php: " + phpVersion);
					}
				} else if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
					if(phpVersion.startsWith("5.3.")) {
						phpMinorVersion = "5.3";
						requiredPackage = PackageManager.PackageName.PHP_5_3;
						out.print(". /opt/mysql-5.1/setenv.sh\n");
						out.print(". /opt/postgresql-8.3/setenv.sh\n");
					} else if(phpVersion.startsWith("5.4.")) {
						phpMinorVersion = "5.4";
						requiredPackage = PackageManager.PackageName.PHP_5_4;
						out.print(". /opt/mysql-5.6/setenv.sh\n");
						out.print(". /opt/postgresql-9.2/setenv.sh\n");
					} else if(phpVersion.startsWith("5.5.")) {
						phpMinorVersion = "5.5";
						requiredPackage = PackageManager.PackageName.PHP_5_5;
						out.print(". /opt/mysql-5.6/setenv.sh\n");
						out.print(". /opt/postgresql-9.2/setenv.sh\n");
					} else if(phpVersion.startsWith("5.6.")) {
						phpMinorVersion = "5.6";
						requiredPackage = PackageManager.PackageName.PHP_5_6;
						out.print(". /opt/mysql-5.7/setenv.sh\n");
						out.print(". /opt/postgresql-9.4/setenv.sh\n");
					} else {
						throw new SQLException("Unexpected version for php: " + phpVersion);
					}
				} else {
					throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
				}
				out.print("exec ").print(HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration().getPhpCgiPath(phpMinorVersion)).print(" \"$@\"\n");
			}
			// Make sure required RPM is installed
			if(requiredPackage != null) PackageManager.installPackage(requiredPackage);

			// Only rewrite when needed
			int uid = httpdSite.getLinuxServerAccount().getUid().getId();
			int gid = httpdSite.getLinuxServerGroup().getGid().getId();
			int mode = 0755;
			// Create parent if missing
			UnixFile parent = cgibinDirectory.getParent();
			if(!parent.getStat().exists()) parent.mkdir(true, 0775, uid, gid);
			// Create cgi-bin if missing
			DaemonFileUtils.mkdir(cgibinDirectory, 0755, uid, gid);
			DaemonFileUtils.atomicWrite(
				phpFile,
				bout.toByteArray(),
				mode,
				uid,
				gid,
				null,
				restorecon
			);
			// Make sure permissions correct
			Stat phpStat = phpFile.getStat();
			if(phpStat.getUid()!=uid || phpStat.getGid()!=gid) phpFile.chown(uid, gid);
			if(phpStat.getMode()!=mode) phpFile.setMode(mode);
		} else {
			if(phpFile.getStat().exists()) phpFile.delete();
			// Remove any php.ini, too
			UnixFile phpIniFile = new UnixFile(cgibinDirectory, "php.ini", false);
			if(phpIniFile.getStat().exists()) phpIniFile.delete();
		}
	}

	/**
	 * Creates the test index.html file if it is missing.
	 * 
	 * TODO: Generate proper disabled page automatically.
	 *       Or, better, put into logic of static site rebuild.
	 */
	protected void createTestIndex(UnixFile indexFile) throws IOException, SQLException {
		if(!indexFile.getStat().exists()) {
			HttpdSiteURL primaryHsu = httpdSite.getPrimaryHttpdSiteURL();
			String primaryUrl = primaryHsu==null ? httpdSite.getSiteName() : primaryHsu.getHostname().toString();
			// Write to temp file first
			UnixFile tempFile = UnixFile.mktemp(indexFile.getPath()+".");
			try {
				try (ChainWriter out = new ChainWriter(new FileOutputStream(tempFile.getFile()))) {
					out.print("<html>\n"
							+ "  <head><title>Test HTML Page for ").encodeXhtml(primaryUrl).print("</title></head>\n"
							+ "  <body>\n"
							+ "    Test HTML Page for ").encodeXhtml(primaryUrl).print("\n"
							+ "  </body>\n"
							+ "</html>\n");
				}
				// Set permissions and ownership
				tempFile.setMode(0664);
				tempFile.chown(httpdSite.getLinuxServerAccount().getUid().getId(), httpdSite.getLinuxServerGroup().getGid().getId());
				// Move into place
				tempFile.renameTo(indexFile);
			} finally {
				// If still exists then there was a problem, clean-up
				if(tempFile.getStat().exists()) tempFile.delete();
			}
		}
	}

	/**
	 * Gets the user ID that apache for this site runs as.
	 * If this site runs as multiple UIDs on multiple Apache instances, will
	 * return the "apache" user.
	 * If the site has no binds, returns UID for "apache".
	 * If the site is named <code>HttpdSite.DISABLED</code>, always returns UID for "apache".
	 */
	public int getApacheUid() throws IOException, SQLException {
		int uid = -1;
		if(!HttpdSite.DISABLED.equals(httpdSite.getSiteName())) {
			for(HttpdSiteBind hsb : httpdSite.getHttpdSiteBinds()) {
				int hsUid = hsb.getHttpdBind().getHttpdServer().getLinuxServerAccount().getUid().getId();
				if(uid==-1) {
					uid = hsUid;
				} else if(uid!=hsUid) {
					// uid mismatch, fall-through to use "apache"
					uid = -1;
					break;
				}
			}
		}
		if(uid==-1) {
			AOServer aoServer = AOServDaemon.getThisAOServer();
			LinuxServerAccount apacheLsa = aoServer.getLinuxServerAccount(LinuxAccount.APACHE);
			if(apacheLsa==null) throw new SQLException("Unable to find LinuxServerAccount: "+LinuxAccount.APACHE+" on "+aoServer.getHostname());
			uid = apacheLsa.getUid().getId();
		}
		return uid;
	}

	private static final SortedSet<Location> standardRejectedLocations = new TreeSet<>();
	private static final SortedSet<Location> unmodifiableStandardRejectedLocations = Collections.unmodifiableSortedSet(standardRejectedLocations);
	static {
		// TODO: Benchmark faster with single or multiple rules

		// Protect CVS files http://www.bsd.net.au/article.php?story=2003031221495562
		standardRejectedLocations.add(new Location(true, "/\\.#"));
		standardRejectedLocations.add(new Location(true, ".*/CVS/.*"));
		standardRejectedLocations.add(new Location(true, ".*/CVSROOT/.*"));
		//standardRejectedLocations.add(new Location(true, "/CVS/Attic"));
		//standardRejectedLocations.add(new Location(true, "/CVS/Entries"));
		// Already covered by Entries: standardRejectedLocations.add(new Location(true, "/CVS/Entries\\.Static"));
		//standardRejectedLocations.add(new Location(true, "/CVS/Repository"));
		//standardRejectedLocations.add(new Location(true, "/CVS/RevisionCache"));
		//standardRejectedLocations.add(new Location(true, "/CVS/Root"));
		//standardRejectedLocations.add(new Location(true, "/CVS/\\.#merg"));

		// Protect .git directories
		standardRejectedLocations.add(new Location(true, ".*/\\.git/.*"));

		// Protect core dumps
		standardRejectedLocations.add(new Location(true, ".*/core\\.[0-9]{1,5}"));
	}

	public static class Location implements Comparable<Location> {

		final private boolean isRegularExpression;
		final private String location;

		public Location(boolean isRegularExpression, String location) {
			this.isRegularExpression = isRegularExpression;
			this.location = location;
		}

		public boolean isRegularExpression() {
			return isRegularExpression;
		}

		public String getLocation() {
			return location;
		}

		@Override
		public int hashCode() {
			int hashCode = location.hashCode();
			// Negate for regular expressions
			if(isRegularExpression) hashCode = -hashCode;
			return hashCode;
		}

		@Override
		public boolean equals(Object O) {
			if(O==null) return false;
			if(!(O instanceof Location)) return false;
			Location other = (Location)O;
			return
				isRegularExpression == other.isRegularExpression
				&& location.equals(other.location)
			;
		}

		@Override
		public int compareTo(Location other) {
			// Put non regular expressions first since they are (presumably) faster to process
			if(!isRegularExpression && other.isRegularExpression) return -1;
			if(isRegularExpression && !other.isRegularExpression) return 1;
			// Then compare by location
			return location.compareTo(other.location);
		}
	}

	/**
	 * Gets an unmodifable set of URL patterns that should be rejected.
	 */
	public SortedSet<Location> getRejectedLocations() throws IOException, SQLException {
		return unmodifiableStandardRejectedLocations;
	}

	public static class PermanentRewriteRule {
		public final String pattern;
		public final String substitution;
		public final boolean noEscape;
		private PermanentRewriteRule(String pattern, String substitution, boolean noEscape) {
			this.pattern = pattern;
			this.substitution = substitution;
			this.noEscape = noEscape;
		}
		private PermanentRewriteRule(String pattern, String substitution) {
			this(pattern, substitution, true);
		}
	}

	private static final List<PermanentRewriteRule> standardPermanentRewriteRules = new ArrayList<>();
	private static final List<PermanentRewriteRule> unmodifiableStandardPermanentRewriteRules = Collections.unmodifiableList(standardPermanentRewriteRules);
	static {
		// emacs / kwrite
		standardPermanentRewriteRules.add(new PermanentRewriteRule("^(.*)~$", "$1"));
		standardPermanentRewriteRules.add(new PermanentRewriteRule("^(.*)~/(.*)$", "$1/$2"));

		// vi / vim
		// .test.php.swp
		standardPermanentRewriteRules.add(new PermanentRewriteRule("^(.*/)\\.([^/]+)\\.swp$", "$1$2"));
		standardPermanentRewriteRules.add(new PermanentRewriteRule("^(.*/)\\.([^/]+)\\.swp/(.*)$", "$1$2/$3"));

		// Some other kind (seen as left-over #wp-config.php# in web root)
		// #wp-config.php#
		standardPermanentRewriteRules.add(new PermanentRewriteRule("^(.*/)#([^/]+)#$", "$1$2")); // TODO [NE]? % encoded?
		standardPermanentRewriteRules.add(new PermanentRewriteRule("^(.*/)#([^/]+)#/(.*)$", "$1$2/$3")); // TODO [NE]? % encoded?

		// TODO: nano .save files? https://askubuntu.com/questions/601985/what-are-save-files

		// Should we report these in the distro scans instead of using these rules?

		//standardPermanentRewriteRules.put("^(.*)\\.do~$", "$1.do");
		//standardPermanentRewriteRules.put("^(.*)\\.do~/(.*)$", "$1.do/$2");
		//standardPermanentRewriteRules.put("^(.*)\\.jsp~$", "$1.jsp");
		//standardPermanentRewriteRules.put("^(.*)\\.jsp~/(.*)$", "$1.jsp/$2");
		//standardPermanentRewriteRules.put("^(.*)\\.jspa~$", "$1.jspa");
		//standardPermanentRewriteRules.put("^(.*)\\.jspa~/(.*)$", "$1.jspa/$2");
		//standardPermanentRewriteRules.put("^(.*)\\.php~$", "$1.php");
		//standardPermanentRewriteRules.put("^(.*)\\.php~/(.*)$", "$1.php/$2");
		//standardPermanentRewriteRules.put("^(.*)\\.php3~$", "$1.php3");
		//standardPermanentRewriteRules.put("^(.*)\\.php3~/(.*)$", "$1.php3/$2");
		//standardPermanentRewriteRules.put("^(.*)\\.php4~$", "$1.php4");
		//standardPermanentRewriteRules.put("^(.*)\\.php4~/(.*)$", "$1.php4/$2");
		//standardPermanentRewriteRules.put("^(.*)\\.phtml~$", "$1.phtml");
		//standardPermanentRewriteRules.put("^(.*)\\.phtml~/(.*)$", "$1.phtml/$2");
		//standardPermanentRewriteRules.put("^(.*)\\.shtml~$", "$1.shtml");
		//standardPermanentRewriteRules.put("^(.*)\\.shtml~/(.*)$", "$1.shtml/$2");
		//standardPermanentRewriteRules.put("^(.*)\\.vm~$", "$1.vm");
		//standardPermanentRewriteRules.put("^(.*)\\.vm~/(.*)$", "$1.vm/$2");
		//standardPermanentRewriteRules.put("^(.*)\\.xml~$", "$1.xml");
		//standardPermanentRewriteRules.put("^(.*)\\.xml~/(.*)$", "$1.xml/$2");
	}

	/**
	 * Gets the set of permanent rewrite rules.  By default, this protects
	 * automatic backups of common file extensions that contain server-side
	 * code that should not be externally visible.
	 */
	public List<PermanentRewriteRule> getPermanentRewriteRules() {
		return unmodifiableStandardPermanentRewriteRules;
	}

	/**
	 * By default, sites will block all TRACE and TRACK requests.
	 *
	 * Seriously consider security ramifications before enabling TRACK and TRACE.
	 */
	public boolean blockAllTraceAndTrackRequests() {
		return true;
	}

	public static class JkSetting implements Comparable<JkSetting> {

		final private boolean isMount;
		final private String path;
		final private String jkCode;

		public JkSetting(boolean isMount, String path, String jkCode) {
			this.isMount = isMount;
			this.path = path;
			this.jkCode = jkCode;
		}

		public boolean isMount() {
			return isMount;
		}

		public String getPath() {
			return path;
		}

		public String getJkCode() {
			return jkCode;
		}

		@Override
		public int hashCode() {
			int hashCode = path.hashCode()*31 + jkCode.hashCode();
			// Negate for mounts
			if(isMount) hashCode = -hashCode;
			return hashCode;
		}

		@Override
		public boolean equals(Object O) {
			if(O==null) return false;
			if(!(O instanceof JkSetting)) return false;
			JkSetting other = (JkSetting)O;
			return
				isMount == other.isMount
				&& path.equals(other.path)
				&& jkCode.equals(other.jkCode)
			;
		}

		@Override
		public int compareTo(JkSetting other) {
			// Put mounts before unmounts
			if(isMount && !other.isMount) return -1;
			if(!isMount && other.isMount) return 1;
			// Then compare by path
			int diff = path.compareTo(other.path);
			if(diff!=0) return diff;
			// Finallyl by jkCode
			return jkCode.compareTo(other.jkCode);
		}
	}

	private static final SortedSet<JkSetting> emptyJkSettings = Collections.unmodifiableSortedSet(new TreeSet<JkSetting>());

	/**
	 * Gets the JkMount and JkUnmounts for this site.
	 * 
	 * This default implementation returns an empty set.
	 * 
	 * @return  An empty set if no Jk enabled.
	 */
	public SortedSet<JkSetting> getJkSettings() throws IOException, SQLException {
		return emptyJkSettings;
	}

	public static class WebAppSettings {

		/** https://httpd.apache.org/docs/2.4/mod/core.html#options */
		public static String generateOptions(
			boolean enableSsi,
			boolean enableIndexes,
			boolean enableFollowSymlinks
		) {
			StringBuilder options = new StringBuilder();
			if(enableFollowSymlinks) options.append("FollowSymLinks");
			if(enableSsi) {
				if(options.length() > 0) options.append(' ');
				options.append("IncludesNOEXEC");
			}
			if(enableIndexes) {
				if(options.length() > 0) options.append(' ');
				options.append("Indexes");
			}
			if(options.length() == 0) {
				return "None";
			} else {
				return options.toString();
			}
		}

		/** https://httpd.apache.org/docs/2.4/mod/core.html#options */
		public static String generateCgiOptions(
			boolean enableCgi,
			boolean enableFollowSymlinks
		) {
			if(enableCgi) {
				if(enableFollowSymlinks) {
					return "ExecCGI FollowSymLinks";
				} else {
					return "ExecCGI";
				}
			} else {
				return "None";
			}
		}

		private final UnixPath docBase;
		private final String allowOverride;
		private final String options;
		private final boolean enableSsi;
		private final boolean enableCgi;
		private final String cgiOptions;

		public WebAppSettings(UnixPath docBase, String allowOverride, String options, boolean enableSsi, boolean enableCgi, String cgiOptions) {
			this.docBase = docBase;
			this.allowOverride = allowOverride;
			this.options = options;
			this.enableSsi = enableSsi;
			this.enableCgi = enableCgi;
			this.cgiOptions = cgiOptions;
		}

		public WebAppSettings(
			UnixPath docBase,
			String allowOverride,
			boolean enableSsi,
			boolean enableIndexes,
			boolean enableFollowSymlinks,
			boolean enableCgi
		) {
			this(
				docBase,
				allowOverride,
				generateOptions(enableSsi, enableIndexes, enableFollowSymlinks),
				enableSsi,
				enableCgi,
				generateCgiOptions(enableCgi, enableFollowSymlinks)
			);
		}

		public UnixPath getDocBase() {
			return docBase;
		}

		public String getAllowOverride() {
			return allowOverride;
		}

		public String getOptions() {
			return options;
		}

		public boolean enableSsi() {
			return enableSsi;
		}

		public boolean enableCgi() {
			return enableCgi;
		}

		public String getCgiOptions() {
			return cgiOptions;
		}
	}

	/**
	 * Gets all the webapps for this site.  The key is the webapp path
	 * and the value is the settings for that path.  If any webapp enables
	 * CGI, then this site overall must allow CGI.
	 */
	public abstract SortedMap<String,WebAppSettings> getWebapps() throws IOException, SQLException;
}
