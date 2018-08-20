/*
 * Copyright 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.HttpdSharedTomcat;
import com.aoindustries.aoserv.client.HttpdSite;
import com.aoindustries.aoserv.client.HttpdTomcatSharedSite;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.LinuxServerGroup;
import com.aoindustries.aoserv.client.validator.UnixPath;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import static com.aoindustries.aoserv.daemon.httpd.tomcat.TomcatCommon.BACKUP_EXTENSION;
import static com.aoindustries.aoserv.daemon.httpd.tomcat.TomcatCommon.BACKUP_SEPARATOR;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.IoUtils;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.util.SortedArrayList;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages shared aspects of HttpdSharedTomcat version 8.5 and above.
 *
 * @author  AO Industries, Inc.
 */
public abstract class VersionedSharedTomcatManager<TC extends TomcatCommon> extends HttpdSharedTomcatManager<TC> {

	private static final Logger logger = Logger.getLogger(VersionedSharedTomcatManager.class.getName());

	VersionedSharedTomcatManager(HttpdSharedTomcat sharedTomcat) {
		super(sharedTomcat);
	}

	/**
	 * Gets the name of the Tomcat directory under <code>/opt/</code>.
	 */
	protected abstract String getApacheTomcatDir();

	/**
	 * Writes the server.xml file.
	 *
	 * @param out  Is in UTF-8 encoding.
	 */
	protected abstract void writeServerXml(
		ChainWriter out,
		HttpdSharedTomcat sharedTomcat,
		List<HttpdTomcatSharedSite> sites
	) throws IOException, SQLException;

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

		final String apacheTomcatDir = getApacheTomcatDir();

		final UnixFile bin           = new UnixFile(sharedTomcatDirectory, "bin", false);
		final UnixFile binProfileD   = new UnixFile(bin, "profile.d", false);
		final UnixFile conf          = new UnixFile(sharedTomcatDirectory, "conf", false);
		final UnixFile serverXml     = new UnixFile(conf, "server.xml", false);
		final UnixFile daemon        = new UnixFile(sharedTomcatDirectory, "daemon", false);
		final UnixFile temp          = new UnixFile(sharedTomcatDirectory, "temp", false);
		final UnixFile work          = new UnixFile(sharedTomcatDirectory, "work", false);
		final UnixFile workCatalina  = new UnixFile(work, "Catalina", false);

		final String backupSuffix = TomcatCommon.getBackupSuffix();
		final ByteArrayOutputStream bout = new ByteArrayOutputStream();

		boolean needRestart = false;

		// Create and fill in the directory if it does not exist or is owned by root.
		Stat sharedTomcatStat = sharedTomcatDirectory.getStat();
		if (!sharedTomcatStat.exists() || sharedTomcatStat.getUid() == UnixFile.ROOT_GID) {

			// /var/opt/apache-tomcat/(name)/
			if (!sharedTomcatStat.exists()) sharedTomcatDirectory.mkdir();
			sharedTomcatDirectory.setMode(0770);

			// bin/
			{
				DaemonFileUtils.mkdir(
					bin, 0770, lsaUID, lsgGID,
					DaemonFileUtils.findUnusedBackup(bin + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
				);
				// bin/bootstrap.jar
				UnixFile bootstrapJar = new UnixFile(bin, "bootstrap.jar", false);
				DaemonFileUtils.ln(
					"../" + optSlash + apacheTomcatDir + "/bin/bootstrap.jar",
					bootstrapJar, lsaUID, lsgGID,
					DaemonFileUtils.findUnusedBackup(bootstrapJar + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
				);
				// bin/catalina.sh
				UnixFile catalinaSh = new UnixFile(bin, "catalina.sh", false);
				DaemonFileUtils.ln(
					"../" + optSlash + apacheTomcatDir + "/bin/catalina.sh",
					catalinaSh, lsaUID, lsgGID,
					DaemonFileUtils.findUnusedBackup(catalinaSh + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
				);
				// bin/commons-daemon.jar
				UnixFile commonsDaemonJar = new UnixFile(bin, "commons-daemon.jar", false);
				DaemonFileUtils.ln(
					"../" + optSlash + apacheTomcatDir + "/bin/commons-daemon.jar",
					commonsDaemonJar, lsaUID, lsgGID,
					DaemonFileUtils.findUnusedBackup(commonsDaemonJar + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
				);
				// bin/digest.sh
				UnixFile digestSh = new UnixFile(bin, "digest.sh", false);
				DaemonFileUtils.ln(
					"../" + optSlash + apacheTomcatDir + "/bin/digest.sh",
					digestSh, lsaUID, lsgGID,
					DaemonFileUtils.findUnusedBackup(digestSh + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
				);
				// bin/profile is now bin/profile.d
				UnixFile profile = new UnixFile(bin, "profile", false);
				if(profile.getStat().exists()) {
					profile.renameTo(DaemonFileUtils.findUnusedBackup(profile + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION));
				}
				// bin/profile.sites is now bin/profile.d/httpd-sites.sh
				UnixFile profileSites = new UnixFile(bin, "profile.sites", false);
				if(profileSites.getStat().exists()) {
					profileSites.renameTo(DaemonFileUtils.findUnusedBackup(profileSites + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION));
				}
				UnixFile profileSitesNew = new UnixFile(bin, "profile.sites.new", false);
				if(profileSitesNew.getStat().exists()) {
					profileSitesNew.renameTo(DaemonFileUtils.findUnusedBackup(profileSitesNew + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION));
				}
				UnixFile profileSitesOld = new UnixFile(bin, "profile.sites.old", false);
				if(profileSitesOld.getStat().exists()) {
					profileSitesOld.renameTo(DaemonFileUtils.findUnusedBackup(profileSitesOld + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION));
				}
				// bin/profile.d/
				{
					DaemonFileUtils.mkdir(
						binProfileD, 0750, lsaUID, lsgGID,
						DaemonFileUtils.findUnusedBackup(binProfileD + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
					);
					Set<String> profileDContents = new HashSet<>();
					// bin/profile.d/catalina.sh
					profileDContents.add("catalina.sh");
					bout.reset();
					try (ChainWriter out = new ChainWriter(bout)) {
						out.print("export CATALINA_BASE=\"").print(sharedTomcatDirectory).print("\"\n"
								+ "export CATALINA_HOME=\"").print(sharedTomcatDirectory).print("\"\n"
								+ "export CATALINA_TEMP=\"").print(temp).print("\"\n"
								+ "\n"
								+ "export PATH=\"${PATH}:").print(bin).print("\"\n");
					}
					UnixFile profileCatalinaSh = new UnixFile(binProfileD, "catalina.sh", false);
					DaemonFileUtils.atomicWrite(
						profileCatalinaSh, bout.toByteArray(), 0640, lsaUID, lsgGID,
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
						javaDisableUsagingTrackingSh, bout.toByteArray(), 0640, lsaUID, lsgGID,
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
						javaHeadlessSh, bout.toByteArray(), 0640, lsaUID, lsgGID,
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
						javaHeapsizeSh, bout.toByteArray(), 0640, lsaUID, lsgGID,
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
						javaServerSh, bout.toByteArray(), 0640, lsaUID, lsgGID,
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
						jdkSh, lsaUID, lsgGID,
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
						umaskSh, bout.toByteArray(), 0640, lsaUID, lsgGID,
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
							+ "TOMCAT_HOME=\"").print(sharedTomcatDirectory).print("\"\n"
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
							+ "        if [ \"$SITES\" != \"\" ]; then\n"
							+ "            cd \"$TOMCAT_HOME\"\n"
							+ "            \"${TOMCAT_HOME}/bin/catalina.sh\" stop 2>&1 >>\"${TOMCAT_HOME}/var/log/tomcat_err\"\n"
							+ "        fi\n"
							+ "        kill \"$(cat \"${TOMCAT_HOME}/var/run/java.pid\")\" &>/dev/null\n"
							+ "        rm -f \"${TOMCAT_HOME}/var/run/java.pid\"\n"
							+ "    fi\n"
							+ "elif [ \"$1\" = \"daemon\" ]; then\n"
							+ "    AOSERV_PROFILE_D=\"${TOMCAT_HOME}/bin/profile.d\"\n"
							+ "    . /etc/profile\n"
							+ "\n"
							+ "    if [ \"$SITES\" != \"\" ]; then\n"
							+ "        cd \"$TOMCAT_HOME\"\n"
							+ "        while [ 1 ]; do\n"
							+ "            mv -f \"${TOMCAT_HOME}/var/log/tomcat_err\" \"${TOMCAT_HOME}/var/log/tomcat_err_$(date +%Y%m%d_%H%M%S)\"\n"
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
					tomcat, bout.toByteArray(), 0700, lsaUID, lsgGID,
					DaemonFileUtils.findUnusedBackup(tomcat + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION),
					null
				);
				// bin/setclasspath.sh
				UnixFile setclasspathSh = new UnixFile(bin, "setclasspath.sh", false);
				DaemonFileUtils.ln(
					"../" + optSlash + apacheTomcatDir + "/bin/setclasspath.sh",
					setclasspathSh, lsaUID, lsgGID,
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
					shutdownSh, bout.toByteArray(), 0700, lsaUID, lsgGID,
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
					startupSh, bout.toByteArray(), 0700, lsaUID, lsgGID,
					DaemonFileUtils.findUnusedBackup(startupSh + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION),
					null
				);
				// bin/tomcat-juli.jar
				UnixFile tomcatJuliJar = new UnixFile(bin, "tomcat-juli.jar", false);
				DaemonFileUtils.ln(
					"../" + optSlash + apacheTomcatDir + "/bin/tomcat-juli.jar",
					tomcatJuliJar, lsaUID, lsgGID,
					DaemonFileUtils.findUnusedBackup(tomcatJuliJar + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
				);
				// bin/tool-wrapper.sh
				UnixFile toolWrapperSh = new UnixFile(bin, "tool-wrapper.sh", false);
				DaemonFileUtils.ln(
					"../" + optSlash + apacheTomcatDir + "/bin/tool-wrapper.sh",
					toolWrapperSh, lsaUID, lsgGID,
					DaemonFileUtils.findUnusedBackup(toolWrapperSh + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
				);
				// bin/version.sh
				UnixFile versionSh = new UnixFile(bin, "version.sh", false);
				DaemonFileUtils.ln(
					"../" + optSlash + apacheTomcatDir + "/bin/version.sh",
					versionSh, lsaUID, lsgGID,
					DaemonFileUtils.findUnusedBackup(versionSh + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
				);
			}
			// conf/
			{
				DaemonFileUtils.mkdir(
					conf, 0770, lsaUID, lsgGID,
					DaemonFileUtils.findUnusedBackup(conf + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
				);
				// conf/Catalina/
				UnixFile confCatalina = new UnixFile(conf, "Catalina", false);
				DaemonFileUtils.mkdir(
					confCatalina, 0770, lsaUID, lsgGID,
					DaemonFileUtils.findUnusedBackup(confCatalina + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
				);
				// conf/catalina.policy
				UnixFile catalinaPolicy = new UnixFile(conf, "catalina.policy", false);
				DaemonFileUtils.ln(
					"../" + optSlash + apacheTomcatDir + "/conf/catalina.policy",
					catalinaPolicy, lsaUID, lsgGID,
					DaemonFileUtils.findUnusedBackup(catalinaPolicy + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
				);
				// conf/catalina.properties
				UnixFile catalinaProperties = new UnixFile(conf, "catalina.properties", false);
				DaemonFileUtils.ln(
					"../" + optSlash + apacheTomcatDir + "/conf/catalina.properties",
					catalinaProperties, lsaUID, lsgGID,
					DaemonFileUtils.findUnusedBackup(catalinaProperties + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
				);
				// conf/context.xml
				UnixFile contextXml = new UnixFile(conf, "context.xml", false);
				DaemonFileUtils.ln(
					"../" + optSlash + apacheTomcatDir + "/conf/context.xml",
					contextXml, lsaUID, lsgGID,
					DaemonFileUtils.findUnusedBackup(contextXml + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
				);
				// conf/logging.properties
				UnixFile loggingProperties = new UnixFile(conf, "logging.properties", false);
				DaemonFileUtils.ln(
					"../" + optSlash + apacheTomcatDir + "/conf/logging.properties",
					loggingProperties, lsaUID, lsgGID,
					DaemonFileUtils.findUnusedBackup(loggingProperties + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
				);
				// conf/server.xml (backup any existing, new will be created below to handle both auto and manual modes)
				if(serverXml.getStat().exists()) {
					serverXml.renameTo(
						DaemonFileUtils.findUnusedBackup(serverXml + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
					);
				}
				// conf/tomcat-users.xsd
				UnixFile tomcatUsersXsd = new UnixFile(conf, "tomcat-users.xsd", false);
				DaemonFileUtils.ln(
					"../" + optSlash + apacheTomcatDir + "/conf/tomcat-users.xsd",
					tomcatUsersXsd, lsaUID, lsgGID,
					DaemonFileUtils.findUnusedBackup(tomcatUsersXsd + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
				);
				// conf/tomcat-users.xml
				UnixFile tomcatUsersXml = new UnixFile(conf, "tomcat-users.xml", false);
				bout.reset();
				try (InputStream in = new FileInputStream("/opt/" + apacheTomcatDir + "/conf/tomcat-users.xml")) {
					IoUtils.copy(in, bout);
				}
				DaemonFileUtils.atomicWrite(
					tomcatUsersXml, bout.toByteArray(), 0660, lsaUID, lsgGID,
					DaemonFileUtils.findUnusedBackup(tomcatUsersXml + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION),
					null
				);
				// conf/web.xml
				UnixFile webXml = new UnixFile(conf, "web.xml", false);
				DaemonFileUtils.ln(
					"../" + optSlash + apacheTomcatDir + "/conf/web.xml",
					webXml, lsaUID, lsgGID,
					DaemonFileUtils.findUnusedBackup(webXml + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
				);
			}
			// daemon/
			DaemonFileUtils.mkdir(
				daemon, 0770, lsaUID, lsgGID,
				DaemonFileUtils.findUnusedBackup(daemon + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
			);
			// lib/
			{
				UnixFile lib = new UnixFile(sharedTomcatDirectory, "lib", false);
				DaemonFileUtils.mkdir(
					lib, 0770, lsaUID, lsgGID,
					DaemonFileUtils.findUnusedBackup(lib + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
				);
				// lib/* -> /opt/(apacheTomcatDir)/lib/*
				DaemonFileUtils.lnAll(
					"../" + optSlash + apacheTomcatDir + "/lib/",
					lib, lsaUID, lsgGID,
					backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION
				);
			}
			// logs -> var/log
			UnixFile logs = new UnixFile(sharedTomcatDirectory, "logs", false);
			DaemonFileUtils.ln(
				"var/log",
				logs, lsaUID, lsgGID,
				DaemonFileUtils.findUnusedBackup(logs + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
			);
			// temp/
			DaemonFileUtils.mkdir(
				temp, 0770, lsaUID, lsgGID,
				DaemonFileUtils.findUnusedBackup(temp + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
			);
			// var/
			{
				UnixFile var = new UnixFile(sharedTomcatDirectory, "var", false);
				DaemonFileUtils.mkdir(
					var, 0770, lsaUID, lsgGID,
					DaemonFileUtils.findUnusedBackup(var + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
				);
				// var/log/
				UnixFile varLog = new UnixFile(var, "log", false);
				DaemonFileUtils.mkdir(
					varLog, 0770, lsaUID, lsgGID,
					DaemonFileUtils.findUnusedBackup(varLog + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
				);
				// var/run/
				UnixFile varRun = new UnixFile(var, "run", false);
				DaemonFileUtils.mkdir(
					varRun, 0770, lsaUID, lsgGID,
					DaemonFileUtils.findUnusedBackup(varRun + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
				);
			}
			// work/
			{
				DaemonFileUtils.mkdir(
					work, 0750, lsaUID, lsgGID,
					DaemonFileUtils.findUnusedBackup(work + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
				);
				// work/Catalina/
				DaemonFileUtils.mkdir(
					workCatalina, 0750, lsaUID, lsgGID,
					DaemonFileUtils.findUnusedBackup(workCatalina + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
				);
			}

			// Set the ownership to avoid future rebuilds of this directory
			sharedTomcatDirectory.chown(lsaUID, lsgGID);

			needRestart = true;
		}

		// always rebuild bin/profile.d/httpd-sites.sh
		List<HttpdTomcatSharedSite> sites = sharedTomcat.getHttpdTomcatSharedSites();
		bout.reset();
		try (ChainWriter out = new ChainWriter(bout)) {
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
					+ "done\n");
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
					autoWarning,
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
}
