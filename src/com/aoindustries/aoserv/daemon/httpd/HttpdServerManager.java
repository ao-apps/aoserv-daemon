/*
 * Copyright 2008-2013, 2014, 2015, 2016, 2017, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.distribution.SoftwareVersion;
import com.aoindustries.aoserv.client.linux.Group;
import com.aoindustries.aoserv.client.linux.GroupServer;
import com.aoindustries.aoserv.client.linux.PosixPath;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.linux.User;
import com.aoindustries.aoserv.client.linux.UserServer;
import com.aoindustries.aoserv.client.net.AppProtocol;
import com.aoindustries.aoserv.client.net.Bind;
import com.aoindustries.aoserv.client.pki.Certificate;
import com.aoindustries.aoserv.client.util.ApacheEscape;
import static com.aoindustries.aoserv.client.util.ApacheEscape.escape;
import com.aoindustries.aoserv.client.web.Header;
import com.aoindustries.aoserv.client.web.HttpdBind;
import com.aoindustries.aoserv.client.web.HttpdServer;
import com.aoindustries.aoserv.client.web.Location;
import com.aoindustries.aoserv.client.web.RewriteRule;
import com.aoindustries.aoserv.client.web.Site;
import com.aoindustries.aoserv.client.web.VirtualHost;
import com.aoindustries.aoserv.client.web.VirtualHostName;
import com.aoindustries.aoserv.client.web.tomcat.Context;
import com.aoindustries.aoserv.client.web.tomcat.SharedTomcat;
import com.aoindustries.aoserv.client.web.tomcat.SharedTomcatSite;
import com.aoindustries.aoserv.client.web.tomcat.Worker;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.unix.linux.LinuxProcess;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.FileUtils;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.net.InetAddress;
import com.aoindustries.net.Port;
import com.aoindustries.selinux.SEManagePort;
import com.aoindustries.util.StringUtility;
import com.aoindustries.util.concurrent.ConcurrencyLimiter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.apache.commons.lang3.NotImplementedException;

/**
 * Manages HttpdServer configurations and control.
 * <p>
 * TODO: Install PHP packages as-needed on CentOS 7, including extensions and shared built-ins.
 * </p>
 * <p>
 * TODO: Write/update /etc/system/system/httpd[@name}.service.d/php.conf for PHP 7+
 *       Or, could this be somehow added by the php_7_* packages?
 * </p>
 *
 * @author  AO Industries, Inc.
 */
public class HttpdServerManager {

	private static final Logger logger = Logger.getLogger(HttpdServerManager.class.getName());

	private HttpdServerManager() {}

	/**
	 * Reverts configurations for Let's Encrypt compatibility.
	 * The recent change to extensive use of Apache directives to simplify Apache
	 * configs is not completely compatible with the current implementation of the
	 * Let's Encrypt certbot Apache plugin.  This can be revisited in the future
	 * as Certbot matures.
	 */
	private static final boolean CERTBOT_COMPAT = true;

	/**
	 * The directory that all HTTPD configs are located in (/etc/httpd).
	 */
	static final String SERVER_ROOT = "/etc/httpd";

	/**
	 * The directory that HTTPD conf are located in (/etc/httpd/conf).
	 */
	static final String CONF_DIRECTORY = SERVER_ROOT + "/conf";

	/**
	 * The pattern matching secondary httpd#.conf files.
	 */
	private static final Pattern HTTPD_N_CONF_REGEXP = Pattern.compile("^httpd[0-9]+\\.conf$");

	/**
	 * The pattern matching secondary httpd@&lt;name&gt;.conf files.
	 */
	private static final Pattern HTTPD_NAME_CONF_REGEXP = Pattern.compile("^httpd@.+\\.conf$");

	/**
	 * The pattern matching secondary httpd@&lt;name&gt; files.
	 */
	private static final Pattern HTTPD_NAME_REGEXP = Pattern.compile("^httpd@.+$");

	/**
	 * The pattern matching secondary php# config directories.
	 */
	private static final Pattern PHP_N_REGEXP = Pattern.compile("^php[0-9]+$");

	/**
	 * The pattern matching secondary php@&lt;name&gt; config directories.
	 */
	private static final Pattern PHP_NAME_REGEXP = Pattern.compile("^php@.+$");

	/**
	 * The name of the PHP <a href="http://php.net/manual/en/configuration.file.php#configuration.file.scan">PHP_INI_SCAN_DIR</a>
	 * when used as <code>mod_php</code>: <code>/etc/httpd/conf/php[@&lt;name&gt;]/conf.d</code>.
	 */
	private static final String MOD_PHP_CONF_D = "conf.d";

	/**
	 * The pattern matching secondary workers#.properties files.
	 */
	private static final Pattern WORKERS_N_PROPERTIES_REGEXP = Pattern.compile("^workers[0-9]+\\.properties$");

	/**
	 * The pattern matching secondary workers@&lt;name&gt;.properties files.
	 */
	private static final Pattern WORKERS_NAME_PROPERTIES_REGEXP = Pattern.compile("^workers@.+\\.properties$");

	/**
	 * The directory that tmpfiles.d/httpd[@&lt;name&gt;].conf files are located in (/etc/tmpfiles.d).
	 */
	static final String ETC_TMPFILES_D = "/etc/tmpfiles.d";

	/**
	 * The directory that individual host and bind configurations are in.
	 */
	private static final String CONF_HOSTS = CONF_DIRECTORY + "/hosts";

	/**
	 * The directory that individual host and bind configurations are for CentOS 7.
	 */
	private static final String SITES_AVAILABLE = SERVER_ROOT + "/sites-available";

	/**
	 * The directory that individual host and bind configurations are for CentOS 7.
	 */
	private static final String SITES_ENABLED = SERVER_ROOT + "/sites-enabled";

	/**
	 * The init.d directory.
	 */
	private static final String INIT_DIRECTORY = "/etc/rc.d/init.d";

	/**
	 * The pattern matching secondary httpd# files.
	 */
	private static final Pattern HTTPD_N_REGEXP = Pattern.compile("^httpd[0-9]+$");

	/**
	 * The systemd multi-user.target.wants directory where enabled/disabled httpd.service and httpd@.service instances are found.
	 */
	public static final String MULTI_USER_WANTS_DIRECTORY = "/etc/systemd/system/multi-user.target.wants";

	/**
	 * The pattern matching service httpd@&lt;name&gt;.service files.
	 * Used to clean old instances from {@link #MULTI_USER_WANTS_DIRECTORY}.
	 */
	private static final Pattern HTTPD_NAME_SERVICE_REGEXP = Pattern.compile("^httpd@.+\\.service$");

	/**
	 * The directory that contains PHP variable files.
	 */
	private static final UnixFile VAR_LIB_PHP_DIRECTORY = new UnixFile("/var/lib/php");

	/**
	 * The name of the PHP session folder for the default Apache instance
	 * and also the per-site PHP session folder.
	 *
	 * @see  HttpdSiteManager#VAR_PHP
	 */
	static final String PHP_SESSION = "session";

	/**
	 * The pattern matching PHP session[@&lt;name&gt;] directories.
	 * Used to clean old instances from {@link #VAR_LIB_PHP_DIRECTORY}.
	 */
	private static final Pattern PHP_SESSION_REGEXP = Pattern.compile("^" + PHP_SESSION + "@.+$");

	/**
	 * The SELinux type for httpd.
	 */
	private static final String SELINUX_TYPE = "http_port_t";

	/**
	 * The SELinux type for Tomcat AJP ports.
	 */
	private static final String AJP_SELINUX_TYPE = "ajp_port_t";

	/**
	 * Dollar escape not supported on CentOS 5 due to lack of <code>Define</code> directive.
	 */
	private static final String CENTOS_5_DOLLAR_VARIABLE = null;

	/**
	 * Dollar escape as variable named "$" is set in the aoserv-httpd-config package
	 * as <code>Define $ $</code> in <code>core.inc</code>, escaped as <code>${$}</code>.
	 */
	private static final String CENTOS_7_DOLLAR_VARIABLE = ApacheEscape.DEFAULT_DOLLAR_VARIABLE;

	/**
	 * Gets the workers#.properties or workers[@&lt;name&gt;].properties file path.
	 */
	private static String getWorkersFile(HttpdServer hs) throws IOException, SQLException {
		OperatingSystemVersion osv = hs.getAOServer().getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		switch(osvId) {
			case OperatingSystemVersion.CENTOS_5_I686_AND_X86_64 :
				String name = hs.getName();
				int num = (name == null) ? 1 : Integer.parseInt(name);
				return "workers" + num + ".properties";
			case OperatingSystemVersion.CENTOS_7_X86_64 :
				String escapedName = hs.getSystemdEscapedName();
				return (escapedName == null) ? "workers.properties" : ("workers@" + escapedName + ".properties");
			default :
				throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
		}
	}

	/**
	 * Gets the httpd#.conf or httpd[@&lt;name&gt;].conf file name.
	 */
	private static String getHttpdConfFile(HttpdServer hs) throws IOException, SQLException {
		OperatingSystemVersion osv = hs.getAOServer().getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		switch(osvId) {
			case OperatingSystemVersion.CENTOS_5_I686_AND_X86_64 :
				String name = hs.getName();
				int num = (name == null) ? 1 : Integer.parseInt(name);
				return "httpd" + num + ".conf";
			case OperatingSystemVersion.CENTOS_7_X86_64 :
				String escapedName = hs.getSystemdEscapedName();
				return (escapedName == null) ? "httpd.conf" : ("httpd@" + escapedName + ".conf");
			default :
				throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
		}
	}

	/**
	 * Only called by the already synchronized <code>HttpdManager.doRebuild()</code> method.
	 */
	static void doRebuild(
		List<File> deleteFileList,
		Set<HttpdServer> serversNeedingReloaded,
		Set<UnixFile> restorecon
	) throws IOException, SQLException {
		// Used below
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		Server aoServer = AOServDaemon.getThisAOServer();

		// Rebuild /etc/httpd/conf/hosts/ or /etc/httpd/sites-available and /etc/httpd/sites-enabled files
		doRebuildConfHosts(aoServer, bout, deleteFileList, serversNeedingReloaded, restorecon);

		// Rebuild /etc/httpd/conf/ files
		Set<Port> enabledAjpPorts = new HashSet<>();
		boolean[] hasAnyCgi = {false};
		boolean[] hasAnyModPhp = {false};
		doRebuildConf(aoServer, bout, deleteFileList, serversNeedingReloaded, enabledAjpPorts, restorecon, hasAnyCgi, hasAnyModPhp);

		// Control the /etc/rc.d/init.d/httpd# files or /etc/systemd/system/multi-user.target.wants/httpd[@<name>].service links
		doRebuildInitScripts(aoServer, bout, deleteFileList, serversNeedingReloaded, restorecon);

		// Configure SELinux
		doRebuildSELinux(aoServer, serversNeedingReloaded, enabledAjpPorts, hasAnyCgi[0], hasAnyModPhp[0]);

		// Other filesystem fixes related to logging
		fixFilesystem(deleteFileList);
	}

	/**
	 * Rebuilds the files in /etc/httpd/conf/hosts/
	 * or /etc/httpd/sites-available and /etc/httpd/sites-enabled
	 */
	private static void doRebuildConfHosts(
		Server thisAoServer,
		ByteArrayOutputStream bout,
		List<File> deleteFileList,
		Set<HttpdServer> serversNeedingReloaded,
		Set<UnixFile> restorecon
	) throws IOException, SQLException {
		OperatingSystemVersion osv = thisAoServer.getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		switch(osvId) {
			case OperatingSystemVersion.CENTOS_5_I686_AND_X86_64 : {
				// The config directory should only contain files referenced in the database, or "disabled"
				String[] list = new File(CONF_HOSTS).list();
				Set<String> extraFiles = new HashSet<>(list.length*4/3+1);
				extraFiles.addAll(Arrays.asList(list));

				// Iterate through each site
				for(Site httpdSite : thisAoServer.getHttpdSites()) {
					// Some values used below
					final String siteName = httpdSite.getName();
					final HttpdSiteManager manager = HttpdSiteManager.getInstance(httpdSite);
					final GroupServer lsg = httpdSite.getLinuxServerGroup();
					final int lsgGID = lsg.getGid().getId();
					final List<VirtualHost> binds = httpdSite.getHttpdSiteBinds();

					// Remove from delete list
					extraFiles.remove(siteName);

					// The shared config part
					final UnixFile sharedFile = new UnixFile(CONF_HOSTS, siteName);
					if(!manager.httpdSite.isManual() || !sharedFile.getStat().exists()) {
						if(
							DaemonFileUtils.atomicWrite(
								sharedFile,
								buildHttpdSiteSharedFile(manager, bout, restorecon),
								0640,
								UnixFile.ROOT_UID,
								lsgGID,
								null,
								restorecon
							)
						) {
							// File changed, all servers that use this site need restarted
							for(VirtualHost hsb : binds) serversNeedingReloaded.add(hsb.getHttpdBind().getHttpdServer());
						}
					}

					// Each of the binds
					for(VirtualHost bind : binds) {
						// Some value used below
						final boolean isManual = bind.isManual();
						final boolean isDisabled = bind.isDisabled();
						final String predisableConfig = bind.getPredisableConfig();
						// TODO: predisable_config and disabled state do not interact well.  When disabled, the predisable_config keeps getting used instead of any newly generated file.
						final HttpdBind httpdBind = bind.getHttpdBind();
						final Bind nb = httpdBind.getNetBind();
						// Generate the filename
						final String bindFilename;
						{
							String bindEscapedName = bind.getSystemdEscapedName();
							if(bindEscapedName == null) {
								bindFilename = siteName+"_"+nb.getIpAddress().getInetAddress()+"_"+nb.getPort().getPort();
							} else {
								bindFilename = siteName+"_"+nb.getIpAddress().getInetAddress()+"_"+nb.getPort().getPort()+"_"+bindEscapedName;
							}
						}
						final UnixFile bindFile = new UnixFile(CONF_HOSTS, bindFilename);
						final boolean exists = bindFile.getStat().exists();

						// Remove from delete list
						extraFiles.remove(bindFilename);

						// Will only be verified when not exists, auto mode, disabled, or predisabled config need to be restored
						if(
							!exists                                 // Not exists
							|| !isManual                            // Auto mode
							|| isDisabled                           // Disabled
							|| predisableConfig!=null               // Predisabled config needs to be restored
						) {
							// Save manual config file for later restoration
							if(exists && isManual && isDisabled && predisableConfig==null) {
								bind.setPredisableConfig(FileUtils.readFileAsString(bindFile.getFile()));
							}

							// Restore/build the file
							byte[] newContent;
							if(isManual && !isDisabled && predisableConfig!=null) {
								// Restore manual config values
								newContent = predisableConfig.getBytes();
							} else {
								// Create auto config
								if(isDisabled) PackageManager.installPackage(PackageManager.PackageName.AOSERV_HTTPD_SITE_DISABLED);
								newContent = buildHttpdSiteBindFile(
									manager,
									bind,
									isDisabled ? Site.DISABLED : siteName,
									bout
								);
							}
							// Write only when missing or modified
							if(
								DaemonFileUtils.atomicWrite(
									bindFile,
									newContent,
									0640,
									UnixFile.ROOT_UID,
									lsgGID,
									null,
									restorecon
								)
							) {
								// Reload server if the file is modified
								serversNeedingReloaded.add(httpdBind.getHttpdServer());
							}
						}
					}
				}

				// Mark files for deletion
				for(String filename : extraFiles) {
					if(!filename.equals(Site.DISABLED)) {
						File toDelete = new File(CONF_HOSTS, filename);
						if(logger.isLoggable(Level.INFO)) logger.info("Scheduling for removal: " + toDelete);
						deleteFileList.add(toDelete);
					}
				}
				break;
			}
			case OperatingSystemVersion.CENTOS_7_X86_64 : {
				// The config directory should only contain files referenced in the database, or "disabled.inc" or README.txt
				Set<String> extraFiles = new HashSet<>();
				{
					String[] list = new File(SITES_AVAILABLE).list();
					if(list != null) extraFiles.addAll(Arrays.asList(list));
				}

				// Iterate through each site
				for(Site httpdSite : thisAoServer.getHttpdSites()) {
					// Some values used below
					final String siteName = httpdSite.getName();
					final HttpdSiteManager manager = HttpdSiteManager.getInstance(httpdSite);
					final GroupServer lsg = httpdSite.getLinuxServerGroup();
					final int lsgGID = lsg.getGid().getId();
					final List<VirtualHost> binds = httpdSite.getHttpdSiteBinds();

					// Remove from delete list
					String sharedFilename = siteName + ".inc";
					extraFiles.remove(sharedFilename);

					// The shared config part
					final UnixFile sharedFile = new UnixFile(SITES_AVAILABLE, sharedFilename);
					if(!manager.httpdSite.isManual() || !sharedFile.getStat().exists()) {
						if(
							DaemonFileUtils.atomicWrite(
								sharedFile,
								buildHttpdSiteSharedFile(manager, bout, restorecon),
								0640,
								UnixFile.ROOT_UID,
								lsgGID,
								null,
								restorecon
							)
						) {
							// File changed, all servers that use this site need restarted
							for(VirtualHost hsb : binds) serversNeedingReloaded.add(hsb.getHttpdBind().getHttpdServer());
						}
					}

					// Each of the binds
					for(VirtualHost bind : binds) {
						// Some value used below
						final boolean isManual = bind.isManual();
						final boolean isDisabled = bind.isDisabled();
						final String predisableConfig = bind.getPredisableConfig();
						// TODO: predisable_config and disabled state do not interact well.  When disabled, the predisable_config keeps getting used instead of any newly generated file.
						final HttpdBind httpdBind = bind.getHttpdBind();
						final Bind nb = httpdBind.getNetBind();

						// Generate the filename
						final String bindFilename;
						{
							String bindEscapedName = bind.getSystemdEscapedName();
							if(bindEscapedName == null) {
								bindFilename = siteName+"_"+nb.getIpAddress().getInetAddress()+"_"+nb.getPort().getPort()+".conf";
							} else {
								bindFilename = siteName+"_"+nb.getIpAddress().getInetAddress()+"_"+nb.getPort().getPort()+"_"+bindEscapedName+".conf";
							}
						}
						final UnixFile bindFile = new UnixFile(SITES_AVAILABLE, bindFilename);
						final boolean exists = bindFile.getStat().exists();

						// Remove from delete list
						extraFiles.remove(bindFilename);

						// Will only be verified when not exists, auto mode, disabled, or predisabled config need to be restored
						if(
							!exists                                 // Not exists
							|| !isManual                            // Auto mode
							|| isDisabled                           // Disabled
							|| predisableConfig!=null               // Predisabled config needs to be restored
						) {
							// Save manual config file for later restoration
							if(exists && isManual && isDisabled && predisableConfig==null) {
								bind.setPredisableConfig(FileUtils.readFileAsString(bindFile.getFile()));
							}

							// Restore/build the file
							byte[] newContent;
							if(isManual && !isDisabled && predisableConfig!=null) {
								// Restore manual config values
								newContent = predisableConfig.getBytes();
							} else {
								// Create auto config
								if(isDisabled) PackageManager.installPackage(PackageManager.PackageName.AOSERV_HTTPD_SITE_DISABLED);
								newContent = buildHttpdSiteBindFile(
									manager,
									bind,
									isDisabled ? (Site.DISABLED+".inc") : sharedFilename,
									bout
								);
							}
							// Write only when missing or modified
							if(
								DaemonFileUtils.atomicWrite(
									bindFile,
									newContent,
									0640,
									UnixFile.ROOT_UID,
									lsgGID,
									null,
									restorecon
								)
							) {
								// Reload server if the file is modified
								serversNeedingReloaded.add(httpdBind.getHttpdServer());
							}
						}
					}
				}

				// Mark files for deletion
				for(String filename : extraFiles) {
					if(
						!filename.equals(Site.DISABLED + ".inc")
						&& !filename.equals("README.txt")
					) {
						File toDelete = new File(SITES_AVAILABLE, filename);
						if(logger.isLoggable(Level.INFO)) logger.info("Scheduling for removal: " + toDelete);
						deleteFileList.add(toDelete);
					}
				}

				// Symlinks in sites-enabled
				extraFiles.clear();
				{
					String[] list = new File(SITES_ENABLED).list();
					if(list != null) extraFiles.addAll(Arrays.asList(list));
				}

				// Iterate through each site
				for(Site httpdSite : thisAoServer.getHttpdSites()) {
					// Some values used below
					final String siteName = httpdSite.getName();
					// Each of the binds
					for(VirtualHost bind : httpdSite.getHttpdSiteBinds()) {
						// Some value used below
						final HttpdBind httpdBind = bind.getHttpdBind();
						final Bind nb = httpdBind.getNetBind();

						// Generate the filename
						final String bindFilename;
						{
							String bindEscapedName = bind.getSystemdEscapedName();
							if(bindEscapedName == null) {
								bindFilename = siteName+"_"+nb.getIpAddress().getInetAddress()+"_"+nb.getPort().getPort()+".conf";
							} else {
								bindFilename = siteName+"_"+nb.getIpAddress().getInetAddress()+"_"+nb.getPort().getPort()+"_"+bindEscapedName+".conf";
							}
						}
						final String symlinkTarget = "../sites-available/" + bindFilename;

						final UnixFile symlinkFile = new UnixFile(SITES_ENABLED, bindFilename);
						final Stat symlinkStat = symlinkFile.getStat();

						// Remove from delete list
						extraFiles.remove(bindFilename);

						if(
							!symlinkStat.exists()
							|| !symlinkStat.isSymLink()
							|| !symlinkTarget.equals(symlinkFile.readLink())
						) {
							if(symlinkStat.exists()) symlinkFile.delete();
							symlinkFile.symLink(symlinkTarget);
							serversNeedingReloaded.add(httpdBind.getHttpdServer());
						}
					}
				}

				// Mark files for deletion
				for(String filename : extraFiles) {
					if(!filename.equals("README.txt")) {
						File toDelete = new File(SITES_ENABLED, filename);
						if(logger.isLoggable(Level.INFO)) logger.info("Scheduling for removal: " + toDelete);
						deleteFileList.add(toDelete);
					}
				}
				break;
			}
			default :
				throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
		}
	}

	/**
	 * Builds the contents for the shared part of a Site config.
	 */
	private static byte[] buildHttpdSiteSharedFile(HttpdSiteManager manager, ByteArrayOutputStream bout, Set<UnixFile> restorecon) throws IOException, SQLException {
		final Site httpdSite = manager.httpdSite;
		final UserServer lsa = httpdSite.getLinuxServerAccount();
		final int uid = lsa.getUid().getId();
		final GroupServer lsg = httpdSite.getLinuxServerGroup();
		final int gid = lsg.getGid().getId();
		final SortedSet<HttpdSiteManager.JkSetting> jkSettings = manager.getJkSettings();

		// Build to a temporary buffer
		bout.reset();
		try (ChainWriter out = new ChainWriter(bout)) {
			OperatingSystemVersion osv = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			switch(osvId) {
				case OperatingSystemVersion.CENTOS_5_I686_AND_X86_64 : {
					final String dollarVariable = CENTOS_5_DOLLAR_VARIABLE;
					out.print("ServerAdmin ").print(escape(dollarVariable, httpdSite.getServerAdmin().toString())).print('\n');

					// Enable CGI PHP option if the site supports CGI and PHP
					if(manager.enablePhp() && manager.enableCgi()) {
						out.print("\n"
								+ "# Use CGI-based PHP when not using mod_php\n"
								+ "<IfModule !sapi_apache2.c>\n"
								+ "    <IfModule !mod_php5.c>\n"
								+ "        Action php-script /cgi-bin/php\n"
								// Avoid *.php.txt going to PHP: http://php.net/manual/en/install.unix.apache2.php
								+ "        <FilesMatch \\.php$>\n"
								+ "            SetHandler php-script\n"
								+ "        </FilesMatch>\n"
								//+ "        AddHandler php-script .php\n"
								+ "    </IfModule>\n"
								+ "</IfModule>\n");
					}

					// The CGI user info
					out.print("\n"
							+ "# Use suexec when available\n"
							+ "<IfModule mod_suexec.c>\n"
							+ "    SuexecUserGroup ").print(escape(dollarVariable, httpdSite.getLinuxServerAccount().getLinuxAccount().getUsername().getUsername().toString())).print(' ').print(escape(dollarVariable, httpdSite.getLinuxServerGroup().getLinuxGroup().getName().toString())).print("\n"
							+ "</IfModule>\n");

					// Protect against TRACE and TRACK
					if(manager.blockAllTraceAndTrackRequests()) {
						out.print("\n"
								+ "# Protect dangerous request methods\n"
								+ "<IfModule mod_rewrite.c>\n"
								+ "    RewriteEngine on\n"
								+ "    RewriteCond %{REQUEST_METHOD} ^(TRACE|TRACK)\n"
								+ "    RewriteRule .* - [F]\n"
								+ "</IfModule>\n");
					}

					// Rejected URLs
					Map<String,List<HttpdSiteManager.Location>> rejectedLocations = manager.getRejectedLocations();
					for(Map.Entry<String,List<HttpdSiteManager.Location>> entry : rejectedLocations.entrySet()) {
						out.print("\n"
								+ "# ").print(entry.getKey()).print('\n');
						for(HttpdSiteManager.Location location : entry.getValue()) {
							if(location.isRegularExpression()) {
								out.print("<LocationMatch ").print(escape(dollarVariable, location.getLocation())).print(">\n"
										+ "    Order deny,allow\n"
										+ "    Deny from All\n"
										+ "</LocationMatch>\n"
								);
							} else {
								out.print("<Location ").print(escape(dollarVariable, location.getLocation())).print(">\n"
										+ "    Order deny,allow\n"
										+ "    Deny from All\n"
										+ "</Location>\n"
								);
							}
						}
					}

					// Rewrite rules
					List<HttpdSiteManager.PermanentRewriteRule> permanentRewrites = manager.getPermanentRewriteRules();
					if(!permanentRewrites.isEmpty()) {
						// Write the standard restricted URL patterns
						out.print("\n"
								+ "# Rewrite rules\n"
								+ "<IfModule mod_rewrite.c>\n"
								+ "    RewriteEngine on\n");
						for(HttpdSiteManager.PermanentRewriteRule permanentRewrite : permanentRewrites) {
							out
								.print("    RewriteRule ")
								.print(escape(dollarVariable, permanentRewrite.pattern))
								.print(' ')
								.print(escape(dollarVariable, permanentRewrite.substitution))
								.print(" [L");
							if(permanentRewrite.noEscape) out.print(",NE");
							out.print(",R=permanent]\n");
						}
						out.print("</IfModule>\n");
					}

					// Write the authenticated locations
					List<Location> hsals = httpdSite.getHttpdSiteAuthenticatedLocations();
					if(!hsals.isEmpty()) {
						out.print("\n"
								+ "# Authenticated Locations\n");
						for(Location hsal : hsals) {
							if(hsal.getHandler() != null) throw new NotImplementedException("SetHandler not implemented on " + osv);
							out.print(hsal.getIsRegularExpression()?"<LocationMatch ":"<Location ").print(escape(dollarVariable, hsal.getPath())).print(">\n");
							if(hsal.getAuthUserFile() != null) out.print("    AuthType Basic\n");
							if(hsal.getAuthName().length() > 0) out.print("    AuthName ").print(escape(dollarVariable, hsal.getAuthName())).print('\n');
							if(hsal.getAuthUserFile() != null) out.print("    AuthUserFile ").print(escape(dollarVariable, hsal.getAuthUserFile().toString())).print('\n');
							if(hsal.getAuthGroupFile() != null) out.print("    AuthGroupFile ").print(escape(dollarVariable, hsal.getAuthGroupFile().toString())).print('\n');
							if(hsal.getRequire().length() > 0) {
								out.print("    require");
								// Split on space, escaping each term
								for(String term : StringUtility.splitString(hsal.getRequire(), ' ')) {
									if(!term.isEmpty()) {
										out.print(' ').print(escape(dollarVariable, term));
									}
								}
								out.print('\n');
							}
							out.print(hsal.getIsRegularExpression()?"</LocationMatch>\n":"</Location>\n");
						}
					}

					// Error if no root webapp found
					boolean foundRoot = false;
					SortedMap<String,HttpdSiteManager.WebAppSettings> webapps = manager.getWebapps();
					for(Map.Entry<String,HttpdSiteManager.WebAppSettings> entry : webapps.entrySet()) {
						String path = entry.getKey();
						HttpdSiteManager.WebAppSettings settings = entry.getValue();
						PosixPath docBase = settings.getDocBase();

						if(path.length()==0) {
							foundRoot = true;
							// DocumentRoot
							out.print("\n"
									+ "# Set up the default webapp\n"
									+ "DocumentRoot ").print(escape(dollarVariable, docBase.toString())).print("\n"
									+ "<Directory ").print(escape(dollarVariable, docBase.toString())).print(">\n"
									+ "    Allow from All\n"
									+ "    AllowOverride ").print(settings.getAllowOverride()).print("\n"
									+ "    Order allow,deny\n"
									+ "    Options ").print(settings.getOptions()).print("\n"
									+ "</Directory>\n");
						} else {
							// Is webapp/alias
							out.print("\n"
									+ "# Set up the ").print(path).print(" webapp\n"
									+ "Alias ").print(escape(dollarVariable, path)).print(" ").print(escape(dollarVariable, docBase.toString())).print("\n"
									+ "<Directory ").print(escape(dollarVariable, docBase.toString())).print(">\n"
									+ "    Allow from All\n"
									+ "    AllowOverride ").print(settings.getAllowOverride()).print("\n"
									+ "    Order allow,deny\n"
									+ "    Options ").print(settings.getOptions()).print("\n"
									+ "</Directory>\n");
						}
						if(settings.enableCgi()) {
							if(!manager.enableCgi()) throw new SQLException("Unable to enable webapp CGI when site has CGI disabled");
							out.print("<Directory ").print(escape(dollarVariable, docBase.toString() + "/cgi-bin")).print(">\n"
									+ "    <IfModule mod_ssl.c>\n"
									+ "        SSLOptions +StdEnvVars\n"
									+ "    </IfModule>\n"
									+ "    Options ").print(settings.getCgiOptions()).print("\n"
									+ "    SetHandler cgi-script\n"
									+ "</Directory>\n");
						}
					}
					if(!foundRoot) throw new SQLException("No DocumentRoot found");

					// Write any JkMount and JkUnmount directives
					if(!jkSettings.isEmpty()) {
						out.print("\n"
								+ "# Request patterns mapped through mod_jk\n"
								+ "<IfModule mod_jk.c>\n");
						for(HttpdSiteManager.JkSetting setting : jkSettings) {
							out
								.print("    ")
								.print(setting.isMount() ? "JkMount" : "JkUnMount")
								.print(' ')
								.print(escape(dollarVariable, setting.getPath()))
								.print(' ')
								.print(escape(dollarVariable, setting.getJkCode()))
								.print('\n');
						}
						out.print("\n"
								+ "    # Remove jsessionid for non-jk requests\n"
								+ "    <IfModule mod_rewrite.c>\n"
								+ "        RewriteEngine On\n"
								+ "        RewriteRule ^(.*);jsessionid=.*$ $1\n"
								+ "    </IfModule>\n"
								+ "</IfModule>\n");
					}
					break;
				}
				case OperatingSystemVersion.CENTOS_7_X86_64 : {
					final String dollarVariable = CENTOS_7_DOLLAR_VARIABLE;
					out.print("ServerAdmin ${site.server_admin}\n");

					// Find all versions of mod_php on any Apache server that runs this site
					SortedSet<Integer> modPhpMajorVersions = new TreeSet<>();
					for(VirtualHost hsb : httpdSite.getHttpdSiteBinds()) {
						SoftwareVersion modPhpVersion = hsb.getHttpdBind().getHttpdServer().getModPhpVersion();
						if(modPhpVersion != null) {
							modPhpMajorVersions.add(
								Integer.parseInt(
									getMajorPhpVersion(
										modPhpVersion.getVersion()
									)
								)
							);
						}
					}

					// Enable CGI PHP option if the site supports CGI and PHP
					if(manager.enablePhp() && manager.enableCgi()) {
						out.print("\n"
								+ "# Use CGI-based PHP");
						if(!modPhpMajorVersions.isEmpty()) out.print(" when not using mod_php");
						out.print('\n');
						String indent = "";
						for(int modPhpMajorVersion : modPhpMajorVersions) {
							out.print(indent).print("<IfModule !php").print(modPhpMajorVersion).print("_module>\n");
							indent += "    ";
						}
						out
							.print(indent).print("<IfModule actions_module>\n")
							.print(indent).print("    Action php-script /cgi-bin/php\n")
							// Avoid *.php.txt going to PHP: http://php.net/manual/en/install.unix.apache2.php
							.print(indent).print("    <FilesMatch \\.php$>\n")
							.print(indent).print("        SetHandler php-script\n")
							.print(indent).print("    </FilesMatch>\n")
							//+ "            AddHandler php-script .php\n"
							.print(indent).print("</IfModule>\n");
						for(int i=0; i<modPhpMajorVersions.size(); i++) {
							indent = indent.substring(0, indent.length() - 4);
							out.print(indent).print("</IfModule>\n");
						}
						assert indent.length() == 0;
					}

					// Configure per-site PHP sessions for mod_php
					if(!modPhpMajorVersions.isEmpty()) {
						UnixFile varDir = new UnixFile(HttpdOperatingSystemConfiguration.CENTOS_7_X86_64.getHttpdSitesDirectory().toString() + "/" + httpdSite.getName() + "/" + HttpdSiteManager.VAR);
						UnixFile varPhpDir = new UnixFile(varDir, HttpdSiteManager.VAR_PHP, true);
						UnixFile sessionDir = new UnixFile(varPhpDir, PHP_SESSION, true);
						out.print("\n"
								+ "# Use per-site PHP session directory when using mod_php\n");
						for(int modPhpMajorVersion : modPhpMajorVersions) {
							out.print("<IfModule php").print(modPhpMajorVersion).print("_module>\n"
									+ "    php_value session.save_path ").print(escape(dollarVariable, sessionDir.toString())).print("\n"
									+ "</IfModule>\n");
						}
						// Create var directory if missing
						if(DaemonFileUtils.mkdir(varDir, 0770, uid, gid)) {
							restorecon.add(varDir);
						}
						// Create var/php directory if missing
						if(DaemonFileUtils.mkdir(varPhpDir, 0770, uid, gid)) {
							restorecon.add(varPhpDir);
						}
						// Create var/php/session directory if missing
						if(DaemonFileUtils.mkdir(sessionDir, 0770, uid, gid)) {
							restorecon.add(sessionDir);
						}
					}

					// The CGI user info
					out.print("\n"
							+ "# Use suexec when available\n"
							+ "<IfModule suexec_module>\n"
							+ "    SuexecUserGroup ${site.user} ${site.group}\n"
							+ "</IfModule>\n");

					if(
						manager.blockAllTraceAndTrackRequests()
						|| httpdSite.getBlockScm()
						|| httpdSite.getBlockCoreDumps()
						|| httpdSite.getBlockEditorBackups()
					) {
						out.print("\n"
								+ "# Site options\n");
						if(manager.blockAllTraceAndTrackRequests()) {
							out.print("Include site-options/block_trace_track.inc\n");
						}
						if(httpdSite.getBlockScm()) {
							out.print("Include site-options/block_scm.inc\n");
						}
						if(httpdSite.getBlockCoreDumps()) {
							out.print("Include site-options/block_core_dumps.inc\n");
						}
						if(httpdSite.getBlockEditorBackups()) {
							out.print("Include site-options/block_editor_backups.inc\n");
						}
					}

					// Rejected URLs
					Map<String,List<HttpdSiteManager.Location>> rejectedLocations = manager.getRejectedLocations();
					for(Map.Entry<String,List<HttpdSiteManager.Location>> entry : rejectedLocations.entrySet()) {
						out.print("\n"
								+ "# ").print(entry.getKey()).print('\n'
								+ "<IfModule authz_core_module>\n");
						for(HttpdSiteManager.Location location : entry.getValue()) {
							if(location.isRegularExpression()) {
								out.print("    <LocationMatch ").print(escape(dollarVariable, location.getLocation())).print(">\n"
										+ "        Require all denied\n"
										+ "    </LocationMatch>\n");
							} else {
								out.print("    <Location ").print(escape(dollarVariable, location.getLocation())).print(">\n"
										+ "        Require all denied\n"
										+ "    </Location>\n");
							}
						}
						out.print("</IfModule>\n");
					}

					// Rewrite rules
					List<HttpdSiteManager.PermanentRewriteRule> permanentRewrites = manager.getPermanentRewriteRules();
					if(!permanentRewrites.isEmpty()) {
						// Write the standard restricted URL patterns
						out.print("\n"
								+ "# Rewrite rules\n"
								+ "<IfModule rewrite_module>\n"
								+ "    RewriteEngine on\n");
						for(HttpdSiteManager.PermanentRewriteRule permanentRewrite : permanentRewrites) {
							out
								.print("    RewriteRule ")
								.print(escape(dollarVariable, permanentRewrite.pattern))
								.print(' ')
								.print(escape(dollarVariable, permanentRewrite.substitution))
								.print(" [END");
							if(permanentRewrite.noEscape) out.print(",NE");
							out.print(",R=permanent]\n");
						}
						out.print("</IfModule>\n");
					}

					// Write the authenticated locations
					List<Location> hsals = httpdSite.getHttpdSiteAuthenticatedLocations();
					if(!hsals.isEmpty()) {
						out.print("\n"
								+ "# Authenticated Locations\n");
						for(Location hsal : hsals) {
							String handler = hsal.getHandler();
							String indent;
							if(Location.Handler.SERVER_STATUS.equals(handler)) {
								out.print("<IfModule status_module>\n");
								indent = "    ";
							} else {
								// Other handler or no handler
								indent = "";
							}
							out.print(indent).print(hsal.getIsRegularExpression()?"<LocationMatch ":"<Location ").print(escape(dollarVariable, hsal.getPath())).print(">\n");
							boolean includeAuthType = hsal.getAuthUserFile() != null;
							boolean includeAuthName = hsal.getAuthName().length()>0;
							if(includeAuthType || includeAuthName) {
								out.print(indent).print("    <IfModule authn_core_module>\n");
								if(includeAuthType) out.print(indent).print("        AuthType Basic\n");
								if(includeAuthName) out.print(indent).print("        AuthName ").print(escape(dollarVariable, hsal.getAuthName())).print("\n");
								out.print(indent).print("    </IfModule>\n");
							}
							if(hsal.getAuthUserFile() != null) {
								PackageManager.installPackage(PackageManager.PackageName.HTTPD_TOOLS);
								out
									.print(indent).print("    <IfModule authn_file_module>\n")
									.print(indent).print("        AuthUserFile ").print(getEscapedPrefixReplacement(dollarVariable, hsal.getAuthUserFile().toString(), "/var/www/" + httpdSite.getName() + "/", "/var/www/${site.name}/")).print('\n')
									.print(indent).print("    </IfModule>\n");
							}
							if(hsal.getAuthGroupFile() != null) {
								out
									.print(indent).print("    <IfModule authz_groupfile_module>\n")
									.print(indent).print("        AuthGroupFile ").print(getEscapedPrefixReplacement(dollarVariable, hsal.getAuthGroupFile().toString(), "/var/www/" + httpdSite.getName() + "/", "/var/www/${site.name}/")).print('\n')
									.print(indent).print("    </IfModule>\n");
							}
							if(hsal.getRequire().length()>0) {
								out
									.print(indent).print("    <IfModule authz_core_module>\n")
									.print(indent).print("        Require");
								// Split on space, escaping each term
								for(String term : StringUtility.splitString(hsal.getRequire(), ' ')) {
									if(!term.isEmpty()) {
										out.print(' ').print(escape(dollarVariable, term));
									}
								}
								out.print('\n')
									.print(indent).print("    </IfModule>\n");
							}
							if(handler != null) {
								out.print('\n')
									.print(indent).print("    SetHandler ").print(escape(dollarVariable, handler)).print('\n');
								if(Location.Handler.SERVER_STATUS.equals(handler)) {
									// Limit server status to GET and POST
									// See https://httpd.apache.org/docs/2.4/mod/core.html#limitexcept
									out
										.print(indent).print("    <IfModule authz_core_module>\n")
										.print(indent).print("        <LimitExcept GET POST>\n")
										.print(indent).print("            Require all denied\n")
										.print(indent).print("        </LimitExcept>\n")
										.print(indent).print("    </IfModule>\n");
								}
							}
							out.print(indent).print(hsal.getIsRegularExpression()?"</LocationMatch>\n":"</Location>\n");
							if(Location.Handler.SERVER_STATUS.equals(handler)) {
								out.print("</IfModule>\n");
							}
						}
					}

					// Error if no root webapp found
					boolean foundRoot = false;
					SortedMap<String,HttpdSiteManager.WebAppSettings> webapps = manager.getWebapps();
					for(Map.Entry<String,HttpdSiteManager.WebAppSettings> entry : webapps.entrySet()) {
						String path = entry.getKey();
						HttpdSiteManager.WebAppSettings settings = entry.getValue();
						PosixPath docBase = settings.getDocBase();

						boolean isInModPhp = false;
						for(VirtualHost hsb : httpdSite.getHttpdSiteBinds()) {
							if(hsb.getHttpdBind().getHttpdServer().getModPhpVersion() != null) {
								isInModPhp = true;
								break;
							}
						}

						if(path.length()==0) {
							foundRoot = true;
							// DocumentRoot
							out.print("\n"
									+ "# Set up the default webapp\n"
									+ "DocumentRoot ")
								.print(CERTBOT_COMPAT
									? escape(dollarVariable, docBase.toString())
									: getEscapedPrefixReplacement(dollarVariable, docBase.toString(), "/var/www/" + httpdSite.getName() + "/", "/var/www/${site.name}/"))
								.print("\n"
									+ "<Directory ").print(getEscapedPrefixReplacement(dollarVariable, docBase.toString(), "/var/www/" + httpdSite.getName() + "/", "/var/www/${site.name}/")).print(">\n"
									+ "    <IfModule authz_core_module>\n"
									+ "        Require all granted\n"
									+ "    </IfModule>\n"
									+ "    AllowOverride ").print(settings.getAllowOverride()).print("\n"
									+ "    Options ").print(settings.getOptions()).print("\n"
									+ "    <IfModule dir_module>\n");
							if(!jkSettings.isEmpty()) {
								out.print("        <IfModule jk_module>\n"
										+ "            DirectoryIndex index.jsp\n"
										+ "        </IfModule>\n");
							}
							out.print("        DirectoryIndex index.xml\n"); // This was Cocoon, enable Tomcat 3.* only?
							if(settings.enableSsi()) {
								out.print("        <IfModule include_module>\n"
										+ "            DirectoryIndex index.shtml\n"
										+ "        </IfModule>\n");
							}
							if(manager.enablePhp() || isInModPhp) {
								out.print("        DirectoryIndex index.php\n");
							}
							out.print("        DirectoryIndex index.html\n"
									+ "        <IfModule negotiation_module>\n"
									+ "            DirectoryIndex index.html.var\n"
									+ "        </IfModule>\n"
									+ "        DirectoryIndex index.htm\n"
									+ "        <IfModule negotiation_module>\n"
									+ "            DirectoryIndex index.htm.var\n"
									+ "        </IfModule>\n");
							if(manager.enableCgi()) {
								out.print("        DirectoryIndex index.cgi\n");
							}
							out.print("        DirectoryIndex default.html\n"
									+ "        <IfModule negotiation_module>\n"
									+ "            DirectoryIndex default.html.var\n"
									+ "        </IfModule>\n");
							if(!jkSettings.isEmpty()) {
								out.print("        <IfModule jk_module>\n"
										+ "            DirectoryIndex default.jsp\n"
										+ "        </IfModule>\n");
							}
							if(settings.enableSsi()) {
								out.print("        <IfModule include_module>\n"
										+ "            DirectoryIndex default.shtml\n"
										+ "        </IfModule>\n");
							}
							if(manager.enableCgi()) {
								out.print("        DirectoryIndex default.cgi\n");
							}
							out.print("        DirectoryIndex Default.htm\n"
									+ "        <IfModule negotiation_module>\n"
									+ "            DirectoryIndex Default.htm.var\n"
									+ "        </IfModule>\n"
									+ "    </IfModule>\n"
									+ "</Directory>\n");
							if(settings.enableCgi()) {
								if(!manager.enableCgi()) throw new SQLException("Unable to enable webapp CGI when site has CGI disabled");
								out.print("<Directory ").print(getEscapedPrefixReplacement(dollarVariable, docBase.toString() + "/cgi-bin", "/var/www/" + httpdSite.getName() + "/", "/var/www/${site.name}/")).print(">\n"
										+ "    <IfModule ssl_module>\n"
										+ "        SSLOptions +StdEnvVars\n"
										+ "    </IfModule>\n"
										+ "    Options ").print(settings.getCgiOptions()).print("\n"
										+ "    SetHandler cgi-script\n"
										+ "</Directory>\n");
							}
						} else {
							// Is webapp/alias
							out.print("\n"
									+ "# Set up the ").print(path).print(" webapp\n"
									+ "<IfModule alias_module>\n"
									+ "    Alias ").print(escape(dollarVariable, path)).print(' ').print(getEscapedPrefixReplacement(dollarVariable, docBase.toString(), "/var/www/" + httpdSite.getName() + "/", "/var/www/${site.name}/")).print("\n"
									+ "    <Directory ").print(getEscapedPrefixReplacement(dollarVariable, docBase.toString(), "/var/www/" + httpdSite.getName() + "/", "/var/www/${site.name}/")).print(">\n"
									+ "        <IfModule authz_core_module>\n"
									+ "            Require all granted\n"
									+ "        </IfModule>\n"
									+ "        AllowOverride ").print(settings.getAllowOverride()).print("\n"
									+ "        Options ").print(settings.getOptions()).print("\n"
									+ "        <IfModule dir_module>\n");
							if(!jkSettings.isEmpty()) {
								out.print("            <IfModule jk_module>\n"
										+ "                DirectoryIndex index.jsp\n"
										+ "            </IfModule>\n");
							}
							out.print("            DirectoryIndex index.xml\n"); // This was Cocoon, enable Tomcat 3.* only?
							if(settings.enableSsi()) {
								out.print("            <IfModule include_module>\n"
										+ "                DirectoryIndex index.shtml\n"
										+ "            </IfModule>\n");
							}
							if(manager.enablePhp() || isInModPhp) {
								out.print("            DirectoryIndex index.php\n");
							}
							out.print("            DirectoryIndex index.html\n"
									+ "            <IfModule negotiation_module>\n"
									+ "                DirectoryIndex index.html.var\n"
									+ "            </IfModule>\n"
									+ "            DirectoryIndex index.htm\n"
									+ "            <IfModule negotiation_module>\n"
									+ "                DirectoryIndex index.htm.var\n"
									+ "            </IfModule>\n");
							if(manager.enableCgi()) {
								out.print("            DirectoryIndex index.cgi\n");
							}
							out.print("            DirectoryIndex default.html\n"
									+ "            <IfModule negotiation_module>\n"
									+ "                DirectoryIndex default.html.var\n"
									+ "            </IfModule>\n");
							if(!jkSettings.isEmpty()) {
								out.print("            <IfModule jk_module>\n"
										+ "                DirectoryIndex default.jsp\n"
										+ "            </IfModule>\n");
							}
							if(settings.enableSsi()) {
								out.print("            <IfModule include_module>\n"
										+ "                DirectoryIndex default.shtml\n"
										+ "            </IfModule>\n");
							}
							if(manager.enableCgi()) {
								out.print("            DirectoryIndex default.cgi\n");
							}
							out.print("            DirectoryIndex Default.htm\n"
									+ "            <IfModule negotiation_module>\n"
									+ "                DirectoryIndex Default.htm.var\n"
									+ "            </IfModule>\n"
									+ "        </IfModule>\n"
									+ "    </Directory>\n");
							if(settings.enableCgi()) {
								if(!manager.enableCgi()) throw new SQLException("Unable to enable webapp CGI when site has CGI disabled");
								out.print("    <Directory ").print(escape(dollarVariable, docBase.toString() + "/cgi-bin")).print(">\n"
										+ "        <IfModule ssl_module>\n"
										+ "            SSLOptions +StdEnvVars\n"
										+ "        </IfModule>\n"
										+ "        Options ").print(settings.getCgiOptions()).print("\n"
										+ "        SetHandler cgi-script\n"
										+ "    </Directory>\n");
							}
							out.print("</IfModule>\n");
						}
					}
					if(!foundRoot) throw new SQLException("No DocumentRoot found");

					// Write any JkMount and JkUnmount directives
					if(!jkSettings.isEmpty()) {
						out.print("\n"
								+ "# Request patterns mapped through mod_jk\n"
								+ "<IfModule jk_module>\n");
						for(HttpdSiteManager.JkSetting setting : jkSettings) {
							out
								.print("    ")
								.print(setting.isMount() ? "JkMount" : "JkUnMount")
								.print(' ')
								.print(escape(dollarVariable, setting.getPath()))
								.print(' ')
								.print(escape(dollarVariable, setting.getJkCode()))
								.print('\n');
						}
						out.print("\n"
								+ "    # Remove jsessionid for non-jk requests\n"
								+ "    JkStripSession On\n"
								+ "</IfModule>\n");
					}
					break;
				}
				default :
					throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
			}
		}
		return bout.toByteArray();
	}

	/**
	 * Rebuilds the files in /etc/httpd/conf/
	 * <ul>
	 *   <li>/etc/httpd/conf/httpd#.conf or /etc/httpd/conf/httpd[@&lt;name&gt;].conf</li>
	 *   <li>/etc/httpd/conf/workers#.properties or /etc/httpd/conf/workers[@&lt;name&gt;].properties</li>
	 * </ul>
	 */
	private static void doRebuildConf(
		Server thisAoServer,
		ByteArrayOutputStream bout,
		List<File> deleteFileList,
		Set<HttpdServer> serversNeedingReloaded,
		Set<Port> enabledAjpPorts,
		Set<UnixFile> restorecon,
		boolean[] hasAnyCgi,
		boolean[] hasAnyModPhp
	) throws IOException, SQLException {
		OperatingSystemVersion osv = thisAoServer.getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		List<HttpdServer> hss = thisAoServer.getHttpdServers();
		// The files that should exist in /etc/httpd/conf
		Set<String> httpdConfFilenames = new HashSet<>(hss.size()*4/3+1);
		// The files that whould exist in /var/lib/php
		Set<String> varLibPhpFilenames = new HashSet<>(hss.size()*4/3+1);
		// The files that should exist in /etc/tmpfiles.d/httpd[@<name>].conf
		Set<String> etcTmpfilesFilenames = new HashSet<>(hss.size()*4/3+1);
		// The files that should exist in /run/httpd[@<name>]
		Set<String> runFilenames = new HashSet<>(hss.size()*4/3+1);
		// Track if has any alternate (named) instances
		boolean hasAlternateInstance = false;
		// Track which httpd[-n]-after-network-online packages are needed
		boolean hasSpecificAddress = false;
		// Rebuild per-server files
		for(HttpdServer hs : hss) {
			List<Site> sites = hs.getHttpdSites();
			String httpdConfFilename = getHttpdConfFile(hs);
			UnixFile httpdConf = new UnixFile(CONF_DIRECTORY, httpdConfFilename);
			httpdConfFilenames.add(httpdConfFilename);
			// Rebuild the httpd.conf file
			if(
				DaemonFileUtils.atomicWrite(
					httpdConf,
					buildHttpdConf(hs, sites, httpdConfFilenames, varLibPhpFilenames, bout, restorecon, hasAnyCgi, hasAnyModPhp),
					0644,
					UnixFile.ROOT_UID,
					UnixFile.ROOT_GID,
					null,
					restorecon
				)
			) {
				serversNeedingReloaded.add(hs);
			}
			if(hs.getName() == null) {
				for(HttpdBind hb : hs.getHttpdBinds()) {
					InetAddress ia = hb.getNetBind().getIpAddress().getInetAddress();
					if(!ia.isLoopback() && !ia.isUnspecified()) {
						hasSpecificAddress = true;
						break;
					}
				}
			} else {
				hasAlternateInstance = true;
			}

			// Rebuild the workers.properties file
			// Only include mod_jk when at least one site has jk settings
			boolean hasJkSettings = false;
			for(Site site : sites) {
				HttpdSiteManager manager = HttpdSiteManager.getInstance(site);
				if(!manager.getJkSettings().isEmpty()) {
					hasJkSettings = true;
					break;
				}
			}
			if(hasJkSettings) {
				String workersFilename = getWorkersFile(hs);
				UnixFile workersFile = new UnixFile(CONF_DIRECTORY, workersFilename);
				httpdConfFilenames.add(workersFilename);
				if(
					DaemonFileUtils.atomicWrite(
						workersFile,
						buildWorkersFile(hs, bout, enabledAjpPorts),
						0644,
						UnixFile.ROOT_UID,
						UnixFile.ROOT_GID,
						null,
						restorecon
					)
				) {
					serversNeedingReloaded.add(hs);
				}
			}
			if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
				String escapedName = hs.getSystemdEscapedName();
				UserServer lsa = hs.getLinuxServerAccount();
				GroupServer lsg = hs.getLinuxServerGroup();
				User.Name user = lsa.getLinuxAccount_username_id();
				Group.Name group = lsg.getLinuxGroup().getName();
				int uid = lsa.getUid().getId();
				int gid = lsg.getGid().getId();
				String tmpFilesFilename;
				String runFilename;
				if(escapedName == null) {
					// First in standard location
					tmpFilesFilename = "httpd.conf";
					runFilename = "httpd";
				} else {
					// Secondary Apache instances
					tmpFilesFilename = "httpd@" + escapedName + ".conf";
					runFilename = "httpd@" + escapedName;
				}
				// Default server with user apache and group apache uses the default at /usr/lib/tmpfiles.d/httpd.conf
				if(
					escapedName != null
					|| !user.equals(User.APACHE)
					|| !group.equals(Group.APACHE)
				) {
					etcTmpfilesFilenames.add(tmpFilesFilename);
					// Custom entry in /etc/tmpfiles.d/httpd[@<name>].conf
					byte[] newContent;
					{
						bout.reset();
						try (ChainWriter out = new ChainWriter(bout)) {
							out.print("#\n"
									+ "# Generated by ").print(HttpdServerManager.class.getName()).print("\n"
									+ "#\n"
									+ "d /run/").print(runFilename).print("   710 root ").print(group).print("\n"
									+ "d /run/").print(runFilename).print("/htcacheclean   700 ").print(user).print(" ").print(group).print('\n');
						}
						newContent = bout.toByteArray();
					}
					UnixFile tmpFilesUF = new UnixFile(ETC_TMPFILES_D, tmpFilesFilename);
					if(
						DaemonFileUtils.atomicWrite(
							tmpFilesUF,
							newContent,
							0644,
							UnixFile.ROOT_UID,
							UnixFile.ROOT_GID,
							null,
							restorecon
						)
					) {
						serversNeedingReloaded.add(hs);
					}
				}
				// Create/update /run/httpd[@<name>](/.*)?
				runFilenames.add(runFilename);
				UnixFile runDirUF = new UnixFile("/run", runFilename);
				if(
					DaemonFileUtils.mkdir(
						runDirUF,
						0710,
						UnixFile.ROOT_UID,
						gid
					)
				) {
					serversNeedingReloaded.add(hs);
				}
				UnixFile htcachecleanDirUF = new UnixFile(runDirUF, "htcacheclean", false);
				if(
					DaemonFileUtils.mkdir(
						htcachecleanDirUF,
						0700,
						uid,
						gid
					)
				) {
					serversNeedingReloaded.add(hs);
				}
			}
		}
		// Delete extra httpdConfFilenames
		{
			String[] list = new File(CONF_DIRECTORY).list();
			if(list != null) {
				String phpDefault;
				String workersDefault;
				Pattern httpdConfPattern;
				Pattern phpPattern;
				Pattern workersPattern;
				if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
					phpDefault = null;
					workersDefault = null;
					httpdConfPattern = HTTPD_N_CONF_REGEXP;
					phpPattern = PHP_N_REGEXP;
					workersPattern = WORKERS_N_PROPERTIES_REGEXP;
				} else if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
					phpDefault = "php";
					workersDefault = "workers.properties";
					httpdConfPattern = HTTPD_NAME_CONF_REGEXP;
					phpPattern = PHP_NAME_REGEXP;
					workersPattern = WORKERS_NAME_PROPERTIES_REGEXP;
				} else {
					throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
				}
				for(String filename : list) {
					if(
						!httpdConfFilenames.contains(filename)
						&& (
							// Note: httpd.conf is never deleted
							filename.equals(phpDefault)
							|| filename.equals(workersDefault)
							|| httpdConfPattern.matcher(filename).matches()
							|| phpPattern.matcher(filename).matches()
							|| workersPattern.matcher(filename).matches()
						)
					) {
						File toDelete = new File(CONF_DIRECTORY, filename);
						if(logger.isLoggable(Level.INFO)) logger.info("Scheduling for removal: " + toDelete);
						deleteFileList.add(toDelete);
					}
				}
			}
		}
		// Delete extra varLibPhpFilenames
		{
			String[] list = VAR_LIB_PHP_DIRECTORY.list();
			if(list != null) {
				for(String filename : list) {
					if(
						!varLibPhpFilenames.contains(filename)
						&& (
							PHP_SESSION.equals(filename)
							|| PHP_SESSION_REGEXP.matcher(filename).matches()
						)
					) {
						File toDelete = new File(VAR_LIB_PHP_DIRECTORY.getFile(), filename);
						if(logger.isLoggable(Level.INFO)) logger.info("Scheduling for removal: " + toDelete);
						deleteFileList.add(toDelete);
					}
				}
			}
		}
		if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
			// Schedule remove extra /etc/tmpfiles.d/httpd[@<name>].conf
			String[] list = new File(ETC_TMPFILES_D).list();
			if(list != null) {
				for(String filename : list) {
					if(
						!etcTmpfilesFilenames.contains(filename)
						&& (
							"httpd.conf".equals(filename)
							|| HTTPD_NAME_CONF_REGEXP.matcher(filename).matches()
						)
					) {
						File toDelete = new File(ETC_TMPFILES_D, filename);
						if(logger.isLoggable(Level.INFO)) logger.info("Scheduling for removal: " + toDelete);
						deleteFileList.add(toDelete);
					}
				}
			}
			// Schedule remove extra /run/httpd@<name> directories
			list = new File("/run").list();
			if(list != null) {
				for(String filename : list) {
					if(
						!runFilenames.contains(filename)
						// Note: /run/httpd is not deleted since it is part of the standard RPM
						&& HTTPD_NAME_REGEXP.matcher(filename).matches()
					) {
						File toDelete = new File("/run", filename);
						if(logger.isLoggable(Level.INFO)) logger.info("Scheduling for removal: " + toDelete);
						deleteFileList.add(toDelete);
					}
				}
			}
		}
		if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
			// Install httpd-after-network-online package on CentOS 7 when needed
			if(hasSpecificAddress) {
				PackageManager.installPackage(PackageManager.PackageName.HTTPD_AFTER_NETWORK_ONLINE);
			}
			// Uninstall httpd-after-network-online package on CentOS 7 when not needed
			else if(AOServDaemonConfiguration.isPackageManagerUninstallEnabled()) {
				PackageManager.removePackage(PackageManager.PackageName.HTTPD_AFTER_NETWORK_ONLINE);
			}
			// Install httpd-n package on CentOS 7 when needed
			if(hasAlternateInstance) {
				PackageManager.installPackage(PackageManager.PackageName.HTTPD_N);
			}
		}
	}

	/**
	 * Builds the httpd#.conf file for CentOS 5
	 */
	private static byte[] buildHttpdConfCentOs5(
		HttpdServer hs,
		List<Site> sites,
		Set<String> httpdConfFilenames,
		ByteArrayOutputStream bout,
		boolean[] hasAnyCgi,
		boolean[] hasAnyModPhp
	) throws IOException, SQLException {
		final String dollarVariable = CENTOS_5_DOLLAR_VARIABLE;
		final HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
		if(osConfig != HttpdOperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64) throw new AssertionError("This method is for CentOS 5 only");
		OperatingSystemVersion osv = hs.getAOServer().getServer().getOperatingSystemVersion();
		assert osv.getPkey() == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64;
		final int serverNum;
		{
			String name = hs.getName();
			serverNum = (name == null) ? 1 : Integer.parseInt(name);
		}
		bout.reset();
		try (ChainWriter out = new ChainWriter(bout)) {
			UserServer lsa = hs.getLinuxServerAccount();
			boolean isEnabled = !lsa.isDisabled();
			// The version of PHP module to run
			SoftwareVersion phpVersion = hs.getModPhpVersion();
			if(phpVersion != null) httpdConfFilenames.add("php" + serverNum);
			out.print("ServerRoot ").print(escape(dollarVariable, SERVER_ROOT)).print("\n"
					+ "Include conf/modules_conf/core\n"
					+ "PidFile /var/run/httpd").print(serverNum).print(".pid\n"
					+ "Timeout ").print(hs.getTimeOut()).print("\n"
					+ "CoreDumpDirectory /var/log/httpd/httpd").print(serverNum).print("\n"
					+ "LockFile /var/log/httpd/httpd").print(serverNum).print("/accept.lock\n"
					+ "\n"
					+ "Include conf/modules_conf/prefork\n"
					+ "Include conf/modules_conf/worker\n"
					+ "\n"
					+ "<IfModule prefork.c>\n"
					+ "    ListenBacklog 511\n"
					+ "    ServerLimit ").print(hs.getMaxConcurrency()).print("\n"
					+ "    MaxClients ").print(hs.getMaxConcurrency()).print("\n"
					+ "</IfModule>\n"
					+ "\n"
					+ "LoadModule auth_basic_module modules/mod_auth_basic.so\n"
					+ "#LoadModule auth_digest_module modules/mod_auth_digest.so\n"
					+ "LoadModule authn_file_module modules/mod_authn_file.so\n"
					+ "#LoadModule authn_alias_module modules/mod_authn_alias.so\n"
					+ "#LoadModule authn_anon_module modules/mod_authn_anon.so\n"
					+ "#LoadModule authn_dbm_module modules/mod_authn_dbm.so\n"
					+ "#LoadModule authn_default_module modules/mod_authn_default.so\n"
					+ "LoadModule authz_host_module modules/mod_authz_host.so\n"
					+ "LoadModule authz_user_module modules/mod_authz_user.so\n"
					+ "#LoadModule authz_owner_module modules/mod_authz_owner.so\n"
					+ "LoadModule authz_groupfile_module modules/mod_authz_groupfile.so\n"
					+ "#LoadModule authz_dbm_module modules/mod_authz_dbm.so\n"
					+ "#LoadModule authz_default_module modules/mod_authz_default.so\n"
					+ "#LoadModule ldap_module modules/mod_ldap.so\n"
					+ "#LoadModule authnz_ldap_module modules/mod_authnz_ldap.so\n");
			// Comment-out include module when no site has .shtml enabled
			boolean hasSsi = false;
			for(Site site : sites) {
				if(site.getEnableSsi()) {
					hasSsi = true;
					break;
				}
			}
			if(!hasSsi) out.print('#');
			out.print("LoadModule include_module modules/mod_include.so\n"
					+ "LoadModule log_config_module modules/mod_log_config.so\n"
					+ "#LoadModule logio_module modules/mod_logio.so\n"
					+ "LoadModule env_module modules/mod_env.so\n"
					+ "#LoadModule ext_filter_module modules/mod_ext_filter.so\n"
					+ "LoadModule mime_magic_module modules/mod_mime_magic.so\n"
					+ "LoadModule expires_module modules/mod_expires.so\n"
					+ "LoadModule deflate_module modules/mod_deflate.so\n"
					+ "LoadModule headers_module modules/mod_headers.so\n"
					+ "#LoadModule usertrack_module modules/mod_usertrack.so\n"
					+ "LoadModule setenvif_module modules/mod_setenvif.so\n"
					+ "LoadModule mime_module modules/mod_mime.so\n"
					+ "#LoadModule dav_module modules/mod_dav.so\n"
					+ "LoadModule status_module modules/mod_status.so\n");
			// Comment-out mod_autoindex when no sites used auto-indexes
			boolean hasIndexes = false;
			for(Site site : sites) {
				if(site.getEnableIndexes()) {
					hasIndexes = true;
					break;
				}
			}
			if(!hasIndexes) out.print('#');
			out.print("LoadModule autoindex_module modules/mod_autoindex.so\n"
					+ "#LoadModule info_module modules/mod_info.so\n"
					+ "#LoadModule dav_fs_module modules/mod_dav_fs.so\n"
					+ "#LoadModule vhost_alias_module modules/mod_vhost_alias.so\n"
					+ "LoadModule negotiation_module modules/mod_negotiation.so\n"
					+ "LoadModule dir_module modules/mod_dir.so\n"
					+ "LoadModule imagemap_module modules/mod_imagemap.so\n"
					+ "LoadModule actions_module modules/mod_actions.so\n"
					+ "#LoadModule speling_module modules/mod_speling.so\n"
					+ "#LoadModule userdir_module modules/mod_userdir.so\n"
					+ "LoadModule alias_module modules/mod_alias.so\n"
					+ "LoadModule rewrite_module modules/mod_rewrite.so\n"
					+ "LoadModule proxy_module modules/mod_proxy.so\n"
					+ "#LoadModule proxy_balancer_module modules/mod_proxy_balancer.so\n"
					+ "#LoadModule proxy_ftp_module modules/mod_proxy_ftp.so\n"
					+ "LoadModule proxy_http_module modules/mod_proxy_http.so\n"
					+ "#LoadModule proxy_connect_module modules/mod_proxy_connect.so\n"
					+ "#LoadModule cache_module modules/mod_cache.so\n");
			if(hs.useSuexec()) out.print("LoadModule suexec_module modules/mod_suexec.so\n");
			else out.print("#LoadModule suexec_module modules/mod_suexec.so\n");
			out.print("#LoadModule disk_cache_module modules/mod_disk_cache.so\n"
					+ "#LoadModule file_cache_module modules/mod_file_cache.so\n"
					+ "#LoadModule mem_cache_module modules/mod_mem_cache.so\n");
			// Comment-out cgi_module when no CGI enabled
			boolean hasCgi = false;
			for(Site site : sites) {
				HttpdSiteManager manager = HttpdSiteManager.getInstance(site);
				if(manager.enableCgi()) {
					hasCgi = true;
					hasAnyCgi[0] = true;
					break;
				}
			}
			if(!hasCgi) out.print('#');
			out.print("LoadModule cgi_module modules/mod_cgi.so\n"
					+ "#LoadModule cern_meta_module modules/mod_cern_meta.so\n"
					+ "#LoadModule asis_module modules/mod_asis.so\n");
			// Only include mod_jk when at least one site has jk settings
			boolean hasJkSettings = false;
			for(Site site : sites) {
				HttpdSiteManager manager = HttpdSiteManager.getInstance(site);
				if(!manager.getJkSettings().isEmpty()) {
					hasJkSettings = true;
					break;
				}
			}
			if(hasJkSettings) {
				out.print("LoadModule jk_module modules/mod_jk-1.2.27.so\n");
			}
			// Comment-out ssl module when has no ssl
			boolean hasSsl = false;
			HAS_SSL :
			for(Site site : sites) {
				for(VirtualHost hsb : site.getHttpdSiteBinds(hs)) {
					if(AppProtocol.HTTPS.equals(hsb.getHttpdBind().getNetBind().getAppProtocol().getProtocol())) {
						hasSsl = true;
						break HAS_SSL;
					}
				}
			}
			// Install mod_ssl when first needed
			final boolean modSslInstalled;
			if(hasSsl) {
				// TODO: Uninstall mod_ssl when no longer needed
				PackageManager.installPackage(PackageManager.PackageName.MOD_SSL);
				modSslInstalled = true;
			} else {
				modSslInstalled = PackageManager.getInstalledPackage(PackageManager.PackageName.MOD_SSL) != null;
			}
			if(modSslInstalled) {
				if(!hasSsl) out.print('#');
				out.print("LoadModule ssl_module modules/mod_ssl.so\n");
			}
			Boolean mod_wsgi = hs.getModWsgi();
			if(mod_wsgi != null && mod_wsgi) {
				throw new NotImplementedException("mod_wsdl support is not implemented on CentOS 5");
			}
			if(isEnabled && phpVersion != null) {
				hasAnyModPhp[0] = true;
				String version = phpVersion.getVersion();
				String phpMinorVersion = getMinorPhpVersion(version);
				String phpMajorVersion = getMajorPhpVersion(version);
				out.print("\n"
						+ "# Enable mod_php\n"
						+ "LoadModule ").print(escape(dollarVariable, "php" + phpMajorVersion + "_module")).print(" ").print(escape(dollarVariable, "/opt/php-" + phpMinorVersion + "-i686/lib/apache/libphp" + phpMajorVersion + ".so")).print("\n"
						+ "AddType application/x-httpd-php .php\n"
						+ "AddType application/x-httpd-php-source .phps\n");
			}
			out.print("\n"
					+ "Include conf/modules_conf/mod_ident\n"
					+ "Include conf/modules_conf/mod_log_config\n"
					+ "Include conf/modules_conf/mod_mime_magic\n"
					+ "Include conf/modules_conf/mod_setenvif\n"
					+ "Include conf/modules_conf/mod_proxy\n"
					+ "Include conf/modules_conf/mod_mime\n"
					+ "Include conf/modules_conf/mod_dav\n"
					+ "Include conf/modules_conf/mod_status\n");
			// Comment-out mod_autoindex when no sites used auto-indexes
			if(!hasIndexes) out.print('#');
			out.print("Include conf/modules_conf/mod_autoindex\n"
					+ "Include conf/modules_conf/mod_negotiation\n"
					+ "Include conf/modules_conf/mod_dir\n"
					+ "Include conf/modules_conf/mod_userdir\n");
			// Comment-out ssl module when has no ssl
			if(!hasSsl) out.print('#');
			out.print("Include conf/modules_conf/mod_ssl\n");
			// Only include mod_jk when at least one site has jk settings
			if(hasJkSettings) out.print("Include conf/modules_conf/mod_jk\n");
			out.print("\n"
					+ "ServerAdmin ").print(escape(dollarVariable, "root@" + hs.getAOServer().getHostname())).print("\n"
					+ "\n"
					+ "<IfModule mod_ssl.c>\n"
					+ "    SSLSessionCache shmcb:/var/cache/httpd/mod_ssl/ssl_scache").print(serverNum).print("(512000)\n"
					+ "</IfModule>\n"
					+ "\n");
			// Use apache if the account is disabled
			if(isEnabled) {
				out.print("User ").print(escape(dollarVariable, lsa.getLinuxAccount().getUsername().getUsername().toString())).print("\n"
						+ "Group ").print(escape(dollarVariable, hs.getLinuxServerGroup().getLinuxGroup().getName().toString())).print('\n');
			} else {
				out.print("User ").print(escape(dollarVariable, User.APACHE.toString())).print("\n"
						+ "Group ").print(escape(dollarVariable, Group.APACHE.toString())).print('\n');
			}
			out.print("\n"
					+ "ServerName ").print(escape(dollarVariable, hs.getAOServer().getHostname().toString())).print("\n"
					+ "\n"
					+ "ErrorLog /var/log/httpd/httpd").print(serverNum).print("/error_log\n"
					+ "CustomLog /var/log/httpd/httpd").print(serverNum).print("/access_log combined\n"
					+ "\n"
					+ "<IfModule mod_dav_fs.c>\n"
					+ "    DAVLockDB /var/lib/dav").print(serverNum).print("/lockdb\n"
					+ "</IfModule>\n"
					+ "\n");
			if(hasJkSettings) {
				out.print("<IfModule mod_jk.c>\n"
						+ "    JkWorkersFile /etc/httpd/conf/workers").print(serverNum).print(".properties\n"
						+ "    JkLogFile /var/log/httpd/httpd").print(serverNum).print("/mod_jk.log\n"
						+ "    JkShmFile /var/log/httpd/httpd").print(serverNum).print("/jk-runtime-status\n"
						+ "</IfModule>\n"
						+ "\n");
			}

			// List of binds
			for(HttpdBind hb : hs.getHttpdBinds()) {
				Bind nb=hb.getNetBind();
				InetAddress ip=nb.getIpAddress().getInetAddress();
				int port=nb.getPort().getPort();
				out.print("Listen ").print(escape(dollarVariable, ip.toBracketedString() + ":" + port)).print("\n"
						+ "NameVirtualHost ").print(escape(dollarVariable, ip.toBracketedString() + ":" + port)).print('\n');
			}

			// The list of sites to include
			for(int d=0;d<2;d++) {
				boolean listFirst=d==0;
				out.print('\n');
				for(Site site : sites) {
					if(site.getListFirst()==listFirst) {
						for(VirtualHost bind : site.getHttpdSiteBinds(hs)) {
							Bind nb=bind.getHttpdBind().getNetBind();
							InetAddress ipAddress=nb.getIpAddress().getInetAddress();
							int port=nb.getPort().getPort();
							String includeFilename;
							{
								String bindEscapedName = bind.getSystemdEscapedName();
								if(bindEscapedName == null) {
									includeFilename = "conf/hosts/" + site.getName()+"_"+ipAddress+"_"+port;
								} else {
									includeFilename = "conf/hosts/" + site.getName()+"_"+ipAddress+"_"+port+"_"+bindEscapedName;
								}
							}
							out.print("Include ").print(escape(dollarVariable, includeFilename)).print('\n');
						}
					}
				}
			}
		}
		return bout.toByteArray();
	}

	/**
	 * Builds the httpd[@&lt;name&gt;].conf file for CentOS 7
	 */
	private static byte[] buildHttpdConfCentOs7(
		HttpdServer hs,
		List<Site> sites,
		Set<String> httpdConfFilenames,
		Set<String> varLibPhpFilenames,
		ByteArrayOutputStream bout,
		Set<UnixFile> restorecon,
		boolean[] hasAnyCgi,
		boolean[] hasAnyModPhp
	) throws IOException, SQLException {
		final String dollarVariable = CENTOS_7_DOLLAR_VARIABLE;
		final HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
		if(osConfig != HttpdOperatingSystemConfiguration.CENTOS_7_X86_64) throw new AssertionError("This method is for CentOS 7 only");
		OperatingSystemVersion osv = hs.getAOServer().getServer().getOperatingSystemVersion();
		assert osv.getPkey() == OperatingSystemVersion.CENTOS_7_X86_64;
		PackageManager.installPackages(
			PackageManager.PackageName.HTTPD,
			PackageManager.PackageName.AOSERV_HTTPD_CONFIG
		);
		final String escapedName = hs.getSystemdEscapedName();
		bout.reset();
		try (ChainWriter out = new ChainWriter(bout)) {
			UserServer lsa = hs.getLinuxServerAccount();
			boolean isEnabled = !lsa.isDisabled();
			// The version of PHP module to run
			SoftwareVersion phpVersion = hs.getModPhpVersion();
			out.print("#\n"
					+ "# core\n"
					+ "#\n"
					+ "ServerRoot ").print(escape(dollarVariable, SERVER_ROOT)).print("\n"
					+ "Include aoserv.conf.d/core.conf\n");
			final String errorLog;
			if(escapedName != null) {
				errorLog = "/var/log/httpd@" + escapedName + "/error_log";
			} else {
				errorLog = "/var/log/httpd/error_log";
			}
			out.print("ErrorLog ").print(escape(dollarVariable, errorLog)).print("\n"
					+ "ServerAdmin ").print(escape(dollarVariable, "root@" + hs.getAOServer().getHostname())).print("\n"
					+ "ServerName ").print(escape(dollarVariable, hs.getAOServer().getHostname().toString())).print("\n"
					+ "TimeOut ").print(hs.getTimeOut()).print("\n"
					+ "\n"
					+ "#\n"
					+ "# mpm\n"
					+ "#\n"
					+ "# LoadModule mpm_event_module modules/mod_mpm_event.so\n"
					+ "LoadModule mpm_prefork_module modules/mod_mpm_prefork.so\n"
					+ "# LoadModule mpm_worker_module modules/mod_mpm_worker.so\n"
					+ "Include aoserv.conf.d/mpm_*.conf\n");
			final String coreDumpDirectory;
			if(escapedName != null) {
				coreDumpDirectory = "/var/log/httpd@" + escapedName;
			} else {
				coreDumpDirectory = "/var/log/httpd";
			}
			out.print("CoreDumpDirectory ").print(escape(dollarVariable, coreDumpDirectory)).print("\n"
					+ "# ListenBacklog 511\n");
			final String pidFile;
			if(escapedName != null) {
				pidFile = "/run/httpd@" + escapedName + "/httpd.pid";
			} else {
				pidFile = "/run/httpd/httpd.pid";
			}
			int maxConcurrency = hs.getMaxConcurrency();
			// Scale maxSpare to 10 percent of maxConcurrency, but capped to Apache default of 10
			int maxSpareServers = maxConcurrency / 10;
			if(maxSpareServers > 10) maxSpareServers = 10;
			// Scale minSpare to half of maxSpare, but capped to Apache default of 10
			int minSpareServers = maxSpareServers / 2;
			if(minSpareServers > 5) minSpareServers = 5; // This will not happen since max is capped to 10, but keeping as it shows we will not exceed default Apache settings
			// Make sure there is at least one minSpare
			if(minSpareServers < 1) minSpareServers = 1;
			// Make sure maxSpace is greater than minSpare
			if(maxSpareServers <= minSpareServers) maxSpareServers = minSpareServers + 1;
			out.print("PidFile ").print(escape(dollarVariable, pidFile)).print("\n"
					+ "<IfModule mpm_prefork_module>\n"
					+ "    MaxSpareServers ").print(maxSpareServers).print("\n"
					+ "    MinSpareServers ").print(minSpareServers).print("\n"
					+ "    MaxRequestWorkers ").print(maxConcurrency).print("\n"
					+ "    ServerLimit ").print(maxConcurrency).print("\n"
					+ "</IfModule>\n"
					+ "\n"
					+ "#\n"
					+ "# Load Modules\n"
					+ "#\n");

			Boolean mod_access_compat = hs.getModAccessCompat();
			if(mod_access_compat == null || !mod_access_compat) out.print("# ");
			out.print("LoadModule access_compat_module modules/mod_access_compat.so\n");

			Boolean mod_actions = hs.getModActions();
			if(mod_actions == null) {
				// Enabled when cgi-based PHP on a site and mod_php is not used
				if(isEnabled && phpVersion != null) {
					mod_actions = false;
				} else {
					boolean hasCgiPhp = false;
					for(Site site : sites) {
						if(site.getPhpVersion() != null) {
							hasCgiPhp = true;
							break;
						}
					}
					mod_actions = hasCgiPhp;
				}
			}
			if(!mod_actions) out.print("# ");
			out.print("LoadModule actions_module modules/mod_actions.so\n");

			Boolean mod_autoindex = hs.getModAutoindex();
			if(mod_autoindex == null) {
				// Enabled when has any httpd_sites.enable_indexes
				boolean hasIndexes = false;
				for(Site site : sites) {
					if(site.getEnableIndexes()) {
						hasIndexes = true;
						break;
					}
				}
				mod_autoindex = hasIndexes;
			}

			Boolean mod_alias = hs.getModAlias();
			if(mod_alias == null) {
				if(mod_autoindex) {
					// Enabled when mod_autoindex enabled (for /icons/ alias)
					mod_alias = true;
				} else {
					// Enabled when any site has secondary context (they are added by Alias)
					boolean hasSecondaryContext = false;
					HAS_SECONDARY:
					for(Site site : sites) {
						com.aoindustries.aoserv.client.web.tomcat.Site tomcatSite = site.getHttpdTomcatSite();
						if(tomcatSite != null) {
							for(Context context : tomcatSite.getHttpdTomcatContexts()) {
								if(!context.getPath().isEmpty()) {
									hasSecondaryContext = true;
									break HAS_SECONDARY;
								}
							}
						}
					}
					mod_alias = hasSecondaryContext;
				}
			}
			if(!mod_alias) out.print("# ");
			out.print("LoadModule alias_module modules/mod_alias.so\n"
					+ "# LoadModule allowmethods_module modules/mod_allowmethods.so\n"
					+ "# LoadModule asis_module modules/mod_asis.so\n");

			boolean hasUserFile = false;
			boolean hasGroupFile = false;
			boolean hasAuthName = false;
			boolean hasRequire = false;
			for(Site site : sites) {
				for(Location hsal : site.getHttpdSiteAuthenticatedLocations()) {
					if(hsal.getAuthUserFile() != null) {
						hasUserFile = true;
					}
					if(hsal.getAuthGroupFile() != null) {
						hasGroupFile = true;
					}
					if(!hsal.getAuthName().isEmpty()) {
						hasAuthName = true;
					}
					if(!hsal.getRequire().isEmpty()) {
						hasRequire = true;
					}
				}
			}

			Boolean mod_auth_basic = hs.getModAuthBasic();
			if(mod_auth_basic == null) {
				// Enabled when has any httpd_site_authenticated_locations.auth_user_file (for AuthType Basic)
				mod_auth_basic = hasUserFile;
			}
			if(!mod_auth_basic) out.print("# ");
			out.print("LoadModule auth_basic_module modules/mod_auth_basic.so\n"
					+ "# LoadModule auth_digest_module modules/mod_auth_digest.so\n"
					+ "# LoadModule authn_anon_module modules/mod_authn_anon.so\n");

			Boolean mod_authn_core = hs.getModAuthnCore();
			if(mod_authn_core == null) {
				// Enabled when has any httpd_site_authenticated_locations.auth_user_file (for AuthType Basic)
				// Enabled when has any httpd_site_authenticated_locations.auth_name (for AuthName)
				mod_authn_core = hasUserFile || hasAuthName;
			}
			if(!mod_authn_core) out.print("# ");
			out.print("LoadModule authn_core_module modules/mod_authn_core.so\n"
					+ "# LoadModule authn_dbd_module modules/mod_authn_dbd.so\n"
					+ "# LoadModule authn_dbm_module modules/mod_authn_dbm.so\n");
			
			Boolean mod_authn_file = hs.getModAuthnFile();
			if(mod_authn_file == null) {
				// Enabled when has any httpd_site_authenticated_locations.auth_user_file (for AuthUserFile)
				mod_authn_file = hasUserFile;
			}
			if(!mod_authn_file) out.print("# ");
			out.print("LoadModule authn_file_module modules/mod_authn_file.so\n"
					+ "# LoadModule authn_socache_module modules/mod_authn_socache.so\n");
			
			Boolean mod_authz_core = hs.getModAuthzCore();
			if(mod_authz_core == null) {
				// Enabled by default (for Require all denied, Require all granted in aoserv.conf.d/*.conf and per-site/bind configs)
				mod_authz_core = true;
			}
			if(!mod_authz_core) out.print("# ");
			out.print("LoadModule authz_core_module modules/mod_authz_core.so\n"
					+ "# LoadModule authz_dbd_module modules/mod_authz_dbd.so\n"
					+ "# LoadModule authz_dbm_module modules/mod_authz_dbm.so\n");

			Boolean mod_authz_groupfile = hs.getModAuthzGroupfile();
			if(mod_authz_groupfile == null) {
				// Enabled when has any httpd_site_authenticated_locations.auth_group_file (for AuthGroupFile)
				mod_authz_groupfile = hasGroupFile;
			}
			if(!mod_authz_groupfile) out.print("# ");
			out.print("LoadModule authz_groupfile_module modules/mod_authz_groupfile.so\n");

			Boolean mod_authz_host = hs.getModAuthzHost();
			if(mod_authz_host == null) {
				// Disabled, no auto condition currently to turn it on
				//   Might be needed for .htaccess or manual Require ip, Require host, Require local
				mod_authz_host = false;
			}
			if(!mod_authz_host) out.print("# ");
			out.print("LoadModule authz_host_module modules/mod_authz_host.so\n"
					+ "# LoadModule authz_owner_module modules/mod_authz_owner.so\n");

			Boolean mod_authz_user = hs.getModAuthzUser();
			if(mod_authz_user == null) {
				// Enabled when has any httpd_site_authenticated_locations.require (for Require user, Requre valid-user)
				mod_authz_user = hasRequire;
			}
			if(!mod_authz_user) out.print("# ");
			out.print("LoadModule authz_user_module modules/mod_authz_user.so\n");

			if(!mod_autoindex) out.print("# ");
			out.print("LoadModule autoindex_module modules/mod_autoindex.so\n"
					+ "# LoadModule buffer_module modules/mod_buffer.so\n"
					+ "# LoadModule cache_module modules/mod_cache.so\n"
					+ "# LoadModule cache_disk_module modules/mod_cache_disk.so\n"
					+ "# LoadModule cache_socache_module modules/mod_cache_socache.so\n");
			// Comment-out cgi_module when no CGI enabled
			boolean hasCgi = false;
			for(Site site : sites) {
				HttpdSiteManager manager = HttpdSiteManager.getInstance(site);
				if(manager.enableCgi()) {
					hasCgi = true;
					hasAnyCgi[0] = true;
					break;
				}
			}
			if(!hasCgi) out.print("# ");
			out.print("<IfModule mpm_event_module>\n");
			if(!hasCgi) out.print("# ");
			out.print("    LoadModule cgid_module modules/mod_cgid.so\n");
			if(!hasCgi) out.print("# ");
			out.print("</IfModule>\n");
			if(!hasCgi) out.print("# ");
			out.print("<IfModule mpm_prefork_module>\n");
			if(!hasCgi) out.print("# ");
			out.print("    LoadModule cgi_module modules/mod_cgi.so\n");
			if(!hasCgi) out.print("# ");
			out.print("</IfModule>\n");
			if(!hasCgi) out.print("# ");
			out.print("<IfModule mpm_worker_module>\n");
			if(!hasCgi) out.print("# ");
			out.print("    LoadModule cgid_module modules/mod_cgid.so\n");
			if(!hasCgi) out.print("# ");
			out.print("</IfModule>\n"
					+ "# LoadModule charset_lite_module modules/mod_charset_lite.so\n"
					+ "# LoadModule data_module modules/mod_data.so\n"
					+ "# LoadModule dav_module modules/mod_dav.so\n"
					+ "# LoadModule dav_fs_module modules/mod_dav_fs.so\n"
					+ "# LoadModule dav_lock_module modules/mod_dav_lock.so\n"
					+ "# LoadModule dbd_module modules/mod_dbd.so\n");

			Boolean mod_deflate = hs.getModDeflate();
			if(mod_deflate == null) {
				// Enabled by default (unless explicitly disabled)
				mod_deflate = true;
			}
			if(!mod_deflate) out.print("# ");
			out.print("LoadModule deflate_module modules/mod_deflate.so\n"
					+ "# LoadModule dialup_module modules/mod_dialup.so\n");

			Boolean mod_dir = hs.getModDir();
			if(mod_dir == null) {
				// Enabled by default (unless explicitly disabled)
				mod_dir = true;
			}
			if(!mod_dir) out.print("# ");
			out.print("LoadModule dir_module modules/mod_dir.so\n"
					+ "# LoadModule dumpio_module modules/mod_dumpio.so\n"
					+ "# LoadModule echo_module modules/mod_echo.so\n"
					+ "# LoadModule env_module modules/mod_env.so\n"
					+ "# LoadModule expires_module modules/mod_expires.so\n"
					+ "# LoadModule ext_filter_module modules/mod_ext_filter.so\n"
					+ "# LoadModule file_cache_module modules/mod_file_cache.so\n");

			Boolean mod_filter = hs.getModFilter();
			if(mod_filter == null) {
				// Enabled when mod_deflate is enabled (for AddOutputFilterByType in aoserv.conf.d/mod_deflate.conf)
				mod_filter = mod_deflate;
			}
			if(!mod_filter) out.print("# ");
			out.print("LoadModule filter_module modules/mod_filter.so\n");

			Boolean mod_headers = hs.getModHeaders();
			if(mod_headers == null) {
				mod_headers = false;
				HTTPD_SITES:
				for(Site site : sites) {
					for(VirtualHost bind : site.getHttpdSiteBinds(hs)) {
						// Enabled when has any httpd_site_bind_headers
						if(!bind.getHttpdSiteBindHeaders().isEmpty()) {
							mod_headers = true;
							break HTTPD_SITES;
						}
					}
				}
			}
			if(!mod_headers) out.print("# ");
			out.print("LoadModule headers_module modules/mod_headers.so\n"
					+ "# LoadModule heartbeat_module modules/mod_heartbeat.so\n"
					+ "# LoadModule heartmonitor_module modules/mod_heartmonitor.so\n");

			Boolean mod_include = hs.getModInclude();
			if(mod_include == null) {
				// Enabled when has any httpd_sites.enable_ssi
				boolean hasSsi = false;
				for(Site site : sites) {
					if(site.getEnableSsi()) {
						hasSsi = true;
						break;
					}
				}
				mod_include = hasSsi;
			}
			if(!mod_include) out.print("# ");
			out.print("LoadModule include_module modules/mod_include.so\n"
					+ "# LoadModule info_module modules/mod_info.so\n");

			Boolean mod_jk = hs.getModJk();
			if(mod_jk == null) {
				// Enabled when any site has a JkMount or JkUnMount
				boolean hasJkSettings = false;
				for(Site site : sites) {
					HttpdSiteManager manager = HttpdSiteManager.getInstance(site);
					if(!manager.getJkSettings().isEmpty()) {
						hasJkSettings = true;
						break;
					}
				}
				mod_jk = hasJkSettings;
			}
			final boolean modJkInstalled;
			if(mod_jk) {
				// TODO: Uninstall tomcat_connectors when no longer needed
				PackageManager.installPackage(PackageManager.PackageName.TOMCAT_CONNECTORS);
				modJkInstalled = true;
			} else {
				modJkInstalled = PackageManager.getInstalledPackage(PackageManager.PackageName.TOMCAT_CONNECTORS) != null;
			}
			if(modJkInstalled) {
				if(!mod_jk) out.print("# ");
				out.print("LoadModule jk_module modules/mod_jk.so\n");
			}
			out.print("# LoadModule lbmethod_bybusyness_module modules/mod_lbmethod_bybusyness.so\n"
					+ "# LoadModule lbmethod_byrequests_module modules/mod_lbmethod_byrequests.so\n"
					+ "# LoadModule lbmethod_bytraffic_module modules/mod_lbmethod_bytraffic.so\n"
					+ "# LoadModule lbmethod_heartbeat_module modules/mod_lbmethod_heartbeat.so\n");

			Boolean mod_log_config = hs.getModLogConfig();
			if(mod_log_config == null) {
				// Enabled by default (unless explicitly disabled)
				mod_log_config = true;
			}
			if(!mod_log_config) out.print("# ");
			out.print("LoadModule log_config_module modules/mod_log_config.so\n"
					+ "# LoadModule log_debug_module modules/mod_log_debug.so\n"
					+ "# LoadModule log_forensic_module modules/mod_log_forensic.so\n"
					+ "# LoadModule logio_module modules/mod_logio.so\n"
					+ "# LoadModule lua_module modules/mod_lua.so\n"
					+ "# LoadModule macro_module modules/mod_macro.so\n");

			Boolean mod_mime = hs.getModMime();
			if(mod_mime == null) {
				// Enabled by default (unless explicitly disabled)
				// Enabled when has mod_php (for AddType .php and AddType .phps)
				// Enabled when mod_negotiation is enabled (for AddHandler .var)
				mod_mime = true;
			}
			if(!mod_mime) out.print("# ");
			out.print("LoadModule mime_module modules/mod_mime.so\n");

			Boolean mod_mime_magic = hs.getModMimeMagic();
			if(mod_mime_magic == null) {
				// Enabled by default (unless explicitly disabled)
				mod_mime_magic = true;
			}
			if(!mod_mime_magic) out.print("# ");
			out.print("LoadModule mime_magic_module modules/mod_mime_magic.so\n");

			Boolean mod_negotiation = hs.getModNegotiation();
			if(mod_negotiation == null) {
				// Disabled by default (unless explicitly enabled)
				mod_negotiation = false;
			}
			if(!mod_negotiation) out.print("# ");
			out.print("LoadModule negotiation_module modules/mod_negotiation.so\n");

			Boolean mod_proxy_http = hs.getModProxyHttp();
			if(mod_proxy_http == null) {
				// Disabled by default (unless explicitly enabled)
				mod_proxy_http = false;
			}

			Boolean mod_proxy = hs.getModProxy();
			if(mod_proxy == null) {
				// Enabled when mod_proxy_http is enabled
				mod_proxy = mod_proxy_http;
			}
			if(!mod_proxy) out.print("# ");
			out.print("LoadModule proxy_module modules/mod_proxy.so\n"
					+ "# LoadModule proxy_ajp_module modules/mod_proxy_ajp.so\n"
					+ "# LoadModule proxy_balancer_module modules/mod_proxy_balancer.so\n"
					+ "# LoadModule proxy_connect_module modules/mod_proxy_connect.so\n"
					+ "# LoadModule proxy_express_module modules/mod_proxy_express.so\n"
					+ "# LoadModule proxy_fcgi_module modules/mod_proxy_fcgi.so\n"
					+ "# LoadModule proxy_fdpass_module modules/mod_proxy_fdpass.so\n"
					+ "# LoadModule proxy_ftp_module modules/mod_proxy_ftp.so\n");

			if(!mod_proxy_http) out.print("# ");
			out.print("LoadModule proxy_http_module modules/mod_proxy_http.so\n"
					+ "# LoadModule proxy_scgi_module modules/mod_proxy_scgi.so\n"
					+ "# LoadModule proxy_wstunnel_module modules/mod_proxy_wstunnel.so\n"
					+ "# LoadModule ratelimit_module modules/mod_ratelimit.so\n"
					+ "# LoadModule reflector_module modules/mod_reflector.so\n"
					+ "# LoadModule remoteip_module modules/mod_remoteip.so\n");

			Boolean mod_reqtimeout = hs.getModReqtimeout();
			if(mod_reqtimeout == null) {
				// Enabled by default (unless explicitly disabled)
				mod_reqtimeout = true;
			}
			if(!mod_reqtimeout) out.print("# ");
			out.print("LoadModule reqtimeout_module modules/mod_reqtimeout.so\n"
					+ "# LoadModule request_module modules/mod_request.so\n");

			Boolean mod_rewrite = hs.getModRewrite();
			if(mod_rewrite == null) {
				mod_rewrite = false;
				HTTPD_SITES:
				for(Site site : sites) {
					final HttpdSiteManager manager = HttpdSiteManager.getInstance(site);
					// Enabled when has any httpd_sites.block_trace_track
					if(manager.blockAllTraceAndTrackRequests()) {
						mod_rewrite = true;
						break HTTPD_SITES;
					}
					// Enabled when has any permanent rewrites
					if(!manager.getPermanentRewriteRules().isEmpty()) {
						mod_rewrite = true;
						break HTTPD_SITES;
					}
					for(VirtualHost bind : site.getHttpdSiteBinds(hs)) {
						// Enabled when has any httpd_site_binds.redirect_to_primary_hostname
						if(bind.getRedirectToPrimaryHostname()) {
							mod_rewrite = true;
							break HTTPD_SITES;
						}
						// Enabled when has any RewriteRule
						if(!bind.getRewriteRules().isEmpty()) {
							mod_rewrite = true;
							break HTTPD_SITES;
						}
					}
				}
			}
			if(!mod_rewrite) out.print("# ");
			out.print("LoadModule rewrite_module modules/mod_rewrite.so\n"
					+ "# LoadModule sed_module modules/mod_sed.so\n");

			Boolean mod_ssl = hs.getModSsl();
			if(mod_ssl == null) {
				// Enabled when has any httpd_site_binds.ssl_cert_file
				boolean hasSsl = false;
				HAS_SSL :
				for(Site site : sites) {
					for(VirtualHost hsb : site.getHttpdSiteBinds(hs)) {
						if(AppProtocol.HTTPS.equals(hsb.getHttpdBind().getNetBind().getAppProtocol().getProtocol())) {
							hasSsl = true;
							break HAS_SSL;
						}
					}
				}
				mod_ssl = hasSsl;
			}

			Boolean mod_setenvif = hs.getModSetenvif();
			if(mod_setenvif == null) {
				// Enabled when mod_ssl is enabled (for BrowserMatch SSL downgrade)
				mod_setenvif = mod_ssl;
			}
			if(!mod_setenvif) out.print("# ");
			out.print("LoadModule setenvif_module modules/mod_setenvif.so\n"
					+ "# LoadModule slotmem_plain_module modules/mod_slotmem_plain.so\n"
					+ "# LoadModule slotmem_shm_module modules/mod_slotmem_shm.so\n"
					+ "# LoadModule socache_dbm_module modules/mod_socache_dbm.so\n"
					+ "# LoadModule socache_memcache_module modules/mod_socache_memcache.so\n");

			Boolean mod_socache_shmcb = hs.getModSocacheShmcb();
			if(mod_socache_shmcb == null) {
				// Enabled when mod_ssl is enabled (for SSLSessionCache shmcb:/run/httpd)
				mod_socache_shmcb = mod_ssl;
			}
			if(!mod_socache_shmcb) out.print("# ");
			out.print("LoadModule socache_shmcb_module modules/mod_socache_shmcb.so\n"
					+ "# LoadModule speling_module modules/mod_speling.so\n");

			final boolean modSslInstalled;
			if(mod_ssl) {
				// TODO: Uninstall mod_ssl when no longer needed
				PackageManager.installPackage(PackageManager.PackageName.MOD_SSL);
				modSslInstalled = true;
			} else {
				modSslInstalled = PackageManager.getInstalledPackage(PackageManager.PackageName.MOD_SSL) != null;
			}
			if(modSslInstalled) {
				if(!mod_ssl) out.print("# ");
				out.print("LoadModule ssl_module modules/mod_ssl.so\n");
			}

			Boolean mod_status = hs.getModStatus();
			if(mod_status == null) {
				// Enabled when used by any "server-status" handler
				boolean hasStatusHandler = false;
				HAS_HANDLER :
				for(Site site : sites) {
					for(Location hsal : site.getHttpdSiteAuthenticatedLocations()) {
						if(Location.Handler.SERVER_STATUS.equals(hsal.getHandler())) {
							hasStatusHandler = true;
							break HAS_HANDLER;
						}
					}
				}
				mod_status = hasStatusHandler;
			}
			if(!mod_status) out.print("# ");
			out.print("LoadModule status_module modules/mod_status.so\n"
					+ "# LoadModule substitute_module modules/mod_substitute.so\n");
			if(!hs.useSuexec()) out.print("# ");
			out.print("LoadModule suexec_module modules/mod_suexec.so\n"
					+ "LoadModule systemd_module modules/mod_systemd.so\n"
					+ "# LoadModule unique_id_module modules/mod_unique_id.so\n"
					+ "LoadModule unixd_module modules/mod_unixd.so\n"
					+ "# LoadModule userdir_module modules/mod_userdir.so\n"
					+ "# LoadModule usertrack_module modules/mod_usertrack.so\n"
					+ "# LoadModule version_module modules/mod_version.so\n"
					+ "# LoadModule vhost_alias_module modules/mod_vhost_alias.so\n"
					+ "# LoadModule watchdog_module modules/mod_watchdog.so\n");
			final boolean modWsgiEnabled;
			{
				Boolean mod_wsgi = hs.getModWsgi();
				modWsgiEnabled = mod_wsgi != null && mod_wsgi;
			}
			final boolean modWsgiInstalled;
			if(modWsgiEnabled) {
				PackageManager.installPackage(PackageManager.PackageName.MOD_WSGI);
				modWsgiInstalled = true;
			} else {
				modWsgiInstalled = PackageManager.getInstalledPackage(PackageManager.PackageName.MOD_WSGI) != null;
			}
			if(modWsgiInstalled) {
				if(!modWsgiEnabled) out.print("# ");
				out.print("LoadModule wsgi_module modules/mod_wsgi.so\n");
			}
			out.print("\n"
					+ "#\n"
					+ "# Configure Modules\n"
					+ "#\n"
					+ "Include aoserv.conf.d/mod_*.conf\n"
					+ "<IfModule dav_fs_module>\n");
			final String davLockDB;
			if(escapedName != null) {
				davLockDB = "/var/lib/dav@" + escapedName + "/lockdb";
			} else {
				davLockDB = "/var/lib/dav/lockdb";
			}
			out.print("    DavLockDB ").print(escape(dollarVariable, davLockDB)).print("\n"
					+ "</IfModule>\n");
			if(mod_jk || modJkInstalled) {
				out.print("<IfModule jk_module>\n");
				final String jkWorkersFile;
				if(escapedName != null) {
					jkWorkersFile = "conf/workers@" + escapedName + ".properties";
				} else {
					jkWorkersFile = "conf/workers.properties";
				}
				out.print("    JkWorkersFile ").print(escape(dollarVariable, jkWorkersFile)).print('\n');
				final String jkShmFile;
				if(escapedName != null) {
					jkShmFile = "/var/log/httpd@" + escapedName + "/jk-runtime-status";
				} else {
					jkShmFile = "/var/log/httpd/jk-runtime-status";
				}
				out.print("    JkShmFile ").print(escape(dollarVariable, jkShmFile)).print('\n');
				final String jkLogFile;
				if(escapedName != null) {
					jkLogFile = "/var/log/httpd@" + escapedName + "/mod_jk.log";
				} else {
					jkLogFile = "/var/log/httpd/mod_jk.log";
				}
				out.print("    JkLogFile ").print(escape(dollarVariable, jkLogFile)).print("\n"
						+ "</IfModule>\n");
			}
			out.print("<IfModule log_config_module>\n");
			final String customLog;
			if(escapedName != null) {
				customLog = "/var/log/httpd@" + escapedName + "/access_log";
			} else {
				customLog = "/var/log/httpd/access_log";
			}
			out.print("    CustomLog ").print(escape(dollarVariable, customLog)).print(" combined\n"
					+ "</IfModule>\n");
			if(mod_ssl || modSslInstalled) {
				out.print("<IfModule ssl_module>\n");
				final String sslSessionCache;
				if(escapedName != null) {
					sslSessionCache = "shmcb:/run/httpd@" + escapedName + "/sslcache(512000)";
				} else {
					sslSessionCache = "shmcb:/run/httpd/sslcache(512000)";
				}
				out.print("    SSLSessionCache ").print(escape(dollarVariable, sslSessionCache)).print("\n"
						+ "</IfModule>\n");
			}
			// Use apache if the account is disabled
			out.print("<IfModule unixd_module>\n");
			if(isEnabled) {
				out.print("    User ").print(escape(dollarVariable, lsa.getLinuxAccount().getUsername().getUsername().toString())).print("\n"
						+ "    Group ").print(escape(dollarVariable, hs.getLinuxServerGroup().getLinuxGroup().getName().toString())).print('\n');
			} else {
				out.print("    User ").print(escape(dollarVariable, User.APACHE.toString())).print("\n"
						+ "    Group ").print(escape(dollarVariable, Group.APACHE.toString())).print('\n');
			}
			out.print("</IfModule>\n");
			if(phpVersion != null) {
				String version = phpVersion.getVersion();
				String phpMinorVersion = getMinorPhpVersion(version);
				// Create initial PHP config directory
				UnixFile phpIniDir;
				if(escapedName == null) {
					phpIniDir = new UnixFile(CONF_DIRECTORY + "/php");
					httpdConfFilenames.add("php");
				} else {
					phpIniDir = new UnixFile(CONF_DIRECTORY + "/php@" + escapedName);
					httpdConfFilenames.add("php@" + escapedName);
				}
				int gid = hs.getLinuxServerGroup().getGid().getId();
				if(DaemonFileUtils.mkdir(phpIniDir, 0750, UnixFile.ROOT_UID, gid)) {
					restorecon.add(phpIniDir);
				}
				// Create the conf.d config directory for PHP 7+
				String expectedTarget;
				UnixFile confD;
				if(phpVersion.getVersion().startsWith("5.")) {
					expectedTarget = "../../../../opt/php-" + phpMinorVersion + "/lib/php.ini";
					confD = null;
				} else {
					expectedTarget = "../../../opt/php-" + phpMinorVersion + "/php.ini";
					confD = new UnixFile(phpIniDir, MOD_PHP_CONF_D, true);
				}
				if(confD != null) {
					if(DaemonFileUtils.mkdir(confD, 0750, UnixFile.ROOT_UID, gid)) {
						restorecon.add(confD);
					}
					// TODO: Create and update symlinks in conf.d matching an AOServ-configured set of extensions
				} else {
					// TODO: Remove any auto symlinks in conf.d, if exists
					// TODO: Remove conf.d is then empty
				}
				// Create or update php.ini symbolic link
				UnixFile phpIni = new UnixFile(phpIniDir, "php.ini", true);
				Stat phpIniStat = phpIni.getStat();
				if(!phpIniStat.exists()) {
					phpIni.symLink(expectedTarget);
					restorecon.add(phpIni);
				} else if(phpIniStat.isSymLink()) {
					// Replace symlink if goes to a different PHP version?
					String actualTarget = phpIni.readLink();
					if(
						!actualTarget.equals(expectedTarget)
						&& (
							// PHP 5
							actualTarget.startsWith("../../../../opt/php-")
							// PHP 7+
							|| actualTarget.startsWith("../../../opt/php-")
						)
					) {
						// Update link
						phpIni.delete();
						phpIni.symLink(expectedTarget);
						restorecon.add(phpIni);
					}
				}
				// Create session directory, if needed
				UnixFile sessionDir;
				{
					String sessionDirName;
					if(escapedName == null) {
						sessionDirName = PHP_SESSION;
					} else {
						sessionDirName = PHP_SESSION + "@" + escapedName;
					}
					sessionDir = new UnixFile(VAR_LIB_PHP_DIRECTORY, sessionDirName, true);
					varLibPhpFilenames.add(sessionDirName);
				}
				int uid = hs.getLinuxServerAccount().getUid().getId();
				if(DaemonFileUtils.mkdir(VAR_LIB_PHP_DIRECTORY, 0755, UnixFile.ROOT_UID, UnixFile.ROOT_GID)) {
					restorecon.add(VAR_LIB_PHP_DIRECTORY);
				}
				if(DaemonFileUtils.mkdir(sessionDir, 0700, uid, gid)) {
					restorecon.add(sessionDir);
				}
				if(isEnabled) {
					hasAnyModPhp[0] = true;
					String phpMajorVersion = getMajorPhpVersion(version);
					out.print("\n"
							+ "#\n"
							+ "# Enable mod_php\n"
							+ "#\n"
							+ "LoadModule ").print(escape(dollarVariable, "php" + phpMajorVersion + "_module")).print(' ').print(escape(dollarVariable, "/opt/php-" + phpMinorVersion + "/lib/apache/libphp" + phpMajorVersion + ".so")).print("\n"
							+ "<IfModule php").print(phpMajorVersion).print("_module>\n"
							+ "    PHPIniDir ").print(escape(dollarVariable, phpIniDir.toString())).print("\n"
							+ "    php_value session.save_path ").print(escape(dollarVariable, sessionDir.toString())).print("\n"
							+ "    <IfModule mime_module>\n"
							+ "        AddType application/x-httpd-php .php\n"
							+ "        AddType application/x-httpd-php-source .phps\n"
							+ "    </IfModule>\n"
							+ "</IfModule>\n");
				}
			}

			// List of binds
			out.print("\n"
					+ "#\n"
					+ "# Binds\n"
					+ "#\n");
			for(HttpdBind hb : hs.getHttpdBinds()) {
				Bind nb=hb.getNetBind();
				InetAddress ip=nb.getIpAddress().getInetAddress();
				int port = nb.getPort().getPort();
				out.print("Listen ").print(escape(dollarVariable, ip.toBracketedString() + ":" + port));
				String appProtocol = nb.getAppProtocol().getProtocol();
				if(appProtocol.equals(AppProtocol.HTTP)) {
					if(port != 80) out.print(" http");
				} else if(appProtocol.equals(AppProtocol.HTTPS)) {
					if(port != 443) out.print(" https");
				} else {
					throw new SQLException("Unexpected app protocol: " + appProtocol);
				}
				out.print('\n');
			}
			out.print("\n"
					+ "#\n"
					+ "# Sites\n"
					+ "#\n");
			// TODO: Could use wildcard include if there are no list-first sites (or they happen to match the ordering?) and there is only one apache instance
			for(int d = 0; d < 2; d++) {
				boolean listFirst = (d == 0);
				for(Site site : sites) {
					if(site.getListFirst() == listFirst) {
						for(VirtualHost bind : site.getHttpdSiteBinds(hs)) {
							Bind nb = bind.getHttpdBind().getNetBind();
							InetAddress ipAddress = nb.getIpAddress().getInetAddress();
							int port=nb.getPort().getPort();
							String includeFilename;
							{
								String bindEscapedName = bind.getSystemdEscapedName();
								if(bindEscapedName == null) {
									includeFilename = "sites-enabled/" + site.getName()+"_"+ipAddress+"_"+port + ".conf";
								} else {
									includeFilename = "sites-enabled/" + site.getName()+"_"+ipAddress+"_"+port+"_"+bindEscapedName + ".conf";
								}
							}
							out.print("Include ").print(escape(dollarVariable, includeFilename)).print('\n');
						}
					}
				}
			}
		}
		return bout.toByteArray();
	}

	/**
	 * Builds the httpd[[-]#].conf file contents for the provided HttpdServer.
	 */
	private static byte[] buildHttpdConf(
		HttpdServer hs,
		List<Site> sites,
		Set<String> httpdConfFilenames,
		Set<String> varLibPhpFilenames,
		ByteArrayOutputStream bout,
		Set<UnixFile> restorecon,
		boolean[] hasAnyCgi,
		boolean[] hasAnyModPhp
	) throws IOException, SQLException {
		HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
		switch(osConfig) {
			case CENTOS_5_I686_AND_X86_64 : return buildHttpdConfCentOs5(hs, sites, httpdConfFilenames, bout, hasAnyCgi, hasAnyModPhp);
			case CENTOS_7_X86_64          : return buildHttpdConfCentOs7(hs, sites, httpdConfFilenames, varLibPhpFilenames, bout, restorecon, hasAnyCgi, hasAnyModPhp);
			default                       : throw new AssertionError("Unexpected value for osConfig: "+osConfig);
		}
	}

	private static boolean isWorkerEnabled(Worker worker) throws IOException, SQLException {
		SharedTomcat hst = worker.getHttpdSharedTomcat();
		if(hst != null) {
			if(hst.isDisabled()) return false;
			// Must also have at least one enabled site
			for(SharedTomcatSite htss : hst.getHttpdTomcatSharedSites()) {
				if(!htss.getHttpdTomcatSite().getHttpdSite().isDisabled()) return true;
			}
			// Does not have any enabled site
			return false;
		}
		com.aoindustries.aoserv.client.web.tomcat.Site hts = worker.getTomcatSite();
		if(hts != null) return !hts.getHttpdSite().isDisabled();
		throw new SQLException("worker is attached to neither SharedTomcat nor Site: " + worker);
	}

	/**
	 * Builds the workers#.properties or workers[@&lt;name&gt;].properties file contents for the provided HttpdServer.
	 */
	private static byte[] buildWorkersFile(HttpdServer hs, ByteArrayOutputStream bout, Set<Port> enabledAjpPorts) throws IOException, SQLException {
		AOServConnector conn = AOServDaemon.getConnector();
		List<Worker> workers = hs.getHttpdWorkers();
		bout.reset();
		try (ChainWriter out = new ChainWriter(bout)) {
			out.print("worker.list=");
			boolean didOne = false;
			for(Worker worker : workers) {
				if(isWorkerEnabled(worker)) {
					if(didOne) out.print(',');
					else didOne = true;
					out.print(worker.getName().getCode());
				}
			}
			out.print('\n');
			for(Worker worker : workers) {
				if(isWorkerEnabled(worker)) {
					String code = worker.getName().getCode();
					Port port = worker.getBind().getPort();
					out.print("\n"
							+ "worker.").print(code).print(".type=").print(worker.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol()).print("\n"
							+ "worker.").print(code).print(".host=127.0.0.1\n" // For use IPv4 on CentOS 7
							+ "worker.").print(code).print(".port=").print(port.getPort()).print('\n');
					enabledAjpPorts.add(port);
				}
			}
		}
		return bout.toByteArray();
	}

	private static String getEscapedPrefixReplacement(String dollarVariable, String value, String expectedPrefix, String replacementPrefix) {
		if(value.startsWith(expectedPrefix)) {
			String suffix = value.substring(expectedPrefix.length());
			if(!suffix.contains("${")) return escape(dollarVariable, replacementPrefix + suffix, true);
		}
		return escape(dollarVariable, value);
	}

	private static String getEscapedSslPath(String dollarVariable, PosixPath sslCert, String primaryHostname) {
		// Let's Encrypt certificate
		String sslCertStr = sslCert.toString();
		{
			String prefix = "/etc/letsencrypt/live/" + primaryHostname + "/";
			if(sslCertStr.startsWith(prefix)) {
				String suffix = sslCertStr.substring(prefix.length());
				if(!suffix.contains("${")) return escape(dollarVariable, "/etc/letsencrypt/live/${bind.primary_hostname}/" + suffix, true);
			}
		}
		// CentOS 7:
		if(sslCertStr.equals("/etc/pki/tls/private/" + primaryHostname + ".key")) return "/etc/pki/tls/private/${bind.primary_hostname}.key";
		if(sslCertStr.equals("/etc/pki/tls/certs/" + primaryHostname + ".cert")) return "/etc/pki/tls/certs/${bind.primary_hostname}.cert";
		if(sslCertStr.equals("/etc/pki/tls/certs/" + primaryHostname + ".chain")) return "/etc/pki/tls/certs/${bind.primary_hostname}.chain";
		return escape(dollarVariable, sslCertStr);
	}

	/**
	 * Determines if a RewriteRule redirects all traffic.  This is used to know when the main site configuration
	 * is not required to be included.
	 */
	private static boolean isRedirectAll(RewriteRule rewriteRule) {
		// String substitution = rewriteRule.getSubstitution();
		String pattern = rewriteRule.getPattern();
		return
			(
				"^".equals(pattern)
				|| "^(.*)$".equals(pattern)
			)
			// && !substitution.equals("-")
			&& (
				// Redirects with "L", "last", or "END"
				rewriteRule.hasFlag("R", "redirect")
				&& rewriteRule.hasFlag("L", "last", "END")
			) || (
				// Proxy via "P" or "proxy" ("L" is implied)
				rewriteRule.hasFlag("P", "proxy")
			) || (
				// Flag "L", "last", "END" stops processing, expecting request to still be handled by this virtual host configuration
				!rewriteRule.hasFlag("L", "last", "END")
				// Flag "S" or "skip" jumps to a different rule, so does not constitute redirecting all
				&& !rewriteRule.hasFlag("S", "skip")
				// Flag "T" or "type" sets the MIME type, so does not constitute redirecting all
				&& !rewriteRule.hasFlag("T", "type")
			);
	}

	/**
	 * Builds the contents of a VirtualHost file.
	 */
	private static byte[] buildHttpdSiteBindFile(HttpdSiteManager manager, VirtualHost bind, String siteInclude, ByteArrayOutputStream bout) throws IOException, SQLException {
		OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
		OperatingSystemVersion osv = manager.httpdSite.getAoServer().getServer().getOperatingSystemVersion();
		HttpdBind httpdBind = bind.getHttpdBind();
		Bind netBind = httpdBind.getNetBind();
		int port = netBind.getPort().getPort();
		InetAddress ipAddress = netBind.getIpAddress().getInetAddress();
		VirtualHostName primaryHSU = bind.getPrimaryHttpdSiteURL();
		String primaryHostname = primaryHSU.getHostname().toString();

		// TODO: Robots NOINDEX, NOFOLLOW on test URL, when it is not the primary?
		// TODO: Canonical URL header for non-primary, non-test: https://support.google.com/webmasters/answer/139066
		bout.reset();
		try (ChainWriter out = new ChainWriter(bout)) {
			switch(osConfig) {
				case CENTOS_5_I686_AND_X86_64 : {
					final String dollarVariable = CENTOS_5_DOLLAR_VARIABLE;
					out.print("<VirtualHost ").print(escape(dollarVariable, ipAddress.toBracketedString() + ":" + port)).print(">\n"
							+ "    ServerName \\\n"
							+ "        ").print(escape(dollarVariable, primaryHostname)).print('\n');
					List<VirtualHostName> altURLs=bind.getAltHttpdSiteURLs();
					if(!altURLs.isEmpty()) {
						out.print("    ServerAlias");
						for(VirtualHostName altURL : altURLs) {
							out.print(" \\\n        ").print(escape(dollarVariable, altURL.getHostname().toString()));
						}
						out.print('\n');
					}
					out.print("\n"
							+ "    CustomLog ").print(escape(dollarVariable, bind.getAccessLog().toString())).print(" combined\n"
							+ "    ErrorLog ").print(escape(dollarVariable, bind.getErrorLog().toString())).print('\n');
					if(AppProtocol.HTTPS.equals(netBind.getAppProtocol().getProtocol())) {
						Certificate sslCert = bind.getCertificate();
						if(sslCert == null) throw new SQLException("SSLCertificate not found for VirtualHost #" + bind.getPkey());
						// Use any directly configured chain file
						out.print("\n"
								+ "    <IfModule mod_ssl.c>\n"
								+ "        SSLCertificateFile ").print(escape(dollarVariable, sslCert.getCertFile().toString())).print("\n"
								+ "        SSLCertificateKeyFile ").print(escape(dollarVariable, sslCert.getKeyFile().toString())).print('\n');
						PosixPath sslChain = sslCert.getChainFile();
						if(sslChain != null) {
							out.print("        SSLCertificateChainFile ").print(escape(dollarVariable, sslChain.toString())).print('\n');
						}
						boolean enableCgi = manager.enableCgi();
						boolean enableSsi = manager.httpdSite.getEnableSsi();
						if(enableCgi && enableSsi) {
							out.print("        <Files ~ \\.(cgi|shtml)$>\n"
									+ "            SSLOptions +StdEnvVars\n"
									+ "        </Files>\n");
						} else if(enableCgi) {
							out.print("        <Files ~ \\.cgi$>\n"
									+ "            SSLOptions +StdEnvVars\n"
									+ "        </Files>\n");
						} else if(enableSsi) {
							out.print("        <Files ~ \\.shtml$>\n"
									+ "            SSLOptions +StdEnvVars\n"
									+ "        </Files>\n");
						}
						out.print("        SSLEngine On\n"
								+ "    </IfModule>\n"
						);
					}
					if(bind.getRedirectToPrimaryHostname()) {
						out.print("\n"
								+ "    # Redirect requests that are not to either the IP address or the primary hostname to the primary hostname\n"
								+ "    RewriteEngine on\n"
								+ "    RewriteCond %{HTTP_HOST} ").print(escape(dollarVariable, "!=" + primaryHostname)).print(" [NC]\n"
								+ "    RewriteCond %{HTTP_HOST} ").print(escape(dollarVariable, "!=" + ipAddress)).print("\n"
								+ "    RewriteRule ^ ").print(escape(dollarVariable, primaryHSU.getURLNoSlash() + "%{REQUEST_URI}")).print(" [L,NE,R=permanent]\n");
					}
					boolean hasRedirectAll = false;
					List<RewriteRule> rewriteRules = bind.getRewriteRules();
					if(!rewriteRules.isEmpty()) {
						out.print("\n"
								+ "    # Redirects\n"
								+ "    RewriteEngine on\n");
						for(RewriteRule rewriteRule : rewriteRules) {
							String comment = rewriteRule.getComment();
							if(comment != null) {
								// TODO: Maybe separate escapeComment method for this?
								out.print("    # ").print(escape(dollarVariable, comment, true)).print('\n');
							}
							// Auto-detect a redirect-all bind
							if(isRedirectAll(rewriteRule)) {
								hasRedirectAll = true;
							}
							out.print("    ").print(rewriteRule.getApacheDirective(dollarVariable)).print('\n');
						}
					}
					List<Header> headers = bind.getHttpdSiteBindHeaders();
					if(!headers.isEmpty()) {
						out.print("\n"
								+ "    # Headers\n");
						for(Header header : headers) {
							String comment = header.getComment();
							if(comment != null) {
								// TODO: Maybe separate escapeComment method for this?
								out.print("    # ").print(escape(dollarVariable, comment, true)).print('\n');
							}
							out.print("    ").print(header.getApacheDirective(dollarVariable)).print('\n');
						}
					}
					final String escapedSiteInclude = escape(dollarVariable, "conf/hosts/" + siteInclude);
					String includeSiteConfig = bind.getIncludeSiteConfig();
					if(includeSiteConfig == null) {
						if(hasRedirectAll) includeSiteConfig = "IfModule !rewrite_module";
					}
					out.print('\n');
					if("false".equals(includeSiteConfig)) {
						out.print("    # Include ").print(escapedSiteInclude).print("\n");
					} else if(includeSiteConfig != null && includeSiteConfig.startsWith("IfModule ")) {
						out.print("    <" + includeSiteConfig + ">\n"
								+ "        Include ").print(escapedSiteInclude).print("\n"
								+ "    </IfModule>\n");
					} else {
						out.print("    Include ").print(escapedSiteInclude).print("\n");
					}
					out.print("\n"
							+ "</VirtualHost>\n");
					break;
				}
				case CENTOS_7_X86_64 : {
					final String bindEscapedName = bind.getSystemdEscapedName();
					final String dollarVariable = CENTOS_7_DOLLAR_VARIABLE;
					final Certificate sslCert;
					final String protocol;
					final boolean isDefaultPort;
					{
						String appProtocol = netBind.getAppProtocol().getProtocol();
						if(AppProtocol.HTTP.equals(appProtocol)) {
							sslCert = null;
							protocol = "http";
							isDefaultPort = port == 80;
						} else if(AppProtocol.HTTPS.equals(appProtocol)) {
							sslCert = bind.getCertificate();
							if(sslCert == null) throw new SQLException("SSLCertificate not found for VirtualHost #" + bind.getPkey());
							protocol = "https";
							isDefaultPort = port == 443;
						} else {
							throw new SQLException("Unsupported protocol: " + appProtocol);
						}
					}
					final String siteName = manager.httpdSite.getName();
					final String ipString = ipAddress.toBracketedString();
					out.print("Define bind.pkey             ").print(bind.getPkey()).print("\n"
							+ "Define bind.protocol         ").print(escape(dollarVariable, protocol)).print("\n"
							+ "Define bind.ip_address       ").print(escape(dollarVariable, ipString)).print("\n"
							+ "Define bind.port             ").print(port).print("\n"
							+ "Define bind.name             ").print(escape(dollarVariable, bindEscapedName==null ? "" : bindEscapedName)).print("\n"
							+ "Define bind.primary_hostname ").print(escape(dollarVariable, primaryHostname)).print("\n"
							+ "Define site.name             ").print(escape(dollarVariable, siteName)).print("\n"
							+ "Define site.user             ").print(escape(dollarVariable, manager.httpdSite.getLinuxServerAccount().getLinuxAccount().getUsername().getUsername().toString())).print("\n"
							+ "Define site.group            ").print(escape(dollarVariable, manager.httpdSite.getLinuxServerGroup().getLinuxGroup().getName().toString())).print("\n"
							+ "Define site.server_admin     ").print(escape(dollarVariable, manager.httpdSite.getServerAdmin().toString())).print("\n"
							+ "\n"
							+ "<VirtualHost ")
						.print(CERTBOT_COMPAT ? escape(dollarVariable, ipString) : "${bind.ip_address}")
						.print(':')
						.print(CERTBOT_COMPAT ? port : "${bind.port}")
						.print(">\n"
							+ "    ServerName \\\n"
							+ "        ")
						.print(CERTBOT_COMPAT ? escape(dollarVariable, primaryHostname) : "${bind.primary_hostname}")
						.print('\n');
					List<VirtualHostName> altURLs=bind.getAltHttpdSiteURLs();
					if(!altURLs.isEmpty()) {
						out.print("    ServerAlias");
						for(VirtualHostName altURL : altURLs) {
							out.print(" \\\n        ").print(escape(dollarVariable, altURL.getHostname().toString()));
						}
						out.print('\n');
					}
					out.print("\n"
							+ "    <IfModule log_config_module>\n");
					String noNameLogPrefix = "/var/log/httpd-sites/" + siteName + "/" + protocol + "/";
					String namedLogPrefix  = "/var/log/httpd-sites/" + siteName + "/" + protocol + "-" + bindEscapedName + "/";
					String accessLog = bind.getAccessLog().toString();
					if(bindEscapedName == null || accessLog.startsWith(noNameLogPrefix)) {
						out.print("        CustomLog ").print(getEscapedPrefixReplacement(dollarVariable, accessLog, noNameLogPrefix, "/var/log/httpd-sites/${site.name}/${bind.protocol}/")).print(" combined\n");
					} else {
						out.print("        CustomLog ").print(getEscapedPrefixReplacement(dollarVariable, accessLog, namedLogPrefix, "/var/log/httpd-sites/${site.name}/${bind.protocol}-${bind.name}/")).print(" combined\n");
					}
					out.print("    </IfModule>\n");
					String errorLog = bind.getErrorLog().toString();
					if(bindEscapedName == null || errorLog.startsWith(noNameLogPrefix)) {
						out.print("    ErrorLog ").print(getEscapedPrefixReplacement(dollarVariable, errorLog, noNameLogPrefix, "/var/log/httpd-sites/${site.name}/${bind.protocol}/")).print('\n');
					} else {
						out.print("    ErrorLog ").print(getEscapedPrefixReplacement(dollarVariable, errorLog, namedLogPrefix, "/var/log/httpd-sites/${site.name}/${bind.protocol}-${bind.name}/")).print('\n');
					}
					if(sslCert != null) {
						// Use any directly configured chain file
						out.print("\n"
								+ "    <IfModule ssl_module>\n"
								+ "        SSLCertificateFile ").print(getEscapedSslPath(dollarVariable, sslCert.getCertFile(), primaryHostname)).print("\n"
								+ "        SSLCertificateKeyFile ").print(getEscapedSslPath(dollarVariable, sslCert.getKeyFile(), primaryHostname)).print('\n');
						PosixPath sslChain = sslCert.getChainFile();
						if(sslChain != null) {
							out.print("        SSLCertificateChainFile ").print(getEscapedSslPath(dollarVariable, sslChain, primaryHostname)).print('\n');
						}
						boolean enableCgi = manager.enableCgi();
						boolean enableSsi = manager.httpdSite.getEnableSsi();
						if(enableCgi && enableSsi) {
							out.print("        <Files ~ \\.(cgi|shtml)$>\n"
									+ "            SSLOptions +StdEnvVars\n"
									+ "        </Files>\n");
						} else if(enableCgi) {
							out.print("        <Files ~ \\.cgi$>\n"
									+ "            SSLOptions +StdEnvVars\n"
									+ "        </Files>\n");
						} else if(enableSsi) {
							out.print("        <Files ~ \\.shtml$>\n"
									+ "            SSLOptions +StdEnvVars\n"
									+ "        </Files>\n");
						}
						// See https://unix.stackexchange.com/questions/162478/how-to-disable-sslv3-in-apache
						// TODO: Test if required in our Apache 2.4
						// TODO:     Test with: https://www.tinfoilsecurity.com/poodle (make a routine task to test this site yearly?)
						out.print("        SSLProtocol all -SSLv2 -SSLv3\n"
								+ "        SSLEngine On\n"
								+ "    </IfModule>\n"
						);
					}
					if(bind.getRedirectToPrimaryHostname()) {
						out.print("\n"
								+ "    # Binds options\n");
						if(isDefaultPort) {
							out.print("    Include bind-options/redirect_to_primary_hostname_default_port.inc\n");
						} else {
							out.print("    Include bind-options/redirect_to_primary_hostname_other_port.inc\n");
						}
					}
					boolean hasRedirectAll = false;
					List<RewriteRule> rewriteRules = bind.getRewriteRules();
					if(!rewriteRules.isEmpty()) {
						out.print("\n"
								+ "    # Redirects\n"
								+ "    <IfModule rewrite_module>\n"
								+ "        RewriteEngine on\n");
						for(RewriteRule rewriteRule : rewriteRules) {
							String comment = rewriteRule.getComment();
							if(comment != null) {
								// TODO: Maybe separate escapeComment method for this?
								out.print("        # ").print(escape(dollarVariable, comment, true)).print('\n');
							}
							// Auto-detect a redirect-all bind
							if(isRedirectAll(rewriteRule)) {
								hasRedirectAll = true;
							}
							out.print("        ").print(rewriteRule.getApacheDirective(dollarVariable)).print('\n');
						}
						out.print("    </IfModule>\n");
					}
					List<Header> headers = bind.getHttpdSiteBindHeaders();
					if(!headers.isEmpty()) {
						out.print("\n"
								+ "    # Headers\n"
								+ "    <IfModule headers_module>\n");
						for(Header header : headers) {
							String comment = header.getComment();
							if(comment != null) {
								// TODO: Maybe separate escapeComment method for this?
								out.print("        # ").print(escape(dollarVariable, comment, true)).print('\n');
							}
							out.print("        ").print(header.getApacheDirective(dollarVariable)).print('\n');
						}
						out.print("    </IfModule>\n");
					}
					final String escapedSiteInclude;
					if(!CERTBOT_COMPAT && siteInclude.equals(siteName + ".inc")) {
						escapedSiteInclude = "sites-available/${site.name}.inc";
					} else {
						escapedSiteInclude = escape(dollarVariable, "sites-available/" + siteInclude);
					}
					String includeSiteConfig = bind.getIncludeSiteConfig();
					if(includeSiteConfig == null) {
						if(hasRedirectAll) includeSiteConfig = "IfModule !rewrite_module";
					}
					out.print('\n');
					if("false".equals(includeSiteConfig)) {
						out.print("    # Include ").print(escapedSiteInclude).print("\n");
					} else if(includeSiteConfig != null && includeSiteConfig.startsWith("IfModule ")) {
						out.print("    <" + includeSiteConfig + ">\n"
								+ "        Include ").print(escapedSiteInclude).print("\n"
								+ "    </IfModule>\n");
					} else {
						out.print("    Include ").print(escapedSiteInclude).print("\n");
					}
					out.print("\n"
							+ "</VirtualHost>\n"
							+ "\n"
							+ "UnDefine bind.pkey\n"
							+ "UnDefine bind.protocol\n"
							+ "UnDefine bind.ip_address\n"
							+ "UnDefine bind.port\n"
							+ "UnDefine bind.name\n"
							+ "UnDefine bind.primary_hostname\n"
							+ "UnDefine site.name\n"
							+ "UnDefine site.user\n"
							+ "UnDefine site.group\n"
							+ "UnDefine site.server_admin\n");
					break;
				}
				default :
					throw new AssertionError();
			}
		}
		return bout.toByteArray();
	}

	/**
	 * Reloads the configs for all provided <code>HttpdServer</code>s.
	 */
	public static void reloadConfigs(Set<HttpdServer> serversNeedingReloaded) throws IOException, SQLException {
		for(HttpdServer hs : serversNeedingReloaded) reloadConfigs(hs);
	}

	private static final Object processControlLock = new Object();
	private static void reloadConfigs(HttpdServer hs) throws IOException, SQLException {
		OperatingSystemVersion osv = hs.getAOServer().getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		synchronized(processControlLock) {
			switch(osvId) {
				case OperatingSystemVersion.CENTOS_5_I686_AND_X86_64 : {
					String name = hs.getName();
					int num = (name == null) ? 1 : Integer.parseInt(name);
					AOServDaemon.exec(
						"/etc/rc.d/init.d/httpd" + num,
						"reload" // Should this be restart for SSL changes?
					);
					break;
				}
				case OperatingSystemVersion.CENTOS_7_X86_64 : {
					String escapedName = hs.getSystemdEscapedName();
					AOServDaemon.exec(
						"/usr/bin/systemctl",
						"reload-or-restart",
						(escapedName == null) ? "httpd.service" : ("httpd@" + escapedName + ".service")
					);
					break;
				}
				default :
					throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
			}
		}
	}

	/**
	 * Calls all Apache instances with the provided command.
	 */
	private static void controlApache(String command) throws IOException, SQLException {
		OperatingSystemVersion osv = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		synchronized(processControlLock) {
			switch(osvId) {
				case OperatingSystemVersion.CENTOS_5_I686_AND_X86_64 :
					for(HttpdServer hs : AOServDaemon.getThisAOServer().getHttpdServers()) {
						String name = hs.getName();
						int num = (name == null) ? 1 : Integer.parseInt(name);
						AOServDaemon.exec(
							"/etc/rc.d/init.d/httpd" + num,
							command
						);
					}
					break;
				case OperatingSystemVersion.CENTOS_7_X86_64 :
					for(HttpdServer hs : AOServDaemon.getThisAOServer().getHttpdServers()) {
						String escapedName = hs.getSystemdEscapedName();
						AOServDaemon.exec(
							"/usr/bin/systemctl",
							command,
							(escapedName == null) ? "httpd.service" : ("httpd@" + escapedName + ".service")
						);
					}
					break;
				default :
					throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
			}
		}
	}

	/**
	 * Restarts all Apache instances.
	 */
	public static void restartApache() throws IOException, SQLException {
		controlApache("restart");
	}

	/**
	 * Starts all Apache instances.
	 */
	public static void startApache() throws IOException, SQLException {
		controlApache("start");
	}

	/**
	 * Stops all Apache instances.
	 */
	public static void stopApache() throws IOException, SQLException {
		controlApache("stop");
	}

	/**
	 * Gets the major (first number only) form of a PHP version.
	 */
	static String getMajorPhpVersion(String version) {
		int pos = version.indexOf('.');
		return pos == -1 ? version : version.substring(0, pos);
	}

	/**
	 * Gets the minor (first two numbers only) form of a PHP version.
	 */
	static String getMinorPhpVersion(String version) {
		int pos = version.indexOf('.');
		if(pos == -1) return version;
		pos = version.indexOf('.', pos+1);
		return pos == -1 ? version : version.substring(0, pos);
	}

	private static final UnixFile[] centOs5AlwaysDelete = {
		new UnixFile("/etc/httpd/conf/httpd1.conf.old"),
		new UnixFile("/etc/httpd/conf/httpd2.conf.old"),
		new UnixFile("/etc/httpd/conf/httpd3.conf.old"),
		new UnixFile("/etc/httpd/conf/httpd4.conf.old"),
		new UnixFile("/etc/httpd/conf/httpd5.conf.old"),
		new UnixFile("/etc/httpd/conf/httpd6.conf.old"),
		new UnixFile("/etc/httpd/conf/httpd7.conf.old"),
		new UnixFile("/etc/httpd/conf/httpd8.conf.old"),
		new UnixFile("/etc/httpd/conf/workers1.properties.old"),
		new UnixFile("/etc/httpd/conf/workers2.properties.old"),
		new UnixFile("/etc/httpd/conf/workers3.properties.old"),
		new UnixFile("/etc/httpd/conf/workers4.properties.old"),
		new UnixFile("/etc/httpd/conf/workers5.properties.old"),
		new UnixFile("/etc/httpd/conf/workers6.properties.old"),
		new UnixFile("/etc/httpd/conf/workers7.properties.old"),
		new UnixFile("/etc/httpd/conf/workers8.properties.old"),
		new UnixFile("/etc/rc.d/init.d/httpd"),
		new UnixFile("/opt/aoserv-daemon/init.d/httpd1"),
		new UnixFile("/opt/aoserv-daemon/init.d/httpd2"),
		new UnixFile("/opt/aoserv-daemon/init.d/httpd3"),
		new UnixFile("/opt/aoserv-daemon/init.d/httpd4"),
		new UnixFile("/opt/aoserv-daemon/init.d/httpd5"),
		new UnixFile("/opt/aoserv-daemon/init.d/httpd6"),
		new UnixFile("/opt/aoserv-daemon/init.d/httpd7"),
		new UnixFile("/opt/aoserv-daemon/init.d/httpd8")
	};

	/**
	 * Fixes any filesystem stuff related to Apache.
	 */
	private static void fixFilesystem(List<File> deleteFileList) throws IOException, SQLException {
		HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
		if(osConfig==HttpdOperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64) {
			// Make sure these files don't exist.  They may be due to upgrades or a
			// result of RPM installs.
			for(UnixFile uf : centOs5AlwaysDelete) {
				if(uf.getStat().exists()) {
					File toDelete = uf.getFile();
					if(logger.isLoggable(Level.INFO)) logger.info("Scheduling for removal: " + toDelete);
					deleteFileList.add(toDelete);
				}
			}
		}
	}

	/**
	 * Rebuilds /etc/rc.d/init.d/httpd# init scripts
	 * or /etc/systemd/system/multi-user.target.wants/httpd[@&lt;name&gt;].service links.
	 * Also stops and disables instances that should no longer exist.
	 */
	private static void doRebuildInitScripts(
		Server thisAoServer,
		ByteArrayOutputStream bout,
		List<File> deleteFileList,
		Set<HttpdServer> serversNeedingReloaded,
		Set<UnixFile> restorecon
	) throws IOException, SQLException {
		List<HttpdServer> hss = thisAoServer.getHttpdServers();
		OperatingSystemVersion osv = thisAoServer.getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		switch(osvId) {
			case OperatingSystemVersion.CENTOS_5_I686_AND_X86_64 : {
				Set<String> dontDeleteFilenames = new HashSet<>(hss.size()*4/3+1);
				for(HttpdServer hs : hss) {
					String name = hs.getName();
					int num = (name == null) ? 1 : Integer.parseInt(name);
					bout.reset();
					try (ChainWriter out = new ChainWriter(bout)) {
						out.print("#!/bin/bash\n"
								+ "#\n"
								+ "# httpd").print(num).print("        Startup script for the Apache HTTP Host ").print(num).print("\n"
								+ "#\n"
								+ "# chkconfig: 345 85 15\n"
								+ "# description: Apache is a World Wide Web server.  It is used to serve \\\n"
								+ "#              HTML files and CGI.\n"
								+ "# processname: httpd").print(num).print("\n"
								+ "# config: /etc/httpd/conf/httpd").print(num).print(".conf\n"
								+ "# pidfile: /var/run/httpd").print(num).print(".pid\n"
								+ "\n");
						// mod_php requires MySQL and PostgreSQL in the path
						SoftwareVersion modPhpVersion = hs.getModPhpVersion();
						if(modPhpVersion!=null) {
							PackageManager.PackageName requiredPackage;
							String version = modPhpVersion.getVersion();
							String minorVersion = getMinorPhpVersion(version);
							switch (minorVersion) {
								case "4.4":
									requiredPackage = null;
									out.print(". /opt/mysql-5.0-i686/setenv.sh\n");
									out.print(". /opt/postgresql-7.3-i686/setenv.sh\n");
									out.print('\n');
									break;
								case "5.2":
									requiredPackage = PackageManager.PackageName.PHP_5_2_I686;
									out.print(". /opt/mysql-5.0-i686/setenv.sh\n");
									out.print(". /opt/postgresql-8.3-i686/setenv.sh\n");
									out.print('\n');
									break;
								case "5.3":
									requiredPackage = PackageManager.PackageName.PHP_5_3_I686;
									out.print(". /opt/mysql-5.1-i686/setenv.sh\n");
									out.print(". /opt/postgresql-8.3-i686/setenv.sh\n");
									out.print('\n');
									break;
								case "5.4":
									requiredPackage = PackageManager.PackageName.PHP_5_4_I686;
									out.print(". /opt/mysql-5.6-i686/setenv.sh\n");
									out.print(". /opt/postgresql-9.2-i686/setenv.sh\n");
									out.print('\n');
									break;
								case "5.5":
									requiredPackage = PackageManager.PackageName.PHP_5_5_I686;
									out.print(". /opt/mysql-5.6-i686/setenv.sh\n");
									out.print(". /opt/postgresql-9.2-i686/setenv.sh\n");
									out.print('\n');
									break;
								case "5.6":
									requiredPackage = PackageManager.PackageName.PHP_5_6_I686;
									out.print(". /opt/mysql-5.7-i686/setenv.sh\n");
									out.print(". /opt/postgresql-9.4-i686/setenv.sh\n");
									out.print('\n');
									break;
								default:
									throw new SQLException("Unexpected version for mod_php: "+version);
							}

							// Make sure required RPM is installed
							if(requiredPackage != null) PackageManager.installPackage(requiredPackage);
						}
						out.print("NUM=").print(num).print("\n"
								+ ". /opt/aoserv-daemon/init.d/httpd\n");
					}
					String filename = "httpd"+num;
					dontDeleteFilenames.add(filename);
					if(
						DaemonFileUtils.atomicWrite(
							new UnixFile(INIT_DIRECTORY, filename),
							bout.toByteArray(),
							0700,
							UnixFile.ROOT_UID,
							UnixFile.ROOT_GID,
							null,
							restorecon
						)
					) {
						// Make start at boot
						AOServDaemon.exec(
							"/sbin/chkconfig",
							"--add",
							filename
						);
						AOServDaemon.exec(
							"/sbin/chkconfig",
							filename,
							"on"
						);
						// Make reload
						serversNeedingReloaded.add(hs);
					}
				}
				String[] list = new File(INIT_DIRECTORY).list();
				if(list != null) {
					for(String filename : list) {
						if(
							!dontDeleteFilenames.contains(filename)
							&& HTTPD_N_REGEXP.matcher(filename).matches()
						) {
							// chkconfig off
							AOServDaemon.exec(
								"/sbin/chkconfig",
								filename,
								"off"
							);
							// stop
							String fullPath = INIT_DIRECTORY + '/' + filename;
							AOServDaemon.exec(
								fullPath,
								"stop"
							);
							File toDelete = new File(fullPath);
							if(logger.isLoggable(Level.INFO)) logger.info("Scheduling for removal: " + toDelete);
							deleteFileList.add(toDelete);
						}
					}
				}
				break;
			}
			case OperatingSystemVersion.CENTOS_7_X86_64 : {
				boolean hasAlternateInstance = false;
				Set<String> dontDeleteFilenames = new HashSet<>(hss.size()*4/3+1);
				for(HttpdServer hs : hss) {
					String escapedName = hs.getSystemdEscapedName();
					if(escapedName != null) hasAlternateInstance = true;
					if(hs.getHttpdBinds().isEmpty()) {
						// Disable when doesn't have any active httpd_binds
						serversNeedingReloaded.remove(hs);
					} else {
						String filename = (escapedName == null) ? "httpd.service" : ("httpd@" + escapedName + ".service");
						dontDeleteFilenames.add(filename);
						UnixFile link = new UnixFile(MULTI_USER_WANTS_DIRECTORY, filename);
						if(!link.getStat().exists()) {
							// Make start at boot
							AOServDaemon.exec(
								"/usr/bin/systemctl",
								"enable",
								filename
							);
							if(!link.getStat().exists()) throw new AssertionError("Link does not exist after systemctl enable: " + link);
							// Make reload
							serversNeedingReloaded.add(hs);
						}
					}
				}
				String[] list = new File(MULTI_USER_WANTS_DIRECTORY).list();
				if(list != null) {
					for(String filename : list) {
						if(
							!dontDeleteFilenames.contains(filename)
							&& (
								"httpd.service".equals(filename)
								|| HTTPD_NAME_SERVICE_REGEXP.matcher(filename).matches()
							)
						) {
							// Note: It seems OK to send escaped service filename, so we're not unescaping back to the original service name
							// stop
							AOServDaemon.exec(
								"/usr/bin/systemctl",
								"stop",
								filename
							);
							// disable
							AOServDaemon.exec(
								"/usr/bin/systemctl",
								"disable",
								filename
							);
							UnixFile link = new UnixFile(MULTI_USER_WANTS_DIRECTORY, filename);
							if(link.getStat().exists()) throw new AssertionError("Link exists after systemctl disable: " + link);
						}
					}
				}
				// Uninstall httpd-n package on CentOS 7 when not needed
				if(!hasAlternateInstance && AOServDaemonConfiguration.isPackageManagerUninstallEnabled()) {
					PackageManager.removePackage(PackageManager.PackageName.HTTPD_N);
				}
				break;
			}
			default :
				throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
		}
	}

	/**
	 * Manages SELinux.
	 */
	private static void doRebuildSELinux(
		Server aoServer,
		Set<HttpdServer> serversNeedingReloaded,
		Set<Port> enabledAjpPorts,
		boolean hasAnyCgi,
		boolean hasAnyModPhp
	) throws IOException, SQLException {
		OperatingSystemVersion osv = aoServer.getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		switch(osvId) {
			case OperatingSystemVersion.CENTOS_5_I686_AND_X86_64 :
				// SELinux left in Permissive state, not configured here
				break;
			case OperatingSystemVersion.CENTOS_7_X86_64 : {
				// Install /usr/bin/semanage if missing
				PackageManager.installPackage(PackageManager.PackageName.POLICYCOREUTILS_PYTHON);
				// Find the set of distinct ports used by Apache
				SortedSet<Port> httpdPorts = new TreeSet<>();
				List<HttpdServer> hss = aoServer.getHttpdServers();
				for(HttpdServer hs : hss) {
					for(HttpdBind hb : hs.getHttpdBinds()) {
						httpdPorts.add(hb.getNetBind().getPort());
					}
				}
				// Add any other local HTTP server (such as proxied user-space applications)
				for(Bind nb : aoServer.getServer().getNetBinds()) {
					String protocol = nb.getAppProtocol().getProtocol();
					if(AppProtocol.HTTP.equals(protocol) || AppProtocol.HTTPS.equals(protocol)) {
						httpdPorts.add(nb.getPort());
					}
				}
				// Reconfigure SELinux ports
				if(SEManagePort.configure(httpdPorts, SELINUX_TYPE)) {
					serversNeedingReloaded.addAll(hss);
				}
				if(SEManagePort.configure(enabledAjpPorts, AJP_SELINUX_TYPE)) {
					serversNeedingReloaded.addAll(hss);
				}
				// Control SELinux booleans
				setSeBool("httpd_enable_cgi", hasAnyCgi);
				setSeBool("httpd_can_network_connect_db", hasAnyCgi || hasAnyModPhp);
				setSeBool("httpd_setrlimit", hasAnyCgi || hasAnyModPhp);
				// TODO: Auto-control setsebool -P httpd_can_network_memcache on  once per-Apache and per-Site PHP extensions are configured by AOServ.
				//       It is enabled on app2.www.keepandshare.com manually now.
				break;
			}
			default :
				throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
		}
	}

	private static class SeBoolLock {
	};

	private static final SeBoolLock seBoolLock = new SeBoolLock();

	private static void setSeBool(String bool, boolean value) throws IOException {
		synchronized(seBoolLock) {
			boolean current;
			{
				String result = AOServDaemon.execAndCapture("/usr/sbin/getsebool", bool);
				if(result.equals(bool + " --> on\n")) current = true;
				else if(result.equals(bool + " --> off\n")) current = false;
				else throw new IOException("Unexpected result from getsebool: " + result);
			}
			if(current != value) {
				String strVal = value ? "on" : "off";
				if(logger.isLoggable(Level.INFO)) logger.info("Setting SELinux boolean: " + bool + " --> " + strVal);
				AOServDaemon.exec("/usr/sbin/setsebool", "-P", bool, strVal);
			}
		}
	}

	private static final ConcurrencyLimiter<Integer,Integer> getHttpdServerConcurrencyLimiter = new ConcurrencyLimiter<>();

	/**
	 * Gets the current concurrency for an Apache instance.
	 */
	public static int getHttpdServerConcurrency(int httpdServer) throws IOException, SQLException {
		try {
			return getHttpdServerConcurrencyLimiter.executeSerialized(
				httpdServer,
				() -> {
					AOServConnector conn = AOServDaemon.getConnector();
					Server thisAOServer = AOServDaemon.getThisAOServer();
					OperatingSystemVersion osv = thisAOServer.getServer().getOperatingSystemVersion();
					int osvId = osv.getPkey();
					HttpdServer hs = conn.getWeb().getHttpdServer().get(httpdServer);
					if(hs == null) throw new SQLException("HttpdServer not found: " + httpdServer);
					int ppid;
					{
						if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
							String name = hs.getName();
							int num = (name == null ? 1 : Integer.parseInt(name));
							ppid = Integer.parseInt(
								FileUtils.readFileAsString(
									new File("/var/run/httpd" + num + ".pid")
								).trim()
							);
						} else if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
							String serviceName;
							{
								String systemdName = hs.getSystemdEscapedName();
								if(systemdName == null) {
									serviceName = "httpd.service";
								} else {
									serviceName = "httpd@" + systemdName + ".service";
								}
							}
							// Get the parent PID from systemd
							String pidLine = AOServDaemon.execAndCapture(
								"/usr/bin/systemctl",
								"show",
								"--property=MainPID",
								serviceName
							);
							int pos = pidLine.indexOf('=');
							if(pos == -1) throw new IOException("No \"=\" in output from systemctl: " + pidLine);
							try {
								ppid = Integer.parseInt(pidLine.substring(pos + 1).trim());
							} catch(NumberFormatException e) {
								throw new IOException("Can't parse pidLine: " + pidLine, e);
							}
						} else {
							throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
						}
					}
					// Count the number of processes that have the expected cmdline (to distiguish from mod_wsgi workers)
					// and have the correct ppid
					int count = 0;
					{
						File procDir = new File("/proc");
						String[] procList = procDir.list();
						if(procList == null) throw new IOException("Not a directory? " + procDir);
						for(String filename : procList) {
							int flen = filename.length();
							boolean allNum = true;
							for(int d = 0; d < flen; d++) {
								char ch = filename.charAt(d);
								if(ch<'0' || ch>'9') {
									allNum = false;
									break;
								}
							}
							if(allNum) {
								try {
									int pid = Integer.parseInt(filename);
									LinuxProcess process = new LinuxProcess(pid);
									String[] cmdline = process.getCmdline();
									if(
										cmdline.length >= 1
										&& "/usr/sbin/httpd".equals(cmdline[0])
										// Not on CentOS 5: && "-DFOREGROUND".equals(cmdline[cmdline.length - 1])
									) {
										String statusPpid = process.getStatus("PPid");
										if(statusPpid != null && Integer.parseInt(statusPpid) == ppid) {
											count++;
										}
									}
								} catch(FileNotFoundException err) {
									if(logger.isLoggable(Level.FINE)) {
										logger.log(Level.FINE, "It is normal that this is thrown if the process has already closed", err);
									}
								}
							}
						}
					}
					return count;
				}
			);
		} catch(InterruptedException e) {
			InterruptedIOException ioErr = new InterruptedIOException(e.getMessage());
			ioErr.initCause(e);
			throw ioErr;
		} catch(ExecutionException e) {
			Throwable cause = e.getCause();
			if(cause instanceof IOException) throw (IOException)cause;
			if(cause instanceof SQLException) throw (SQLException)cause;
			throw new IOException(e);
		}
	}
}
