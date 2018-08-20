/*
 * Copyright 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
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
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import static com.aoindustries.aoserv.daemon.httpd.tomcat.TomcatCommon.BACKUP_EXTENSION;
import static com.aoindustries.aoserv.daemon.httpd.tomcat.TomcatCommon.BACKUP_SEPARATOR;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.IoUtils;
import com.aoindustries.io.unix.UnixFile;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Manages HttpdTomcatStdSite version 8.5.X configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdTomcatStdSiteManager_8_5_X extends HttpdTomcatStdSiteManager<TomcatCommon_8_5_X> {

	HttpdTomcatStdSiteManager_8_5_X(HttpdTomcatStdSite tomcatStdSite) throws SQLException, IOException {
		super(tomcatStdSite);
	}

	/**
	 * TODO: Support upgrades in-place
	 */
	@Override
	protected void buildSiteDirectoryContents(String optSlash, UnixFile siteDirectory) throws IOException, SQLException {
		// Resolve and allocate stuff used throughout the method
		final OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
		final LinuxServerAccount lsa = httpdSite.getLinuxServerAccount();
		final int uid = lsa.getUid().getId();
		final int gid = httpdSite.getLinuxServerGroup().getGid().getId();
		final AOServer thisAoServer = AOServDaemon.getThisAOServer();
		int uid_min = thisAoServer.getUidMin().getId();
		int gid_min = thisAoServer.getGidMin().getId();

		final UnixFile temp = new UnixFile(siteDirectory, "temp", false);

		final String backupSuffix = TomcatCommon.getBackupSuffix();
		final ByteArrayOutputStream bout = new ByteArrayOutputStream();

		/*
		 * Create the skeleton of the site, the directories and links.
		 */
		// bin/
		{
			UnixFile bin = new UnixFile(siteDirectory, "bin", false);
			DaemonFileUtils.mkdir(
				bin, 0770, uid, gid,
				DaemonFileUtils.findUnusedBackup(bin + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
			);
			// bin/bootstrap.jar
			UnixFile bootstrapJar = new UnixFile(bin, "bootstrap.jar", false);
			DaemonFileUtils.ln(
				"../" + optSlash + "apache-tomcat-8.5/bin/bootstrap.jar",
				bootstrapJar, uid, gid,
				DaemonFileUtils.findUnusedBackup(bootstrapJar + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
			);
			// bin/catalina.sh
			UnixFile catalinaSh = new UnixFile(bin, "catalina.sh", false);
			DaemonFileUtils.ln(
				"../" + optSlash + "apache-tomcat-8.5/bin/catalina.sh",
				catalinaSh, uid, gid,
				DaemonFileUtils.findUnusedBackup(catalinaSh + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
			);
			// bin/commons-daemon.jar
			UnixFile commonsDaemonJar = new UnixFile(bin, "commons-daemon.jar", false);
			DaemonFileUtils.ln(
				"../" + optSlash + "apache-tomcat-8.5/bin/commons-daemon.jar",
				commonsDaemonJar, uid, gid,
				DaemonFileUtils.findUnusedBackup(commonsDaemonJar + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
			);
			// bin/digest.sh
			UnixFile digestSh = new UnixFile(bin, "digest.sh", false);
			DaemonFileUtils.ln(
				"../" + optSlash + "apache-tomcat-8.5/bin/digest.sh",
				digestSh, uid, gid,
				DaemonFileUtils.findUnusedBackup(digestSh + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
			);
			// bin/profile is now bin/profile.d
			UnixFile profile = new UnixFile(bin, "profile", false);
			if(profile.getStat().exists()) {
				profile.renameTo(DaemonFileUtils.findUnusedBackup(profile + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION));
			}
			// bin/profile.d/
			{
				UnixFile binProfileD = new UnixFile(bin, "profile.d", false);
				DaemonFileUtils.mkdir(
					binProfileD, 0750, uid, gid,
					DaemonFileUtils.findUnusedBackup(binProfileD + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
				);
				Set<String> profileDContents = new HashSet<>();
				// bin/profile.d/catalina.sh
				profileDContents.add("catalina.sh");
				bout.reset();
				try (ChainWriter out = new ChainWriter(bout)) {
					out.print("export CATALINA_BASE=\"").print(siteDirectory).print("\"\n"
							+ "export CATALINA_HOME=\"").print(siteDirectory).print("\"\n"
							+ "export CATALINA_TEMP=\"").print(temp).print("\"\n"
							+ "\n"
							+ "export PATH=\"${PATH}:").print(bin).print("\"\n");
				}
				UnixFile profileCatalinaSh = new UnixFile(binProfileD, "catalina.sh", false);
				DaemonFileUtils.atomicWrite(
					profileCatalinaSh, bout.toByteArray(), 0640, uid, gid,
					DaemonFileUtils.findUnusedBackup(profileCatalinaSh + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION),
					null
				);
				// bin/profile.d/java-disable-usage-tracking.sh
				profileDContents.add("java-disable-usage-tracking.sh");
				bout.reset();
				try (ChainWriter out = new ChainWriter(bout)) {
					out.print("export JAVA_OPTS=\"${JAVA_OPTS:-}${JAVA_OPTS+ }-Djdk.disableLastUsageTracking=true\"\n");
				}
				UnixFile javaDisableUsagingTrackingSh = new UnixFile(binProfileD, "java-disable-usage-tracking.sh", false);
				DaemonFileUtils.atomicWrite(
					javaDisableUsagingTrackingSh, bout.toByteArray(), 0640, uid, gid,
					DaemonFileUtils.findUnusedBackup(javaDisableUsagingTrackingSh + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION),
					null
				);
				// bin/profile.d/java-headless.sh
				profileDContents.add("java-headless.sh");
				bout.reset();
				try (ChainWriter out = new ChainWriter(bout)) {
					out.print("export JAVA_OPTS=\"${JAVA_OPTS:-}${JAVA_OPTS+ }-Djava.awt.headless=true\"\n");
				}
				UnixFile javaHeadlessSh = new UnixFile(binProfileD, "java-headless.sh", false);
				DaemonFileUtils.atomicWrite(
					javaHeadlessSh, bout.toByteArray(), 0640, uid, gid,
					DaemonFileUtils.findUnusedBackup(javaHeadlessSh + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION),
					null
				);
				// bin/profile.d/java-heapsize.sh
				profileDContents.add("java-heapsize.sh");
				bout.reset();
				try (ChainWriter out = new ChainWriter(bout)) {
					out.print("export JAVA_OPTS=\"${JAVA_OPTS:-}${JAVA_OPTS+ }-Xmx128M\"\n");
				}
				UnixFile javaHeapsizeSh = new UnixFile(binProfileD, "java-heapsize.sh", false);
				DaemonFileUtils.atomicWrite(
					javaHeapsizeSh, bout.toByteArray(), 0640, uid, gid,
					DaemonFileUtils.findUnusedBackup(javaHeapsizeSh + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION),
					null
				);
				// bin/profile.d/java-server.sh
				profileDContents.add("java-server.sh");
				bout.reset();
				try (ChainWriter out = new ChainWriter(bout)) {
					out.print("export JAVA_OPTS=\"${JAVA_OPTS:-}${JAVA_OPTS+ }-server\"\n");
				}
				UnixFile javaServerSh = new UnixFile(binProfileD, "java-server.sh", false);
				DaemonFileUtils.atomicWrite(
					javaServerSh, bout.toByteArray(), 0640, uid, gid,
					DaemonFileUtils.findUnusedBackup(javaServerSh + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION),
					null
				);
				// bin/profile.d/jdk.sh			
				profileDContents.add("jdk.sh");
				String jdkShTarget;
				{
					String jdkProfileSh = osConfig.getDefaultJdkProfileSh().toString();
					if(!jdkProfileSh.startsWith("/opt/")) throw new IllegalStateException("jdkProfileSh does not start with \"/opt/\": " + jdkProfileSh);
					jdkShTarget = "../../" + optSlash + jdkProfileSh.substring("/opt/".length());
				}
				UnixFile jdkSh = new UnixFile(binProfileD, "jdk.sh", false);
				DaemonFileUtils.ln(
					jdkShTarget,
					jdkSh, uid, gid,
					DaemonFileUtils.findUnusedBackup(jdkSh + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
				);
				// bin/profile.d/umask.sh
				profileDContents.add("umask.sh");
				bout.reset();
				try (ChainWriter out = new ChainWriter(bout)) {
					out.print("umask 002\n");
				}
				UnixFile umaskSh = new UnixFile(binProfileD, "umask.sh", false);
				DaemonFileUtils.atomicWrite(
					umaskSh, bout.toByteArray(), 0640, uid, gid,
					DaemonFileUtils.findUnusedBackup(umaskSh + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION),
					null
				);
				// Backup anything unexpected in bin/profile.d
				for(String filename : binProfileD.list()) {
					if(!profileDContents.contains(filename)) {
						UnixFile backmeup = new UnixFile(binProfileD, filename, false);
						backmeup.renameTo(DaemonFileUtils.findUnusedBackup(backmeup + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION));
					}
				}
			}
			// bin/tomcat
			bout.reset();
			try (ChainWriter out = new ChainWriter(bout)) {
				out.print("#!/bin/bash\n"
						+ "\n"
						+ "TOMCAT_HOME=\"").print(siteDirectory).print("\"\n"
						+ "\n"
						+ "if [ \"$1\" = \"start\" ]; then\n"
						+ "    # Stop any running Tomcat\n"
						+ "    env -i \"$0\" stop\n"
						+ "    # Remove work files to force JSP recompilation on Tomcat restart\n"
						+ "    find \"${TOMCAT_HOME}/work/Catalina\" -type f -path \"*/org/apache/jsp/*.class\" -or -path \"*/org/apache/jsp/*.java\" -delete\n"
						+ "    find \"${TOMCAT_HOME}/work/Catalina\" -type d -mindepth 1 -empty -delete\n"
						+ "    # Start Tomcat wrapper in the background\n"
						+ "    env -i \"$0\" daemon &\n"
						+ "    echo \"$!\" >\"${TOMCAT_HOME}/var/run/tomcat.pid\"\n"
						+ "elif [ \"$1\" = \"stop\" ]; then\n"
						+ "    AOSERV_PROFILE_D=\"${TOMCAT_HOME}/bin/profile.d\"\n"
						+ "    . /etc/profile\n"
						+ "\n"
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
						+ "elif [ \"$1\" = \"daemon\" ]; then\n"
						+ "    AOSERV_PROFILE_D=\"${TOMCAT_HOME}/bin/profile.d\"\n"
						+ "    . /etc/profile\n"
						+ "\n"
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
						+ "    echo \"Usage:\" 1>&2\n"
						+ "    echo \"tomcat {start|stop}\" 1>&2\n"
						+ "    echo \"        start - start tomcat\" 1>&2\n"
						+ "    echo \"        stop  - stop tomcat\" 1>&2\n"
						+ "    exit 64 # EX_USAGE in /usr/include/sysexits.h\n"
						+ "fi\n"
				);
			}
			UnixFile tomcat = new UnixFile(bin, "tomcat", false);
			DaemonFileUtils.atomicWrite(
				tomcat, bout.toByteArray(), 0700, uid, gid,
				DaemonFileUtils.findUnusedBackup(tomcat + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION),
				null
			);
			// bin/setclasspath.sh
			UnixFile setclasspathSh = new UnixFile(bin, "setclasspath.sh", false);
			DaemonFileUtils.ln(
				"../" + optSlash + "apache-tomcat-8.5/bin/setclasspath.sh",
				setclasspathSh, uid, gid,
				DaemonFileUtils.findUnusedBackup(setclasspathSh + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
			);
			// bin/shutdown.sh
			bout.reset();
			try (ChainWriter out = new ChainWriter(bout)) {
				out.print("#!/bin/sh\n"
						  + "exec \"").print(tomcat).print("\" stop\n");
			}
			UnixFile shutdownSh = new UnixFile(bin, "shutdown.sh", false);
			DaemonFileUtils.atomicWrite(
				shutdownSh, bout.toByteArray(), 0700, uid, gid,
				DaemonFileUtils.findUnusedBackup(shutdownSh + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION),
				null
			);
			// bin/startup.sh
			bout.reset();
			try (ChainWriter out = new ChainWriter(bout)) {
				out.print("#!/bin/sh\n"
						  + "exec \"").print(tomcat).print("\" start\n");
			}
			UnixFile startupSh = new UnixFile(bin, "startup.sh", false);
			DaemonFileUtils.atomicWrite(
				startupSh, bout.toByteArray(), 0700, uid, gid,
				DaemonFileUtils.findUnusedBackup(startupSh + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION),
				null
			);
			// bin/tomcat-juli.jar
			UnixFile tomcatJuliJar = new UnixFile(bin, "tomcat-juli.jar", false);
			DaemonFileUtils.ln(
				"../" + optSlash + "apache-tomcat-8.5/bin/tomcat-juli.jar",
				tomcatJuliJar, uid, gid,
				DaemonFileUtils.findUnusedBackup(tomcatJuliJar + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
			);
			// bin/tool-wrapper.sh
			UnixFile toolWrapperSh = new UnixFile(bin, "tool-wrapper.sh", false);
			DaemonFileUtils.ln(
				"../" + optSlash + "apache-tomcat-8.5/bin/tool-wrapper.sh",
				toolWrapperSh, uid, gid,
				DaemonFileUtils.findUnusedBackup(toolWrapperSh + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
			);
			// bin/version.sh
			UnixFile versionSh = new UnixFile(bin, "version.sh", false);
			DaemonFileUtils.ln(
				"../" + optSlash + "apache-tomcat-8.5/bin/version.sh",
				versionSh, uid, gid,
				DaemonFileUtils.findUnusedBackup(versionSh + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
			);
		}
		// conf/
		{
			UnixFile conf = new UnixFile(siteDirectory, "conf", false);
			DaemonFileUtils.mkdir(
				conf, 0775, uid, gid,
				DaemonFileUtils.findUnusedBackup(conf + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
			);
			// conf/Catalina/
			UnixFile confCatalina = new UnixFile(conf, "Catalina", false);
			DaemonFileUtils.mkdir(
				confCatalina, 0770, uid, gid,
				DaemonFileUtils.findUnusedBackup(confCatalina + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
			);
			// conf/catalina.policy
			UnixFile catalinaPolicy = new UnixFile(conf, "catalina.policy", false);
			DaemonFileUtils.ln(
				"../" + optSlash + "apache-tomcat-8.5/conf/catalina.policy",
				catalinaPolicy, uid, gid,
				DaemonFileUtils.findUnusedBackup(catalinaPolicy + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
			);
			// conf/catalina.properties
			UnixFile catalinaProperties = new UnixFile(conf, "catalina.properties", false);
			DaemonFileUtils.ln(
				"../" + optSlash + "apache-tomcat-8.5/conf/catalina.properties",
				catalinaProperties, uid, gid,
				DaemonFileUtils.findUnusedBackup(catalinaProperties + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
			);
			// conf/context.xml
			UnixFile contextXml = new UnixFile(conf, "context.xml", false);
			DaemonFileUtils.ln(
				"../" + optSlash + "apache-tomcat-8.5/conf/context.xml",
				contextXml, uid, gid,
				DaemonFileUtils.findUnusedBackup(contextXml + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
			);
			// conf/logging.properties
			UnixFile loggingProperties = new UnixFile(conf, "logging.properties", false);
			DaemonFileUtils.ln(
				"../" + optSlash + "apache-tomcat-8.5/conf/logging.properties",
				loggingProperties, uid, gid,
				DaemonFileUtils.findUnusedBackup(loggingProperties + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
			);
			// conf/server.xml (backup any existing, new will be created below to handle both auto and manual modes)
			final UnixFile serverXml = new UnixFile(conf, "server.xml", false);
			if(serverXml.getStat().exists()) {
				serverXml.renameTo(
					DaemonFileUtils.findUnusedBackup(serverXml + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
				);
			}
			// conf/tomcat-users.xsd
			UnixFile tomcatUsersXsd = new UnixFile(conf, "tomcat-users.xsd", false);
			DaemonFileUtils.ln(
				"../" + optSlash + "apache-tomcat-8.5/conf/tomcat-users.xsd",
				tomcatUsersXsd, uid, gid,
				DaemonFileUtils.findUnusedBackup(tomcatUsersXsd + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
			);
			// conf/tomcat-users.xml
			UnixFile tomcatUsersXml = new UnixFile(conf, "tomcat-users.xml", false);
			bout.reset();
			try (InputStream in = new FileInputStream("/opt/apache-tomcat-8.5/conf/tomcat-users.xml")) {
				IoUtils.copy(in, bout);
			}
			DaemonFileUtils.atomicWrite(
				tomcatUsersXml, bout.toByteArray(), 0660, uid, gid,
				DaemonFileUtils.findUnusedBackup(tomcatUsersXml + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION),
				null
			);
			// conf/web.xml
			UnixFile webXml = new UnixFile(conf, "web.xml", false);
			DaemonFileUtils.ln(
				"../" + optSlash + "apache-tomcat-8.5/conf/web.xml",
				webXml, uid, gid,
				DaemonFileUtils.findUnusedBackup(webXml + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
			);
		}
		// daemon/
		{
			UnixFile daemon = new UnixFile(siteDirectory, "daemon", false);
			DaemonFileUtils.mkdir(
				daemon, 0770, uid, gid,
				DaemonFileUtils.findUnusedBackup(daemon + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
			);
			if (!httpdSite.isDisabled()) {
				DaemonFileUtils.ln(
					"../bin/tomcat",
					new UnixFile(daemon, "tomcat", false), uid, gid
				);
			}
		}
		// lib/
		{
			UnixFile lib = new UnixFile(siteDirectory, "lib", false);
			DaemonFileUtils.mkdir(
				lib, 0770, uid, gid,
				DaemonFileUtils.findUnusedBackup(lib + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
			);
			// lib/* -> /opt/(apacheTomcatDir)/lib/*
			DaemonFileUtils.lnAll(
				"../" + optSlash + "apache-tomcat-8.5/lib/",
				lib, uid, gid,
				backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION
			);
		}
		// logs -> var/log
		UnixFile logs = new UnixFile(siteDirectory, "logs", false);
		DaemonFileUtils.ln(
			"var/log",
			logs, uid, gid,
			DaemonFileUtils.findUnusedBackup(logs + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
		);
		// temp/
		DaemonFileUtils.mkdir(
			temp, 0770, uid, gid,
			DaemonFileUtils.findUnusedBackup(temp + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
		);
		// var/
		{
			UnixFile var = new UnixFile(siteDirectory, "var", false);
			DaemonFileUtils.mkdir(
				var, 0770, uid, gid,
				DaemonFileUtils.findUnusedBackup(var + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
			);
			// var/log/
			UnixFile varLog = new UnixFile(var, "log", false);
			DaemonFileUtils.mkdir(
				varLog, 0770, uid, gid,
				DaemonFileUtils.findUnusedBackup(varLog + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
			);
			// var/run/
			UnixFile varRun = new UnixFile(var, "run", false);
			DaemonFileUtils.mkdir(
				varRun, 0770, uid, gid,
				DaemonFileUtils.findUnusedBackup(varRun + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
			);
		}
		// webapps/
		{
			UnixFile webapps = new UnixFile(siteDirectory, "webapps", false);
			DaemonFileUtils.mkdir(
				webapps, 0775, uid, gid,
				DaemonFileUtils.findUnusedBackup(webapps + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
			);
			// webapps/ROOT/
			{
				UnixFile root = new UnixFile(webapps, HttpdTomcatContext.ROOT_DOC_BASE, false);
				DaemonFileUtils.mkdir(
					root, 0775, uid, gid,
					DaemonFileUtils.findUnusedBackup(root + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
				);
				// webapps/ROOT/WEB-INF/
				{
					UnixFile webInf = new UnixFile(root, "WEB-INF", false);
					DaemonFileUtils.mkdir(
						webInf, 0775, uid, gid,
						DaemonFileUtils.findUnusedBackup(webInf + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
					);
					// webapps/ROOT/WEB-INF/classes/
					UnixFile classes = new UnixFile(webInf, "classes", false);
					DaemonFileUtils.mkdir(
						classes, 0770, uid, gid,
						DaemonFileUtils.findUnusedBackup(classes + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
					);
					// webapps/ROOT/WEB-INF/lib/
					UnixFile lib = new UnixFile(webInf, "lib", false);
					DaemonFileUtils.mkdir(
						lib, 0770, uid, gid,
						DaemonFileUtils.findUnusedBackup(lib + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
					);
					// webapps/ROOT/WEB-INF/web.xml
					UnixFile webXml = new UnixFile(webInf, "web.xml", false);
					try (
						InputStream in = new FileInputStream("/opt/apache-tomcat-8.5/webapps/ROOT/WEB-INF/web.xml");
						OutputStream out = webXml.getSecureOutputStream(uid, gid, 0664, false, uid_min, gid_min)
					) {
						IoUtils.copy(in, out);
					}
				}
			}
		}
		// work/
		{
			UnixFile work = new UnixFile(siteDirectory, "work", false);
			DaemonFileUtils.mkdir(
				work, 0750, uid, gid,
				DaemonFileUtils.findUnusedBackup(work + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
			);
			// work/Catalina/
			UnixFile workCatalina = new UnixFile(work, "Catalina", false);
			DaemonFileUtils.mkdir(
				workCatalina, 0750, uid, gid,
				DaemonFileUtils.findUnusedBackup(workCatalina + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
			);
		}
	}

	@Override
	public TomcatCommon_8_5_X getTomcatCommon() {
		return TomcatCommon_8_5_X.getInstance();
	}

	@Override
	protected byte[] buildServerXml(UnixFile siteDirectory, String autoWarning) throws IOException, SQLException {
		final TomcatCommon tomcatCommon = getTomcatCommon();
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
