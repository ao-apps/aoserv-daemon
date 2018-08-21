/*
 * Copyright 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.HttpdSharedTomcat;
import com.aoindustries.aoserv.client.HttpdSite;
import com.aoindustries.aoserv.client.HttpdSiteBind;
import com.aoindustries.aoserv.client.HttpdSiteURL;
import com.aoindustries.aoserv.client.HttpdTomcatContext;
import com.aoindustries.aoserv.client.HttpdTomcatDataSource;
import com.aoindustries.aoserv.client.HttpdTomcatParameter;
import com.aoindustries.aoserv.client.HttpdTomcatSharedSite;
import com.aoindustries.aoserv.client.HttpdTomcatSite;
import com.aoindustries.aoserv.client.HttpdWorker;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.LinuxServerGroup;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.client.validator.UnixPath;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.httpd.tomcat.Install.Delete;
import com.aoindustries.aoserv.daemon.httpd.tomcat.Install.Generated;
import static com.aoindustries.aoserv.daemon.httpd.tomcat.VersionedTomcatCommon.BACKUP_EXTENSION;
import static com.aoindustries.aoserv.daemon.httpd.tomcat.VersionedTomcatCommon.BACKUP_SEPARATOR;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.net.DomainName;
import com.aoindustries.util.SortedArrayList;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages shared aspects of HttpdSharedTomcat version 8.5 and above.
 *
 * @author  AO Industries, Inc.
 */
public abstract class VersionedSharedTomcatManager<TC extends VersionedTomcatCommon> extends HttpdSharedTomcatManager<TC> {

	private static final Logger logger = Logger.getLogger(VersionedSharedTomcatManager.class.getName());

	VersionedSharedTomcatManager(HttpdSharedTomcat sharedTomcat) {
		super(sharedTomcat);
	}

	/**
	 * Writes the server.xml file.
	 *
	 * @param out  Is in UTF-8 encoding.
	 */
	protected void writeServerXml(
		ChainWriter out,
		HttpdSharedTomcat sharedTomcat,
		List<HttpdTomcatSharedSite> sites
	) throws IOException, SQLException {
		final TC tomcatCommon = getTomcatCommon();
		final OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
		final HttpdOperatingSystemConfiguration httpdConfig = osConfig.getHttpdOperatingSystemConfiguration();
		final UnixPath wwwDirectory = httpdConfig.getHttpdSitesDirectory();
		String autoWarning = getAutoWarningXml();

		HttpdWorker hw = sharedTomcat.getTomcat4Worker();
		NetBind shutdownPort = sharedTomcat.getTomcat4ShutdownPort();
		if(shutdownPort == null) throw new SQLException("Unable to find shutdown key for HttpdSharedTomcat: " + sharedTomcat);
		String shutdownKey = sharedTomcat.getTomcat4ShutdownKey();
		if(shutdownKey == null) throw new SQLException("Unable to find shutdown key for HttpdSharedTomcat: " + sharedTomcat);
		out.print("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		if(!sharedTomcat.isManual()) out.print(autoWarning);
		out.print("<Server port=\"").encodeXmlAttribute(shutdownPort.getPort().getPort()).print("\" shutdown=\"").encodeXmlAttribute(shutdownKey).print("\">\n"
				+ "  <Listener className=\"org.apache.catalina.startup.VersionLoggerListener\" />\n"
				+ "  <!-- Security listener. Documentation at /docs/config/listeners.html\n"
				+ "  <Listener className=\"org.apache.catalina.security.SecurityListener\" />\n"
				+ "  -->\n"
				+ "  <!--APR library loader. Documentation at /docs/apr.html -->\n"
				+ "  <Listener className=\"org.apache.catalina.core.AprLifecycleListener\" SSLEngine=\"on\" />\n"
				+ "  <!-- Prevent memory leaks due to use of particular java/javax APIs-->\n"
				+ "  <Listener className=\"org.apache.catalina.core.JreMemoryLeakPreventionListener\" />\n"
				+ "  <Listener className=\"org.apache.catalina.mbeans.GlobalResourcesLifecycleListener\" />\n"
				+ "  <Listener className=\"org.apache.catalina.core.ThreadLocalLeakPreventionListener\" />\n"
				+ "\n"
				+ "  <!-- Global JNDI resources\n"
				+ "       Documentation at /docs/jndi-resources-howto.html\n"
				+ "  -->\n"
				+ "  <GlobalNamingResources>\n"
				+ "    <!-- Editable user database that can also be used by\n"
				+ "         UserDatabaseRealm to authenticate users\n"
				+ "    -->\n"
				+ "    <Resource name=\"UserDatabase\" auth=\"Container\"\n"
				+ "              type=\"org.apache.catalina.UserDatabase\"\n"
				+ "              description=\"User database that can be updated and saved\"\n"
				+ "              factory=\"org.apache.catalina.users.MemoryUserDatabaseFactory\"\n"
				+ "              pathname=\"conf/tomcat-users.xml\" />\n"
				+ "  </GlobalNamingResources>\n"
				+ "\n"
				+ "  <Service name=\"Catalina\">\n"
				+ "    <Connector\n"
				+ "      port=\"").encodeXmlAttribute(hw.getNetBind().getPort().getPort()).print("\"\n"
				+ "      address=\"").encodeXmlAttribute(IPAddress.LOOPBACK_IP).print("\"\n"
				+ "      maxPostSize=\"").encodeXmlAttribute(sharedTomcat.getMaxPostSize()).print("\"\n"
				+ "      protocol=\"AJP/1.3\"\n"
				+ "      redirectPort=\"8443\"\n"
				+ "      URIEncoding=\"UTF-8\"\n"
				+ "    />\n"
				+ "\n"
				+ "    <Engine name=\"Catalina\" defaultHost=\"localhost\">\n"
				+ "      <!-- Use the LockOutRealm to prevent attempts to guess user passwords\n"
				+ "           via a brute-force attack -->\n"
				+ "      <Realm className=\"org.apache.catalina.realm.LockOutRealm\">\n"
				+ "        <!-- This Realm uses the UserDatabase configured in the global JNDI\n"
				+ "             resources under the key \"UserDatabase\".  Any edits\n"
				+ "             that are performed against this UserDatabase are immediately\n"
				+ "             available for use by the Realm.  -->"
				+ "        <Realm className=\"org.apache.catalina.realm.UserDatabaseRealm\"\n"
				+ "               resourceName=\"UserDatabase\"/>\n"
				+ "      </Realm>\n");
		for (HttpdTomcatSharedSite site : sites) {
			HttpdSite hs = site.getHttpdTomcatSite().getHttpdSite();
			if(!hs.isDisabled()) {
				DomainName primaryHostname = hs.getPrimaryHttpdSiteURL().getHostname();
				out.print("\n"
						+ "      <Host\n"
						+ "        name=\"").encodeXmlAttribute(primaryHostname.toString()).print("\"\n"
						+ "        appBase=\"").encodeXmlAttribute(wwwDirectory).print('/').encodeXmlAttribute(hs.getSiteName()).print("/webapps\"\n"
						+ "        unpackWARs=\"").encodeXmlAttribute(sharedTomcat.getUnpackWARs()).print("\"\n"
						+ "        autoDeploy=\"").encodeXmlAttribute(sharedTomcat.getAutoDeploy()).print("\"\n"
						+ "      >\n");
				List<String> usedHostnames = new SortedArrayList<>();
				usedHostnames.add(primaryHostname.toString());
				List<HttpdSiteBind> binds = hs.getHttpdSiteBinds();
				for (HttpdSiteBind bind : binds) {
					for (HttpdSiteURL url : bind.getHttpdSiteURLs()) {
						DomainName hostname = url.getHostname();
						if(!usedHostnames.contains(hostname.toString())) {
							out.print("        <Alias>").encodeXhtml(hostname).print("</Alias>\n");
							usedHostnames.add(hostname.toString());
						}
					}
					// When listed first, also include the IP addresses as aliases
					if(hs.listFirst()) {
						String ip = bind.getHttpdBind().getNetBind().getIPAddress().getInetAddress().toString();
						if(!usedHostnames.contains(ip)) {
							out.print("        <Alias>").encodeXhtml(ip).print("</Alias>\n");
							usedHostnames.add(ip);
						}
					}
				}
				HttpdTomcatSite tomcatSite = hs.getHttpdTomcatSite();
				for(HttpdTomcatContext htc : tomcatSite.getHttpdTomcatContexts()) {
					if(!htc.isServerXmlConfigured()) out.print("        <!--\n");
					out.print("        <Context\n");
					if(htc.getClassName() != null) out.print("          className=\"").encodeXmlAttribute(htc.getClassName()).print("\"\n");
					out.print("          cookies=\"").encodeXmlAttribute(htc.useCookies()).print("\"\n"
							+ "          crossContext=\"").encodeXmlAttribute(htc.allowCrossContext()).print("\"\n"
							+ "          docBase=\"").encodeXmlAttribute(htc.getDocBase()).print("\"\n"
							+ "          override=\"").encodeXmlAttribute(htc.allowOverride()).print("\"\n"
							+ "          path=\"").encodeXmlAttribute(htc.getPath()).print("\"\n"
							+ "          privileged=\"").encodeXmlAttribute(htc.isPrivileged()).print("\"\n"
							+ "          reloadable=\"").encodeXmlAttribute(htc.isReloadable()).print("\"\n"
							+ "          useNaming=\"").encodeXmlAttribute(htc.useNaming()).print("\"\n");
					if(htc.getWrapperClass() != null) out.print("          wrapperClass=\"").encodeXmlAttribute(htc.getWrapperClass()).print("\"\n");
					// Not present in Tomcat 8.5+: out.print("          debug=\"").encodeXmlAttribute(htc.getDebugLevel()).print("\"\n");
					if(htc.getWorkDir() != null) out.print("          workDir=\"").encodeXmlAttribute(htc.getWorkDir()).print("\"\n");
					List<HttpdTomcatParameter> parameters = htc.getHttpdTomcatParameters();
					List<HttpdTomcatDataSource> dataSources = htc.getHttpdTomcatDataSources();
					if(parameters.isEmpty() && dataSources.isEmpty()) {
						out.print("        />\n");
					} else {
						out.print("        >\n");
						// Parameters
						for(HttpdTomcatParameter parameter : parameters) {
							tomcatCommon.writeHttpdTomcatParameter(parameter, out);
						}
						// Data Sources
						for(HttpdTomcatDataSource dataSource : dataSources) {
							tomcatCommon.writeHttpdTomcatDataSource(dataSource, out);
						}
						out.print("        </Context>\n");
					}
					if(!htc.isServerXmlConfigured()) out.print("        -->\n");
				}
				out.print("      </Host>\n");
			}
		}
		out.print("    </Engine>\n"
				+ "  </Service>\n"
				+ "</Server>\n");
	}

	@Override
	void buildSharedTomcatDirectory(String optSlash, UnixFile sharedTomcatDirectory, List<File> deleteFileList, Set<HttpdSharedTomcat> sharedTomcatsNeedingRestarted) throws IOException, SQLException {
		/*
		 * Get values used in the rest of the loop.
		 */
		final OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
		final HttpdOperatingSystemConfiguration httpdConfig = osConfig.getHttpdOperatingSystemConfiguration();
		final AOServer thisAoServer = AOServDaemon.getThisAOServer();
		int uid_min = thisAoServer.getUidMin().getId();
		int gid_min = thisAoServer.getGidMin().getId();
		final LinuxServerAccount lsa = sharedTomcat.getLinuxServerAccount();
		final int lsaUID = lsa.getUid().getId();
		final LinuxServerGroup lsg = sharedTomcat.getLinuxServerGroup();
		final int lsgGID = lsg.getGid().getId();
		final UnixPath wwwDirectory = httpdConfig.getHttpdSitesDirectory();

		final TC tomcatCommon = getTomcatCommon();
		final String apacheTomcatDir = tomcatCommon.getApacheTomcatDir();

		final UnixFile bin           = new UnixFile(sharedTomcatDirectory, "bin", false);
		final UnixFile binProfileD   = new UnixFile(bin, "profile.d", false);
		final UnixFile conf          = new UnixFile(sharedTomcatDirectory, "conf", false);
		final UnixFile serverXml     = new UnixFile(conf, "server.xml", false);
		final UnixFile daemon        = new UnixFile(sharedTomcatDirectory, "daemon", false);
		final UnixFile work          = new UnixFile(sharedTomcatDirectory, "work", false);
		final UnixFile workCatalina  = new UnixFile(work, "Catalina", false);

		final String backupSuffix = VersionedTomcatCommon.getBackupSuffix();
		final ByteArrayOutputStream bout = new ByteArrayOutputStream();

		boolean needRestart = false;

		// Create and fill in the directory if it does not exist or is owned by root.
		Stat sharedTomcatStat = sharedTomcatDirectory.getStat();
		if (!sharedTomcatStat.exists() || sharedTomcatStat.getUid() == UnixFile.ROOT_GID) {

			// /var/opt/apache-tomcat/(name)/
			if (!sharedTomcatStat.exists()) sharedTomcatDirectory.mkdir();
			sharedTomcatDirectory.setMode(0770);

			List<Install> installFiles = getInstallFiles(optSlash, sharedTomcatDirectory);
			for(Install installFile : installFiles) {
				installFile.install(optSlash, apacheTomcatDir, sharedTomcatDirectory, lsaUID, lsgGID, backupSuffix);
			}

			// Set the ownership to avoid future rebuilds of this directory
			sharedTomcatDirectory.chown(lsaUID, lsgGID);

			needRestart = true;
		}

		// always rebuild bin/profile.d/httpd-sites.sh
		List<HttpdTomcatSharedSite> sites = sharedTomcat.getHttpdTomcatSharedSites();
		bout.reset();
		try (ChainWriter out = new ChainWriter(new OutputStreamWriter(bout, StandardCharsets.UTF_8))) {
			out.print("export SITES=\"");
			boolean didOne = false;
			for(HttpdTomcatSharedSite site : sites) {
				HttpdSite hs = site.getHttpdTomcatSite().getHttpdSite();
				if(!hs.isDisabled()) {
					if(didOne) out.print(' ');
					else didOne = true;
					out.print(hs.getSiteName());
				}
			}
			out.print("\"\n"
					+ "\n"
					+ "for SITE in $SITES\n"
					+ "do\n"
					+ "    export PATH=\"${PATH}:").print(wwwDirectory).print("/${SITE}/bin\"\n"
					+ "done\n"
					+ "unset SITE\n");
		}
		UnixFile httpdSitesSh = new UnixFile(binProfileD, "httpd-sites.sh", false);
		if(
			DaemonFileUtils.atomicWrite(
				httpdSitesSh, bout.toByteArray(), 0640, lsaUID, lsgGID,
				DaemonFileUtils.findUnusedBackup(httpdSitesSh + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION),
				null
			)
		) {
			needRestart = true;
		}
		
		// make work directories and remove extra work dirs
		List<String> workFiles = new SortedArrayList<>();
		String[] wlist = workCatalina.getFile().list();
		if(wlist != null) {
			workFiles.addAll(Arrays.asList(wlist));
		}
		for (HttpdTomcatSharedSite site : sites) {
			HttpdSite hs = site.getHttpdTomcatSite().getHttpdSite();
			if(!hs.isDisabled()) {
				String subwork = hs.getPrimaryHttpdSiteURL().getHostname().toString();
				workFiles.remove(subwork);
				if(
					DaemonFileUtils.mkdir(
						new UnixFile(workCatalina, subwork, false), 0750, lsaUID, hs.getLinuxServerGroup().getGid().getId()
					)
				) {
					needRestart = true;
				}
			}
		}
		for(String workFile : workFiles) {
			File toDelete = new File(workCatalina.getFile(), workFile);
			if(logger.isLoggable(Level.INFO)) logger.info("Scheduling for removal: " + toDelete);
			deleteFileList.add(toDelete);
		}

		// always rebuild conf/server.xml
		String autoWarning = getAutoWarningXml();
		String autoWarningOld = getAutoWarningXmlOld();
		if(!sharedTomcat.isManual() || !serverXml.getStat().exists()) {
			bout.reset();
			try (ChainWriter out = new ChainWriter(new OutputStreamWriter(bout, StandardCharsets.UTF_8))) {
				writeServerXml(out, sharedTomcat, sites);
			}
			if(
				DaemonFileUtils.atomicWrite(
					serverXml, bout.toByteArray(), 0640, lsaUID, lsgGID,
					DaemonFileUtils.findUnusedBackup(serverXml + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION),
					null
				)
			) {
				// Must restart JVM if this file has changed
				needRestart = true;
			}
		} else {
			try {
				DaemonFileUtils.stripFilePrefix(
					serverXml,
					autoWarningOld,
					uid_min,
					gid_min
				);
				DaemonFileUtils.stripFilePrefix(
					serverXml,
					"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + autoWarning,
					uid_min,
					gid_min
				);
			} catch(IOException err) {
				// Errors OK because this is done in manual mode and they might have symbolic linked stuff
			}
		}

		// Enable/Disable
		boolean hasEnabledSite = false;
		for(HttpdTomcatSharedSite htss : sharedTomcat.getHttpdTomcatSharedSites()) {
			if(!htss.getHttpdTomcatSite().getHttpdSite().isDisabled()) {
				hasEnabledSite = true;
				break;
			}
		}
		UnixFile daemonSymlink = new UnixFile(daemon, "tomcat", false);
		if(!sharedTomcat.isDisabled() && hasEnabledSite) {
			// Enabled
			if(!daemonSymlink.getStat().exists()) {
				daemonSymlink.symLink("../bin/tomcat").chown(
					lsaUID,
					lsgGID
				);
			}
			// Start if needed
			if(needRestart) sharedTomcatsNeedingRestarted.add(sharedTomcat);
		} else {
			// Disabled
			if(daemonSymlink.getStat().exists()) daemonSymlink.delete();
		}
	}

	protected static byte[] generateTomcatScript(String optSlash, String apacheTomcatDir, UnixFile installDir) throws IOException {
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		try (ChainWriter out = new ChainWriter(new OutputStreamWriter(bout, StandardCharsets.UTF_8))) {
			out.print("#!/bin/bash\n"
					+ "#\n"
					+ "# Generated by ").print(VersionedSharedTomcatManager.class.getName()).print("\n"
					+ "#\n"
					+ "if [ \"${AOSERV_PROFILE_D:-}\" != '").print(installDir).print("/bin/profile.d' ]; then\n"
					+ "    exec env -i AOSERV_PROFILE_D='").print(installDir).print("/bin/profile.d' \"$0\" \"$@\"\n"
					+ "fi\n"
					+ ". /etc/profile\n"
					+ "\n"
					+ "TOMCAT_HOME='").print(installDir).print("'\n"
					+ "\n"
					+ "if [ \"$1\" = 'start' ]; then\n"
					+ "    # Stop any running Tomcat\n"
					+ "    \"$0\" stop\n"
					+ "    # Remove work files to force JSP recompilation on Tomcat restart\n"
					+ "    find \"${TOMCAT_HOME}/work/Catalina\" -type f -path '*/org/apache/jsp/*.class' -or -path '*/org/apache/jsp/*.java' -delete\n"
					+ "    find \"${TOMCAT_HOME}/work/Catalina\" -mindepth 1 -type d -empty -delete\n"
					+ "    # Start Tomcat wrapper in the background\n"
					+ "    \"$0\" daemon &\n"
					+ "    echo \"$!\" >\"${TOMCAT_HOME}/var/run/tomcat.pid\"\n"
					+ "elif [ \"$1\" = 'stop' ]; then\n"
					+ "    if [ -f \"${TOMCAT_HOME}/var/run/tomcat.pid\" ]; then\n"
					+ "        kill \"$(cat \"${TOMCAT_HOME}/var/run/tomcat.pid\")\"\n"
					+ "        rm -f \"${TOMCAT_HOME}/var/run/tomcat.pid\"\n"
					+ "    fi\n"
					+ "    if [ -f \"${TOMCAT_HOME}/var/run/java.pid\" ]; then\n"
					+ "        if [ \"$SITES\" != '' ]; then\n"
					+ "            cd \"$TOMCAT_HOME\"\n"
					+ "            \"${TOMCAT_HOME}/bin/catalina.sh\" stop 2>&1 >>\"${TOMCAT_HOME}/var/log/tomcat_err\"\n"
					+ "        fi\n"
					+ "        kill \"$(cat \"${TOMCAT_HOME}/var/run/java.pid\")\" &>/dev/null\n"
					+ "        rm -f \"${TOMCAT_HOME}/var/run/java.pid\"\n"
					+ "    fi\n"
					+ "elif [ \"$1\" = 'daemon' ]; then\n"
					+ "    if [ \"$SITES\" != '' ]; then\n"
					+ "        cd \"$TOMCAT_HOME\"\n"
					+ "        while [ 1 ]; do\n"
					+ "            [ -e \"${TOMCAT_HOME}/var/log/tomcat_err\" ] && mv -f \"${TOMCAT_HOME}/var/log/tomcat_err\" \"${TOMCAT_HOME}/var/log/tomcat_err_$(date +%Y%m%d_%H%M%S)\"\n"
					+ "            \"${TOMCAT_HOME}/bin/catalina.sh\" run >&\"${TOMCAT_HOME}/var/log/tomcat_err\" &\n"
					+ "            echo \"$!\" >\"${TOMCAT_HOME}/var/run/java.pid\"\n"
					+ "            wait\n"
					+ "            RETCODE=\"$?\"\n"
					+ "            echo \"$(date): JVM died with a return code of $RETCODE, restarting in 5 seconds\" >>\"${TOMCAT_HOME}/var/log/jvm_crashes.log\"\n"
					+ "            sleep 5\n"
					+ "        done\n"
					+ "    fi\n"
					+ "    rm -f \"${TOMCAT_HOME}/var/run/tomcat.pid\"\n"
					+ "else\n"
					+ "    echo 'Usage:' 1>&2\n"
					+ "    echo 'tomcat {start|stop}' 1>&2\n"
					+ "    echo '        start - start tomcat' 1>&2\n"
					+ "    echo '        stop  - stop tomcat' 1>&2\n"
					+ "    exit 64 # EX_USAGE in /usr/include/sysexits.h\n"
					+ "fi\n"
			);
		}
		return bout.toByteArray();
	}

	/**
	 * Gets the set of files that are installed during install and upgrade/downgrade.
	 * Each path is relative to CATALINA_HOME/CATALINA_BASE.
	 *
	 * @see  VersionedTomcatCommon#getInstallFiles(com.aoindustries.io.unix.UnixFile)
	 */
	protected List<Install> getInstallFiles(String optSlash, UnixFile installDir) throws IOException, SQLException {
		List<Install> installFiles = new ArrayList<>();
		installFiles.addAll(getTomcatCommon().getInstallFiles(optSlash, installDir, 0770));
		// bin/profile.sites is now bin/profile.d/httpd-sites.sh as of Tomcat 8.5
		installFiles.add(new Delete   ("bin/profile.sites"));
		installFiles.add(new Delete   ("bin/profile.sites.new"));
		installFiles.add(new Delete   ("bin/profile.sites.old"));
		installFiles.add(new Generated("bin/tomcat", 0700, VersionedSharedTomcatManager::generateTomcatScript));
		return installFiles;
	}
}
