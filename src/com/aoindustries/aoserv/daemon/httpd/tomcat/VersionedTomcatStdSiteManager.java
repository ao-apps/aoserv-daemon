/*
 * Copyright 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.HttpdJKProtocol;
import com.aoindustries.aoserv.client.HttpdTomcatContext;
import com.aoindustries.aoserv.client.HttpdTomcatDataSource;
import com.aoindustries.aoserv.client.HttpdTomcatParameter;
import com.aoindustries.aoserv.client.HttpdTomcatStdSite;
import com.aoindustries.aoserv.client.HttpdWorker;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.httpd.tomcat.Install.Copy;
import com.aoindustries.aoserv.daemon.httpd.tomcat.Install.Generated;
import com.aoindustries.aoserv.daemon.httpd.tomcat.Install.Mkdir;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages shared aspects of HttpdTomcatStdSite version 8.5 and above.
 *
 * @author  AO Industries, Inc.
 */
abstract class VersionedTomcatStdSiteManager<TC extends VersionedTomcatCommon> extends HttpdTomcatStdSiteManager<TC> {

	VersionedTomcatStdSiteManager(HttpdTomcatStdSite tomcatStdSite) throws SQLException, IOException {
		super(tomcatStdSite);
	}

	/**
	 * TODO: Support upgrades in-place
	 */
	@Override
	protected void buildSiteDirectoryContents(String optSlash, UnixFile siteDirectory) throws IOException, SQLException {
		// Resolve and allocate stuff used throughout the method
		final LinuxServerAccount lsa = httpdSite.getLinuxServerAccount();
		final int uid = lsa.getUid().getId();
		final int gid = httpdSite.getLinuxServerGroup().getGid().getId();

		final TC tomcatCommon = getTomcatCommon();
		final String apacheTomcatDir = tomcatCommon.getApacheTomcatDir();

		final String backupSuffix = VersionedTomcatCommon.getBackupSuffix();

		/*
		 * Create the skeleton of the site, the directories and links.
		 */
		List<Install> installFiles = getInstallFiles(optSlash, siteDirectory);
		for(Install installFile : installFiles) {
			installFile.install(optSlash, apacheTomcatDir, siteDirectory, uid, gid, backupSuffix);
		}

		// daemon/
		{
			// TODO: Is this link updated elsewhere, thus this not needed here?
			UnixFile daemon = new UnixFile(siteDirectory, "daemon", false);
			if (!httpdSite.isDisabled()) {
				DaemonFileUtils.ln(
					"../bin/tomcat",
					new UnixFile(daemon, "tomcat", false), uid, gid
				);
			}
		}
	}

	@Override
	protected byte[] buildServerXml(UnixFile siteDirectory, String autoWarning) throws IOException, SQLException {
		final TC tomcatCommon = getTomcatCommon();
		AOServConnector conn = AOServDaemon.getConnector();

		// Build to RAM first
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		try (ChainWriter out = new ChainWriter(new OutputStreamWriter(bout, StandardCharsets.UTF_8))) {
			HttpdWorker hw;
			{
				List<HttpdWorker> hws = tomcatSite.getHttpdWorkers();
				if(hws.size() != 1) throw new SQLException("Expected to only find one HttpdWorker for HttpdTomcatStdSite #" + httpdSite.getPkey() + ", found " + hws.size());
				hw = hws.get(0);
				String hwProtocol = hw.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol();
				if(!hwProtocol.equals(HttpdJKProtocol.AJP13)) {
					throw new SQLException("HttpdWorker #" + hw.getPkey() + " for HttpdTomcatStdSite #" + httpdSite.getPkey() + " must be AJP13 but it is " + hwProtocol);
				}
			}
			if(!httpdSite.isManual()) out.print(autoWarning);
			NetBind shutdownPort = tomcatStdSite.getTomcat4ShutdownPort();
			if(shutdownPort == null) throw new SQLException("Unable to find shutdown port for HttpdTomcatStdSite=" + tomcatStdSite);
			String shutdownKey = tomcatStdSite.getTomcat4ShutdownKey();
			if(shutdownKey == null) throw new SQLException("Unable to find shutdown key for HttpdTomcatStdSite=" + tomcatStdSite);
			out.print("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
					+ "<Server port=\"").encodeXmlAttribute(shutdownPort.getPort().getPort()).print("\" shutdown=\"").encodeXmlAttribute(shutdownKey).print("\">\n"
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
					+ "      maxPostSize=\"").encodeXmlAttribute(tomcatStdSite.getMaxPostSize()).print("\"\n"
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
					+ "      </Realm>\n"
					+ "\n"
					+ "      <Host\n"
					+ "        name=\"localhost\"\n"
					+ "        appBase=\"webapps\"\n"
					+ "        unpackWARs=\"").encodeXmlAttribute(tomcatStdSite.getUnpackWARs()).print("\"\n"
					+ "        autoDeploy=\"").encodeXmlAttribute(tomcatStdSite.getAutoDeploy()).print("\"\n"
					+ "      >\n");
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
				out.print("          debug=\"").encodeXmlAttribute(htc.getDebugLevel()).print("\"\n");
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
			out.print("      </Host>\n"
					+ "    </Engine>\n"
					+ "  </Service>\n"
					+ "</Server>\n");
		}
		return bout.toByteArray();
	}

	protected static byte[] generateTomcatScript(String optSlash, String apacheTomcatDir, UnixFile installDir) throws IOException {
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		try (ChainWriter out = new ChainWriter(new OutputStreamWriter(bout, StandardCharsets.UTF_8))) {
			out.print("#!/bin/bash\n"
					+ "#\n"
					+ "# Generated by ").print(VersionedTomcatStdSiteManager.class.getName()).print("\n"
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
					+ "        cd \"$TOMCAT_HOME\"\n"
					+ "        \"${TOMCAT_HOME}/bin/catalina.sh\" stop 2>&1 >>\"${TOMCAT_HOME}/var/log/tomcat_err\"\n"
					+ "        kill \"$(cat \"${TOMCAT_HOME}/var/run/java.pid\")\" &>/dev/null\n"
					+ "        rm -f \"${TOMCAT_HOME}/var/run/java.pid\"\n"
					+ "    fi\n"
					+ "elif [ \"$1\" = 'daemon' ]; then\n"
					+ "    cd \"$TOMCAT_HOME\"\n"
					+ "    while [ 1 ]; do\n"
					+ "        mv -f \"${TOMCAT_HOME}/var/log/tomcat_err\" \"${TOMCAT_HOME}/var/log/tomcat_err_$(date +%Y%m%d_%H%M%S)\"\n"
					+ "        \"${TOMCAT_HOME}/bin/catalina.sh\" run >&\"${TOMCAT_HOME}/var/log/tomcat_err\" &\n"
					+ "        echo \"$!\" >\"${TOMCAT_HOME}/var/run/java.pid\"\n"
					+ "        wait\n"
					+ "        RETCODE=\"$?\"\n"
					+ "        echo \"$(date): JVM died with a return code of $RETCODE, restarting in 5 seconds\" >>\"${TOMCAT_HOME}/var/log/jvm_crashes.log\"\n"
					+ "        sleep 5\n"
					+ "    done\n"
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
		installFiles.addAll(getTomcatCommon().getInstallFiles(optSlash, installDir, 0775)); // 0775 to allow Apache to read any passwd/group files in the conf/ directory
		// bin/profile.sites is now bin/profile.d/httpd-sites.sh as of Tomcat 8.5
		installFiles.add(new Generated("bin/tomcat", 0700, VersionedTomcatStdSiteManager::generateTomcatScript));
		installFiles.add(new Mkdir    ("webapps", 0775));
		installFiles.add(new Mkdir    ("webapps/" + HttpdTomcatContext.ROOT_DOC_BASE, 0775));
		// TODO: Do no create these on upgrade:
		installFiles.add(new Mkdir    ("webapps/" + HttpdTomcatContext.ROOT_DOC_BASE + "/WEB-INF", 0770));
		installFiles.add(new Mkdir    ("webapps/" + HttpdTomcatContext.ROOT_DOC_BASE + "/WEB-INF/classes", 0770));
		installFiles.add(new Mkdir    ("webapps/" + HttpdTomcatContext.ROOT_DOC_BASE + "/WEB-INF/lib", 0770));
		installFiles.add(new Copy     ("webapps/" + HttpdTomcatContext.ROOT_DOC_BASE + "/WEB-INF/web.xml", 0660));
		return installFiles;
	}

	@Override
	protected boolean upgradeSiteDirectoryContents(String optSlash, UnixFile siteDirectory) throws IOException, SQLException {
		// The only thing that needs to be modified is the included Tomcat
		return getTomcatCommon().upgradeTomcatDirectory(
			optSlash,
			siteDirectory,
			httpdSite.getLinuxServerAccount().getUid().getId(),
			httpdSite.getLinuxServerGroup().getGid().getId()
		);
	}
}