/*
 * Copyright 2008-2013, 2014, 2015, 2016, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.AOSHCommand;
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.HttpdSharedTomcat;
import com.aoindustries.aoserv.client.HttpdTomcatVersion;
import com.aoindustries.aoserv.client.validator.UnixPath;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.httpd.HttpdSiteManager;
import com.aoindustries.aoserv.daemon.httpd.StopStartable;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.io.FileUtils;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.validation.ValidationException;
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
import java.util.logging.Level;

/**
 * Manages HttpdSharedTomcat configurations.
 *
 * @author  AO Industries, Inc.
 */
public abstract class HttpdSharedTomcatManager<TC extends TomcatCommon> implements StopStartable {

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
		if(htv.isTomcat7_0_X(connector)) return new HttpdSharedTomcatManager_7_0_X(sharedTomcat);
		if(htv.isTomcat8_0_X(connector)) return new HttpdSharedTomcatManager_8_0_X(sharedTomcat);
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
		try {
			// Get values used in the rest of the method.
			HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
			AOServer aoServer = AOServDaemon.getThisAOServer();

			// The www group directories that exist but are not used will be removed
			UnixFile wwwgroupDirectory = new UnixFile(osConfig.getHttpdSharedTomcatsDirectory().toString());
			String[] list = wwwgroupDirectory.list();
			Set<String> wwwgroupRemoveList = new HashSet<>(list.length*4/3+1);
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

				// Install any required RPMs
				PackageManager.installPackages(manager.getRequiredPackages());

				// Create and fill in any incomplete installations.
				final String tomcatName = sharedTomcat.getName();
				UnixFile sharedTomcatDirectory = new UnixFile(wwwgroupDirectory, tomcatName, false);
				manager.buildSharedTomcatDirectory(sharedTomcatDirectory, deleteFileList, sharedTomcatsNeedingRestarted);
				if(manager.upgradeSharedTomcatDirectory(sharedTomcatDirectory)) sharedTomcatsNeedingRestarted.add(sharedTomcat);
				wwwgroupRemoveList.remove(tomcatName);
			}

			// Stop, disable, and mark files for deletion
			for (String tomcatName : wwwgroupRemoveList) {
				UnixFile removeFile = new UnixFile(wwwgroupDirectory, tomcatName, false);
				// Stop and disable any daemons
				stopAndDisableDaemons(removeFile);
				// Only remove the directory when not used by a home directory
				if(!aoServer.isHomeUsed(UnixPath.valueOf(removeFile.getPath()))) deleteFileList.add(removeFile.getFile());
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
	public static void stopStartAndRestart(Set<HttpdSharedTomcat> sharedTomcatsNeedingRestarted) throws IOException, SQLException {
		for(HttpdSharedTomcat sharedTomcat : AOServDaemon.getThisAOServer().getHttpdSharedTomcats()) {
			final HttpdSharedTomcatManager<?> manager = getInstance(sharedTomcat);

			Callable<Object> commandCallable;
			if(!sharedTomcat.isDisabled() && !sharedTomcat.getHttpdTomcatSharedSites().isEmpty()) {
				// Enabled and has sites, start or restart
				if(sharedTomcatsNeedingRestarted.contains(sharedTomcat)) {
					commandCallable = () -> {
						if(manager.stop()) {
							try {
								Thread.sleep(5000);
							} catch(InterruptedException err) {
								LogFactory.getLogger(HttpdSharedTomcatManager.class).log(Level.WARNING, null, err);
								// Restore the interrupted status
								Thread.currentThread().interrupt();
							}
						}
						manager.start();
						return null;
					};
				} else {
					commandCallable = () -> {
						manager.start();
						return null;
					};
				}
			} else {
				// Disabled or has no sites, can only stop if needed
				commandCallable = () -> {
					manager.stop();
					return null;
				};
			}
			try {
				Future<Object> commandFuture = AOServDaemon.executorService.submit(commandCallable);
				commandFuture.get(60, TimeUnit.SECONDS);
			} catch(InterruptedException err) {
				LogFactory.getLogger(HttpdSharedTomcatManager.class).log(Level.WARNING, null, err);
				// Restore the interrupted status
				Thread.currentThread().interrupt();
			} catch(ExecutionException | TimeoutException err) {
				LogFactory.getLogger(HttpdSharedTomcatManager.class).log(Level.WARNING, null, err);
			}
		}
	}

	/**
	 * @see  HttpdSiteManager#stopAndDisableDaemons
	 */
	private static void stopAndDisableDaemons(UnixFile sharedTomcatDirectory) throws IOException, SQLException {
		HttpdSiteManager.stopAndDisableDaemons(sharedTomcatDirectory);
	}

	final protected HttpdSharedTomcat sharedTomcat;

	HttpdSharedTomcatManager(HttpdSharedTomcat sharedTomcat) {
		this.sharedTomcat = sharedTomcat;
	}

	/**
	 * Gets the auto-mode warning for this website for use in XML files.  This
	 * may be used on any config files that a user would be tempted to change
	 * directly.
	 *
	 * TODO: Change www.aoindustries.com to aoindustries.com
	 */
	String getAutoWarningXmlOld() throws IOException, SQLException {
		return
			"<!--\n"
			+ "  Warning: This file is automatically created by HttpdManager.  Any manual changes\n"
			+ "  to this file will be overwritten.  Please set the is_manual flag for this multi-site\n"
			+ "  JVM to be able to make permanent changes to this file.\n"
			+ "\n"
			+ "  Control Panel: https://www.aoindustries.com/clientarea/control/httpd/HttpdSharedTomcatCP.ao?pkey="+sharedTomcat.getPkey()+"\n"
			+ "\n"
			+ "  AOSH: "+AOSHCommand.SET_HTTPD_SHARED_TOMCAT_IS_MANUAL+" "+sharedTomcat.getName()+" "+sharedTomcat.getAOServer().getHostname()+" true\n"
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
	String getAutoWarningXml() throws IOException, SQLException {
		return
			"<!--\n"
			+ "  Warning: This file is automatically created by HttpdManager.  Any manual changes\n"
			+ "  to this file will be overwritten.  Please set the is_manual flag for this multi-site\n"
			+ "  JVM to be able to make permanent changes to this file.\n"
			+ "\n"
			+ "  Control Panel: https://aoindustries.com/clientarea/control/httpd/HttpdSharedTomcatCP.ao?pkey="+sharedTomcat.getPkey()+"\n"
			+ "\n"
			+ "  AOSH: "+AOSHCommand.SET_HTTPD_SHARED_TOMCAT_IS_MANUAL+" "+sharedTomcat.getName()+" "+sharedTomcat.getAOServer().getHostname()+" true\n"
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
	 *
	 * TODO: Change www.aoindustries.com to aoindustries.com
	 */
	/*String getAutoWarningUnixOld() throws IOException, SQLException {
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
	}*/

	/**
	 * Gets the auto-mode warning using Unix-style comments (#).  This
	 * may be used on any config files that a user would be tempted to change
	 * directly.
	 *
	 * TODO: Change www.aoindustries.com to aoindustries.com
	 */
	/*String getAutoWarningUnix() throws IOException, SQLException {
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
			+ "# (205) 454-2556\n"
			+ "#\n"
		;
	}*/

	/**
	 * Gets any packages that must be installed for this site.
	 *
	 * By default, uses the package required for Tomcat.
	 */
	protected Set<PackageManager.PackageName> getRequiredPackages() {
		return getTomcatCommon().getRequiredPackages();
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
	 * Upgrades the site directory contents for an auto-upgrade.
	 *
	 * @return  <code>true</code> if the site needs to be restarted.
	 */
	protected abstract boolean upgradeSharedTomcatDirectory(UnixFile siteDirectory) throws IOException, SQLException;

	/**
	 * Gets the PID file.
	 */
	public UnixFile getPidFile() throws IOException, SQLException {
		return new UnixFile(
			HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration().getHttpdSharedTomcatsDirectory()
			+ "/"
			+ sharedTomcat.getName()
			+ "/var/run/tomcat.pid"
		);
	}

	@Override
	public boolean isStartable() {
		return !sharedTomcat.isDisabled();
	}

	public String getStartStopScriptPath() throws IOException, SQLException {
		return
			HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration().getHttpdSharedTomcatsDirectory()
			+ "/"
			+ sharedTomcat.getName()
			+ "/bin/tomcat"
		;
	}

	@Override
	public boolean stop() throws IOException, SQLException {
		UnixFile pidFile = getPidFile();
		if(pidFile.getStat().exists()) {
			AOServDaemon.suexec(
				sharedTomcat.getLinuxServerAccount().getLinuxAccount().getUsername().getUsername(),
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
				sharedTomcat.getLinuxServerAccount().getLinuxAccount().getUsername().getUsername(),
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
						sharedTomcat.getLinuxServerAccount().getLinuxAccount().getUsername().getUsername(),
						getStartStopScriptPath()+" start",
						0
					);
					return true;
				}
			} catch(NumberFormatException err) {
				LogFactory.getLogger(HttpdSharedTomcatManager.class).log(Level.WARNING, null, err);
			}
			return false;
		}
	}

	abstract TC getTomcatCommon();
}
