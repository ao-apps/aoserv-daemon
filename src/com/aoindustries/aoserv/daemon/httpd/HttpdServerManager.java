/*
 * Copyright 2008-2013, 2014, 2015, 2016, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.HttpdBind;
import com.aoindustries.aoserv.client.HttpdServer;
import com.aoindustries.aoserv.client.HttpdSharedTomcat;
import com.aoindustries.aoserv.client.HttpdSite;
import com.aoindustries.aoserv.client.HttpdSiteAuthenticatedLocation;
import com.aoindustries.aoserv.client.HttpdSiteBind;
import com.aoindustries.aoserv.client.HttpdSiteBindRedirect;
import com.aoindustries.aoserv.client.HttpdSiteURL;
import com.aoindustries.aoserv.client.HttpdTomcatSharedSite;
import com.aoindustries.aoserv.client.HttpdTomcatSite;
import com.aoindustries.aoserv.client.HttpdWorker;
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.client.LinuxGroup;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.LinuxServerGroup;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.Protocol;
import com.aoindustries.aoserv.client.TechnologyVersion;
import com.aoindustries.aoserv.client.validator.GroupId;
import com.aoindustries.aoserv.client.validator.UnixPath;
import com.aoindustries.aoserv.client.validator.UserId;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.FileUtils;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.lang.ObjectUtils;
import com.aoindustries.net.InetAddress;
import com.aoindustries.net.Port;
import com.aoindustries.selinux.SEManagePort;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Manages HttpdServer configurations and control.
 *
 * @author  AO Industries, Inc.
 */
public class HttpdServerManager {

	private static final Logger logger = Logger.getLogger(HttpdServerManager.class.getName());

	private HttpdServerManager() {}

	/**
	 * The directory that all HTTPD configs are located in (/etc/httpd).
	 */
	static final String SERVER_ROOT = "/etc/httpd";

	/**
	 * The directory that HTTPD conf are located in (/etc/httpd/conf).
	 */
	static final String CONF_DIRECTORY = SERVER_ROOT + "/conf";

	/**
	 * The pattern matching secondary httpd-#.conf files.
	 */
	private static final Pattern HTTPD_N_CONF_REGEXP = Pattern.compile("^httpd-[0-9]+\\.conf$");

	/**
	 * The pattern matching secondary httpd-# files.
	 */
	private static final Pattern HTTPD_N_REGEXP = Pattern.compile("^httpd-[0-9]+$");

	/**
	 * The pattern matching secondary php-# config directories.
	 */
	private static final Pattern PHP_N_REGEXP = Pattern.compile("^php-[0-9]+$");

	/**
	 * The pattern matching secondary workers-#.properties files.
	 */
	private static final Pattern WORKERS_N_PROPERTIES_REGEXP = Pattern.compile("^workers-[0-9]+\\.properties$");

	/**
	 * The directory that tmpfiles.d/httpd[-#].conf files are located in (/etc/tmpfiles.d).
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
	 * The SELinux type for httpd.
	 */
	private static final String SELINUX_TYPE = "http_port_t";

	/**
	 * The SELinux type for Tomcat AJP ports.
	 */
	private static final String AJP_SELINUX_TYPE = "ajp_port_t";

	/**
	 * Gets the workers[[-]#].properties file path.
	 */
	private static String getWorkersFile(HttpdServer hs) throws IOException, SQLException {
		int num = hs.getNumber();
		OperatingSystemVersion osv = hs.getAOServer().getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		switch(osvId) {
			case OperatingSystemVersion.CENTOS_5_I686_AND_X86_64 :
				return "workers" + num + ".properties";
			case OperatingSystemVersion.CENTOS_7_X86_64 :
				if(num == 1) return "workers.properties";
				else return "workers-" + num + ".properties";
			default :
				throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
		}
	}

	/**
	 * Gets the httpd[-[#]].conf file name.
	 */
	private static String getHttpdConfFile(HttpdServer hs) throws IOException, SQLException {
		int num = hs.getNumber();
		OperatingSystemVersion osv = hs.getAOServer().getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		switch(osvId) {
			case OperatingSystemVersion.CENTOS_5_I686_AND_X86_64 :
				return "httpd" + num + ".conf";
			case OperatingSystemVersion.CENTOS_7_X86_64 :
				if(num == 1) return "httpd.conf";
				else return "httpd-" + num+".conf";
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
		AOServer aoServer = AOServDaemon.getThisAOServer();

		// Rebuild /etc/httpd/conf/hosts/ or /etc/httpd/sites-available and /etc/httpd/sites-enabled files
		doRebuildConfHosts(aoServer, bout, deleteFileList, serversNeedingReloaded, restorecon);

		// Rebuild /etc/httpd/conf/ files
		Set<Port> enabledAjpPorts = new HashSet<>();
		boolean[] hasAnyCgi = {false};
		boolean[] hasAnyModPhp = {false};
		doRebuildConf(aoServer, bout, deleteFileList, serversNeedingReloaded, enabledAjpPorts, restorecon, hasAnyCgi, hasAnyModPhp);

		// Control the /etc/rc.d/init.d/httpd* files or /etc/systemd/system/httpd[-#].service
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
		AOServer thisAoServer,
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
				for(HttpdSite httpdSite : thisAoServer.getHttpdSites()) {
					// Some values used below
					final String siteName = httpdSite.getSiteName();
					final HttpdSiteManager manager = HttpdSiteManager.getInstance(httpdSite);
					final LinuxServerGroup lsg = httpdSite.getLinuxServerGroup();
					final int lsgGID = lsg.getGid().getId();
					final List<HttpdSiteBind> binds = httpdSite.getHttpdSiteBinds();

					// Remove from delete list
					extraFiles.remove(siteName);

					// The shared config part
					final UnixFile sharedFile = new UnixFile(CONF_HOSTS, siteName);
					if(!manager.httpdSite.isManual() || !sharedFile.getStat().exists()) {
						if(
							DaemonFileUtils.atomicWrite(
								sharedFile,
								buildHttpdSiteSharedFile(manager, bout),
								0640,
								UnixFile.ROOT_UID,
								lsgGID,
								null,
								restorecon
							)
						) {
							// File changed, all servers that use this site need restarted
							for(HttpdSiteBind hsb : binds) serversNeedingReloaded.add(hsb.getHttpdBind().getHttpdServer());
						}
					}

					// Each of the binds
					for(HttpdSiteBind bind : binds) {
						// Some value used below
						final boolean isManual = bind.isManual();
						final boolean isDisabled = bind.isDisabled();
						final String predisableConfig = bind.getPredisableConfig();
						// TODO: predisable_config and disabled state do not interact well.  When disabled, the predisable_config keeps getting used instead of any newly generated file.
						final HttpdBind httpdBind = bind.getHttpdBind();
						final NetBind nb = httpdBind.getNetBind();

						// Generate the filename
						final String bindFilename = siteName+"_"+nb.getIPAddress().getInetAddress()+"_"+nb.getPort().getPort();
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
									isDisabled ? HttpdSite.DISABLED : siteName,
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
					if(!filename.equals(HttpdSite.DISABLED)) {
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
				for(HttpdSite httpdSite : thisAoServer.getHttpdSites()) {
					// Some values used below
					final String siteName = httpdSite.getSiteName();
					final HttpdSiteManager manager = HttpdSiteManager.getInstance(httpdSite);
					final LinuxServerGroup lsg = httpdSite.getLinuxServerGroup();
					final int lsgGID = lsg.getGid().getId();
					final List<HttpdSiteBind> binds = httpdSite.getHttpdSiteBinds();

					// Remove from delete list
					String sharedFilename = siteName + ".inc";
					extraFiles.remove(sharedFilename);

					// The shared config part
					final UnixFile sharedFile = new UnixFile(SITES_AVAILABLE, sharedFilename);
					if(!manager.httpdSite.isManual() || !sharedFile.getStat().exists()) {
						if(
							DaemonFileUtils.atomicWrite(
								sharedFile,
								buildHttpdSiteSharedFile(manager, bout),
								0640,
								UnixFile.ROOT_UID,
								lsgGID,
								null,
								restorecon
							)
						) {
							// File changed, all servers that use this site need restarted
							for(HttpdSiteBind hsb : binds) serversNeedingReloaded.add(hsb.getHttpdBind().getHttpdServer());
						}
					}

					// Each of the binds
					for(HttpdSiteBind bind : binds) {
						// Some value used below
						final boolean isManual = bind.isManual();
						final boolean isDisabled = bind.isDisabled();
						final String predisableConfig = bind.getPredisableConfig();
						// TODO: predisable_config and disabled state do not interact well.  When disabled, the predisable_config keeps getting used instead of any newly generated file.
						final HttpdBind httpdBind = bind.getHttpdBind();
						final NetBind nb = httpdBind.getNetBind();

						// Generate the filename
						final String bindFilename = siteName+"_"+nb.getIPAddress().getInetAddress()+"_"+nb.getPort().getPort()+".conf";
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
									isDisabled ? (HttpdSite.DISABLED+".inc") : sharedFilename,
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
						!filename.equals(HttpdSite.DISABLED + ".inc")
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
				for(HttpdSite httpdSite : thisAoServer.getHttpdSites()) {
					// Some values used below
					final String siteName = httpdSite.getSiteName();
					// Each of the binds
					for(HttpdSiteBind bind : httpdSite.getHttpdSiteBinds()) {
						// Some value used below
						final HttpdBind httpdBind = bind.getHttpdBind();
						final NetBind nb = httpdBind.getNetBind();

						// Generate the filename
						final String bindFilename = siteName+"_"+nb.getIPAddress().getInetAddress()+"_"+nb.getPort().getPort()+".conf";
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
	 * Builds the contents for the shared part of a HttpdSite config.
	 */
	private static byte[] buildHttpdSiteSharedFile(HttpdSiteManager manager, ByteArrayOutputStream bout) throws IOException, SQLException {
		final HttpdSite httpdSite = manager.httpdSite;
		final LinuxServerAccount lsa = httpdSite.getLinuxServerAccount();
		final LinuxServerGroup lsg = httpdSite.getLinuxServerGroup();
		final SortedSet<HttpdSiteManager.JkSetting> jkSettings = manager.getJkSettings();

		// Build to a temporary buffer
		bout.reset();
		try (ChainWriter out = new ChainWriter(bout)) {
			OperatingSystemVersion osv = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			switch(osvId) {
				case OperatingSystemVersion.CENTOS_5_I686_AND_X86_64 : {
					out.print("    ServerAdmin ").print(httpdSite.getServerAdmin()).print('\n');

					// Enable CGI PHP option if the site supports CGI and PHP
					if(manager.enablePhp() && manager.enableCgi()) {
						out.print("\n"
								+ "    # Use CGI-based PHP when not using mod_php\n"
								+ "    <IfModule !sapi_apache2.c>\n"
								+ "        <IfModule !mod_php5.c>\n"
								+ "            Action php-script /cgi-bin/php\n"
								// Avoid *.php.txt going to PHP: http://php.net/manual/en/install.unix.apache2.php
								+ "            <FilesMatch \\.php$>\n"
								+ "                SetHandler php-script\n"
								+ "            </FilesMatch>\n"
								//+ "            AddHandler php-script .php\n"
								+ "        </IfModule>\n"
								+ "    </IfModule>\n");
					}

					// The CGI user info
					out.print("\n"
							+ "    # Use suexec when available\n"
							+ "    <IfModule mod_suexec.c>\n"
							+ "        SuexecUserGroup ").print(lsa.getLinuxAccount().getUsername()).print(' ').print(lsg.getLinuxGroup().getName()).print("\n"
							+ "    </IfModule>\n");

					// Protect against TRACE and TRACK
					if(manager.blockAllTraceAndTrackRequests()) {
						out.print("\n"
								+ "    # Protect dangerous request methods\n"
								+ "    <IfModule mod_rewrite.c>\n"
								+ "        RewriteEngine on\n"
								+ "        RewriteCond %{REQUEST_METHOD} ^(TRACE|TRACK)\n"
								+ "        RewriteRule .* - [F]\n"
								+ "    </IfModule>\n");
					}

					// Rejected URLs
					Map<String,List<HttpdSiteManager.Location>> rejectedLocations = manager.getRejectedLocations();
					for(Map.Entry<String,List<HttpdSiteManager.Location>> entry : rejectedLocations.entrySet()) {
						out.print("\n"
								+ "    # ").print(entry.getKey()).print('\n');
						for(HttpdSiteManager.Location location : entry.getValue()) {
							if(location.isRegularExpression()) {
								out.print("    <LocationMatch \"").print(location.getLocation()).print("\">\n"
										+ "        Order deny,allow\n"
										+ "        Deny from All\n"
										+ "    </LocationMatch>\n"
								);
							} else {
								out.print("    <Location \"").print(location.getLocation()).print("\">\n"
										+ "        Order deny,allow\n"
										+ "        Deny from All\n"
										+ "    </Location>\n"
								);
							}
						}
					}

					// Rewrite rules
					List<HttpdSiteManager.PermanentRewriteRule> permanentRewrites = manager.getPermanentRewriteRules();
					if(!permanentRewrites.isEmpty()) {
						// Write the standard restricted URL patterns
						out.print("\n"
								+ "    # Rewrite rules\n"
								+ "    <IfModule mod_rewrite.c>\n"
								+ "        RewriteEngine on\n");
						for(HttpdSiteManager.PermanentRewriteRule permanentRewrite : permanentRewrites) {
							out
								.print("        RewriteRule ")
								.print(permanentRewrite.pattern)
								.print(' ')
								.print(permanentRewrite.substitution)
								.print(" [L");
							if(permanentRewrite.noEscape) out.print(",NE");
							out.print(",R=permanent]\n");
						}
						out.print("    </IfModule>\n");
					}

					// Write the authenticated locations
					List<HttpdSiteAuthenticatedLocation> hsals = httpdSite.getHttpdSiteAuthenticatedLocations();
					if(!hsals.isEmpty()) {
						out.print("\n"
								+ "    # Authenticated Locations\n");
						for(HttpdSiteAuthenticatedLocation hsal : hsals) {
							out.print("    <").print(hsal.getIsRegularExpression()?"LocationMatch":"Location").print(" \"").print(hsal.getPath()).print("\">\n");
							if(hsal.getAuthUserFile() != null || hsal.getAuthGroupFile() != null) out.print("        AuthType Basic\n");
							if(hsal.getAuthName().length()>0) out.print("        AuthName \"").print(hsal.getAuthName()).print("\"\n");
							if(hsal.getAuthUserFile() != null) out.print("        AuthUserFile \"").print(hsal.getAuthUserFile()).print("\"\n");
							if(hsal.getAuthGroupFile() != null) out.print("        AuthGroupFile \"").print(hsal.getAuthGroupFile()).print("\"\n");
							if(hsal.getRequire().length()>0) out.print("        require ").print(hsal.getRequire()).print('\n');
							out.print("    </").print(hsal.getIsRegularExpression()?"LocationMatch":"Location").print(">\n");
						}
					}

					// Error if no root webapp found
					boolean foundRoot = false;
					SortedMap<String,HttpdSiteManager.WebAppSettings> webapps = manager.getWebapps();
					for(Map.Entry<String,HttpdSiteManager.WebAppSettings> entry : webapps.entrySet()) {
						String path = entry.getKey();
						HttpdSiteManager.WebAppSettings settings = entry.getValue();
						UnixPath docBase = settings.getDocBase();

						if(path.length()==0) {
							foundRoot = true;
							// DocumentRoot
							out.print("\n"
									+ "    # Set up the default webapp\n"
									+ "    DocumentRoot \"").print(docBase).print("\"\n"
									+ "    <Directory \"").print(docBase).print("\">\n"
									+ "        Allow from All\n"
									+ "        AllowOverride ").print(settings.getAllowOverride()).print("\n"
									+ "        Order allow,deny\n"
									+ "        Options ").print(settings.getOptions()).print("\n"
									+ "    </Directory>\n");
						} else {
							// Is webapp/alias
							out.print("\n"
									+ "    # Set up the ").print(path).print(" webapp\n"
									+ "    Alias \"").print(path).print("\" \"").print(docBase).print("\"\n"
									+ "    <Directory \"").print(docBase).print("\">\n"
									+ "        Allow from All\n"
									+ "        AllowOverride ").print(settings.getAllowOverride()).print("\n"
									+ "        Order allow,deny\n"
									+ "        Options ").print(settings.getOptions()).print("\n"
									+ "    </Directory>\n");
						}
						if(settings.enableCgi()) {
							if(!manager.enableCgi()) throw new SQLException("Unable to enable webapp CGI when site has CGI disabled");
							out.print("    <Directory \"").print(docBase).print("/cgi-bin\">\n"
									+ "        <IfModule mod_ssl.c>\n"
									+ "            SSLOptions +StdEnvVars\n"
									+ "        </IfModule>\n"
									+ "        Options ").print(settings.getCgiOptions()).print("\n"
									+ "        SetHandler cgi-script\n"
									+ "    </Directory>\n");
							/*
							out.print("    ScriptAlias \"").print(path).print("/cgi-bin/\" \"").print(docBase).print("/cgi-bin/\"\n"
									+ "    <Directory \"").print(docBase).print("/cgi-bin\">\n"
									+ "        Options ExecCGI\n"
									+ "        <IfModule mod_ssl.c>\n"
									+ "            SSLOptions +StdEnvVars\n"
									+ "        </IfModule>\n"
									+ "        Allow from All\n"
									+ "        Order allow,deny\n"
									+ "    </Directory>\n");*/
						}
					}
					if(!foundRoot) throw new SQLException("No DocumentRoot found");

					// Write any JkMount and JkUnmount directives
					if(!jkSettings.isEmpty()) {
						out.print("\n"
								+ "    # Request patterns mapped through mod_jk\n"
								+ "    <IfModule mod_jk.c>\n");
						for(HttpdSiteManager.JkSetting setting : jkSettings) {
							out
								.print("        ")
								.print(setting.isMount() ? "JkMount" : "JkUnMount")
								.print(' ')
								.print(setting.getPath())
								.print(' ')
								.print(setting.getJkCode())
								.print('\n');
						}
						out.print("\n"
								+ "        # Remove jsessionid for non-jk requests\n"
								+ "        <IfModule mod_rewrite.c>\n"
								+ "            RewriteEngine On\n"
								+ "            RewriteRule ^(.*);jsessionid=.*$ $1\n"
								+ "        </IfModule>\n"
								+ "    </IfModule>\n");
					}
					break;
				}
				case OperatingSystemVersion.CENTOS_7_X86_64 : {
					out.print("    ServerAdmin ").print(httpdSite.getServerAdmin()).print('\n');

					// Enable CGI PHP option if the site supports CGI and PHP
					if(manager.enablePhp() && manager.enableCgi()) {
						out.print("\n"
								+ "    # Use CGI-based PHP when not using mod_php\n"
								+ "    <IfModule !php5_module>\n"
								+ "        <IfModule !php7_module>\n"
								+ "            <IfModule actions_module>\n"
								+ "                Action php-script /cgi-bin/php\n"
								// Avoid *.php.txt going to PHP: http://php.net/manual/en/install.unix.apache2.php
								+ "                <FilesMatch \\.php$>\n"
								+ "                    SetHandler php-script\n"
								+ "                </FilesMatch>\n"
								//+ "                AddHandler php-script .php\n"
								+ "            </IfModule>\n"
								+ "        </IfModule>\n"
								+ "    </IfModule>\n");
					}

					// The CGI user info
					out.print("\n"
							+ "    # Use suexec when available\n"
							+ "    <IfModule suexec_module>\n"
							+ "        SuexecUserGroup ").print(lsa.getLinuxAccount().getUsername()).print(' ').print(lsg.getLinuxGroup().getName()).print("\n"
							+ "    </IfModule>\n");

					// Protect against TRACE and TRACK
					if(manager.blockAllTraceAndTrackRequests()) {
						out.print("\n"
								+ "    # Protect dangerous request methods\n"
								+ "    <IfModule rewrite_module>\n"
								+ "        RewriteEngine on\n"
								+ "        RewriteCond %{REQUEST_METHOD} ^(TRACE|TRACK)\n"
								+ "        RewriteRule .* - [F]\n"
								+ "    </IfModule>\n");
					}

					// Rejected URLs
					Map<String,List<HttpdSiteManager.Location>> rejectedLocations = manager.getRejectedLocations();
					for(Map.Entry<String,List<HttpdSiteManager.Location>> entry : rejectedLocations.entrySet()) {
						out.print("\n"
								+ "    # ").print(entry.getKey()).print('\n');
						for(HttpdSiteManager.Location location : entry.getValue()) {
							if(location.isRegularExpression()) {
								out.print("    <LocationMatch \"").print(location.getLocation()).print("\">\n"
										+ "        <IfModule authz_core_module>\n"
										+ "            Require all denied\n"
										+ "        </IfModule>\n"
										+ "    </LocationMatch>\n"
								);
							} else {
								out.print("    <Location \"").print(location.getLocation()).print("\">\n"
										+ "        <IfModule authz_core_module>\n"
										+ "            Require all denied\n"
										+ "        </IfModule>\n"
										+ "    </Location>\n"
								);
							}
						}
					}

					// Rewrite rules
					List<HttpdSiteManager.PermanentRewriteRule> permanentRewrites = manager.getPermanentRewriteRules();
					if(!permanentRewrites.isEmpty()) {
						// Write the standard restricted URL patterns
						out.print("\n"
								+ "    # Rewrite rules\n"
								+ "    <IfModule rewrite_module>\n"
								+ "        RewriteEngine on\n");
						for(HttpdSiteManager.PermanentRewriteRule permanentRewrite : permanentRewrites) {
							out
								.print("        RewriteRule ")
								.print(permanentRewrite.pattern)
								.print(' ')
								.print(permanentRewrite.substitution)
								.print(" [END");
							if(permanentRewrite.noEscape) out.print(",NE");
							out.print(",R=permanent]\n");
						}
						out.print("    </IfModule>\n");
					}

					// Write the authenticated locations
					List<HttpdSiteAuthenticatedLocation> hsals = httpdSite.getHttpdSiteAuthenticatedLocations();
					if(!hsals.isEmpty()) {
						out.print("\n"
								+ "    # Authenticated Locations\n");
						for(HttpdSiteAuthenticatedLocation hsal : hsals) {
							out.print("    ").print(hsal.getIsRegularExpression()?"<LocationMatch ":"<Location ").print('"').print(hsal.getPath()).print("\">\n");
							boolean includeAuthType = hsal.getAuthUserFile() != null || hsal.getAuthGroupFile() != null;
							boolean includeAuthName = hsal.getAuthName().length()>0;
							if(includeAuthType || includeAuthName) {
								out.print("        <IfModule authn_core_module>\n");
								if(includeAuthType) out.print("            AuthType Basic\n");
								if(includeAuthName) out.print("            AuthName \"").print(hsal.getAuthName()).print("\"\n");
								out.print("        </IfModule>\n");
							}
							if(hsal.getAuthUserFile() != null) {
								PackageManager.installPackage(PackageManager.PackageName.HTTPD_TOOLS);
								out.print(
									"        <IfModule authn_file_module>\n"
									+ "            AuthUserFile \"").print(hsal.getAuthUserFile()).print("\"\n"
									+ "        </IfModule>\n");
							}
							if(hsal.getAuthGroupFile() != null) {
								out.print(
									"        <IfModule authz_groupfile_module>\n"
									+ "            AuthGroupFile \"").print(hsal.getAuthGroupFile()).print("\"\n"
									+ "        </IfModule>\n");
							}
							if(hsal.getRequire().length()>0) {
								out.print(
									"        <IfModule authz_core_module>\n"
									+ "            Require ").print(hsal.getRequire()).print("\n"
									+ "        </IfModule>\n");
							}
							out.print("    ").print(hsal.getIsRegularExpression()?"</LocationMatch>":"</Location>").print('\n');
						}
					}

					// Error if no root webapp found
					boolean foundRoot = false;
					SortedMap<String,HttpdSiteManager.WebAppSettings> webapps = manager.getWebapps();
					for(Map.Entry<String,HttpdSiteManager.WebAppSettings> entry : webapps.entrySet()) {
						String path = entry.getKey();
						HttpdSiteManager.WebAppSettings settings = entry.getValue();
						UnixPath docBase = settings.getDocBase();

						if(path.length()==0) {
							foundRoot = true;
							// DocumentRoot
							out.print("\n"
									+ "    # Set up the default webapp\n"
									+ "    DocumentRoot \"").print(docBase).print("\"\n"
									+ "    <Directory \"").print(docBase).print("\">\n"
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
							boolean isInModPhp = false;
							for(HttpdSiteBind hsb : httpdSite.getHttpdSiteBinds()) {
								if(hsb.getHttpdBind().getHttpdServer().getModPhpVersion() != null) {
									isInModPhp = true;
									break;
								}
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
								out.print("    <Directory \"").print(docBase).print("/cgi-bin\">\n"
										+ "        <IfModule ssl_module>\n"
										+ "            SSLOptions +StdEnvVars\n"
										+ "        </IfModule>\n"
										+ "        Options ").print(settings.getCgiOptions()).print("\n"
										+ "        SetHandler cgi-script\n"
										+ "    </Directory>\n");
							}
						} else {
							// Is webapp/alias
							out.print("\n"
									+ "    # Set up the ").print(path).print(" webapp\n"
									+ "    <IfModule alias_module>\n"
									+ "        Alias \"").print(path).print("\" \"").print(docBase).print("\"\n"
									+ "        <Directory \"").print(docBase).print("\">\n"
									+ "            <IfModule authz_core_module>\n"
									+ "                Require all granted\n"
									+ "            </IfModule>\n"
									+ "            AllowOverride ").print(settings.getAllowOverride()).print("\n"
									+ "            Options ").print(settings.getOptions()).print("\n"
									+ "        </Directory>\n");
							if(settings.enableCgi()) {
								if(!manager.enableCgi()) throw new SQLException("Unable to enable webapp CGI when site has CGI disabled");
								out.print("        <Directory \"").print(docBase).print("/cgi-bin\">\n"
										+ "            <IfModule ssl_module>\n"
										+ "                SSLOptions +StdEnvVars\n"
										+ "            </IfModule>\n"
										+ "            Options ").print(settings.getCgiOptions()).print("\n"
										+ "            SetHandler cgi-script\n"
										+ "        </Directory>\n");
							}
							out.print("    </IfModule>\n");
						}
					}
					if(!foundRoot) throw new SQLException("No DocumentRoot found");

					// Write any JkMount and JkUnmount directives
					if(!jkSettings.isEmpty()) {
						out.print("\n"
								+ "    # Request patterns mapped through mod_jk\n"
								+ "    <IfModule jk_module>\n");
						for(HttpdSiteManager.JkSetting setting : jkSettings) {
							out
								.print("        ")
								.print(setting.isMount() ? "JkMount" : "JkUnMount")
								.print(' ')
								.print(setting.getPath())
								.print(' ')
								.print(setting.getJkCode())
								.print('\n');
						}
						out.print("\n"
								+ "        # Remove jsessionid for non-jk requests\n"
								+ "        JkStripSession On\n"
								+ "    </IfModule>\n");
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
	 *   <li>/etc/httpd/conf/httpd[[-]#].conf</li>
	 *   <li>/etc/httpd/conf/workers[[-]#].properties</li>
	 * </ul>
	 */
	private static void doRebuildConf(
		AOServer thisAoServer,
		ByteArrayOutputStream bout,
		List<File> deleteFileList,
		Set<HttpdServer> serversNeedingReloaded,
		Set<Port> enabledAjpPorts,
		Set<UnixFile> restorecon,
		boolean[] hasAnyCgi,
		boolean[] hasAnyModPhp
	) throws IOException, SQLException {
		int osvId = thisAoServer.getServer().getOperatingSystemVersion().getPkey();
		List<HttpdServer> hss = thisAoServer.getHttpdServers();
		// The files that should exist in /etc/httpd/conf
		Set<String> httpdConfFilenames = new HashSet<>(hss.size()*4/3+1);
		// The files that should exist in /etc/tmpfiles.d/httpd[-#].conf
		Set<String> etcTmpfilesFilenames = new HashSet<>(hss.size()*4/3+1);
		// The files that should exist in /run/httpd[-#]
		Set<String> runFilenames = new HashSet<>(hss.size()*4/3+1);
		// Rebuild per-server files
		for(HttpdServer hs : hss) {
			List<HttpdSite> sites = hs.getHttpdSites();
			String httpdConfFilename = getHttpdConfFile(hs);
			UnixFile httpdConf = new UnixFile(CONF_DIRECTORY, httpdConfFilename);
			httpdConfFilenames.add(httpdConfFilename);
			// Rebuild the httpd.conf file
			if(
				DaemonFileUtils.atomicWrite(
					httpdConf,
					buildHttpdConf(hs, sites, httpdConfFilenames, bout, restorecon, hasAnyCgi, hasAnyModPhp),
					0644,
					UnixFile.ROOT_UID,
					UnixFile.ROOT_GID,
					null,
					restorecon
				)
			) {
				serversNeedingReloaded.add(hs);
			}

			// Rebuild the workers.properties file
			// Only include mod_jk when at least one site has jk settings
			boolean hasJkSettings = false;
			for(HttpdSite site : sites) {
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
				int serverNum = hs.getNumber();
				LinuxServerAccount lsa = hs.getLinuxServerAccount();
				LinuxServerGroup lsg = hs.getLinuxServerGroup();
				UserId user = lsa.getLinuxAccount().getUsername().getUsername();
				GroupId group = lsg.getLinuxGroup().getName();
				int uid = lsa.getUid().getId();
				int gid = lsg.getGid().getId();
				String tmpFilesFilename;
				String runFilename;
				if(serverNum == 1) {
					// First in standard location
					tmpFilesFilename = "httpd.conf";
					runFilename = "httpd";
				} else {
					// Secondary Apache instances
					tmpFilesFilename = "httpd-" + serverNum + ".conf";
					runFilename = "httpd-" + serverNum;
				}
				// Server #1 with user apache and group apache uses the default at /usr/lib/tmpfiles.d/httpd.conf
				if(
					serverNum != 1
					|| !user.equals(LinuxAccount.APACHE)
					|| !group.equals(LinuxGroup.APACHE)
				) {
					etcTmpfilesFilenames.add(tmpFilesFilename);
					// Custom entry in /etc/tmpfiles.d/httpd[-#].conf
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
				// Create/update /run/httpd[-#](/.*)?
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
		String[] list = new File(CONF_DIRECTORY).list();
		if(list != null) {
			for(String filename : list) {
				if(
					!httpdConfFilenames.contains(filename)
					&& (
						// Note: httpd.conf is never deleted
						"php".equals(filename)
						|| "workers.properties".equals(filename)
						|| HTTPD_N_CONF_REGEXP.matcher(filename).matches()
						|| PHP_N_REGEXP.matcher(filename).matches()
						|| WORKERS_N_PROPERTIES_REGEXP.matcher(filename).matches()
					)
				) {
					File toDelete = new File(CONF_DIRECTORY, filename);
					if(logger.isLoggable(Level.INFO)) logger.info("Scheduling for removal: " + toDelete);
					deleteFileList.add(toDelete);
				}
			}
		}
		if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
			// Schedule remove extra /etc/tmpfiles.d/httpd*.conf
			list = new File(ETC_TMPFILES_D).list();
			if(list != null) {
				for(String filename : list) {
					if(
						!etcTmpfilesFilenames.contains(filename)
						&& (
							"httpd.conf".equals(filename)
							|| HTTPD_N_CONF_REGEXP.matcher(filename).matches()
						)
					) {
						File toDelete = new File(ETC_TMPFILES_D, filename);
						if(logger.isLoggable(Level.INFO)) logger.info("Scheduling for removal: " + toDelete);
						deleteFileList.add(toDelete);
					}
				}
			}
			// Schedule remove extra /run/httpd-.. directories
			list = new File("/run").list();
			if(list != null) {
				for(String filename : list) {
					if(
						!runFilenames.contains(filename)
						// Note: /run/httpd is not deleted since it is part of the standard RPM
						&& HTTPD_N_REGEXP.matcher(filename).matches()
					) {
						File toDelete = new File("/run", filename);
						if(logger.isLoggable(Level.INFO)) logger.info("Scheduling for removal: " + toDelete);
						deleteFileList.add(toDelete);
					}
				}
			}
		}
	}

	/**
	 * Builds the httpd#.conf file for CentOS 5
	 */
	private static byte[] buildHttpdConfCentOs5(
		HttpdServer hs,
		List<HttpdSite> sites,
		Set<String> httpdConfFilenames,
		ByteArrayOutputStream bout,
		boolean[] hasAnyCgi,
		boolean[] hasAnyModPhp
	) throws IOException, SQLException {
		final HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
		if(osConfig != HttpdOperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64) throw new AssertionError("This method is for CentOS 5 only");
		final int serverNum = hs.getNumber();
		bout.reset();
		try (ChainWriter out = new ChainWriter(bout)) {
			LinuxServerAccount lsa = hs.getLinuxServerAccount();
			boolean isEnabled = !lsa.isDisabled();
			// The version of PHP module to run
			TechnologyVersion phpVersion = hs.getModPhpVersion();
			if(phpVersion != null) httpdConfFilenames.add("php" + serverNum);
			out.print("ServerRoot \""+SERVER_ROOT+"\"\n"
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
			for(HttpdSite site : sites) {
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
			for(HttpdSite site : sites) {
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
			for(HttpdSite site : sites) {
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
			for(HttpdSite site : sites) {
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
			for(HttpdSite site : sites) {
				for(HttpdSiteBind hsb : site.getHttpdSiteBinds(hs)) {
					if(hsb.getSslCertFile() != null) {
						hasSsl = true;
						break HAS_SSL;
					}
				}
			}
			// Install mod_ssl when first needed
			if(hasSsl) PackageManager.installPackage(PackageManager.PackageName.MOD_SSL);
			boolean isModSslInstalled = PackageManager.getInstalledPackage(PackageManager.PackageName.MOD_SSL) != null;
			if(hasSsl || isModSslInstalled) {
				if(!hasSsl) out.print('#');
				out.print("LoadModule ssl_module modules/mod_ssl.so\n");
			}
			if(isEnabled && phpVersion != null) {
				hasAnyModPhp[0] = true;
				String version = phpVersion.getVersion();
				String phpMinorVersion = getMinorPhpVersion(version);
				String phpMajorVersion = getMajorPhpVersion(version);
				out.print("\n"
						+ "# Enable mod_php\n"
						+ "LoadModule php").print(phpMajorVersion).print("_module /opt/php-").print(phpMinorVersion).print("-i686/lib/apache/").print(getPhpLib(phpVersion)).print("\n"
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
					+ "ServerAdmin root@").print(hs.getAOServer().getHostname()).print("\n"
					+ "\n"
					+ "<IfModule mod_ssl.c>\n"
					+ "    SSLSessionCache shmcb:/var/cache/httpd/mod_ssl/ssl_scache").print(serverNum).print("(512000)\n"
					+ "</IfModule>\n"
					+ "\n");
			// Use apache if the account is disabled
			if(isEnabled) {
				out.print("User ").print(lsa.getLinuxAccount().getUsername().getUsername()).print("\n"
						+ "Group ").print(hs.getLinuxServerGroup().getLinuxGroup().getName()).print('\n');
			} else {
				out.print("User " + LinuxAccount.APACHE + "\n"
						+ "Group " + LinuxGroup.APACHE + "\n");
			}
			out.print("\n"
					+ "ServerName ").print(hs.getAOServer().getHostname()).print("\n"
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
				NetBind nb=hb.getNetBind();
				InetAddress ip=nb.getIPAddress().getInetAddress();
				int port=nb.getPort().getPort();
				out.print("Listen ").print(ip.toBracketedString()).print(':').print(port).print("\n"
						+ "NameVirtualHost ").print(ip.toBracketedString()).print(':').print(port).print('\n');
			}

			// The list of sites to include
			for(int d=0;d<2;d++) {
				boolean listFirst=d==0;
				out.print('\n');
				for(HttpdSite site : sites) {
					if(site.listFirst()==listFirst) {
						for(HttpdSiteBind bind : site.getHttpdSiteBinds(hs)) {
							NetBind nb=bind.getHttpdBind().getNetBind();
							InetAddress ipAddress=nb.getIPAddress().getInetAddress();
							int port=nb.getPort().getPort();
							out.print("Include conf/hosts/").print(site.getSiteName()).print('_').print(ipAddress).print('_').print(port).print('\n');
						}
					}
				}
			}
		}
		return bout.toByteArray();
	}

	/**
	 * Builds the httpd[-#].conf file for CentOS 7
	 */
	private static byte[] buildHttpdConfCentOs7(
		HttpdServer hs,
		List<HttpdSite> sites,
		Set<String> httpdConfFilenames,
		ByteArrayOutputStream bout,
		Set<UnixFile> restorecon,
		boolean[] hasAnyCgi,
		boolean[] hasAnyModPhp
	) throws IOException, SQLException {
		final HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
		if(osConfig != HttpdOperatingSystemConfiguration.CENTOS_7_X86_64) throw new AssertionError("This method is for CentOS 7 only");
		PackageManager.installPackages(
			PackageManager.PackageName.HTTPD,
			PackageManager.PackageName.AOSERV_HTTPD_CONFIG
		);
		final int serverNum = hs.getNumber();
		bout.reset();
		try (ChainWriter out = new ChainWriter(bout)) {
			LinuxServerAccount lsa = hs.getLinuxServerAccount();
			boolean isEnabled = !lsa.isDisabled();
			// The version of PHP module to run
			TechnologyVersion phpVersion = hs.getModPhpVersion();
			out.print("#\n"
					+ "# core\n"
					+ "#\n"
					+ "ServerRoot \"" + SERVER_ROOT + "\"\n"
					+ "Include aoserv.conf.d/core.conf\n"
					+ "ErrorLog \"/var/log/httpd");
			if(serverNum != 1) out.print('-').print(serverNum);
			out.print("/error_log\"\n"
					+ "ServerAdmin root@").print(hs.getAOServer().getHostname()).print("\n"
					+ "ServerName ").print(hs.getAOServer().getHostname()).print("\n"
					+ "TimeOut ").print(hs.getTimeOut()).print("\n"
					+ "\n"
					+ "#\n"
					+ "# mpm\n"
					+ "#\n"
					+ "# LoadModule mpm_event_module modules/mod_mpm_event.so\n"
					+ "LoadModule mpm_prefork_module modules/mod_mpm_prefork.so\n"
					+ "# LoadModule mpm_worker_module modules/mod_mpm_worker.so\n"
					+ "Include aoserv.conf.d/mpm_*.conf\n"
					+ "CoreDumpDirectory /var/log/httpd");
			if(serverNum != 1) out.print('-').print(serverNum);
			out.print("\n"
					+ "# ListenBacklog 511\n"
					+ "PidFile /run/httpd");
			if(serverNum != 1) out.print('-').print(serverNum);
			out.print("/httpd.pid\n"
					+ "<IfModule mpm_prefork_module>\n"
					+ "    MaxRequestWorkers ").print(hs.getMaxConcurrency()).print("\n"
					+ "    ServerLimit ").print(hs.getMaxConcurrency()).print("\n"
					+ "</IfModule>\n"
					+ "\n"
					+ "#\n"
					+ "# Load Modules\n"
					+ "#\n");
			// Enable mod_access_compat when aoserv-httpd-config-compat is installed
			if(PackageManager.getInstalledPackage(PackageManager.PackageName.AOSERV_HTTPD_CONFIG_COMPAT) == null) {
				out.print("# ");
			}
			out.print("LoadModule access_compat_module modules/mod_access_compat.so\n");
			// actions_module not required when mod_php or has no CGI PHP
			if(isEnabled && phpVersion != null) {
				out.print("# ");
			} else {
				boolean hasCgiPhp = false;
				for(HttpdSite site : sites) {
					if(site.getPhpVersion() != null) {
						hasCgiPhp = true;
						break;
					}
				}
				if(!hasCgiPhp) out.print("# ");
			}
			out.print("LoadModule actions_module modules/mod_actions.so\n"
					+ "LoadModule alias_module modules/mod_alias.so\n"
					+ "# LoadModule allowmethods_module modules/mod_allowmethods.so\n"
					+ "# LoadModule asis_module modules/mod_asis.so\n"
					+ "LoadModule auth_basic_module modules/mod_auth_basic.so\n" // TODO: As-needed
					+ "# LoadModule auth_digest_module modules/mod_auth_digest.so\n"
					+ "# LoadModule authn_anon_module modules/mod_authn_anon.so\n"
					+ "LoadModule authn_core_module modules/mod_authn_core.so\n" // TODO: As-needed
					+ "# LoadModule authn_dbd_module modules/mod_authn_dbd.so\n"
					+ "# LoadModule authn_dbm_module modules/mod_authn_dbm.so\n"
					+ "LoadModule authn_file_module modules/mod_authn_file.so\n" // TODO: As-needed
					+ "# LoadModule authn_socache_module modules/mod_authn_socache.so\n"
					+ "LoadModule authz_core_module modules/mod_authz_core.so\n"
					+ "# LoadModule authz_dbd_module modules/mod_authz_dbd.so\n"
					+ "# LoadModule authz_dbm_module modules/mod_authz_dbm.so\n"
					+ "LoadModule authz_groupfile_module modules/mod_authz_groupfile.so\n" // TODO: As-needed
					+ "LoadModule authz_host_module modules/mod_authz_host.so\n" // TODO: As-needed
					+ "# LoadModule authz_owner_module modules/mod_authz_owner.so\n"
					+ "LoadModule authz_user_module modules/mod_authz_user.so\n"); // TODO: As-needed
			// Comment-out mod_autoindex when no sites used auto-indexes
			boolean hasIndexes = false;
			for(HttpdSite site : sites) {
				if(site.getEnableIndexes()) {
					hasIndexes = true;
					break;
				}
			}
			if(!hasIndexes) out.print("# ");
			out.print("LoadModule autoindex_module modules/mod_autoindex.so\n"
					+ "# LoadModule buffer_module modules/mod_buffer.so\n"
					+ "# LoadModule cache_module modules/mod_cache.so\n"
					+ "# LoadModule cache_disk_module modules/mod_cache_disk.so\n"
					+ "# LoadModule cache_socache_module modules/mod_cache_socache.so\n");
			// Comment-out cgi_module when no CGI enabled
			boolean hasCgi = false;
			for(HttpdSite site : sites) {
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
					+ "# LoadModule dbd_module modules/mod_dbd.so\n"
					+ "LoadModule deflate_module modules/mod_deflate.so\n"
					+ "# LoadModule dialup_module modules/mod_dialup.so\n"
					+ "LoadModule dir_module modules/mod_dir.so\n"
					+ "# LoadModule dumpio_module modules/mod_dumpio.so\n"
					+ "# LoadModule echo_module modules/mod_echo.so\n"
					+ "# LoadModule env_module modules/mod_env.so\n"
					+ "# LoadModule expires_module modules/mod_expires.so\n"
					+ "# LoadModule ext_filter_module modules/mod_ext_filter.so\n"
					+ "# LoadModule file_cache_module modules/mod_file_cache.so\n"
					+ "LoadModule filter_module modules/mod_filter.so\n"
					+ "LoadModule headers_module modules/mod_headers.so\n"
					+ "# LoadModule heartbeat_module modules/mod_heartbeat.so\n"
					+ "# LoadModule heartmonitor_module modules/mod_heartmonitor.so\n");
			// Comment-out include module when no site has .shtml enabled
			boolean hasSsi = false;
			for(HttpdSite site : sites) {
				if(site.getEnableSsi()) {
					hasSsi = true;
					break;
				}
			}
			if(!hasSsi) out.print("# ");
			out.print("LoadModule include_module modules/mod_include.so\n"
					+ "# LoadModule info_module modules/mod_info.so\n");
			// Only include mod_jk when at least one site has jk settings
			boolean hasJkSettings = false;
			for(HttpdSite site : sites) {
				HttpdSiteManager manager = HttpdSiteManager.getInstance(site);
				if(!manager.getJkSettings().isEmpty()) {
					hasJkSettings = true;
					break;
				}
			}
			if(hasJkSettings) PackageManager.installPackage(PackageManager.PackageName.TOMCAT_CONNECTORS);
			boolean isModJkInstalled = PackageManager.getInstalledPackage(PackageManager.PackageName.TOMCAT_CONNECTORS) != null;
			if(hasJkSettings || isModJkInstalled) {
				if(!hasJkSettings) out.print("# ");
				out.print("LoadModule jk_module modules/mod_jk.so\n");
			}
			out.print("# LoadModule lbmethod_bybusyness_module modules/mod_lbmethod_bybusyness.so\n"
					+ "# LoadModule lbmethod_byrequests_module modules/mod_lbmethod_byrequests.so\n"
					+ "# LoadModule lbmethod_bytraffic_module modules/mod_lbmethod_bytraffic.so\n"
					+ "# LoadModule lbmethod_heartbeat_module modules/mod_lbmethod_heartbeat.so\n"
					+ "LoadModule log_config_module modules/mod_log_config.so\n"
					+ "# LoadModule log_debug_module modules/mod_log_debug.so\n"
					+ "# LoadModule log_forensic_module modules/mod_log_forensic.so\n"
					+ "# LoadModule logio_module modules/mod_logio.so\n"
					+ "# LoadModule lua_module modules/mod_lua.so\n"
					+ "# LoadModule macro_module modules/mod_macro.so\n"
					+ "LoadModule mime_module modules/mod_mime.so\n"
					+ "LoadModule mime_magic_module modules/mod_mime_magic.so\n"
					+ "LoadModule negotiation_module modules/mod_negotiation.so\n" // TODO: Enable per site, as-needed
					+ "LoadModule proxy_module modules/mod_proxy.so\n" // TODO: Enable per site, as-needed
					+ "# LoadModule proxy_ajp_module modules/mod_proxy_ajp.so\n"
					+ "# LoadModule proxy_balancer_module modules/mod_proxy_balancer.so\n"
					+ "# LoadModule proxy_connect_module modules/mod_proxy_connect.so\n"
					+ "# LoadModule proxy_express_module modules/mod_proxy_express.so\n"
					+ "# LoadModule proxy_fcgi_module modules/mod_proxy_fcgi.so\n"
					+ "# LoadModule proxy_fdpass_module modules/mod_proxy_fdpass.so\n"
					+ "# LoadModule proxy_ftp_module modules/mod_proxy_ftp.so\n"
					+ "LoadModule proxy_http_module modules/mod_proxy_http.so\n" // TODO: As-needed
					+ "# LoadModule proxy_scgi_module modules/mod_proxy_scgi.so\n"
					+ "# LoadModule proxy_wstunnel_module modules/mod_proxy_wstunnel.so\n"
					+ "# LoadModule ratelimit_module modules/mod_ratelimit.so\n"
					+ "# LoadModule reflector_module modules/mod_reflector.so\n"
					+ "# LoadModule remoteip_module modules/mod_remoteip.so\n"
					+ "LoadModule reqtimeout_module modules/mod_reqtimeout.so\n"
					+ "# LoadModule request_module modules/mod_request.so\n"
					+ "LoadModule rewrite_module modules/mod_rewrite.so\n"
					+ "# LoadModule sed_module modules/mod_sed.so\n"
					+ "LoadModule setenvif_module modules/mod_setenvif.so\n" // TODO: As-needed
					+ "# LoadModule slotmem_plain_module modules/mod_slotmem_plain.so\n" // Required?
					+ "# LoadModule slotmem_shm_module modules/mod_slotmem_shm.so\n" // Required?
					+ "# LoadModule socache_dbm_module modules/mod_socache_dbm.so\n" // Required?
					+ "# LoadModule socache_memcache_module modules/mod_socache_memcache.so\n" // Required?
					+ "LoadModule socache_shmcb_module modules/mod_socache_shmcb.so\n"
					+ "# LoadModule speling_module modules/mod_speling.so\n");
			// Comment-out ssl module when has no ssl
			boolean hasSsl = false;
			HAS_SSL :
			for(HttpdSite site : sites) {
				for(HttpdSiteBind hsb : site.getHttpdSiteBinds(hs)) {
					if(hsb.getSslCertFile() != null) {
						hasSsl = true;
						break HAS_SSL;
					}
				}
			}
			// Install mod_ssl when first needed
			if(hasSsl) PackageManager.installPackage(PackageManager.PackageName.MOD_SSL);
			boolean isModSslInstalled = PackageManager.getInstalledPackage(PackageManager.PackageName.MOD_SSL) != null;
			if(hasSsl || isModSslInstalled) {
				if(!hasSsl) out.print("# ");
				out.print("LoadModule ssl_module modules/mod_ssl.so\n");
			}
			out.print("LoadModule status_module modules/mod_status.so\n" // TODO: As-needed
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
					+ "# LoadModule watchdog_module modules/mod_watchdog.so\n"
					+ "\n"
					+ "#\n"
					+ "# Configure Modules\n"
					+ "#\n"
					+ "Include aoserv.conf.d/mod_*.conf\n"
					+ "<IfModule dav_fs_module>\n"
					+ "    DavLockDB /var/lib/dav");
			if(serverNum != 1) out.print('-').print(serverNum);
			out.print("/lockdb\n"
					+ "</IfModule>\n");
			if(hasJkSettings || isModJkInstalled) {
				out.print("<IfModule jk_module>\n"
						+ "    JkWorkersFile \"conf/workers");
				if(serverNum != 1) out.print('-').print(serverNum);
				out.print(".properties\"\n"
						+ "    JkShmFile \"/var/log/httpd");
				if(serverNum != 1) out.print('-').print(serverNum);
				out.print("/jk-runtime-status\"\n"
						+ "    JkLogFile \"/var/log/httpd");
				if(serverNum != 1) out.print('-').print(serverNum);
				out.print("/mod_jk.log\"\n"
						+ "</IfModule>\n");
			}
			out.print("<IfModule log_config_module>\n"
					+ "    CustomLog \"/var/log/httpd");
			if(serverNum != 1) out.print('-').print(serverNum);
			out.print("/access_log\" combined\n"
					+ "</IfModule>\n");
			if(hasSsl || isModSslInstalled) {
				out.print("<IfModule ssl_module>\n"
						+ "    SSLSessionCache shmcb:/run/httpd");
				if(serverNum != 1) out.print('-').print(serverNum);
				out.print("/sslcache(512000)\n"
						+ "</IfModule>\n");
			}
			// Use apache if the account is disabled
			out.print("<IfModule unixd_module>\n");
			if(isEnabled) {
				out.print("    User ").print(lsa.getLinuxAccount().getUsername().getUsername()).print("\n"
						+ "    Group ").print(hs.getLinuxServerGroup().getLinuxGroup().getName()).print('\n');
			} else {
				out.print("    User " + LinuxAccount.APACHE + "\n"
						+ "    Group " + LinuxGroup.APACHE + "\n");
			}
			out.print("</IfModule>\n");
			if(phpVersion != null) {
				String version = phpVersion.getVersion();
				String phpMinorVersion = getMinorPhpVersion(version);
				// Create initial PHP config directory
				UnixFile phpIniDir;
				if(serverNum == 1) {
					phpIniDir = new UnixFile(CONF_DIRECTORY + "/php");
					httpdConfFilenames.add("php");
				} else {
					phpIniDir = new UnixFile(CONF_DIRECTORY + "/php-" + serverNum);
					httpdConfFilenames.add("php-" + serverNum);
				}
				DaemonFileUtils.mkdir(phpIniDir, 0750, UnixFile.ROOT_UID, hs.getLinuxServerGroup().getGid().getId());
				UnixFile phpIni = new UnixFile(phpIniDir, "php.ini", true);
				String expectedTarget = "../../../../opt/php-" + phpMinorVersion + "/lib/php.ini";
				Stat phpIniStat = phpIni.getStat();
				if(!phpIniStat.exists()) {
					phpIni.symLink(expectedTarget);
					restorecon.add(phpIni);
				} else if(phpIniStat.isSymLink()) {
					// Replace symlink if goes to a different PHP version?
					String actualTarget = phpIni.readLink();
					if(
						!actualTarget.equals(expectedTarget)
						&& actualTarget.startsWith("../../../../opt/php-")
					) {
						// Update link
						phpIni.delete();
						phpIni.symLink(expectedTarget);
						restorecon.add(phpIni);
					}
				}
				if(isEnabled) {
					hasAnyModPhp[0] = true;
					String phpMajorVersion = getMajorPhpVersion(version);
					out.print("\n"
							+ "#\n"
							+ "# Enable mod_php\n"
							+ "#\n"
							+ "LoadModule php").print(phpMajorVersion).print("_module /opt/php-").print(phpMinorVersion).print("/lib/apache/").print(getPhpLib(phpVersion)).print("\n"
							+ "<IfModule php5_module>\n"
							+ "    PHPIniDir \"").print(phpIniDir).print("\"\n"
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
				NetBind nb=hb.getNetBind();
				InetAddress ip=nb.getIPAddress().getInetAddress();
				int port = nb.getPort().getPort();
				out.print("Listen ").print(ip.toBracketedString()).print(':').print(port);
				String appProtocol = nb.getAppProtocol().getProtocol();
				if(appProtocol.equals(Protocol.HTTP)) {
					if(port != 80) out.print(" http");
				} else if(appProtocol.equals(Protocol.HTTPS)) {
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
			// TODO: Could use wildcard include if there are no list-first sites and there is only one apache instance
			for(int d = 0; d < 2; d++) {
				boolean listFirst = (d == 0);
				for(HttpdSite site : sites) {
					if(site.listFirst() == listFirst) {
						for(HttpdSiteBind bind : site.getHttpdSiteBinds(hs)) {
							NetBind nb = bind.getHttpdBind().getNetBind();
							InetAddress ipAddress = nb.getIPAddress().getInetAddress();
							int port=nb.getPort().getPort();
							out.print("Include sites-enabled/").print(site.getSiteName()).print('_').print(ipAddress).print('_').print(port).print(".conf\n");
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
		List<HttpdSite> sites,
		Set<String> httpdConfFilenames,
		ByteArrayOutputStream bout,
		Set<UnixFile> restorecon,
		boolean[] hasAnyCgi,
		boolean[] hasAnyModPhp
	) throws IOException, SQLException {
		HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
		switch(osConfig) {
			case CENTOS_5_I686_AND_X86_64 : return buildHttpdConfCentOs5(hs, sites, httpdConfFilenames, bout, hasAnyCgi, hasAnyModPhp);
			case CENTOS_7_X86_64          : return buildHttpdConfCentOs7(hs, sites, httpdConfFilenames, bout, restorecon, hasAnyCgi, hasAnyModPhp);
			default                       : throw new AssertionError("Unexpected value for osConfig: "+osConfig);
		}
	}

	private static boolean isWorkerEnabled(HttpdWorker worker) throws IOException, SQLException {
		HttpdSharedTomcat hst = worker.getHttpdSharedTomcat();
		if(hst != null) {
			if(hst.isDisabled()) return false;
			// Must also have at least one enabled site
			for(HttpdTomcatSharedSite htss : hst.getHttpdTomcatSharedSites()) {
				if(!htss.getHttpdTomcatSite().getHttpdSite().isDisabled()) return true;
			}
			// Does not have any enabled site
			return false;
		}
		HttpdTomcatSite hts = worker.getHttpdTomcatSite();
		if(hts != null) return !hts.getHttpdSite().isDisabled();
		throw new SQLException("worker is attached to neither HttpdSharedTomcat nor HttpdTomcatSite: " + worker);
	}

	/**
	 * Builds the workers#.properties file contents for the provided HttpdServer.
	 */
	private static byte[] buildWorkersFile(HttpdServer hs, ByteArrayOutputStream bout, Set<Port> enabledAjpPorts) throws IOException, SQLException {
		AOServConnector conn = AOServDaemon.getConnector();
		List<HttpdWorker> workers = hs.getHttpdWorkers();
		bout.reset();
		try (ChainWriter out = new ChainWriter(bout)) {
			out.print("worker.list=");
			boolean didOne = false;
			for(HttpdWorker worker : workers) {
				if(isWorkerEnabled(worker)) {
					if(didOne) out.print(',');
					else didOne = true;
					out.print(worker.getCode().getCode());
				}
			}
			out.print('\n');
			for(HttpdWorker worker : workers) {
				if(isWorkerEnabled(worker)) {
					String code = worker.getCode().getCode();
					Port port = worker.getNetBind().getPort();
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

	/**
	 * Builds the contents of a HttpdSiteBind file.
	 */
	private static byte[] buildHttpdSiteBindFile(HttpdSiteManager manager, HttpdSiteBind bind, String siteInclude, ByteArrayOutputStream bout) throws IOException, SQLException {
		OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
		HttpdBind httpdBind = bind.getHttpdBind();
		NetBind netBind = httpdBind.getNetBind();
		int port = netBind.getPort().getPort();
		InetAddress ipAddress = netBind.getIPAddress().getInetAddress();
		HttpdSiteURL primaryHSU = bind.getPrimaryHttpdSiteURL();
		String primaryHostname = primaryHSU.getHostname().toString();

		// TODO: Robots NOINDEX, NOFOLLOW on test URL, when it is not the primary?
		// TODO: Canonical URL header for non-primary, non-test: https://support.google.com/webmasters/answer/139066
		bout.reset();
		try (ChainWriter out = new ChainWriter(bout)) {
			switch(osConfig) {
				case CENTOS_5_I686_AND_X86_64 : {
					out.print("<VirtualHost ").print(ipAddress.toBracketedString()).print(':').print(port).print(">\n"
							+ "    ServerName \\\n"
							+ "        ").print(primaryHostname).print('\n'
					);
					List<HttpdSiteURL> altURLs=bind.getAltHttpdSiteURLs();
					if(!altURLs.isEmpty()) {
						out.print("    ServerAlias");
						for(HttpdSiteURL altURL : altURLs) {
							out.print(" \\\n        ").print(altURL.getHostname().toString());
						}
						out.print('\n');
					}
					out.print("\n"
							+ "    CustomLog ").print(bind.getAccessLog()).print(" combined\n"
							+ "    ErrorLog ").print(bind.getErrorLog()).print("\n"
							+ "\n");
					UnixPath sslCert = bind.getSslCertFile();
					if(sslCert != null) {
						String sslCertStr = sslCert.toString();
						// Use any directly configured chain file
						out.print("    <IfModule mod_ssl.c>\n"
								+ "        SSLCertificateFile ").print(sslCert).print("\n"
								+ "        SSLCertificateKeyFile ").print(bind.getSslCertKeyFile()).print('\n');
						String sslChain = ObjectUtils.toString(bind.getSslCertChainFile());
						if(sslChain != null) {
							out.print("        SSLCertificateChainFile ").print(sslChain).print('\n');
						}
						boolean enableCgi = manager.enableCgi();
						boolean enableSsi = manager.httpdSite.getEnableSsi();
						if(enableCgi && enableSsi) {
							out.print("        <Files ~ \"\\.(cgi|shtml)$\">\n"
									+ "            SSLOptions +StdEnvVars\n"
									+ "        </Files>\n");
						} else if(enableCgi) {
							out.print("        <Files ~ \"\\.cgi$\">\n"
									+ "            SSLOptions +StdEnvVars\n"
									+ "        </Files>\n");
						} else if(enableSsi) {
							out.print("        <Files ~ \"\\.shtml$\">\n"
									+ "            SSLOptions +StdEnvVars\n"
									+ "        </Files>\n");
						}
						out.print("        SSLEngine On\n"
								+ "    </IfModule>\n"
								+ "\n"
						);
					}
					if(bind.getRedirectToPrimaryHostname()) {
						out.print("    # Redirect requests that are not to either the IP address or the primary hostname to the primary hostname\n"
								+ "    RewriteEngine on\n"
								+ "    RewriteCond %{HTTP_HOST} !=").print(primaryHostname).print(" [NC]\n"
								+ "    RewriteCond %{HTTP_HOST} !=").print(ipAddress).print("\n"
								+ "    RewriteRule ^ \"").print(primaryHSU.getURLNoSlash()).print("%{REQUEST_URI}\" [L,NE,R=permanent]\n"
								+ "\n");
					}
					List<HttpdSiteBindRedirect> redirects = bind.getHttpdSiteBindRedirects();
					if(!redirects.isEmpty()) {
						out.print("    # Redirects\n"
								+ "    RewriteEngine on\n");
						for(HttpdSiteBindRedirect redirect : redirects) {
							String comment = redirect.getComment();
							if(comment != null) {
								// TODO: Check comment formatting before full automation
								out.print("    # ").print(comment).print('\n');
							}
							String substitution = redirect.getSubstitution();
							out
								.print("    RewriteRule \"")
								// TODO: Check pattern formatting before full automation
								.print(redirect.getPattern())
								.print("\" ");
							if(substitution.equals("-")) {
								out.print("- [L");
								if(redirect.isNoEscape()) out.print(",NE");
								out.print("]\n");
							} else {
								out.print('"')
									// TODO: Check substitution formatting before full automation
									.print(substitution)
									.print("\" [L");
								if(redirect.isNoEscape()) out.print(",NE");
								out.print(",R=permanent]\n");
							}
						}
						out.print('\n');
					}
					out.print("    Include conf/hosts/").print(siteInclude).print("\n"
							+ "\n"
							+ "</VirtualHost>\n");
					break;
				}
				case CENTOS_7_X86_64 : {
					out.print("<VirtualHost ").print(ipAddress.toBracketedString()).print(':').print(port).print(">\n"
							+ "    ServerName \\\n"
							+ "        ").print(primaryHostname).print('\n'
					);
					List<HttpdSiteURL> altURLs=bind.getAltHttpdSiteURLs();
					if(!altURLs.isEmpty()) {
						out.print("    ServerAlias");
						for(HttpdSiteURL altURL : altURLs) {
							out.print(" \\\n        ").print(altURL.getHostname().toString());
						}
						out.print('\n');
					}
					out.print("\n"
							+ "    <IfModule log_config_module>\n"
							+ "        CustomLog \"").print(bind.getAccessLog()).print("\" combined\n"
							+ "    </IfModule>\n"
							+ "    ErrorLog \"").print(bind.getErrorLog()).print("\"\n"
							+ "\n");
					UnixPath sslCert = bind.getSslCertFile();
					if(sslCert != null) {
						String sslCertStr = sslCert.toString();
						// Use any directly configured chain file
						out.print("    <IfModule ssl_module>\n"
								+ "        SSLCertificateFile ").print(sslCert).print("\n"
								+ "        SSLCertificateKeyFile ").print(bind.getSslCertKeyFile()).print('\n');
						String sslChain = ObjectUtils.toString(bind.getSslCertChainFile());
						if(sslChain != null) {
							out.print("        SSLCertificateChainFile ").print(sslChain).print('\n');
						}
						boolean enableCgi = manager.enableCgi();
						boolean enableSsi = manager.httpdSite.getEnableSsi();
						if(enableCgi && enableSsi) {
							out.print("        <Files ~ \"\\.(cgi|shtml)$\">\n"
									+ "            SSLOptions +StdEnvVars\n"
									+ "        </Files>\n");
						} else if(enableCgi) {
							out.print("        <Files ~ \"\\.cgi$\">\n"
									+ "            SSLOptions +StdEnvVars\n"
									+ "        </Files>\n");
						} else if(enableSsi) {
							out.print("        <Files ~ \"\\.shtml$\">\n"
									+ "            SSLOptions +StdEnvVars\n"
									+ "        </Files>\n");
						}
						// See https://unix.stackexchange.com/questions/162478/how-to-disable-sslv3-in-apache
						// TODO: Test if required in our Apache 2.4
						// TODO:     Test with: https://www.tinfoilsecurity.com/poodle
						out.print("        SSLProtocol all -SSLv2 -SSLv3\n"
								+ "        SSLEngine On\n"
								+ "    </IfModule>\n"
								+ "\n"
						);
					}
					if(bind.getRedirectToPrimaryHostname()) {
						out.print("    # Redirect requests that are not to either the IP address or the primary hostname to the primary hostname\n"
								+ "    <IfModule rewrite_module>\n"
								+ "        RewriteEngine on\n"
								+ "        RewriteCond %{HTTP_HOST} !=").print(primaryHostname).print(" [NC]\n"
								+ "        RewriteCond %{HTTP_HOST} !=").print(ipAddress).print("\n"
								+ "        RewriteRule ^ \"").print(primaryHSU.getURLNoSlash()).print("%{REQUEST_URI}\" [END,NE,R=permanent]\n"
								+ "    </IfModule>\n"
								+ "\n");
					}
					List<HttpdSiteBindRedirect> redirects = bind.getHttpdSiteBindRedirects();
					if(!redirects.isEmpty()) {
						out.print("    # Redirects\n"
								+ "    <IfModule rewrite_module>\n"
								+ "        RewriteEngine on\n");
						for(HttpdSiteBindRedirect redirect : redirects) {
							String comment = redirect.getComment();
							if(comment != null) {
								// TODO: Check comment formatting before full automation
								out.print("        # ").print(comment).print('\n');
							}
							String substitution = redirect.getSubstitution();
							out
								.print("        RewriteRule \"")
								// TODO: Check pattern formatting before full automation
								.print(redirect.getPattern())
								.print("\" ");
							if(substitution.equals("-")) {
								out.print("- [L");
								if(redirect.isNoEscape()) out.print(",NE");
								out.print("]\n");
							} else {
								out.print('"')
									// TODO: Check substitution formatting before full automation
									.print(substitution)
									.print("\" [END");
								if(redirect.isNoEscape()) out.print(",NE");
								out.print(",R=permanent]\n");
							}
						}
						out.print("    </IfModule>\n"
								+ "\n");
					}
					out.print("    Include sites-available/").print(siteInclude).print("\n"
							+ "\n"
							+ "</VirtualHost>\n");
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
		int num = hs.getNumber();
		OperatingSystemVersion osv = hs.getAOServer().getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		synchronized(processControlLock) {
			switch(osvId) {
				case OperatingSystemVersion.CENTOS_5_I686_AND_X86_64 :
					AOServDaemon.exec(
						"/etc/rc.d/init.d/httpd" + num,
						"reload" // Should this be restart for SSL changes?
					);
					break;
				case OperatingSystemVersion.CENTOS_7_X86_64 :
					if(num == 1) {
						AOServDaemon.exec(
							"/usr/bin/systemctl",
							"reload-or-restart",
							"httpd.service"
						);
					} else {
						AOServDaemon.exec(
							"/usr/bin/systemctl",
							"reload-or-restart",
							"httpd-" + num + ".service"
						);
					}
					break;
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
						AOServDaemon.exec(
							"/etc/rc.d/init.d/httpd" + hs.getNumber(),
							command
						);
					}
					break;
				case OperatingSystemVersion.CENTOS_7_X86_64 :
					for(HttpdServer hs : AOServDaemon.getThisAOServer().getHttpdServers()) {
						int num = hs.getNumber();
						if(num == 1) {
							AOServDaemon.exec(
								"/usr/bin/systemctl",
								command,
								"httpd.service"
							);
						} else {
							AOServDaemon.exec(
								"/usr/bin/systemctl",
								command,
								"httpd-" + num + ".service"
							);
						}
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
	 * Gets the shared library name for the given version of PHP.
	 */
	private static String getPhpLib(TechnologyVersion phpVersion) {
		String version=phpVersion.getVersion();
		if(version.equals("4") || version.startsWith("4.")) return "libphp4.so";
		if(version.equals("5") || version.startsWith("5.")) return "libphp5.so";
		throw new RuntimeException("Unsupported PHP version: "+version);
	}

	/**
	 * Gets the major (first number only) form of a PHP version.
	 */
	private static String getMajorPhpVersion(String version) {
		int pos = version.indexOf('.');
		return pos == -1 ? version : version.substring(0, pos);
	}

	/**
	 * Gets the minor (first two numbers only) form of a PHP version.
	 */
	private static String getMinorPhpVersion(String version) {
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
	 * Rebuilds /etc/rc.d/init.d/httpd* init scripts
	 * or /etc/systemd/system/httpd[-#].service files.
	 */
	private static void doRebuildInitScripts(
		AOServer thisAoServer,
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
					int num = hs.getNumber();
					bout.reset();
					try (ChainWriter out = new ChainWriter(bout)) {
						out.print("#!/bin/bash\n"
								+ "#\n"
								+ "# httpd").print(num).print("        Startup script for the Apache HTTP Server ").print(num).print("\n"
								+ "#\n"
								+ "# chkconfig: 345 85 15\n"
								+ "# description: Apache is a World Wide Web server.  It is used to serve \\\n"
								+ "#              HTML files and CGI.\n"
								+ "# processname: httpd").print(num).print("\n"
								+ "# config: /etc/httpd/conf/httpd").print(num).print(".conf\n"
								+ "# pidfile: /var/run/httpd").print(num).print(".pid\n"
								+ "\n");
						// mod_php requires MySQL and PostgreSQL in the path
						TechnologyVersion modPhpVersion = hs.getModPhpVersion();
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
							new UnixFile(INIT_DIRECTORY+"/"+filename),
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
				for(String filename : new File(INIT_DIRECTORY).list()) {
					if(filename.startsWith("httpd")) {
						String suffix = filename.substring(5);
						try {
							// Parse to make sure is a httpd# filename
							int num = Integer.parseInt(suffix);
							if(!dontDeleteFilenames.contains(filename)) {
								// chkconfig off
								AOServDaemon.exec(
									"/sbin/chkconfig",
									filename,
									"off"
								);
								// stop
								String fullPath = INIT_DIRECTORY+"/"+filename;
								AOServDaemon.exec(
									fullPath,
									"stop"
								);
								File toDelete = new File(fullPath);
								if(logger.isLoggable(Level.INFO)) logger.info("Scheduling for removal: " + toDelete);
								deleteFileList.add(toDelete);
							}
						} catch(NumberFormatException err) {
							LogFactory.getLogger(HttpdServerManager.class).log(Level.WARNING, null, err);
						}
					}
				}
				break;
			}
			case OperatingSystemVersion.CENTOS_7_X86_64 : {
				// TODO
				// TODO: Disable when doesn't have any active httpd_binds
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
		AOServer aoServer,
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
}
