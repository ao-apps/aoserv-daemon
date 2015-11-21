/*
 * Copyright 2007-2013, 2014, 2015 by AO Industries, Inc.,
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
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.httpd.tomcat.HttpdTomcatSiteManager;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.FileUtils;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

/**
 * Manages HttpdSite configurations.
 *
 * @author  AO Industries, Inc.
 */
public abstract class HttpdSiteManager {

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
	 * Responsible for control of all things in /www
	 *
	 * Only called by the already synchronized <code>HttpdManager.doRebuild()</code> method.
	 */
	static void doRebuild(
		List<File> deleteFileList,
		Set<HttpdSite> sitesNeedingRestarted,
		Set<HttpdSharedTomcat> sharedTomcatsNeedingRestarted
	) throws IOException, SQLException {
		// Get values used in the rest of the method.
		HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
		AOServer aoServer = AOServDaemon.getThisAOServer();

		// The www directories that exist but are not used will be removed
		UnixFile wwwDirectory = new UnixFile(osConfig.getHttpdSitesDirectory());
		String[] list = wwwDirectory.list();
		Set<String> wwwRemoveList = new HashSet<>(list.length*4/3+1);
		for(int c=0;c<list.length;c++) {
			String dirname=list[c];
			if(
				!dirname.equals("lost+found")
				&& !dirname.equals("aquota.user")
			) {
				wwwRemoveList.add(dirname);
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
			manager.buildSiteDirectory(siteDirectory, sitesNeedingRestarted, sharedTomcatsNeedingRestarted);
			wwwRemoveList.remove(siteName);
		}

		// Stop, disable, and mark files for deletion
		for(String siteName : wwwRemoveList) {
			UnixFile removeFile = new UnixFile(wwwDirectory, siteName, false);
			// Stop and disable any daemons
			stopAndDisableDaemons(removeFile);
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
	static void stopStartAndRestart(Set<HttpdSite> sitesNeedingRestarted) throws IOException, SQLException {
		for(HttpdSite httpdSite : AOServDaemon.getThisAOServer().getHttpdSites()) {
			HttpdSiteManager manager = getInstance(httpdSite);
			if(manager instanceof StopStartable) {
				final StopStartable stopStartRestartable = (StopStartable)manager;
				Callable<Object> commandCallable;
				if(stopStartRestartable.isStartable()) {
					// Enabled, start or restart
					if(sitesNeedingRestarted.contains(httpdSite)) {
						commandCallable = new Callable<Object>() {
							@Override
							public Object call() throws IOException, SQLException {
								if(stopStartRestartable.stop()) {
									try {
										Thread.sleep(5000);
									} catch(InterruptedException err) {
										LogFactory.getLogger(HttpdSiteManager.class).log(Level.WARNING, null, err);
									}
								}
								stopStartRestartable.start();
								return null;
							}
						};
					} else {
						commandCallable = new Callable<Object>() {
							@Override
							public Object call() throws IOException, SQLException {
								stopStartRestartable.start();
								return null;
							}
						};
					}
				} else {
					// Disabled, can only stop if needed
					commandCallable = new Callable<Object>() {
						@Override
						public Object call() throws IOException, SQLException {
							stopStartRestartable.stop();
							return null;
						}
					};
				}
				try {
					Future<Object> commandFuture = AOServDaemon.executorService.submit(commandCallable);
					commandFuture.get(60, TimeUnit.SECONDS);
				} catch(InterruptedException | ExecutionException | TimeoutException err) {
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
			LinuxServerAccount daemonLsa = AOServDaemon.getThisAOServer().getLinuxServerAccount(daemonUid);
			// If the account doesn't exist or is disabled, the process killer will kill any processes
			if(daemonLsa!=null && !daemonLsa.isDisabled()) {
				String[] list = daemonDirectory.list();
				if(list!=null) {
					for(String scriptName : list) {
						final UnixFile scriptFile = new UnixFile(daemonDirectory, scriptName, false);
						// Call stop with a one-minute time-out if not owned by root
						if(daemonUid!=UnixFile.ROOT_UID) {
							final String username = daemonLsa.getLinuxAccount().getUsername().getUsername();
							try {
								Future<Object> stopFuture = AOServDaemon.executorService.submit(
									new Callable<Object>() {
										@Override
										public Object call() throws IOException {
											AOServDaemon.suexec(
												username,
												scriptFile.getPath()+" stop",
												0
											);
											return null;
										}
									}
								);
								stopFuture.get(60, TimeUnit.SECONDS);
							} catch(InterruptedException | ExecutionException | TimeoutException err) {
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
			+ "  (866) 270-6195\n"
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
	 * Gets the auto-mode warning using Unix-style comments (#).  This
	 * may be used on any config files that a user would be tempted to change
	 * directly.
	 */
	/* Change to 2054542556 if re-enabled: public String getAutoWarningUnix() throws IOException, SQLException {
		return
			"#\n"
			+ "# Warning: This file is automatically created by HttpdManager.  Any manual changes\n"
			+ "# to this file will be overwritten.  Please set the is_manual flag for this website\n"
			+ "# to be able to make permanent changes to this file.\n"
			+ "#\n"
			+ "# Control Panel: https://www.aoindustries.com/clientarea/control/httpd/HttpdSiteCP.ao?pkey="+httpdSite.getPkey()+"\n"
			+ "#\n"
			+ "# AOSH: "+AOSHCommand.SET_HTTPD_SITE_IS_MANUAL+" "+httpdSite.getSiteName()+' '+httpdSite.getAOServer().getHostname()+" true\n"
			+ "#\n"
			+ "# support@aoindustries.com\n"
			+ "# (866) 270-6195\n"
			+ "#\n"
		;
	}*/

	/**
	 * Gets any packages that must be installed for this site.
	 *
	 * By default, no specific packages are required.
	 */
	protected Set<PackageManager.PackageName> getRequiredPackages() {
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
	protected abstract void buildSiteDirectory(UnixFile siteDirectory, Set<HttpdSite> sitesNeedingRestarted, Set<HttpdSharedTomcat> sharedTomcatsNeedingRestarted) throws IOException, SQLException;

	/**
	 * Determines if should have anonymous FTP area.
	 */
	public abstract boolean enableAnonymousFtp();

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
				httpdSite.getLinuxServerAccount().getUid().getID(),
				httpdSite.getLinuxServerGroup().getGid().getID()
			);
		}
	}

	/**
	 * Determines if CGI should be enabled.
	 */
	protected abstract boolean enableCgi();

	/**
	 * Determines if PHP should be enabled.
	 *
	 * If this is enabled and CGI is disabled, then the HttpdServer for the
	 * site must use mod_php.
	 */
	protected abstract boolean enablePhp();

	/**
	 * Creates or updates the CGI php script, CGI must be enabled and PHP enabled.
	 * If CGI is disabled or PHP is disabled, removes any php script.
	 * Any existing file will be overwritten, even when in manual mode.
	 */
	protected void createCgiPhpScript(UnixFile cgibinDirectory) throws IOException, SQLException {
		UnixFile phpFile = new UnixFile(cgibinDirectory, "php", false);
		// TODO: If every server this site runs as uses mod_php, then don't make the script
		// TODO: Make based on per-httpd_site setting
		if(enableCgi() && enablePhp()) {
			final int osv = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPkey();
			HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
			final String phpMinorVersion;
			if(phpFile.getStat().exists()) {
				// TODO: Get from DB-based per-httpd_site config
				String contents = FileUtils.readFileAsString(phpFile.getFile());
				if(
					contents.contains("/opt/php-5/bin/php")
					|| contents.contains("/opt/php-5-i686/bin/php")
				) {
					switch(osv) {
						case OperatingSystemVersion.REDHAT_ES_4_X86_64 :
							phpMinorVersion = "5.2";
							break;
						case OperatingSystemVersion.CENTOS_5_I686_AND_X86_64 :
							phpMinorVersion = "5.3";
							break;
						default :
							throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);
					}
				} else if(
					contents.contains("/opt/php-5.2/bin/php")
					|| contents.contains("/opt/php-5.2-i686/bin/php")
				) {
					phpMinorVersion = "5.2";
				} else if(
					contents.contains("/opt/php-5.3/bin/php")
					|| contents.contains("/opt/php-5.3-i686/bin/php")
				) {
					phpMinorVersion = "5.3";
				} else if(
					contents.contains("/opt/php-5.4/bin/php")
					|| contents.contains("/opt/php-5.4-i686/bin/php")
				) {
					phpMinorVersion = "5.4";
				} else if(
					contents.contains("/opt/php-5.5/bin/php")
					|| contents.contains("/opt/php-5.5-i686/bin/php")
				) {
					phpMinorVersion = "5.5";
				} else if(
					contents.contains("/opt/php-4/bin/php")
					|| contents.contains("/opt/php-4-i686/bin/php")
				) {
					phpMinorVersion = "4.4";
				} else {
					phpMinorVersion = osConfig.getDefaultPhpMinorVersion();
				}
			} else {
				phpMinorVersion = osConfig.getDefaultPhpMinorVersion();
			}
			String phpCgiPath = osConfig.getPhpCgiPath(phpMinorVersion);

			PackageManager.PackageName requiredPackage;

			// Build to RAM first
			ByteArrayOutputStream bout = new ByteArrayOutputStream();
			try (ChainWriter out = new ChainWriter(bout)) {
				out.print("#!/bin/sh\n");
				switch (phpMinorVersion) {
					case "4.4":
						requiredPackage = null;
						out.print(". /opt/mysql-5.0-i686/setenv.sh\n");
						out.print(". /opt/postgresql-7.3-i686/setenv.sh\n");
						break;
					case "5.2":
						requiredPackage = PackageManager.PackageName.PHP_5_2;
						out.print(". /opt/mysql-5.0-i686/setenv.sh\n");
						out.print(". /opt/postgresql-8.3-i686/setenv.sh\n");
						break;
					case "5.3":
						requiredPackage = PackageManager.PackageName.PHP_5_3;
						out.print(". /opt/mysql-5.1-i686/setenv.sh\n");
						out.print(". /opt/postgresql-8.3-i686/setenv.sh\n");
						break;
					case "5.4":
						requiredPackage = PackageManager.PackageName.PHP_5_4;
						out.print(". /opt/mysql-5.6-i686/setenv.sh\n");
						out.print(". /opt/postgresql-9.2-i686/setenv.sh\n");
						break;
					case "5.5":
						requiredPackage = PackageManager.PackageName.PHP_5_5;
						out.print(". /opt/mysql-5.6-i686/setenv.sh\n");
						out.print(". /opt/postgresql-9.2-i686/setenv.sh\n");
						break;
					default:
						throw new SQLException("Unexpected version for php: "+phpMinorVersion);
				}
				out.print("exec ").print(phpCgiPath).print(" \"$@\"\n");
			}
			// Make sure required RPM is installed
			if(requiredPackage != null) PackageManager.installPackage(requiredPackage);

			// Only rewrite when needed
			int uid = httpdSite.getLinuxServerAccount().getUid().getID();
			int gid = httpdSite.getLinuxServerGroup().getGid().getID();
			int mode = 0755;
			// Create parent if missing
			UnixFile parent = cgibinDirectory.getParent();
			if(!parent.getStat().exists()) parent.mkdir(true, 0775, uid, gid);
			// Create cgi-bin if missing
			DaemonFileUtils.mkdir(cgibinDirectory, 0755, uid, gid);
			DaemonFileUtils.writeIfNeeded(
				bout.toByteArray(),
				null,
				phpFile,
				uid,
				gid,
				mode
			);
			// Make sure permissions correct
			Stat phpStat = phpFile.getStat();
			if(phpStat.getUid()!=uid || phpStat.getGid()!=gid) phpFile.chown(uid, gid);
			if(phpStat.getMode()!=mode) phpFile.setMode(mode);
		} else {
			if(phpFile.getStat().exists()) phpFile.delete();
		}
	}

	/**
	 * Creates the test CGI script, must have CGI enabled.
	 * If CGI is disabled, does nothing.
	 * Any existing file will not be overwritten, even when in auto mode.
	 */
	protected void createTestCGI(UnixFile cgibinDirectory) throws IOException, SQLException {
		if(enableCgi()) {
			UnixFile testFile = new UnixFile(cgibinDirectory, "test", false);
			if(!testFile.getStat().exists()) {
				HttpdSiteURL primaryHsu = httpdSite.getPrimaryHttpdSiteURL();
				String primaryUrl = primaryHsu==null ? httpdSite.getSiteName() : primaryHsu.getHostname().toString();
				// Write to temp file first
				UnixFile tempFile = UnixFile.mktemp(testFile.getPath()+".", false);
				try {
					try (ChainWriter out = new ChainWriter(new FileOutputStream(tempFile.getFile()))) {
						out.print("#!/usr/bin/perl\n"
								+ "print \"Content-Type: text/html\\n\";\n"
								+ "print \"\\n\";\n"
								+ "print \"<html>\\n\";\n"
								+ "print \"  <head><title>Test CGI Page for ").print(primaryUrl).print("</title></head>\\n\";\n"
								+ "print \"  <body>\\n\";\n"
								+ "print \"    Test CGI Page for ").print(primaryUrl).print("<br />\\n\";\n"
								+ "print \"    <br />\\n\";\n"
								+ "print \"    The current time is \";\n"
								+ "@date=`/bin/date -R`;\n"
								+ "chop(@date);\n"
								+ "print \"@date.\\n\";\n"
								+ "print \"  </body>\\n\";\n"
								+ "print \"</html>\\n\";\n");
					}
					// Set permissions and ownership
					tempFile.setMode(0755);
					tempFile.chown(httpdSite.getLinuxServerAccount().getUid().getID(), httpdSite.getLinuxServerGroup().getGid().getID());
					// Move into place
					tempFile.renameTo(testFile);
				} finally {
					// If still exists then there was a problem, clean-up
					if(tempFile.getStat().exists()) tempFile.delete();
				}
			}
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
			UnixFile tempFile = UnixFile.mktemp(indexFile.getPath()+".", false);
			try {
				try (ChainWriter out = new ChainWriter(new FileOutputStream(tempFile.getFile()))) {
					out.print("<html>\n"
							+ "  <head><title>Test HTML Page for ").print(primaryUrl).print("</title></head>\n"
							+ "  <body>\n"
							+ "    Test HTML Page for ").print(primaryUrl).print("\n"
							+ "  </body>\n"
							+ "</html>\n");
				}
				// Set permissions and ownership
				tempFile.setMode(0664);
				tempFile.chown(httpdSite.getLinuxServerAccount().getUid().getID(), httpdSite.getLinuxServerGroup().getGid().getID());
				// Move into place
				tempFile.renameTo(indexFile);
			} finally {
				// If still exists then there was a problem, clean-up
				if(tempFile.getStat().exists()) tempFile.delete();
			}
		}
	}

	/**
	 * Creates the test PHP script, must have PHP enabled.
	 * If PHP is disabled, does nothing.
	 * Any existing file will not be overwritten, even when in auto mode.
	 */
	protected void createTestPHP(UnixFile rootDirectory) throws IOException, SQLException {
		// TODO: Overwrite the phpinfo pages with this new version, for security
		if(enablePhp()) {
			UnixFile testFile = new UnixFile(rootDirectory, "test.php", false);
			if(!testFile.getStat().exists()) {
				HttpdSiteURL primaryHsu = httpdSite.getPrimaryHttpdSiteURL();
				String primaryUrl = primaryHsu==null ? httpdSite.getSiteName() : primaryHsu.getHostname().toString();
				// Write to temp file first
				UnixFile tempFile = UnixFile.mktemp(testFile.getPath()+".", false);
				try {
					try (ChainWriter out = new ChainWriter(new FileOutputStream(tempFile.getFile()))) {
					out.print("<html>\n"
							+ "  <head><title>Test PHP Page for ").print(primaryUrl).print("</title></head>\n"
							+ "  <body>\n"
							+ "    Test PHP Page for ").print(primaryUrl).print("<br />\n"
							+ "    <br />\n"
							+ "    The current time is <?= date('r') ?>.\n"
							+ "  </body>\n"
							+ "</html>\n");
					}
					// Set permissions and ownership
					tempFile.setMode(0664);
					tempFile.chown(httpdSite.getLinuxServerAccount().getUid().getID(), httpdSite.getLinuxServerGroup().getGid().getID());
					// Move into place
					tempFile.renameTo(testFile);
				} finally {
					// If still exists then there was a problem, clean-up
					if(tempFile.getStat().exists()) tempFile.delete();
				}
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
				int hsUid = hsb.getHttpdBind().getHttpdServer().getLinuxServerAccount().getUid().getID();
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
			uid = apacheLsa.getUid().getID();
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

	private static final SortedMap<String,String> standardPermanentRewriteRules = new TreeMap<>();
	private static final SortedMap<String,String> unmodifiableStandardPermanentRewriteRules = Collections.unmodifiableSortedMap(standardPermanentRewriteRules);
	static {
		// TODO: Benchmark faster with single or multiple rules
		standardPermanentRewriteRules.put("^(.*)~$", "$1");
		standardPermanentRewriteRules.put("^(.*)~/(.*)$", "$1/$2");
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
	public SortedMap<String,String> getPermanentRewriteRules() {
		return unmodifiableStandardPermanentRewriteRules;
	}

	/**
	 * By default, sites will block all TRACE and TRACK requests.
	 *
	 * Seriously consider security ramifications before enabling.
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

		private final String docBase;
		private final String allowOverride;
		private final String options;
		private final boolean enableCgi;

		public WebAppSettings(String docBase, String allowOverride, String options, boolean enableCgi) {
			this.docBase = docBase;
			this.allowOverride = allowOverride;
			this.options = options;
			this.enableCgi = enableCgi;
		}

		public String getDocBase() {
			return docBase;
		}

		public String getAllowOverride() {
			return allowOverride;
		}

		public String getOptions() {
			return options;
		}

		public boolean enableCgi() {
			return enableCgi;
		}
	}

	/**
	 * Gets all the webapps for this site.  The key is the webapp path
	 * and the value is the settings for that path.  If any webapp enables
	 * CGI, then this site overall must allow CGI.
	 */
	public abstract SortedMap<String,WebAppSettings> getWebapps() throws IOException, SQLException;
}
